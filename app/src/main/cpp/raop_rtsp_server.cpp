#include "raop_rtsp_server.h"
#include "bplist_reader.h"
#include "dmap_parser.h"
#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cerrno>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <sstream>
#include <vector>

#define TAG "AirPlayRTSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

namespace airplay {

namespace {

constexpr int kAcceptPollMs = 250;
// Short poll so a packet held back for a retransmission is released on time.
constexpr int kAudioPollMs = 20;
// How long a missing audio packet is awaited (one resend round trip on a LAN) before it is skipped.
constexpr int64_t kAudioResendWaitNs = 60 * 1000000LL;
constexpr int64_t kSecondNs = 1000000000LL;
constexpr int64_t kSecondsFrom1900To1970 = 2208988800LL;
constexpr uint16_t kDefaultTimingPort = 7010;
// UxPlay seeds the AAC-ELD clock 0.2 s after video arrival when no RTCP sync has arrived (DELAY_AAC).
constexpr int64_t kAacInitialSyncDelayNs = 200 * 1000000LL;
constexpr size_t kMaxRtspHeaderBytes = 64 * 1024;
constexpr size_t kMaxRtspBodyBytes = 8 * 1024 * 1024;
// Apple senders use a handful of connections each; this bounds threads and buffers per LAN host.
constexpr size_t kMaxConnections = 32;
// Connections that own nothing (not the session, not casting) and stay silent are closed.
#ifndef AIRPLAY_IDLE_TIMEOUT_SEC
#define AIRPLAY_IDLE_TIMEOUT_SEC 120
#endif
constexpr auto kIdleConnectionTimeout = std::chrono::seconds(AIRPLAY_IDLE_TIMEOUT_SEC);
constexpr size_t kMaxVideoPayloadBytes = 10 * 1024 * 1024;
constexpr size_t kMaxAudioPacketBytes = 4096;
// Largest ALAC frame we accept (4096 samples * 2 channels * 16-bit).
constexpr uint32_t kMaxAlacSamplesPerFrame = 4096;
// Smallest: the ALAC predictor needs room for up to 32 warm-up samples.
constexpr uint32_t kMinAlacSamplesPerFrame = 64;
// The ALAC bit reader does not know the packet length; on a corrupt frame it can read up to
// ~110 bits per stereo sample. Keep that much readable memory behind every payload.
constexpr size_t kAlacReadSlackBytes = kMaxAlacSamplesPerFrame * 2 * 110 / 8 + 1024;
// Senders POST /feedback every ~2 s during a session (UxPlay MISSED_FEEDBACK_LIMIT uses 15 s).
#ifndef AIRPLAY_FEEDBACK_TIMEOUT_SEC
#define AIRPLAY_FEEDBACK_TIMEOUT_SEC 15
#endif
constexpr auto kFeedbackTimeout = std::chrono::seconds(AIRPLAY_FEEDBACK_TIMEOUT_SEC);
// Classic AirPlay senders (the macOS Music app) send no /feedback: their session lives while
// requests or audio packets arrive, and ends after this much silence or when they disconnect.
constexpr auto kClassicIdleTimeout = std::chrono::seconds(120);
constexpr int kRtspPollSeconds = 1;
// AAC-ELD streams open with "no data" filler packets carrying this 4-byte payload (see UxPlay raop_rtp.c).
constexpr uint8_t kNoDataMarker[4] = {0x00, 0x68, 0x34, 0x00};
// ALAC filler packets are exactly 44 bytes (12-byte RTP header + 32-byte payload).
constexpr size_t kAlacFillerPacketBytes = 44;

// RTSP methods this receiver understands; anything else is answered with 501 like UxPlay.
bool isKnownMethod(const std::string& method) {
    static const char* const kMethods[] = {
        "OPTIONS", "GET", "POST", "ANNOUNCE", "SETUP", "RECORD", "GET_PARAMETER",
        "SET_PARAMETER", "PAUSE", "FLUSH", "TEARDOWN"
    };
    for (const char* known : kMethods) {
        if (method == known) return true;
    }
    return false;
}

uint32_t readBe32(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

uint64_t readBe64(const uint8_t* p) {
    return (static_cast<uint64_t>(readBe32(p)) << 32) | readBe32(p + 4);
}

std::string toLower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

std::string trim(const std::string& s) {
    size_t b = s.find_first_not_of(" \t\r\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\r\n");
    return s.substr(b, e - b + 1);
}

std::string xmlEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (char c : in) {
        switch (c) {
            case '&': out += "&amp;"; break;
            case '<': out += "&lt;"; break;
            case '>': out += "&gt;"; break;
            case '"': out += "&quot;"; break;
            case '\'': out += "&apos;"; break;
            default: out.push_back(c); break;
        }
    }
    return out;
}

bool sendAll(int fd, const char* data, size_t length) {
    size_t sent = 0;
    while (sent < length) {
        ssize_t n = send(fd, data + sent, length - sent, MSG_NOSIGNAL);
        if (n > 0) {
            sent += static_cast<size_t>(n);
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

// Returns false when the peer closed, on error, or when shouldAbort() asks to drop the connection
// (checked whenever the socket's receive timeout expires).
bool recvMore(int fd, std::string& buffer, const std::function<bool()>& shouldAbort) {
    char chunk[8192];
    for (;;) {
        ssize_t n = recv(fd, chunk, sizeof(chunk), 0);
        if (n > 0) {
            buffer.append(chunk, static_cast<size_t>(n));
            return true;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (shouldAbort()) return false;
            continue;
        }
        return false;
    }
}

int createTcpListener(int port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("Failed to create TCP socket for port %d: %s", port, strerror(errno));
        return -1;
    }
    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(static_cast<uint16_t>(port));

    if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("Failed to bind TCP socket on port %d: %s", port, strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, 8) < 0) {
        LOGE("Failed to listen on TCP port %d: %s", port, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

int createUdpSocket(int port) {
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        LOGE("Failed to create UDP socket for port %d: %s", port, strerror(errno));
        return -1;
    }
    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    int rcvBuf = 512 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvBuf, sizeof(rcvBuf));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(static_cast<uint16_t>(port));

    if (bind(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("Failed to bind UDP port %d: %s", port, strerror(errno));
        close(fd);
        return -1;
    }
    return fd;
}

// Detects senders that vanish without closing the RTSP connection (Wi-Fi loss, sleep).
void enableTcpKeepAlive(int fd) {
    int on = 1;
    setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &on, sizeof(on));
#ifdef TCP_KEEPIDLE
    int idle = 10;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof(idle));
#endif
#ifdef TCP_KEEPINTVL
    int interval = 5;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &interval, sizeof(interval));
#endif
#ifdef TCP_KEEPCNT
    int count = 3;
    setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &count, sizeof(count));
#endif
}

alac_file* createAlacDecoder(uint32_t samplesPerFrame, uint32_t sampleRate) {
    alac_file* decoder = alac_create(16, 2);
    if (!decoder) return nullptr;

    unsigned char decoderInfo[48] = {0};
    auto setU16 = [](unsigned char* buf, uint16_t val) {
        buf[0] = static_cast<unsigned char>((val >> 8) & 0xFF);
        buf[1] = static_cast<unsigned char>(val & 0xFF);
    };
    auto setU32 = [](unsigned char* buf, uint32_t val) {
        buf[0] = static_cast<unsigned char>((val >> 24) & 0xFF);
        buf[1] = static_cast<unsigned char>((val >> 16) & 0xFF);
        buf[2] = static_cast<unsigned char>((val >> 8) & 0xFF);
        buf[3] = static_cast<unsigned char>(val & 0xFF);
    };

    setU32(&decoderInfo[0], 48);
    std::memcpy(&decoderInfo[4], "frma", 4);
    std::memcpy(&decoderInfo[8], "alac", 4);
    setU32(&decoderInfo[12], 36);
    std::memcpy(&decoderInfo[16], "alac", 4);
    setU32(&decoderInfo[20], 0);

    setU32(&decoderInfo[24], samplesPerFrame); // frameLength
    decoderInfo[28] = 0;                       // compatibleVersion
    decoderInfo[29] = 16;                      // bitDepth (16-bit)
    decoderInfo[30] = 40;                      // pb (0x28)
    decoderInfo[31] = 10;                      // mb (0x0A)
    decoderInfo[32] = 14;                      // kb (0x0E)
    decoderInfo[33] = 2;                       // numChannels (stereo)
    setU16(&decoderInfo[34], 255);             // maxRun
    setU32(&decoderInfo[36], 0);               // maxFrameBytes
    setU32(&decoderInfo[40], 0);               // avgBitRate
    setU32(&decoderInfo[44], sampleRate);      // sampleRate

    alac_set_info(decoder, reinterpret_cast<char*>(decoderInfo));
    return decoder;
}

// Request header lookup; RTSP header names are case-insensitive.
std::string headerValue(const RtspRequest& req, const char* lowerName) {
    for (const auto& kv : req.headers) {
        if (toLower(kv.first) == lowerName) return trim(kv.second);
    }
    return std::string();
}

// Standard base64; senders omit the '=' padding (Apple-Challenge, rsaaeskey).
std::vector<uint8_t> base64Decode(const std::string& in) {
    std::vector<uint8_t> out;
    uint32_t acc = 0;
    int bits = 0;
    for (char c : in) {
        int v;
        if (c >= 'A' && c <= 'Z') v = c - 'A';
        else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
        else if (c >= '0' && c <= '9') v = c - '0' + 52;
        else if (c == '+') v = 62;
        else if (c == '/') v = 63;
        else continue; // padding, whitespace
        acc = (acc << 6) | static_cast<uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<uint8_t>((acc >> bits) & 0xFF));
        }
    }
    return out;
}

// Base64 without '=' padding, as Apple-Response expects (shairport-sync strips it too).
std::string base64EncodeNoPadding(const std::vector<uint8_t>& data) {
    static const char* chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    uint32_t acc = 0;
    int bits = 0;
    for (uint8_t b : data) {
        acc = (acc << 8) | b;
        bits += 8;
        while (bits >= 6) {
            bits -= 6;
            out.push_back(chars[(acc >> bits) & 0x3F]);
        }
    }
    if (bits > 0) out.push_back(chars[(acc << (6 - bits)) & 0x3F]);
    return out;
}

// "A2:E2:2A:F7:C3:BC" -> 6 bytes.
std::vector<uint8_t> macBytes(const std::string& mac) {
    std::vector<uint8_t> out;
    for (size_t i = 0; i + 1 < mac.size() && out.size() < 6; ++i) {
        if (!std::isxdigit(static_cast<unsigned char>(mac[i])) || !std::isxdigit(static_cast<unsigned char>(mac[i + 1]))) continue;
        out.push_back(static_cast<uint8_t>(std::strtoul(mac.substr(i, 2).c_str(), nullptr, 16)));
        ++i;
    }
    return out;
}

// "...;control_port=6001;timing_port=6002" -> the number after [key], or 0.
uint16_t transportPort(const std::string& transport, const char* key) {
    size_t pos = transport.find(key);
    if (pos == std::string::npos) return 0;
    long port = std::strtol(transport.c_str() + pos + std::strlen(key), nullptr, 10);
    return (port > 0 && port <= 0xFFFF) ? static_cast<uint16_t>(port) : 0;
}

} // namespace

std::string RtspResponse::serialize() const {
    std::ostringstream ss;
    ss << protocol << " " << statusCode << " " << statusMessage << "\r\n";
    for (const auto& kv : headers) {
        ss << kv.first << ": " << kv.second << "\r\n";
    }
    if (statusCode != 101) { // A protocol switch carries no body (UxPlay sends no length either)
        ss << "Content-Length: " << body.size() << "\r\n";
    }
    ss << "\r\n";
    std::string headStr = ss.str();
    if (!body.empty()) {
        headStr.append(reinterpret_cast<const char*>(body.data()), body.size());
    }
    return headStr;
}

RaopRtspServer::RaopRtspServer() = default;

RaopRtspServer::~RaopRtspServer() {
    stop();
    std::lock_guard<std::mutex> lock(audioMutex_);
    if (alacDecoder_) {
        alac_free(alacDecoder_);
        alacDecoder_ = nullptr;
    }
}

void RaopRtspServer::setDeviceIdentity(const std::string& deviceName, const std::string& macAddress) {
    std::lock_guard<std::mutex> lock(configMutex_);
    if (!deviceName.empty()) deviceName_ = deviceName;
    if (!macAddress.empty()) macAddress_ = macAddress;
    LOGI("Configured server identity: '%s' (%s)", deviceName_.c_str(), macAddress_.c_str());
}

void RaopRtspServer::setDisplayResolution(int width, int height, float refreshRate) {
    // AirPlay screen mirroring protocol standard is 1080p (1920x1080 @ 60fps).
    // Advertising higher resolutions (e.g. 4K) forces the sender to stream huge 3616x2032 @ 75fps,
    // which exceeds TV hardware decoders (e.g. MediaTek MT5891 VDEC RM alloc fail).
    int targetW = (width > 0 && width <= 1920) ? width : 1920;
    int targetH = (height > 0 && height <= 1080) ? height : 1080;
    float targetFps = (refreshRate > 0.0f && refreshRate <= 60.0f) ? refreshRate : 60.0f;

    std::lock_guard<std::mutex> lock(configMutex_);
    displayWidth_ = targetW;
    displayHeight_ = targetH;
    displayRefreshRate_ = targetFps;
    LOGI("Display resolution negotiated: %dx%d @ %.2f fps", displayWidth_, displayHeight_, displayRefreshRate_);
}

void RaopRtspServer::setPinRequired(bool required) {
    std::lock_guard<std::mutex> lock(configMutex_);
    pinRequired_ = required;
    LOGI("On-screen PIN pairing %s", required ? "required" : "disabled");
}

void RaopRtspServer::disconnectSender() {
    std::lock_guard<std::mutex> session(sessionMutex_);
    if (sessionConnection_.load() == 0) return;
    int fd = sessionConnectionFd_;
    endSessionLocked("stopped on the receiver");
    // Closing the control connection is what tells the sender its session is over.
    if (fd >= 0) shutdown(fd, SHUT_RDWR);
}

void RaopRtspServer::setPairedClients(const std::vector<std::vector<uint8_t>>& ed25519Keys) {
    std::lock_guard<std::mutex> lock(configMutex_);
    pairedClients_ = ed25519Keys;
}

void RaopRtspServer::setVideoCastCallbacks(VideoCastHandler::Callbacks callbacks) {
    callbacks.closeConnections = [this]() {
        std::lock_guard<std::mutex> lock(castMutex_);
        for (int fd : castFds_) shutdown(fd, SHUT_RDWR);
    };
    videoCast_.setCallbacks(std::move(callbacks));
}

void RaopRtspServer::setTxtRecords(const std::vector<uint8_t>& airplayTxt, const std::vector<uint8_t>& raopTxt) {
    std::lock_guard<std::mutex> lock(configMutex_);
    airplayTxt_ = airplayTxt;
    raopTxt_ = raopTxt;
}

bool RaopRtspServer::start(int rtspPort, int videoPort) {
    if (isRunning_.load()) return true;

    rtspPort_ = rtspPort;
    videoPort_ = videoPort;
    videoCast_.setLocalPrefix("http://localhost:" + std::to_string(rtspPort_));
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        videoCast_.setMacAddress(macAddress_);
    }

    // 1. RTSP control socket (port 7000)
    serverFd_ = createTcpListener(rtspPort_);
    if (serverFd_ < 0) {
        return false;
    }

    // 2. Video TCP mirroring socket (port 7100)
    videoServerFd_ = createTcpListener(videoPort_);

    isRunning_.store(true);
    serverThread_ = std::thread(&RaopRtspServer::acceptLoop, this, serverFd_, false);
    if (videoServerFd_ >= 0) {
        videoTcpThread_ = std::thread(&RaopRtspServer::acceptLoop, this, videoServerFd_, true);
    }
    audioThread_ = std::thread(&RaopRtspServer::audioUdpLoop, this);

    LOGI("AirPlay RTSP Server started on port %d, Video TCP on port %d, Audio UDP on %d", rtspPort_, videoPort_, audioDataPort_);
    return true;
}

void RaopRtspServer::stop() {
    if (!isRunning_.exchange(false)) return;

    // Accept loops poll with a short timeout, so they observe isRunning_ and exit promptly.
    if (serverThread_.joinable()) serverThread_.join();
    if (videoTcpThread_.joinable()) videoTcpThread_.join();

    if (serverFd_ >= 0) {
        close(serverFd_);
        serverFd_ = -1;
    }
    if (videoServerFd_ >= 0) {
        close(videoServerFd_);
        videoServerFd_ = -1;
    }

    // Wake every connection thread and wait for it, so none outlives this object.
    stopAllWorkers();

    if (audioThread_.joinable()) audioThread_.join();
    ntp_.stop();

    isStreaming_.store(false);
    sessionConnection_.store(0);
    sessionConnectionFd_ = -1;

    LOGI("AirPlay RTSP, Video & Audio Servers stopped cleanly.");
}

void RaopRtspServer::acceptLoop(int listenFd, bool isVideo) {
    while (isRunning_.load()) {
        pollfd pfd{listenFd, POLLIN, 0};
        int ready = poll(&pfd, 1, kAcceptPollMs);
        reapFinishedWorkers();
        if (ready <= 0) continue;
        if (pfd.revents & (POLLERR | POLLNVAL)) {
            LOGW("%s listening socket reported an error; accept loop exiting", isVideo ? "Video" : "RTSP");
            break;
        }

        sockaddr_in clientAddr{};
        socklen_t clientLen = sizeof(clientAddr);
        int clientFd = accept(listenFd, reinterpret_cast<sockaddr*>(&clientAddr), &clientLen);
        if (clientFd < 0) {
            int err = errno;
            if (err == EINTR || err == EAGAIN || err == EWOULDBLOCK || err == ECONNABORTED) continue;
            if (!isRunning_.load()) break;
            LOGW("%s accept failed: %s", isVideo ? "Video TCP" : "RTSP", strerror(err));
            if (err == EMFILE || err == ENFILE || err == ENOBUFS || err == ENOMEM) {
                usleep(100 * 1000); // Transient resource exhaustion: back off instead of spinning
                continue;
            }
            break;
        }
        if (!isRunning_.load()) {
            close(clientFd);
            break;
        }

        char clientIp[INET_ADDRSTRLEN] = {0};
        inet_ntop(AF_INET, &clientAddr.sin_addr, clientIp, sizeof(clientIp));

        size_t activeConnections;
        {
            std::lock_guard<std::mutex> lock(workersMutex_);
            activeConnections = workers_.size();
        }
        if (activeConnections >= kMaxConnections) {
            LOGW("Refusing connection from %s: %zu connections already open", clientIp, activeConnections);
            close(clientFd);
            continue;
        }

        if (isVideo) {
            int nodelay = 1;
            setsockopt(clientFd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));
            int rcvbuf = 2 * 1024 * 1024;
            setsockopt(clientFd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
            LOGI("Accepted incoming video streaming TCP connection from %s:%d (TCP_NODELAY=1, SO_RCVBUF=2MB)",
                 clientIp, ntohs(clientAddr.sin_port));
            spawnWorker(clientFd, [this, clientFd]() { handleVideoClientConnection(clientFd); });
        } else {
            enableTcpKeepAlive(clientFd);
            uint64_t connectionId = nextConnectionId_.fetch_add(1) + 1;
            LOGI("Accepted incoming AirPlay connection #%" PRIu64 " from %s:%d",
                 connectionId, clientIp, ntohs(clientAddr.sin_port));
            std::string ip(clientIp);
            spawnWorker(clientFd, [this, clientFd, ip, connectionId]() {
                handleClientConnection(clientFd, ip, connectionId);
            });
        }
    }
}

void RaopRtspServer::spawnWorker(int fd, std::function<void()> body) {
    auto done = std::make_shared<std::atomic<bool>>(false);
    std::lock_guard<std::mutex> lock(workersMutex_);
    try {
        workers_.push_back(Worker{fd, done, std::thread([body = std::move(body), done]() {
            body();
            done->store(true);
        })});
    } catch (const std::exception& e) {
        LOGE("Failed to start connection thread: %s", e.what());
        close(fd);
    }
}

void RaopRtspServer::reapFinishedWorkers() {
    std::vector<Worker> finished;
    {
        std::lock_guard<std::mutex> lock(workersMutex_);
        auto firstDone = std::partition(workers_.begin(), workers_.end(),
                                        [](const Worker& w) { return !w.done->load(); });
        std::move(firstDone, workers_.end(), std::back_inserter(finished));
        workers_.erase(firstDone, workers_.end());
    }
    for (auto& w : finished) {
        if (w.thread.joinable()) w.thread.join();
        // The descriptor is only released after its thread is gone, so it can never be reused under it.
        close(w.fd);
    }
}

void RaopRtspServer::stopAllWorkers() {
    std::vector<Worker> all;
    {
        std::lock_guard<std::mutex> lock(workersMutex_);
        all.swap(workers_);
        for (auto& w : all) {
            shutdown(w.fd, SHUT_RDWR);
        }
    }
    for (auto& w : all) {
        if (w.thread.joinable()) w.thread.join();
        close(w.fd);
    }
}

void RaopRtspServer::feedVideoPayload(const uint8_t* data, size_t size, uint64_t pts) {
    std::lock_guard<std::mutex> lock(videoParserMutex_);
    videoParser_.parsePayload(data, size, pts);
}

void RaopRtspServer::handleVideoClientConnection(int clientFd) {
    // Only the session's sender may (re)open the mirroring stream. Without this, any TCP connect
    // to the video port (a LAN scanner, another device) would cut the running mirror off.
    sockaddr_in peer{};
    socklen_t peerLen = sizeof(peer);
    char peerIp[INET_ADDRSTRLEN] = {0};
    if (getpeername(clientFd, reinterpret_cast<sockaddr*>(&peer), &peerLen) == 0) {
        inet_ntop(AF_INET, &peer.sin_addr, peerIp, sizeof(peerIp));
    }
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        if (sessionClientIp_.empty() || sessionClientIp_ != peerIp) {
            LOGW("Ignoring a video connection from %s: no session from that address", peerIp);
            return;
        }
    }
    {
        std::lock_guard<std::mutex> lock(videoMutex_);
        if (activeVideoFd_ >= 0 && activeVideoFd_ != clientFd) {
            LOGI("New mirroring video connection replaces the previous one.");
            shutdown(activeVideoFd_, SHUT_RDWR);
        }
        activeVideoFd_ = clientFd;
    }
    {
        std::lock_guard<std::mutex> lock(videoParserMutex_);
        videoParser_.reset();
    }

    struct timeval tv{5, 0}; // 5-second socket timeout so shutdown requests are noticed while idle
    setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    auto recvExact = [this, clientFd](uint8_t* dst, size_t length) -> bool {
        size_t received = 0;
        while (received < length) {
            ssize_t n = recv(clientFd, dst + received, length - received, 0);
            if (n > 0) {
                received += static_cast<size_t>(n);
            } else if (n == 0) {
                return false;
            } else if (errno == EINTR) {
                continue;
            } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // Idle mirrored screen: keep the video socket alive unless the server is stopping.
                if (!isRunning_.load()) return false;
            } else {
                return false;
            }
        }
        return true;
    };

    // AirPlay Mirror video stream packet format (from UxPlay/RPiPlay reference):
    //   Bytes  [0..3]:   payload_size  (little-endian uint32)
    //   Byte   [4]:      packet_type   (0x00=video NAL, 0x01=codec config/SPS, 0x05=plist info...)
    //   Byte   [5]:      sub-type flags
    //   Bytes  [8..15]:  NTP timestamp (little-endian uint64)
    //   Header is 128 bytes total (fixed).
    //   Bytes [128..128+payload_size]: H.264 payload
    static constexpr size_t VIDEO_HEADER_SIZE = 128;

    uint8_t header[VIDEO_HEADER_SIZE];
    std::vector<uint8_t> payload;
    payload.reserve(256 * 1024);
    uint64_t vPacketCount = 0;

    while (isRunning_.load()) {
        if (!recvExact(header, VIDEO_HEADER_SIZE)) break;

        uint32_t payloadLen = static_cast<uint32_t>(header[0]) |
                             (static_cast<uint32_t>(header[1]) << 8) |
                             (static_cast<uint32_t>(header[2]) << 16) |
                             (static_cast<uint32_t>(header[3]) << 24);

        uint8_t packetType = header[4];

        // Sender NTP time of the frame. Unlike the timing protocol it omits the 1900->1970 seconds
        // (UxPlay adds them before converting). Mapped to local monotonic time once NTP is synced.
        uint64_t rawTimestamp = 0;
        std::memcpy(&rawTimestamp, header + 8, sizeof(rawTimestamp));
        int64_t remoteNs = NtpClient::ntpToNs(rawTimestamp) + kSecondsFrom1900To1970 * kSecondNs;
        int64_t expected = 0;
        videoArrivalOffsetNs_.compare_exchange_strong(expected, NtpClient::monotonicNowNs() - remoteNs);
        uint64_t pts = static_cast<uint64_t>(ntp_.remoteToLocal(remoteNs));

        if (packetType == 0x01 && (header[6] == 0x56 || header[6] == 0x5e)) {
            LOGI("Sender paused the video stream (display asleep)");
        } else if (packetType == 0x01 && (header[6] == 0x16 || header[6] == 0x1e)) {
            LOGI("Sender resumed the video stream");
        }

        vPacketCount++;
        if (vPacketCount % 300 == 1) {
            LOGI("Video stream active: frame #%" PRIu64 " type=0x%02x payloadLen=%u",
                 vPacketCount, packetType, payloadLen);
        }

        if (payloadLen == 0) {
            continue;
        }

        if (payloadLen > kMaxVideoPayloadBytes) {
            // The payload cannot be skipped reliably, so the stream framing is lost: drop the connection.
            LOGW("Invalid video packet (payloadLen=%u type=0x%02x); closing video connection", payloadLen, packetType);
            break;
        }

        if (payload.size() < payloadLen) {
            payload.resize(payloadLen);
        }
        if (!recvExact(payload.data(), payloadLen)) break;

        if (packetType == 0x01) {
            LOGI("Video SPS/PPS config packet: payloadLen=%u", payloadLen);
            std::lock_guard<std::mutex> lock(videoParserMutex_);
            videoParser_.parseSpsPps(payload.data(), payloadLen);
        } else if (packetType == 0x00) {
            if (crypto_.hasVideoKey()) {
                crypto_.decryptVideoPayload(payload.data(), payloadLen);
            }
            std::lock_guard<std::mutex> lock(videoParserMutex_);
            videoParser_.parsePayload(payload.data(), payloadLen, pts);
        } else if (packetType == 0x05) {
            // Periodic AirPlay mirror streaming performance report (unencrypted plist, sent ~1/s)
            // Do not decrypt or forward to H.264 parser.
        } else {
            LOGI("Unknown video packet type=0x%02x payloadLen=%u", packetType, payloadLen);
        }
    }

    {
        std::lock_guard<std::mutex> lock(videoMutex_);
        if (activeVideoFd_ == clientFd) activeVideoFd_ = -1;
    }
    shutdown(clientFd, SHUT_RDWR);
    LOGI("Video streaming TCP client disconnected.");
}

bool RaopRtspServer::readRtspRequest(int clientFd, std::string& pending, RtspRequest& req,
                                     const std::function<bool()>& shouldAbort) {
    req = RtspRequest{};

    size_t headerEnd;
    for (;;) {
        // Ignore stray CRLFs between pipelined requests.
        size_t firstNonBlank = pending.find_first_not_of("\r\n");
        if (firstNonBlank == std::string::npos) {
            pending.clear();
        } else if (firstNonBlank > 0) {
            pending.erase(0, firstNonBlank);
        }

        headerEnd = pending.find("\r\n\r\n");
        if (headerEnd != std::string::npos) break;
        if (pending.size() > kMaxRtspHeaderBytes) {
            LOGW("RTSP request header exceeds %zu bytes; closing connection", kMaxRtspHeaderBytes);
            return false;
        }
        if (!recvMore(clientFd, pending, shouldAbort)) return false;
    }

    std::istringstream stream(pending.substr(0, headerEnd));
    std::string requestLine;
    std::getline(stream, requestLine);
    std::istringstream lineStream(trim(requestLine));
    lineStream >> req.method >> req.uri >> req.version;

    size_t contentLength = 0;
    std::string headerLine;
    while (std::getline(stream, headerLine)) {
        auto colonPos = headerLine.find(':');
        if (colonPos == std::string::npos) continue;
        std::string key = trim(headerLine.substr(0, colonPos));
        std::string val = trim(headerLine.substr(colonPos + 1));
        std::string lowerKey = toLower(key);
        if (lowerKey == "cseq") {
            req.cseq = std::atoi(val.c_str());
        } else if (lowerKey == "content-length") {
            contentLength = static_cast<size_t>(std::strtoul(val.c_str(), nullptr, 10));
        }
        req.headers[key] = val;
    }

    if (contentLength > kMaxRtspBodyBytes) {
        LOGW("RTSP body of %zu bytes exceeds limit; closing connection", contentLength);
        return false;
    }

    size_t bodyStart = headerEnd + 4;
    while (pending.size() < bodyStart + contentLength) {
        if (!recvMore(clientFd, pending, shouldAbort)) return false;
    }
    req.body.assign(pending.begin() + static_cast<std::ptrdiff_t>(bodyStart),
                    pending.begin() + static_cast<std::ptrdiff_t>(bodyStart + contentLength));
    pending.erase(0, bodyStart + contentLength);
    return true;
}

void RaopRtspServer::handleClientConnection(int clientFd, const std::string& clientIp, uint64_t connectionId) {
    std::string pending;
    RtspRequest req;

    // A short receive timeout lets this thread notice server shutdown and a silent sender.
    struct timeval tv{kRtspPollSeconds, 0};
    setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    auto lastRequest = std::chrono::steady_clock::now();
    bool castConnection = false;
    auto shouldAbort = [this, connectionId, &clientIp, &lastRequest, &castConnection]() {
        if (!isRunning_.load()) return true;
        auto idle = std::chrono::steady_clock::now() - lastRequest;
        bool ownsSession = sessionConnection_.load() == connectionId;
        if (!ownsSession && idle > kIdleConnectionTimeout) {
            // The session's sender may keep extra connections silent on purpose (e.g. the event
            // connection to the advertised eventPort); only strangers' idle connections are closed.
            std::lock_guard<std::mutex> lock(configMutex_);
            if (!sessionClientIp_.empty() && sessionClientIp_ == clientIp) return false;
        }
        if (ownsSession && classicSession_.load()) {
            auto lastAudio = std::chrono::steady_clock::time_point(
                std::chrono::steady_clock::duration(lastAudioPacketTicks_.load()));
            auto quiet = std::chrono::steady_clock::now() - std::max(lastRequest, lastAudio);
            if (isStreaming_.load() && quiet > kClassicIdleTimeout) {
                LOGW("Classic AirPlay sender silent for %d s; ending the session",
                     static_cast<int>(kClassicIdleTimeout.count()));
                return true;
            }
            return false;
        }
        // A sender that stops sending /feedback while owning the session has frozen or vanished
        // even if its TCP connection is still open.
        if (isStreaming_.load() && ownsSession && idle > kFeedbackTimeout) {
            LOGW("No request from the session owner for %d s; ending the session",
                 static_cast<int>(kFeedbackTimeout.count()));
            return true;
        }
        if (!ownsSession && !castConnection && idle > kIdleConnectionTimeout) {
            LOGI("Closing idle connection #%" PRIu64, connectionId);
            return true;
        }
        return false;
    };

    while (isRunning_.load() && readRtspRequest(clientFd, pending, req, shouldAbort)) {
        lastRequest = std::chrono::steady_clock::now();
        bool isHttp = req.version.rfind("HTTP/", 0) == 0;
        // Checked before the connection counts as casting, so a refused one is never tracked with
        // the sender's casting connections.
        if (isHttp && !castAllowed(req, clientIp, connectionId)) {
            LOGW("Video casting request %s %s from connection #%" PRIu64 " (%s) without pair-verify; PIN pairing required",
                 req.method.c_str(), req.uri.c_str(), connectionId, clientIp.c_str());
            RtspResponse denied;
            denied.protocol = "HTTP/1.1";
            denied.statusCode = 403;
            denied.statusMessage = "Forbidden";
            std::string serialized = denied.serialize();
            sendAll(clientFd, serialized.data(), serialized.size());
            break;
        }
        std::string appleSessionId;
        for (const auto& kv : req.headers) {
            if (toLower(kv.first) == "x-apple-session-id") appleSessionId = kv.second;
        }
        if (!castConnection && !appleSessionId.empty() && isHttp) {
            castConnection = true;
            std::lock_guard<std::mutex> lock(castMutex_);
            castFds_.insert(clientFd);
        }

        RtspResponse resp = processRtspRequest(req, clientIp, connectionId, clientFd);
        std::string serialized = resp.serialize();
        if (!sendAll(clientFd, serialized.data(), serialized.size())) break;
        if (resp.closeConnection) {
            LOGW("Closing connection #%" PRIu64 " after rejected request", connectionId);
            break;
        }
        if (resp.becomeReverse) {
            // PTTH: from now on the receiver sends requests (events) and the sender answers them.
            videoCast_.attachReverseConnection(clientFd, appleSessionId);
            char discard[4096];
            for (;;) {
                ssize_t n = recv(clientFd, discard, sizeof(discard), 0);
                if (n > 0) continue; // The sender's replies to our events
                if (n < 0 && (errno == EINTR || ((errno == EAGAIN || errno == EWOULDBLOCK) && isRunning_.load()))) continue;
                break;
            }
            videoCast_.detachReverseConnection(clientFd);
            break;
        }
    }

    if (castConnection) {
        bool lastConnection;
        {
            std::lock_guard<std::mutex> lock(castMutex_);
            castFds_.erase(clientFd);
            lastConnection = castFds_.empty();
        }
        if (lastConnection) videoCast_.onSessionClosed();
    }

    shutdown(clientFd, SHUT_RDWR);

    // The control connection lives for the whole session. If it drops without TEARDOWN
    // (Wi-Fi loss, sender sleep, app killed, or a handshake abandoned before RECORD) the session
    // it started must be ended here.
    {
        std::lock_guard<std::mutex> session(sessionMutex_);
        if (sessionConnection_.load() == connectionId) {
            endSessionLocked("RTSP control connection closed without TEARDOWN");
        }
    }
    crypto_.forgetConnection(connectionId);
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        classicNonces_.erase(connectionId);
        classicAuthorized_.erase(connectionId);
        castTrusted_.erase(connectionId);
    }
    LOGI("RTSP client connection #%" PRIu64 " closed for %s", connectionId, clientIp.c_str());
}

bool RaopRtspServer::pinAllows(uint64_t connectionId) {
    bool pinRequired;
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        pinRequired = pinRequired_;
    }
    if (!pinRequired || crypto_.isPairVerified(connectionId)) return true;
    std::lock_guard<std::mutex> lock(configMutex_);
    return classicAuthorized_.count(connectionId) > 0;
}

bool RaopRtspServer::castAllowed(const RtspRequest& req, const std::string& clientIp, uint64_t connectionId) {
    std::string path = req.uri.substr(0, req.uri.find('?'));
    bool verified = crypto_.isPairVerified(connectionId);
    std::lock_guard<std::mutex> lock(configMutex_);
    if (!pinRequired_) return true;
    // Like GET /info: senders may look the receiver up before pairing.
    if (req.method == "GET" && path == "/server-info") return true;
    // The local player fetching the playlists rewritten to http://localhost (no session ID).
    bool loopback = clientIp.rfind("127.", 0) == 0;
    if (loopback && req.method == "GET" && headerValue(req, "x-apple-session-id").empty() &&
        path.size() >= 5 && path.compare(path.size() - 5, 5, ".m3u8") == 0) {
        return true;
    }
    bool trusted = verified || castTrusted_.count(connectionId) > 0 ||
                   std::any_of(castTrusted_.begin(), castTrusted_.end(),
                               [&clientIp](const auto& entry) { return entry.second == clientIp; });
    if (trusted) castTrusted_[connectionId] = clientIp;
    return trusted;
}

void RaopRtspServer::endSessionLocked(const char* reason) {
    classicSession_.store(false);
    bool wasStreaming = isStreaming_.exchange(false);
    sessionConnection_.store(0);
    sessionConnectionFd_ = -1;
    LOGI("Ending AirPlay session: %s", reason);
    stopAudioStream();
    {
        std::lock_guard<std::mutex> lock(videoMutex_);
        if (activeVideoFd_ >= 0) shutdown(activeVideoFd_, SHUT_RDWR);
    }
    ntp_.stop();
    videoArrivalOffsetNs_.store(0);
    crypto_.resetSessionCrypto();
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        clientName_.clear();
        clientModel_.clear();
        verifyKeyConnection_ = 0;
        verifyKey_.clear();
        sessionClientIp_.clear();
    }
    if (wasStreaming && onStreamStopped_) onStreamStopped_();
}

void RaopRtspServer::handlePairSetupPin(const RtspRequest& req, RtspResponse& resp) {
    auto fail = [&resp]() {
        resp.statusCode = 470;
        resp.statusMessage = "Client Authentication Failure";
        resp.body.clear();
    };
    BplistValue request;
    if (!onPinSetup_ || !parseBinaryPlist(req.body, request) || !request.isDict()) {
        fail();
        return;
    }
    auto data = [&request](const char* key) -> const std::vector<uint8_t>* {
        const BplistValue* v = request.find(key);
        return (v && v->type == BplistValue::Type::Data) ? &v->dataVal : nullptr;
    };

    std::vector<std::vector<uint8_t>> out;
    if (const BplistValue* user = request.find("user"); user && user->type == BplistValue::Type::String) {
        // Step 1: {method: "pin", user} -> {pk: B, salt}
        const BplistValue* method = request.find("method");
        if (!method || method->strVal != "pin") return; // UxPlay: other methods get an empty 200
        {
            std::lock_guard<std::mutex> lock(configMutex_);
            pinUser_ = user->strVal;
        }
        if (!onPinSetup_(1, user->strVal, {}, out) || out.size() < 2) return fail();
        resp.body = encodeBinaryPlistDataDict({{"pk", out[0]}, {"salt", out[1]}});
    } else if (data("pk") && data("proof")) {
        // Step 2: {pk: A, proof: M1} -> {proof: M2}
        std::string user;
        {
            std::lock_guard<std::mutex> lock(configMutex_);
            user = pinUser_;
        }
        if (!onPinSetup_(2, user, {*data("pk"), *data("proof")}, out) || out.empty()) {
            LOGW("PIN pairing failed: wrong PIN");
            return fail();
        }
        resp.body = encodeBinaryPlistDataDict({{"proof", out[0]}});
    } else if (data("epk") && data("authTag")) {
        // Step 3: exchange Ed25519 public keys under AES-GCM; the client is now paired.
        const std::vector<uint8_t>& serverKey = crypto_.getEd25519PublicKey();
        if (!onPinSetup_(3, std::string(), {*data("epk"), *data("authTag"), serverKey}, out) || out.size() < 3) {
            LOGW("PIN pairing failed in the key exchange");
            return fail();
        }
        {
            std::lock_guard<std::mutex> lock(configMutex_);
            if (std::find(pairedClients_.begin(), pairedClients_.end(), out[2]) == pairedClients_.end()) {
                pairedClients_.push_back(out[2]);
            }
        }
        LOGI("PIN pairing completed; device registered");
        resp.body = encodeBinaryPlistDataDict({{"epk", out[0]}, {"authTag", out[1]}});
    } else {
        return fail();
    }
    resp.headers["Content-Type"] = "application/x-apple-binary-plist";
}

void RaopRtspServer::configureAudioStream(const BplistValue* stream) {
    configureAudio(stream ? stream->intFor("ct", 0) : 0, stream ? stream->intFor("spf", 0) : 0,
                   stream ? stream->intFor("sr", 44100) : 44100,
                   static_cast<uint16_t>(stream ? stream->intFor("controlPort", 0) : 0));
}

void RaopRtspServer::configureAudio(uint64_t ct, uint64_t spf, uint64_t sr, uint16_t controlPort) {
    if (sr == 0 || sr > 192000) sr = 44100;

    AudioCodec codec;
    switch (ct) {
        case 2: codec = AudioCodec::ALAC; break;
        case 4: codec = AudioCodec::AAC_LC; break;
        case 8: codec = AudioCodec::AAC_ELD; break;
        case 0:
            // Older senders omit "ct"; infer it from the frame size (480 = AAC-ELD used by mirroring).
            codec = (spf == 352) ? AudioCodec::ALAC : (spf == 1024 ? AudioCodec::AAC_LC : AudioCodec::AAC_ELD);
            break;
        default:
            // Decoding an unknown format as ALAC/AAC would only produce noise; keep the stream silent.
            LOGW("Unsupported audio compression type ct=%" PRIu64 "; audio muted for this stream", ct);
            stopAudioStream();
            return;
    }
    // Real senders use 352 (ALAC), 1024 (AAC-LC) or 480 (AAC-ELD). Tiny values are rejected too:
    // the ALAC predictor works on up to 32 warm-up samples per frame.
    if (spf < kMinAlacSamplesPerFrame || spf > kMaxAlacSamplesPerFrame) {
        spf = (codec == AudioCodec::ALAC) ? 352 : (codec == AudioCodec::AAC_LC ? 1024 : 480);
    }

    {
        std::lock_guard<std::mutex> lock(audioMutex_);
        audioBuffer_.reset();
        audioSynced_ = false;
        audioSampleRate_ = static_cast<int>(sr);
        // The sender's RTCP port: retransmission requests go there (UxPlay needs it != 0 to resend).
        audioRemoteControlPort_ = controlPort;
        if (alacDecoder_) {
            alac_free(alacDecoder_);
            alacDecoder_ = nullptr;
        }
        if (codec == AudioCodec::ALAC) {
            alacDecoder_ = createAlacDecoder(static_cast<uint32_t>(spf), static_cast<uint32_t>(sr));
            if (!alacDecoder_) LOGE("Failed to create ALAC decoder!");
        }
        audioCodec_ = codec;
    }

    // ALAC is decoded here and delivered as PCM; AAC variants are decoded by MediaCodec in Java.
    int javaCodec = (codec == AudioCodec::ALAC) ? static_cast<int>(AudioCodec::PCM_S16) : static_cast<int>(codec);
    LOGI("Audio stream configured: ct=%" PRIu64 " codec=%d spf=%" PRIu64 " sr=%" PRIu64,
         ct, static_cast<int>(codec), spf, sr);
    if (onAudioConfig_) onAudioConfig_(javaCodec, static_cast<int>(sr), 2, static_cast<int>(spf));
}

void RaopRtspServer::stopAudioStream() {
    bool wasActive;
    {
        std::lock_guard<std::mutex> lock(audioMutex_);
        wasActive = audioCodec_ != AudioCodec::STOPPED;
        audioCodec_ = AudioCodec::STOPPED;
        audioBuffer_.reset();
        audioSynced_ = false;
        if (alacDecoder_) {
            alac_free(alacDecoder_);
            alacDecoder_ = nullptr;
        }
    }
    if (wasActive && onAudioConfig_) onAudioConfig_(static_cast<int>(AudioCodec::STOPPED), 0, 0, 0);
}

void RaopRtspServer::stopVideoStream() {
    {
        std::lock_guard<std::mutex> lock(videoMutex_);
        if (activeVideoFd_ >= 0) shutdown(activeVideoFd_, SHUT_RDWR);
    }
    videoArrivalOffsetNs_.store(0);
    crypto_.resetVideoCipher();
    LOGI("Mirroring video stream stopped; session continues.");
    if (onVideoStopped_) onVideoStopped_();
}

void RaopRtspServer::answerAppleChallenge(const RtspRequest& req, RtspResponse& resp, int clientFd) {
    std::string challengeText = headerValue(req, "apple-challenge");
    if (challengeText.empty()) return;
    std::vector<uint8_t> challenge = base64Decode(challengeText);
    if (challenge.empty() || challenge.size() > 16 || !onRsa_) {
        LOGW("Cannot answer the Apple-Challenge (%zu bytes)", challenge.size());
        return;
    }
    // shairport-sync apple_challenge: challenge + the receiver's IP on this connection + its MAC
    // (the "XXXXXXXXXXXX@" prefix of the _raop name), zero-padded to 32 bytes, RSA-signed.
    std::vector<uint8_t> buf = challenge;
    sockaddr_storage local{};
    socklen_t localLen = sizeof(local);
    if (getsockname(clientFd, reinterpret_cast<sockaddr*>(&local), &localLen) == 0) {
        if (local.ss_family == AF_INET) {
            const auto* sa = reinterpret_cast<const sockaddr_in*>(&local);
            const auto* ip = reinterpret_cast<const uint8_t*>(&sa->sin_addr.s_addr);
            buf.insert(buf.end(), ip, ip + 4);
        } else if (local.ss_family == AF_INET6) {
            const auto* sa6 = reinterpret_cast<const sockaddr_in6*>(&local);
            const uint8_t* ip = sa6->sin6_addr.s6_addr;
            // An IPv4 client on a dual-stack socket knows the receiver by its IPv4 address.
            if (IN6_IS_ADDR_V4MAPPED(&sa6->sin6_addr)) buf.insert(buf.end(), ip + 12, ip + 16);
            else buf.insert(buf.end(), ip, ip + 16);
        }
    }
    std::string mac;
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        mac = macAddress_;
    }
    std::vector<uint8_t> macRaw = macBytes(mac);
    // Only the classic-only _raop record gets RSA challenges; it advertises the MAC with the last
    // bit flipped (DeviceIdentityHelper.getClassicRaopMacPrefix).
    if (macRaw.size() == 6) macRaw[5] ^= 0x01;
    buf.insert(buf.end(), macRaw.begin(), macRaw.end());
    if (buf.size() < 32) buf.resize(32, 0);

    std::vector<uint8_t> signature;
    if (!onRsa_(0, buf, signature) || signature.empty()) {
        LOGE("Signing the Apple-Challenge failed");
        return;
    }
    resp.headers["Apple-Response"] = base64EncodeNoPadding(signature);
    LOGI("Answered the classic AirPlay Apple-Challenge");
}

bool RaopRtspServer::authorizeClassic(const RtspRequest& req, RtspResponse& resp, uint64_t connectionId) {
    std::string nonce;
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        if (!pinRequired_ || classicAuthorized_.count(connectionId)) return true;
        std::string& stored = classicNonces_[connectionId];
        if (stored.empty()) {
            char buf[33];
            for (int i = 0; i < 16; ++i) std::snprintf(buf + 2 * i, 3, "%02x", static_cast<unsigned>(arc4random() & 0xFF));
            stored = buf;
        }
        nonce = stored;
    }
    // Authorization: Digest username="iTunes", realm="raop", nonce="...", uri="...", response="..."
    ClassicAuth auth;
    auth.senderId = headerValue(req, "dacp-id");
    auth.nonce = nonce;
    auth.method = req.method;
    std::string header = headerValue(req, "authorization");
    auto field = [&header](const char* name) {
        std::string key = std::string(name) + "=\"";
        size_t pos = header.find(key);
        if (pos == std::string::npos) return std::string();
        pos += key.size();
        size_t end = header.find('"', pos);
        return end == std::string::npos ? std::string() : header.substr(pos, end - pos);
    };
    if (header.rfind("Digest ", 0) == 0 && field("nonce") == nonce) {
        auth.username = field("username");
        auth.realm = field("realm");
        auth.uri = field("uri");
        auth.response = field("response");
    }
    if (onClassicAuth_ && onClassicAuth_(auth)) {
        std::lock_guard<std::mutex> lock(configMutex_);
        classicAuthorized_.insert(connectionId);
        LOGI("Classic AirPlay sender authorized (connection #%" PRIu64 ")", connectionId);
        return true;
    }
    LOGI("Classic AirPlay sender asked for the on-screen code (%s)", auth.response.empty() ? "first request" : "wrong code");
    resp.statusCode = 401;
    resp.statusMessage = "Unauthorized";
    resp.headers["WWW-Authenticate"] = "Digest realm=\"raop\", nonce=\"" + nonce + "\"";
    return false;
}

void RaopRtspServer::handleLegacyAnnounce(const RtspRequest& req, RtspResponse& resp, uint64_t connectionId) {
    if (!authorizeClassic(req, resp, connectionId)) return;
    std::string sdp(req.body.begin(), req.body.end());
    LegacyAnnounce announce;
    announce.connectionId = connectionId;
    announce.clientName = headerValue(req, "x-apple-client-name");
    // The Music app sends no name; its User-Agent at least tells a Mac apart.
    if (announce.clientName.empty() && headerValue(req, "user-agent").find("Macintosh") != std::string::npos) {
        announce.clientName = "Mac";
    }
    std::string rtpmap, rsaKeyText, ivText;
    std::istringstream lines(sdp);
    std::string line;
    while (std::getline(lines, line)) {
        line = trim(line);
        if (line.rfind("a=rtpmap:", 0) == 0) rtpmap = line.substr(9);
        else if (line.rfind("a=rsaaeskey:", 0) == 0) rsaKeyText = line.substr(12);
        else if (line.rfind("a=aesiv:", 0) == 0) ivText = line.substr(8);
        else if (line.rfind("a=fmtp:", 0) == 0) {
            // ALAC: "96 352 0 16 40 10 14 2 255 0 0 44100" (payload type, frames per packet, ..., rate).
            std::istringstream fields(line.substr(7));
            std::vector<long> values;
            long v;
            while (fields >> v) values.push_back(v);
            if (values.size() >= 2 && values[1] > 0) announce.samplesPerFrame = static_cast<uint32_t>(values[1]);
            if (values.size() >= 12 && values[11] > 0) announce.sampleRate = static_cast<uint32_t>(values[11]);
        }
    }
    if (rtpmap.find("AppleLossless") == std::string::npos) {
        // Only ALAC is wired up for classic senders (the Music app sends ALAC).
        LOGW("Classic AirPlay ANNOUNCE with unsupported audio format '%s'", rtpmap.c_str());
        resp.statusCode = 415;
        resp.statusMessage = "Unsupported Media Type";
        return;
    }
    if (!rsaKeyText.empty()) {
        std::vector<uint8_t> aesKey;
        if (!onRsa_ || !onRsa_(1, base64Decode(rsaKeyText), aesKey) || aesKey.size() != 16) {
            LOGE("Could not unwrap the classic AirPlay audio key");
            resp.statusCode = 400;
            resp.statusMessage = "Bad Request";
            return;
        }
        announce.aesKey = aesKey;
        announce.aesIv = base64Decode(ivText);
    }
    LOGI("Classic AirPlay ANNOUNCE from '%s': ALAC %u frames/packet, %u Hz, %s",
         announce.clientName.c_str(), announce.samplesPerFrame, announce.sampleRate,
         announce.aesKey.empty() ? "unencrypted" : "RSA-encrypted");
    std::lock_guard<std::mutex> lock(configMutex_);
    legacyAnnounce_ = std::move(announce);
}

void RaopRtspServer::handleLegacySetup(const RtspRequest& req, RtspResponse& resp, const std::string& clientIp,
                                       uint64_t connectionId, int clientFd) {
    if (!pinAllows(connectionId)) {
        LOGW("Classic SETUP from connection #%" PRIu64 " without pairing; PIN pairing required", connectionId);
        resp.statusCode = 470;
        resp.statusMessage = "Connection Authorization Required";
        return;
    }
    LegacyAnnounce announce;
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        announce = legacyAnnounce_;
    }
    if (announce.connectionId != connectionId) {
        LOGW("Classic SETUP from connection #%" PRIu64 " without its ANNOUNCE", connectionId);
        resp.statusCode = 400;
        resp.statusMessage = "Bad Request";
        return;
    }
    std::string transport = headerValue(req, "transport");
    uint16_t remoteControlPort = transportPort(transport, "control_port=");
    uint16_t remoteTimingPort = transportPort(transport, "timing_port=");

    // A classic SETUP starts the whole session (like a session SETUP): the newest sender wins.
    uint64_t owner = sessionConnection_.load();
    if (owner != 0 && owner != connectionId) {
        LOGI("Connection #%" PRIu64 " takes over the session from #%" PRIu64, connectionId, owner);
        int previousFd = sessionConnectionFd_;
        endSessionLocked("another sender took over");
        if (previousFd >= 0) shutdown(previousFd, SHUT_RDWR);
    }
    sessionConnection_.store(connectionId);
    sessionConnectionFd_ = clientFd;
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        sessionClientIp_ = clientIp;
        if (!announce.clientName.empty()) clientName_ = announce.clientName;
    }
    crypto_.setLegacyAudioKey(announce.aesKey, announce.aesIv);
    classicSession_.store(true);
    lastAudioPacketTicks_.store(std::chrono::steady_clock::now().time_since_epoch().count());
    if (remoteTimingPort > 0) ntp_.start(clientIp, remoteTimingPort, kDefaultTimingPort);
    configureAudio(2, announce.samplesPerFrame, announce.sampleRate, remoteControlPort);

    int localTimingPort = ntp_.localPort() > 0 ? ntp_.localPort() : kDefaultTimingPort;
    char transportOut[192];
    std::snprintf(transportOut, sizeof(transportOut),
                  "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=%d;timing_port=%d;server_port=%d",
                  audioControlPort_, localTimingPort, audioDataPort_);
    resp.headers["Transport"] = transportOut;
    resp.headers["Session"] = "1";
    resp.headers["Audio-Jack-Status"] = "connected; type=analog";
    LOGI("Classic AirPlay SETUP: sender control %u / timing %u, receiver audio %d / control %d / timing %d",
         remoteControlPort, remoteTimingPort, audioDataPort_, audioControlPort_, localTimingPort);
}

RtspResponse RaopRtspServer::processRtspRequest(const RtspRequest& req, const std::string& clientIp, uint64_t connectionId,
                                                int clientFd) {
    RtspResponse resp;
    if (req.version.rfind("HTTP/", 0) == 0) {
        // HTTP/1.1 on the AirPlay port is video casting (UxPlay routes it to its HLS handlers).
        LOGI("HTTP Request: %s %s", req.method.c_str(), req.uri.c_str());
        videoCast_.handle(req, resp);
        return resp;
    }
    resp.headers["Server"] = "AirTunes/220.68";
    resp.headers["CSeq"] = std::to_string(req.cseq);

    LOGI("RTSP Request: %s %s (CSeq: %d)", req.method.c_str(), req.uri.c_str(), req.cseq);
    answerAppleChallenge(req, resp, clientFd);

    if (!isKnownMethod(req.method)) {
        // UxPlay: unknown RTSP methods get 501 (unknown HTTP/1.1 requests were answered 200 above).
        resp.statusCode = 501;
        resp.statusMessage = "Not Implemented";
        return resp;
    }

    // Requests that change or end the session are serialised, and only its owner may send them.
    // (SETUP checks ownership itself: a new session SETUP from another sender takes over.)
    std::unique_lock<std::mutex> session(sessionMutex_, std::defer_lock);
    const std::string& m = req.method;
    if (m == "SETUP" || m == "RECORD" || m == "TEARDOWN" || m == "SET_PARAMETER" || m == "GET_PARAMETER" ||
        m == "FLUSH" || m == "PAUSE") {
        session.lock();
        uint64_t owner = sessionConnection_.load();
        if (m != "SETUP" && owner != 0 && owner != connectionId) {
            LOGW("%s from connection #%" PRIu64 " rejected: the session belongs to #%" PRIu64,
                 m.c_str(), connectionId, owner);
            resp.statusCode = 409;
            resp.statusMessage = "Conflict";
            return resp;
        }
    }

    if (req.method == "OPTIONS") {
        resp.headers["Public"] = "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET";
    } else if (req.method == "GET" && req.uri.find("/info") != std::string::npos) {
        // Senders may only ask for the TXT record ({"qualifier": ["txtAirPlay"]}), as handled by UxPlay.
        BplistValue infoRequest;
        if (!req.body.empty() && parseBinaryPlist(req.body, infoRequest) && infoRequest.isDict()) {
            std::string qualifier;
            if (const BplistValue* q = infoRequest.find("qualifier")) {
                if (q->type == BplistValue::Type::String) qualifier = q->strVal;
                else if (q->isArray() && !q->arrayVal.empty()) qualifier = q->arrayVal.front().strVal;
            }
            if (qualifier == "txtAirPlay" || qualifier == "txtRAOP") {
                std::vector<uint8_t> txt;
                {
                    std::lock_guard<std::mutex> lock(configMutex_);
                    txt = (qualifier == "txtAirPlay") ? airplayTxt_ : raopTxt_;
                }
                resp.headers["Content-Type"] = "application/x-apple-binary-plist";
                resp.body = encodeBinaryPlistDataDict(qualifier, txt);
                return resp;
            }
        }

        std::string deviceName, macAddress;
        int width, height;
        float refreshRate;
        {
            std::lock_guard<std::mutex> lock(configMutex_);
            deviceName = deviceName_;
            macAddress = macAddress_;
            width = displayWidth_;
            height = displayHeight_;
            refreshRate = displayRefreshRate_;
        }

        std::ostringstream ss;
        ss << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
           << "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
           << "<plist version=\"1.0\">\n"
           << "<dict>\n"
           << "  <key>deviceID</key><string>" << xmlEscape(macAddress) << "</string>\n"
           << "  <key>macAddress</key><string>" << xmlEscape(macAddress) << "</string>\n"
           << "  <key>name</key><string>" << xmlEscape(deviceName) << "</string>\n"
           << "  <key>model</key><string>AppleTV5,3</string>\n"
           // 0x1E5A7FFFF7: screen mirroring + audio + URL/HLS video casting (bits 0 and 4, as UxPlay
           // advertises with -hls). Keep in sync with AirPlayFeatures.kt.
           << "  <key>features</key><integer>130367356919</integer>\n"
           << "  <key>statusFlags</key><integer>68</integer>\n"
           << "  <key>protovers</key><string>1.1</string>\n"
           << "  <key>srcvers</key><string>220.68</string>\n"
           << "  <key>vv</key><integer>2</integer>\n"
           << "  <key>pk</key><data>" << crypto_.getEd25519PublicKeyBase64() << "</data>\n"
           << "  <key>pi</key><string>2e388006-13ba-4041-9a67-25dd4a43d536</string>\n"
           << "  <key>keepAliveLowPower</key><true/>\n"
           << "  <key>keepAliveSendStatsAsBody</key><true/>\n"
           << "  <key>displays</key>\n"
           << "  <array>\n"
           << "    <dict>\n"
           << "      <key>features</key><integer>14</integer>\n"
           << "      <key>height</key><integer>" << height << "</integer>\n"
           << "      <key>heightPixels</key><integer>" << height << "</integer>\n"
           << "      <key>heightPhysical</key><integer>0</integer>\n"
           << "      <key>overscanned</key><false/>\n"
           << "      <key>refreshRate</key><real>" << (1.0 / refreshRate) << "</real>\n"
           << "      <key>maxFPS</key><integer>60</integer>\n"
           << "      <key>rotation</key><false/>\n"
           << "      <key>uuid</key><string>e0ff8a27-6738-3d56-8a16-cc53aacee925</string>\n"
           << "      <key>width</key><integer>" << width << "</integer>\n"
           << "      <key>widthPixels</key><integer>" << width << "</integer>\n"
           << "      <key>widthPhysical</key><integer>0</integer>\n"
           << "    </dict>\n"
           << "  </array>\n"
           << "  <key>initialVolume</key><real>0.0</real>\n"
           << "  <key>audioLatencies</key>\n"
           << "  <array>\n"
           << "    <dict>\n"
           << "      <key>audioType</key><string>default</string>\n"
           << "      <key>inputLatencyMicros</key><false/>\n"
           << "      <key>outputLatencyMicros</key><false/>\n"
           << "      <key>type</key><integer>100</integer>\n"
           << "    </dict>\n"
           << "    <dict>\n"
           << "      <key>audioType</key><string>default</string>\n"
           << "      <key>inputLatencyMicros</key><false/>\n"
           << "      <key>outputLatencyMicros</key><false/>\n"
           << "      <key>type</key><integer>101</integer>\n"
           << "    </dict>\n"
           << "  </array>\n"
           << "  <key>audioFormats</key>\n"
           << "  <array>\n"
           << "    <dict>\n"
           << "      <key>audioInputFormats</key><integer>67108860</integer>\n"
           << "      <key>audioOutputFormats</key><integer>67108860</integer>\n"
           << "      <key>type</key><integer>100</integer>\n"
           << "    </dict>\n"
           << "    <dict>\n"
           << "      <key>audioInputFormats</key><integer>67108860</integer>\n"
           << "      <key>audioOutputFormats</key><integer>67108860</integer>\n"
           << "      <key>type</key><integer>101</integer>\n"
           << "    </dict>\n"
           << "  </array>\n"
           << "</dict>\n"
           << "</plist>\n";

        std::string infoXml = ss.str();
        resp.headers["Content-Type"] = "text/x-apple-plist+xml";
        resp.body.assign(infoXml.begin(), infoXml.end());
    } else if (req.method == "POST" && req.uri.find("/pair-setup") != std::string::npos &&
               req.uri.find("/pair-setup-pin") == std::string::npos) {
        crypto_.handlePairSetup(req.body, resp.body);
        resp.headers["Content-Type"] = "application/octet-stream";
    } else if (req.method == "POST" && req.uri.find("/pair-pin-start") != std::string::npos) {
        // The sender asks for an on-screen PIN; the Java side generates and shows it.
        LOGI("Sender requested on-screen PIN pairing");
        if (onPinStart_) onPinStart_();
    } else if (req.method == "POST" && req.uri.find("/pair-setup-pin") != std::string::npos) {
        handlePairSetupPin(req, resp);
    } else if (req.method == "POST" && req.uri.find("/pair-verify") != std::string::npos) {
        // UxPlay -pin gate: in step 1 the client's Ed25519 key (bytes 36..67) must belong to a device
        // that paired with the PIN; otherwise answer 200 with no body so it starts PIN pairing.
        if (req.body.size() >= 68 && req.body[0] == 0x01) {
            std::lock_guard<std::mutex> lock(configMutex_);
            std::vector<uint8_t> clientKey(req.body.begin() + 36, req.body.begin() + 68);
            verifyKeyConnection_ = connectionId;
            verifyKey_ = clientKey;
            if (pinRequired_) {
                bool paired = std::find(pairedClients_.begin(), pairedClients_.end(), clientKey) != pairedClients_.end();
                if (!paired) {
                    LOGI("Pair-verify from an unpaired device; PIN pairing required");
                    resp.headers["Content-Type"] = "application/octet-stream";
                    return resp;
                }
            }
        }
        if (!crypto_.handlePairVerify(connectionId, req.body, resp.body)) {
            resp.closeConnection = true; // UxPlay: http_response_set_disconnect on a bad signature
        } else if (crypto_.isPairVerified(connectionId)) {
            std::lock_guard<std::mutex> lock(configMutex_);
            castTrusted_[connectionId] = clientIp;
        }
        resp.headers["Content-Type"] = "application/octet-stream";
    } else if (req.method == "POST" && req.uri.find("/fp-setup") != std::string::npos) {
        if (!crypto_.handleFpSetup(connectionId, req.body, resp.body)) {
            resp.statusCode = 400;
            resp.statusMessage = "Bad Request";
        }
        resp.headers["Content-Type"] = "application/octet-stream";
    } else if (req.method == "POST" && req.uri.find("/feedback") != std::string::npos) {
        resp.headers["Session"] = "1";
    } else if (req.method == "ANNOUNCE") {
        handleLegacyAnnounce(req, resp, connectionId);
        resp.headers["Session"] = "1";
    } else if (req.method == "SETUP" && !headerValue(req, "transport").empty()) {
        handleLegacySetup(req, resp, clientIp, connectionId, clientFd);
    } else if (req.method == "SETUP") {
        BplistValue setup;
        bool parsed = parseBinaryPlist(req.body, setup) && setup.isDict();
        // The session-level SETUP carries the FairPlay key and/or the sender's timing port.
        bool isSessionSetup = parsed && (setup.find("ekey") || setup.find("timingPort"));
        uint64_t owner = sessionConnection_.load();
        if (!pinAllows(connectionId)) {
            // With PIN pairing on, only a sender that proved its paired key may set anything up.
            LOGW("SETUP from connection #%" PRIu64 " without pair-verify; PIN pairing required", connectionId);
            resp.statusCode = 470;
            resp.statusMessage = "Connection Authorization Required";
            return resp;
        }
        if (isSessionSetup) {
            if (owner != 0 && owner != connectionId) {
                // Like an Apple TV (and UxPlay -nohold): the newest sender wins. This also lets a
                // sender that reconnected (Wi-Fi blip) replace its own stale connection at once.
                LOGI("Connection #%" PRIu64 " takes over the session from #%" PRIu64, connectionId, owner);
                int previousFd = sessionConnectionFd_;
                endSessionLocked("another sender took over");
                if (previousFd >= 0) shutdown(previousFd, SHUT_RDWR);
            }
            sessionConnection_.store(connectionId);
            sessionConnectionFd_ = clientFd;
        } else if (owner != 0 && owner != connectionId) {
            LOGW("Stream SETUP from connection #%" PRIu64 " rejected: the session belongs to #%" PRIu64,
                 connectionId, owner);
            resp.statusCode = 409;
            resp.statusMessage = "Conflict";
            return resp;
        } else if (owner == 0) {
            // A stream SETUP without a session SETUP (older senders): this connection owns what it sets up.
            sessionConnection_.store(connectionId);
            sessionConnectionFd_ = clientFd;
            std::lock_guard<std::mutex> lock(configMutex_);
            if (sessionClientIp_.empty()) sessionClientIp_ = clientIp;
        }

        uint64_t streamConnectionId = 0;
        crypto_.handleSetup(connectionId, req.body, streamConnectionId);

        resp.headers["Content-Type"] = "application/x-apple-binary-plist";
        resp.headers["Session"] = "1";
        const BplistValue* audioStream = nullptr;
        bool hasVideoStream = false;
        bool isAudioSetup;
        if (parsed) {
            if (const BplistValue* streams = setup.find("streams"); streams && streams->isArray()) {
                for (const BplistValue& stream : streams->arrayVal) {
                    uint64_t type = stream.intFor("type", 0);
                    if (type == 96 && !audioStream) audioStream = &stream;
                    if (type == 110) hasVideoStream = true;
                }
            }
            // The session-level SETUP names the sender (e.g. "Berk's MacBook Pro").
            if (const BplistValue* name = setup.find("name");
                name && name->type == BplistValue::Type::String && !name->strVal.empty()) {
                std::lock_guard<std::mutex> lock(configMutex_);
                clientName_ = name->strVal;
            }
            // ...and its hardware model (e.g. "iPhone15,2"), which picks the paired-device icon.
            if (const BplistValue* model = setup.find("model");
                model && model->type == BplistValue::Type::String && !model->strVal.empty()) {
                std::lock_guard<std::mutex> lock(configMutex_);
                clientModel_ = model->strVal;
            }
            // It also announces the sender's NTP timing port; query its clock like UxPlay does.
            if (setup.find("ekey") || setup.find("timingPort")) {
                const BplistValue* protocol = setup.find("timingProtocol");
                bool timingDisabled = protocol && protocol->type == BplistValue::Type::String && protocol->strVal == "None";
                uint64_t remoteTimingPort = setup.intFor("timingPort", 0);
                {
                    std::lock_guard<std::mutex> lock(configMutex_);
                    sessionClientIp_ = clientIp;
                }
                if (!timingDisabled && remoteTimingPort > 0 && remoteTimingPort <= 0xFFFF) {
                    ntp_.start(clientIp, static_cast<uint16_t>(remoteTimingPort), kDefaultTimingPort);
                }
            }
            isAudioSetup = (audioStream != nullptr) && !hasVideoStream;
        } else {
            // Fallback heuristic for bodies the plist reader cannot decode.
            std::string bStr(reinterpret_cast<const char*>(req.body.data()), req.body.size());
            bool hasEkey = (bStr.find("ekey") != std::string::npos);
            bool hasStreamConnId = (streamConnectionId != 0 || bStr.find("streamConnectionID") != std::string::npos);
            isAudioSetup = (bStr.find("spf") != std::string::npos ||
                            (bStr.find("controlPort") != std::string::npos && !hasStreamConnId && !hasEkey));
        }

        if (!isAudioSetup) {
            // Apple AirPlay mirroring Video SETUP response binary plist
            // Schema: { "eventPort": 7000, "timingPort": 7010, "streams": [{ "dataPort": videoPort_, "type": 110 }] }
            uint8_t videoSetupResponse[] = {
                98, 112, 108, 105, 115, 116, 48, 48, 211, 1, 2, 3, 4, 5, 11, 89, 101, 118, 101, 110, 116, 80, 111, 114, 116,
                87, 115, 116, 114, 101, 97, 109, 115, 90, 116, 105, 109, 105, 110, 103, 80, 111, 114, 116,
                17, 27, 88, // eventPort = 7000 (0x1B58)
                161, 6, 210, 7, 8, 9, 10, 88, 100, 97, 116, 97, 80, 111, 114, 116, 84, 116, 121, 112, 101,
                17, 27, 188, // dataPort = 7100 (0x1BBC) at bytes 69, 70
                16, 110, // type = 110
                17, 27, 98, // timingPort = 7010 (0x1B62) at bytes 74, 75
                8, 15, 25, 33, 44, 47, 49, 54, 63, 68, 71, 73,
                0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 12,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 76
            };
            uint16_t vPort = static_cast<uint16_t>(videoPort_);
            videoSetupResponse[69] = static_cast<uint8_t>((vPort >> 8) & 0xFF);
            videoSetupResponse[70] = static_cast<uint8_t>(vPort & 0xFF);
            uint16_t tPort = static_cast<uint16_t>(ntp_.localPort() > 0 ? ntp_.localPort() : kDefaultTimingPort);
            videoSetupResponse[74] = static_cast<uint8_t>((tPort >> 8) & 0xFF);
            videoSetupResponse[75] = static_cast<uint8_t>(tPort & 0xFF);

            resp.body.assign(videoSetupResponse, videoSetupResponse + sizeof(videoSetupResponse));
            LOGI("Responded to Video SETUP with streams: dataPort=%d, type=110", videoPort_);
        } else {
            configureAudioStream(audioStream);

            // Apple AirPlay mirroring Audio SETUP response binary plist
            // Schema: { "streams": [{ "dataPort": audioDataPort_, "controlPort": audioControlPort_, "type": 96 }] }
            uint8_t audioSetupResponse[] = {
                98, 112, 108, 105, 115, 116, 48, 48, 209, 1, 2, 87, 115, 116, 114, 101, 97, 109, 115,
                161, 3, 211, 4, 5, 6, 7, 8, 9, 91, 99, 111, 110, 116, 114, 111, 108, 80, 111, 114, 116,
                88, 100, 97, 116, 97, 80, 111, 114, 116, 84, 116, 121, 112, 101,
                17, 27, 100, // controlPort = 7012 (0x1B64) at index 55, 56
                17, 27, 99,  // dataPort = 7011 (0x1B63) at index 58, 59
                16, 96,      // type = 96 at index 61
                8, 11, 19, 21, 28, 40, 49, 54, 57, 60,
                0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 10,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 62
            };
            uint16_t cPort = static_cast<uint16_t>(audioControlPort_);
            audioSetupResponse[55] = static_cast<uint8_t>((cPort >> 8) & 0xFF);
            audioSetupResponse[56] = static_cast<uint8_t>(cPort & 0xFF);
            uint16_t dPort = static_cast<uint16_t>(audioDataPort_);
            audioSetupResponse[58] = static_cast<uint8_t>((dPort >> 8) & 0xFF);
            audioSetupResponse[59] = static_cast<uint8_t>(dPort & 0xFF);

            resp.body.assign(audioSetupResponse, audioSetupResponse + sizeof(audioSetupResponse));
            LOGI("Responded to Audio SETUP with streams: dataPort=%d, controlPort=%d, type=96", audioDataPort_, audioControlPort_);
        }
    } else if (req.method == "RECORD") {
        if (!pinAllows(connectionId)) {
            LOGW("RECORD from connection #%" PRIu64 " without pair-verify; PIN pairing required", connectionId);
            resp.statusCode = 470;
            resp.statusMessage = "Connection Authorization Required";
            return resp;
        }
        if (sessionConnection_.load() == 0) {
            sessionConnection_.store(connectionId);
            sessionConnectionFd_ = clientFd;
            std::lock_guard<std::mutex> lock(configMutex_);
            if (sessionClientIp_.empty()) sessionClientIp_ = clientIp;
        }
        isStreaming_.store(true);

        ClientInfo info;
        info.ipAddress = clientIp;
        {
            std::lock_guard<std::mutex> lock(configMutex_);
            info.deviceName = clientName_.empty() ? "Apple Device" : clientName_;
            info.clientModel = clientModel_;
            if (verifyKeyConnection_ == connectionId) info.clientKey = verifyKey_;
        }
        if (onClientConnected_) onClientConnected_(info);
        if (onStreamStarted_) onStreamStarted_();

        resp.headers["Audio-Latency"] = "11025";
        resp.headers["Audio-Jack-Status"] = "connected; type=analog";
        resp.headers["Session"] = "1";
    } else if (req.method == "GET_PARAMETER") {
        std::string reqBodyStr(reinterpret_cast<const char*>(req.body.data()), req.body.size());
        if (reqBodyStr.find("volume") != std::string::npos) {
            float volumeDb;
            {
                std::lock_guard<std::mutex> lock(configMutex_);
                volumeDb = volumeDb_;
            }
            char paramBody[64];
            std::snprintf(paramBody, sizeof(paramBody), "volume: %.6f\r\n", volumeDb);
            resp.headers["Content-Type"] = "text/parameters";
            resp.body.assign(paramBody, paramBody + std::strlen(paramBody));
        }
        resp.headers["Session"] = "1";
    } else if (req.method == "SET_PARAMETER") {
        auto ctIt = std::find_if(req.headers.begin(), req.headers.end(), [](const auto& kv) {
            return toLower(kv.first) == "content-type";
        });
        std::string contentType = ctIt != req.headers.end() ? toLower(ctIt->second) : std::string();
        if (contentType.find("text/parameters") != std::string::npos) {
            std::string bodyStr(reinterpret_cast<const char*>(req.body.data()), req.body.size());
            auto pos = bodyStr.find("volume:");
            if (pos != std::string::npos) {
                float volumeDb = std::strtof(bodyStr.c_str() + pos + 7, nullptr);
                {
                    std::lock_guard<std::mutex> lock(configMutex_);
                    volumeDb_ = volumeDb;
                }
                LOGI("Sender volume changed: %.2f dB", volumeDb);
                if (onVolumeChanged_) onVolumeChanged_(volumeDb);
            }
            // "progress: start/current/end" in RTP timestamps of the audio stream.
            pos = bodyStr.find("progress:");
            unsigned long long start = 0, current = 0, end = 0;
            if (pos != std::string::npos &&
                std::sscanf(bodyStr.c_str() + pos + 9, " %llu/%llu/%llu", &start, &current, &end) == 3) {
                int sampleRate;
                {
                    std::lock_guard<std::mutex> lock(audioMutex_);
                    sampleRate = audioSampleRate_ > 0 ? audioSampleRate_ : 44100;
                }
                // RTP timestamps are 32-bit and may wrap between start and end.
                int64_t position = static_cast<uint32_t>(static_cast<uint32_t>(current) - static_cast<uint32_t>(start));
                int64_t duration = static_cast<uint32_t>(static_cast<uint32_t>(end) - static_cast<uint32_t>(start));
                if (onProgress_) onProgress_(position * 1000 / sampleRate, duration * 1000 / sampleRate);
            }
        } else if (contentType.find("application/x-dmap-tagged") != std::string::npos) {
            TrackMetadata metadata;
            if (parseDmapMetadata(req.body.data(), req.body.size(), metadata)) {
                LOGI("Now playing: '%s' by '%s' (%s)", metadata.title.c_str(), metadata.artist.c_str(), metadata.album.c_str());
                if (onMetadata_) onMetadata_(metadata.title, metadata.artist, metadata.album);
            }
        } else if (contentType.rfind("image/", 0) == 0) {
            // image/jpeg or image/png carries the cover art; image/none clears it.
            bool hasImage = contentType.find("image/none") == std::string::npos && !req.body.empty();
            LOGI("Cover art received (%zu bytes, %s)", req.body.size(), contentType.c_str());
            if (onCoverArt_) onCoverArt_(hasImage ? req.body.data() : nullptr, hasImage ? req.body.size() : 0);
        }
        resp.headers["Session"] = "1";
    } else if (req.method == "PAUSE" || req.method == "FLUSH") {
        resp.headers["Session"] = "1";
    } else if (req.method == "TEARDOWN") {
        // UxPlay semantics: a body naming stream 96 stops audio, one naming stream 110 stops the
        // mirrored video (the session and audio may continue), and no streams ends the session.
        LOGI("Handling TEARDOWN request (body size=%zu)", req.body.size());
        bool hasAudioType = false;
        bool hasVideoType = false;
        if (!req.body.empty()) {
            BplistValue teardown;
            if (parseBinaryPlist(req.body, teardown) && teardown.isDict()) {
                if (const BplistValue* streams = teardown.find("streams"); streams && streams->isArray()) {
                    for (const BplistValue& stream : streams->arrayVal) {
                        uint64_t type = stream.intFor("type", 0);
                        hasAudioType |= (type == 96);
                        hasVideoType |= (type == 110);
                    }
                }
            } else {
                std::string bodyStr(reinterpret_cast<const char*>(req.body.data()), req.body.size());
                hasAudioType = (bodyStr.find("\x60") != std::string::npos); // 96
                hasVideoType = (bodyStr.find("\x6e") != std::string::npos); // 110
            }
        }
        LOGI("TEARDOWN inspection: hasAudio=%d, hasVideo=%d", hasAudioType, hasVideoType);

        if (hasAudioType) stopAudioStream();
        if (hasVideoType) stopVideoStream();
        if (!hasAudioType && !hasVideoType) endSessionLocked("TEARDOWN");
        resp.headers["Session"] = "1";
    }

    return resp;
}

void RaopRtspServer::audioUdpLoop() {
    // The sockets are owned by this thread for their whole lifetime; stop() only flips isRunning_.
    int dataFd = createUdpSocket(audioDataPort_);
    int controlFd = createUdpSocket(audioControlPort_);
    if (dataFd < 0 || controlFd < 0) {
        if (dataFd >= 0) close(dataFd);
        if (controlFd >= 0) close(controlFd);
        LOGE("Audio UDP receiver disabled: sockets unavailable");
        return;
    }
    LOGI("Audio UDP receiver thread started (Data: %d, Control: %d)", audioDataPort_, audioControlPort_);

    std::vector<uint8_t> packetBuf(kMaxAudioPacketBytes, 0);

    while (isRunning_.load()) {
        pollfd fds[2] = {{dataFd, POLLIN, 0}, {controlFd, POLLIN, 0}};
        int ready = poll(fds, 2, kAudioPollMs);

        if (ready > 0 && (fds[1].revents & POLLIN)) {
            ssize_t n = recv(controlFd, packetBuf.data(), packetBuf.size(), 0);
            if (n >= 4) handleAudioControlPacket(packetBuf.data(), static_cast<size_t>(n));
        }
        if (ready > 0 && (fds[0].revents & POLLIN)) {
            ssize_t n = recv(dataFd, packetBuf.data(), packetBuf.size(), 0);
            if (n >= 12) handleAudioDataPacket(packetBuf.data(), static_cast<size_t>(n));
        }

        // Runs on timeouts too, so a packet awaiting a retransmission is released once its wait expires.
        drainAudioBuffer();
        requestAudioResend(controlFd);
    }

    close(dataFd);
    close(controlFd);
    LOGI("Audio UDP receiver thread exited.");
}

void RaopRtspServer::handleAudioDataPacket(uint8_t* packet, size_t length) {
    size_t headerLen = 12 + 4 * static_cast<size_t>(packet[0] & 0x0F);
    if (packet[0] & 0x10) {
        if (length < headerLen + 4) return;
        size_t extWords = (static_cast<size_t>(packet[headerLen + 2]) << 8) | packet[headerLen + 3];
        headerLen += 4 + extWords * 4;
    }
    if (length < headerLen) return;

    uint16_t seq = static_cast<uint16_t>((packet[2] << 8) | packet[3]);
    uint32_t rtpTime = readBe32(packet + 4);
    uint8_t* payload = packet + headerLen;
    size_t payloadSize = length - headerLen;

    // Filler packets carry no audio and are not encrypted: 12-byte packets sent before time sync,
    // the AAC-ELD "no data" marker, and ALAC format-information packets (see UxPlay raop_rtp.c).
    // They are still buffered (empty) so their sequence numbers are not mistaken for losses.
    static const uint8_t kSilencePacket[4] = {0, 0, 0, 0};
    bool filler = payloadSize == 0 ||
                  (payloadSize == 4 && (std::memcmp(payload, kSilencePacket, 4) == 0 ||
                                        std::memcmp(payload, kNoDataMarker, 4) == 0));

    lastAudioPacketTicks_.store(std::chrono::steady_clock::now().time_since_epoch().count());
    std::lock_guard<std::mutex> lock(audioMutex_);
    if (audioCodec_ == AudioCodec::STOPPED) return;
    if (audioCodec_ == AudioCodec::ALAC && length == kAlacFillerPacketBytes) filler = true;

    if (filler) {
        audioBuffer_.enqueue(seq, rtpTime, nullptr, 0);
        return;
    }
    // Audio decryption using session AES key & IV (AES-128-CBC), before buffering like raop_buffer.c
    crypto_.decryptAudioPayload(payload, payloadSize);
    audioBuffer_.enqueue(seq, rtpTime, payload, payloadSize);
}

void RaopRtspServer::handleAudioControlPacket(uint8_t* packet, size_t length) {
    uint8_t type = packet[1] & 0x7F;
    if (type == 0x54 && length >= 20) {
        // RTCP sync: bytes 4-7 RTP time, 8-15 sender NTP time at which that sample plays.
        std::lock_guard<std::mutex> lock(audioMutex_);
        rtpSync_ = readBe32(packet + 4);
        remoteSyncNs_ = NtpClient::ntpToNs(readBe64(packet + 8));
        audioSynced_ = true;
    } else if (type == 0x56 && length >= 4 + 12) {
        // Retransmission reply: the original RTP packet follows a 4-byte header.
        handleAudioDataPacket(packet + 4, length - 4);
    }
}

int64_t RaopRtspServer::audioLocalTimeLocked(uint32_t rtpTime) {
    if (!audioSynced_ && audioCodec_ == AudioCodec::AAC_ELD) {
        // No RTCP sync yet: anchor the clock to the video arrival time plus DELAY_AAC, as UxPlay does.
        int64_t arrivalOffset = videoArrivalOffsetNs_.load();
        if (arrivalOffset != 0) {
            rtpSync_ = rtpTime;
            remoteSyncNs_ = NtpClient::monotonicNowNs() - arrivalOffset + kAacInitialSyncDelayNs;
            audioSynced_ = true;
        }
    }
    if (!audioSynced_ || audioSampleRate_ <= 0) return 0;
    int32_t delta = static_cast<int32_t>(rtpTime - rtpSync_);
    int64_t remoteNs = remoteSyncNs_ + static_cast<int64_t>(delta) * kSecondNs / audioSampleRate_;
    return ntp_.remoteToLocal(remoteNs);
}

void RaopRtspServer::drainAudioBuffer() {
    static uint64_t audioPacketCount = 0;
    for (;;) {
        std::vector<uint8_t> out;
        int64_t localTime = 0;
        {
            std::lock_guard<std::mutex> lock(audioMutex_);
            if (audioCodec_ == AudioCodec::STOPPED) return;
            AudioRtpBuffer::Packet packet;
            if (!audioBuffer_.dequeue(NtpClient::monotonicNowNs(), kAudioResendWaitNs, packet)) return;
            if (packet.payload.empty()) continue; // Filler packet
            localTime = audioLocalTimeLocked(packet.rtpTime);

            if (audioCodec_ == AudioCodec::ALAC) {
                if (!alacDecoder_) continue;
                // The ALAC bit reader looks past the payload; give it zeroed, readable slack.
                if (alacInput_.size() < packet.payload.size() + kAlacReadSlackBytes) {
                    alacInput_.assign(packet.payload.size() + kAlacReadSlackBytes, 0);
                }
                std::memcpy(alacInput_.data(), packet.payload.data(), packet.payload.size());
                std::memset(alacInput_.data() + packet.payload.size(), 0, 16);
                if (pcmOutput_.size() < kMaxAlacSamplesPerFrame * 4 + 64) {
                    pcmOutput_.resize(kMaxAlacSamplesPerFrame * 4 + 64);
                }
                int outputBytes = static_cast<int>(pcmOutput_.size());
                alac_decode_frame(alacDecoder_, alacInput_.data(), pcmOutput_.data(), &outputBytes);
                if (outputBytes <= 0 || static_cast<size_t>(outputBytes) > pcmOutput_.size()) continue;
                out.assign(pcmOutput_.begin(), pcmOutput_.begin() + outputBytes);
            } else {
                out = std::move(packet.payload);
            }
        }

        if (onAudioPacket_) onAudioPacket_(out.data(), out.size(), static_cast<uint64_t>(localTime));
        if (++audioPacketCount % 500 == 1) {
            LOGI("Audio packet forwarded: %zu bytes, local time=%" PRId64 " (packet #%" PRIu64 ")",
                 out.size(), localTime, audioPacketCount);
        }
    }
}

void RaopRtspServer::requestAudioResend(int controlFd) {
    uint16_t seq = 0;
    uint16_t count = 0;
    uint16_t remotePort;
    {
        std::lock_guard<std::mutex> lock(audioMutex_);
        remotePort = audioRemoteControlPort_;
        if (remotePort == 0 || audioCodec_ == AudioCodec::STOPPED) return;
        if (!audioBuffer_.takeResendRequest(seq, count)) return;
    }
    std::string clientIp;
    {
        std::lock_guard<std::mutex> lock(configMutex_);
        clientIp = sessionClientIp_;
    }
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(remotePort);
    if (inet_pton(AF_INET, clientIp.c_str(), &addr.sin_addr) != 1) return;

    // UxPlay raop_rtp_resend_callback: 0x80, 0x55|0x80, our seq, first missing seq, count.
    uint16_t ourSeq = resendSeq_++;
    uint8_t request[8] = {
        0x80, 0xD5,
        static_cast<uint8_t>(ourSeq >> 8), static_cast<uint8_t>(ourSeq),
        static_cast<uint8_t>(seq >> 8), static_cast<uint8_t>(seq),
        static_cast<uint8_t>(count >> 8), static_cast<uint8_t>(count)
    };
    sendto(controlFd, request, sizeof(request), 0, reinterpret_cast<sockaddr*>(&addr), sizeof(addr));
}

} // namespace airplay
