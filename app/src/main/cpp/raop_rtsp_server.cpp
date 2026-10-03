#include "raop_rtsp_server.h"
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <sstream>
#include <cstring>
#include <vector>
#include <netinet/tcp.h>
#include <cinttypes>
#include "alac.h"

#define TAG "AirPlayRTSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace airplay {

std::string RtspResponse::serialize() const {
    std::ostringstream ss;
    ss << "RTSP/1.0 " << statusCode << " " << statusMessage << "\r\n";
    for (const auto& kv : headers) {
        ss << kv.first << ": " << kv.second << "\r\n";
    }
    ss << "Content-Length: " << body.size() << "\r\n";
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
}

void RaopRtspServer::setDeviceIdentity(const std::string& deviceName, const std::string& macAddress) {
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

    displayWidth_ = targetW;
    displayHeight_ = targetH;
    displayRefreshRate_ = targetFps;
    LOGI("Display resolution negotiated: %dx%d @ %.2f fps", displayWidth_, displayHeight_, displayRefreshRate_);
}

bool RaopRtspServer::start(int rtspPort, int videoPort) {
    if (isRunning_.load()) return true;

    rtspPort_ = rtspPort;
    videoPort_ = videoPort;

    // 1. Create RTSP control socket (port 7000)
    serverFd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (serverFd_ < 0) {
        LOGE("Failed to create RTSP socket: %s", strerror(errno));
        return false;
    }

    int opt = 1;
    setsockopt(serverFd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(static_cast<uint16_t>(rtspPort_));

    if (bind(serverFd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("Failed to bind RTSP socket on port %d: %s", rtspPort_, strerror(errno));
        close(serverFd_);
        serverFd_ = -1;
        return false;
    }

    if (listen(serverFd_, 5) < 0) {
        LOGE("Failed to listen on RTSP socket: %s", strerror(errno));
        close(serverFd_);
        serverFd_ = -1;
        return false;
    }

    // 2. Create Video TCP Mirroring socket (port 7100)
    videoServerFd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (videoServerFd_ < 0) {
        LOGE("Failed to create Video TCP socket: %s", strerror(errno));
    } else {
        setsockopt(videoServerFd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        sockaddr_in videoAddr{};
        videoAddr.sin_family = AF_INET;
        videoAddr.sin_addr.s_addr = INADDR_ANY;
        videoAddr.sin_port = htons(static_cast<uint16_t>(videoPort_));

        if (bind(videoServerFd_, reinterpret_cast<sockaddr*>(&videoAddr), sizeof(videoAddr)) < 0) {
            LOGE("Failed to bind Video TCP socket on port %d: %s", videoPort_, strerror(errno));
            close(videoServerFd_);
            videoServerFd_ = -1;
        } else if (listen(videoServerFd_, 5) < 0) {
            LOGE("Failed to listen on Video TCP socket: %s", strerror(errno));
            close(videoServerFd_);
            videoServerFd_ = -1;
        }
    }

    isRunning_.store(true);
    serverThread_ = std::thread(&RaopRtspServer::serverLoop, this);
    if (videoServerFd_ >= 0) {
        videoTcpThread_ = std::thread(&RaopRtspServer::videoTcpLoop, this);
    }
    audioThread_ = std::thread(&RaopRtspServer::audioUdpLoop, this);

    LOGI("AirPlay RTSP Server started on port %d, Video TCP on port %d, Audio UDP on %d", rtspPort_, videoPort_, audioDataPort_);
    return true;
}

void RaopRtspServer::stop() {
    if (!isRunning_.load()) return;

    isRunning_.store(false);
    isStreaming_.store(false);

    if (serverFd_ >= 0) {
        shutdown(serverFd_, SHUT_RDWR);
        close(serverFd_);
        serverFd_ = -1;
    }

    if (videoServerFd_ >= 0) {
        shutdown(videoServerFd_, SHUT_RDWR);
        close(videoServerFd_);
        videoServerFd_ = -1;
    }

    if (audioDataFd_ >= 0) {
        shutdown(audioDataFd_, SHUT_RDWR);
        close(audioDataFd_);
        audioDataFd_ = -1;
    }

    if (audioControlFd_ >= 0) {
        shutdown(audioControlFd_, SHUT_RDWR);
        close(audioControlFd_);
        audioControlFd_ = -1;
    }

    if (serverThread_.joinable()) {
        serverThread_.join();
    }

    if (videoTcpThread_.joinable()) {
        videoTcpThread_.join();
    }

    if (audioThread_.joinable()) {
        audioThread_.join();
    }

    if (alacDecoder_) {
        alac_free(alacDecoder_);
        alacDecoder_ = nullptr;
    }

    LOGI("AirPlay RTSP, Video & Audio Servers stopped cleanly.");
}

void RaopRtspServer::serverLoop() {
    while (isRunning_.load()) {
        sockaddr_in clientAddr{};
        socklen_t clientLen = sizeof(clientAddr);
        int clientFd = accept(serverFd_, reinterpret_cast<sockaddr*>(&clientAddr), &clientLen);

        if (clientFd < 0) {
            if (isRunning_.load()) {
                LOGW("RTSP accept failed: %s", strerror(errno));
            }
            break;
        }

        char clientIp[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &clientAddr.sin_addr, clientIp, sizeof(clientIp));
        LOGI("Accepted incoming AirPlay connection from %s:%d", clientIp, ntohs(clientAddr.sin_port));

        std::thread([this, clientFd, ip = std::string(clientIp)]() {
            handleClientConnection(clientFd, ip);
        }).detach();
    }
}

void RaopRtspServer::videoTcpLoop() {
    while (isRunning_.load()) {
        sockaddr_in clientAddr{};
        socklen_t clientLen = sizeof(clientAddr);
        int clientFd = accept(videoServerFd_, reinterpret_cast<sockaddr*>(&clientAddr), &clientLen);

        if (clientFd < 0) {
            if (isRunning_.load()) {
                LOGW("Video TCP accept failed: %s", strerror(errno));
            }
            break;
        }

        int nodelay = 1;
        setsockopt(clientFd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));
        int rcvbuf = 2 * 1024 * 1024;
        setsockopt(clientFd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

        char clientIp[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &clientAddr.sin_addr, clientIp, sizeof(clientIp));
        LOGI("Accepted incoming video streaming TCP connection from %s:%d (TCP_NODELAY=1, SO_RCVBUF=2MB)",
             clientIp, ntohs(clientAddr.sin_port));

        std::thread([this, clientFd]() {
            handleVideoClientConnection(clientFd);
        }).detach();
    }
}

void RaopRtspServer::handleVideoClientConnection(int clientFd) {
    int nodelay = 1;
    setsockopt(clientFd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));
    int rcvbuf = 2 * 1024 * 1024;
    setsockopt(clientFd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));

    struct timeval tv{5, 0}; // 5-second socket timeout
    setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

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
        size_t headerRead = 0;
        while (headerRead < VIDEO_HEADER_SIZE) {
            ssize_t n = recv(clientFd, header + headerRead, VIDEO_HEADER_SIZE - headerRead, 0);
            if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    continue; // Timeout during idle screen: keep video socket alive!
                }
                goto end_video;
            } else if (n == 0) {
                goto end_video;
            }
            headerRead += n;
        }

        uint32_t payloadLen = static_cast<uint32_t>(header[0]) |
                             (static_cast<uint32_t>(header[1]) << 8) |
                             (static_cast<uint32_t>(header[2]) << 16) |
                             (static_cast<uint32_t>(header[3]) << 24);

        uint8_t packetType = header[4] & 0xFF;

        uint64_t pts = 0;
        std::memcpy(&pts, header + 8, sizeof(pts));

        vPacketCount++;
        if (vPacketCount % 300 == 1) {
            LOGI("Video stream active: frame #%" PRIu64 " type=0x%02x payloadLen=%u",
                 vPacketCount, packetType, payloadLen);
        }

        if (payloadLen == 0) {
            continue;
        }

        if (payloadLen > 10 * 1024 * 1024) {
            LOGW("Skipping oversized video packet: invalid payloadLen=%u type=0x%02x", payloadLen, packetType);
            continue;
        }

        if (payload.size() < payloadLen) {
            payload.resize(payloadLen);
        }

        size_t payloadRead = 0;
        while (payloadRead < payloadLen) {
            ssize_t n = recv(clientFd, payload.data() + payloadRead, payloadLen - payloadRead, 0);
            if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    continue;
                }
                goto end_video;
            } else if (n == 0) {
                goto end_video;
            }
            payloadRead += n;
        }

        if (packetType == 0x01) {
            LOGI("Video SPS/PPS config packet: payloadLen=%u", payloadLen);
            videoParser_.parseSpsPps(payload.data(), payloadLen);
        } else if (packetType == 0x00) {
            if (crypto_.hasVideoKey()) {
                crypto_.decryptVideoPayload(payload.data(), payloadLen);
            }
            videoParser_.parsePayload(payload.data(), payloadLen, pts);
        } else if (packetType == 0x05) {
            // Periodic AirPlay mirror streaming performance report (unencrypted plist, sent ~1/s)
            // Do not decrypt or forward to H.264 parser.
        } else {
            LOGI("Unknown video packet type=0x%02x payloadLen=%u", packetType, payloadLen);
        }
    }

end_video:
    close(clientFd);
    LOGI("Video streaming TCP client disconnected.");
}

void RaopRtspServer::handleClientConnection(int clientFd, const std::string& clientIp) {
    char buffer[4096];

    while (isRunning_.load()) {
        ssize_t bytesRead = recv(clientFd, buffer, sizeof(buffer) - 1, 0);
        if (bytesRead <= 0) {
            break;
        }

        buffer[bytesRead] = '\0';
        std::string rawRequest(buffer, bytesRead);

        // Parse basic RTSP request lines
        std::istringstream stream(rawRequest);
        std::string requestLine;
        if (!std::getline(stream, requestLine)) continue;

        if (!requestLine.empty() && requestLine.back() == '\r') {
            requestLine.pop_back();
        }

        std::istringstream lineStream(requestLine);
        RtspRequest req;
        lineStream >> req.method >> req.uri >> req.version;

        std::string headerLine;
        int contentLength = 0;
        while (std::getline(stream, headerLine) && headerLine != "\r" && !headerLine.empty()) {
            if (headerLine.back() == '\r') headerLine.pop_back();
            auto colonPos = headerLine.find(':');
            if (colonPos != std::string::npos) {
                std::string key = headerLine.substr(0, colonPos);
                std::string val = headerLine.substr(colonPos + 1);
                while (!val.empty() && val.front() == ' ') val.erase(0, 1);
                req.headers[key] = val;

                if (key == "CSeq") req.cseq = std::atoi(val.c_str());
                if (key == "Content-Length") contentLength = std::atoi(val.c_str());
            }
        }

        // Read remaining body if present
        if (contentLength > 0) {
            std::string headerEnd = "\r\n\r\n";
            auto endPos = rawRequest.find(headerEnd);
            if (endPos != std::string::npos) {
                size_t bodyStart = endPos + 4;
                size_t bodyBytesInBuf = (bytesRead > static_cast<ssize_t>(bodyStart)) ? (bytesRead - bodyStart) : 0;
                size_t bodyBytesToTake = std::min(static_cast<size_t>(contentLength), bodyBytesInBuf);
                req.body.insert(req.body.end(),
                                reinterpret_cast<const uint8_t*>(buffer + bodyStart),
                                reinterpret_cast<const uint8_t*>(buffer + bodyStart + bodyBytesToTake));

                size_t remainingToRead = (static_cast<size_t>(contentLength) > bodyBytesToTake) ?
                                         (static_cast<size_t>(contentLength) - bodyBytesToTake) : 0;
                while (remainingToRead > 0) {
                    char bodyChunk[2048];
                    ssize_t n = recv(clientFd, bodyChunk, std::min(sizeof(bodyChunk), remainingToRead), 0);
                    if (n <= 0) break;
                    req.body.insert(req.body.end(), reinterpret_cast<uint8_t*>(bodyChunk), reinterpret_cast<uint8_t*>(bodyChunk + n));
                    remainingToRead -= n;
                }
            }
        }

        RtspResponse resp = processRtspRequest(req, clientIp);
        std::string serialized = resp.serialize();
        send(clientFd, serialized.data(), serialized.size(), 0);
    }

    close(clientFd);
    LOGI("RTSP client connection closed for %s", clientIp.c_str());
}

RtspResponse RaopRtspServer::processRtspRequest(const RtspRequest& req, const std::string& clientIp) {
    RtspResponse resp;
    resp.headers["Server"] = "AirTunes/220.68";
    resp.headers["CSeq"] = std::to_string(req.cseq);

    LOGI("RTSP Request: %s %s (CSeq: %d)", req.method.c_str(), req.uri.c_str(), req.cseq);

    if (req.method == "OPTIONS") {
        resp.headers["Public"] = "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET";
    } else if (req.method == "GET" && req.uri.find("/info") != std::string::npos) {
        std::ostringstream ss;
        ss << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
           << "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
           << "<plist version=\"1.0\">\n"
           << "<dict>\n"
           << "  <key>deviceID</key><string>" << macAddress_ << "</string>\n"
           << "  <key>macAddress</key><string>" << macAddress_ << "</string>\n"
           << "  <key>name</key><string>" << deviceName_ << "</string>\n"
           << "  <key>model</key><string>AppleTV5,3</string>\n"
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
           << "      <key>height</key><integer>" << displayHeight_ << "</integer>\n"
           << "      <key>heightPixels</key><integer>" << displayHeight_ << "</integer>\n"
           << "      <key>heightPhysical</key><integer>0</integer>\n"
           << "      <key>overscanned</key><false/>\n"
           << "      <key>refreshRate</key><real>" << (1.0 / displayRefreshRate_) << "</real>\n"
           << "      <key>maxFPS</key><integer>60</integer>\n"
           << "      <key>rotation</key><false/>\n"
           << "      <key>uuid</key><string>e0ff8a27-6738-3d56-8a16-cc53aacee925</string>\n"
           << "      <key>width</key><integer>" << displayWidth_ << "</integer>\n"
           << "      <key>widthPixels</key><integer>" << displayWidth_ << "</integer>\n"
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
    } else if (req.method == "POST" && req.uri.find("/pair-setup") != std::string::npos) {
        crypto_.handlePairSetup(req.body, resp.body);
        resp.headers["Content-Type"] = "application/octet-stream";
    } else if (req.method == "POST" && req.uri.find("/pair-verify") != std::string::npos) {
        crypto_.handlePairVerify(req.body, resp.body);
        resp.headers["Content-Type"] = "application/octet-stream";
    } else if (req.method == "POST" && req.uri.find("/fp-setup") != std::string::npos) {
        crypto_.handleFpSetup(req.body, resp.body);
        resp.headers["Content-Type"] = "application/octet-stream";
    } else if (req.method == "POST" && req.uri.find("/feedback") != std::string::npos) {
        resp.headers["Session"] = "1";
    } else if (req.method == "ANNOUNCE") {
        resp.headers["Session"] = "1";
    } else if (req.method == "SETUP") {
        uint64_t streamConnectionId = 0;
        crypto_.handleSetup(req.body, streamConnectionId);

        resp.headers["Content-Type"] = "application/x-apple-binary-plist";
        resp.headers["Session"] = "1";

        std::string bStr(reinterpret_cast<const char*>(req.body.data()), req.body.size());
        bool hasEkey = (bStr.find("ekey") != std::string::npos);
        bool hasStreamConnId = (streamConnectionId != 0 || bStr.find("streamConnectionID") != std::string::npos);
        bool isAudioSetup = (bStr.find("spf") != std::string::npos || (bStr.find("controlPort") != std::string::npos && !hasStreamConnId && !hasEkey));

        if (!isAudioSetup) {
            // Apple AirPlay mirroring Video SETUP response binary plist
            // Schema: { "eventPort": 7000, "timingPort": 7010, "streams": [{ "dataPort": videoPort_, "type": 110 }] }
            static uint8_t baseVideoSetupResponse[] = {
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
            baseVideoSetupResponse[69] = static_cast<uint8_t>((vPort >> 8) & 0xFF);
            baseVideoSetupResponse[70] = static_cast<uint8_t>(vPort & 0xFF);

            resp.body.assign(baseVideoSetupResponse, baseVideoSetupResponse + sizeof(baseVideoSetupResponse));
            LOGI("Responded to Video SETUP with streams: dataPort=%d, type=110", videoPort_);
        } else {
            // Apple AirPlay mirroring Audio SETUP response binary plist
            // Schema: { "streams": [{ "dataPort": audioDataPort_, "controlPort": audioControlPort_, "type": 96 }] }
            static uint8_t baseAudioSetupResponse[] = {
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
            baseAudioSetupResponse[55] = static_cast<uint8_t>((cPort >> 8) & 0xFF);
            baseAudioSetupResponse[56] = static_cast<uint8_t>(cPort & 0xFF);
            uint16_t dPort = static_cast<uint16_t>(audioDataPort_);
            baseAudioSetupResponse[58] = static_cast<uint8_t>((dPort >> 8) & 0xFF);
            baseAudioSetupResponse[59] = static_cast<uint8_t>(dPort & 0xFF);

            resp.body.assign(baseAudioSetupResponse, baseAudioSetupResponse + sizeof(baseAudioSetupResponse));
            LOGI("Responded to Audio SETUP with streams: dataPort=%d, controlPort=%d, type=96", audioDataPort_, audioControlPort_);
        }
    } else if (req.method == "RECORD") {
        isStreaming_.store(true);
        videoParser_.reset();

        ClientInfo info;
        info.ipAddress = clientIp;
        info.deviceName = "Apple Device";
        if (onClientConnected_) onClientConnected_(info);
        if (onStreamStarted_) onStreamStarted_();

        resp.headers["Audio-Latency"] = "11025";
        resp.headers["Audio-Jack-Status"] = "connected; type=analog";
        resp.headers["Session"] = "1";
    } else if (req.method == "GET_PARAMETER") {
        std::string reqBodyStr(reinterpret_cast<const char*>(req.body.data()), req.body.size());
        if (reqBodyStr.find("volume") != std::string::npos) {
            std::string paramBody = "volume: 0.0\r\n";
            resp.headers["Content-Type"] = "text/parameters";
            resp.body.assign(paramBody.begin(), paramBody.end());
        }
        resp.headers["Session"] = "1";
    } else if (req.method == "SET_PARAMETER") {
        resp.headers["Session"] = "1";
    } else if (req.method == "PAUSE" || req.method == "FLUSH") {
        resp.headers["Session"] = "1";
    } else if (req.method == "TEARDOWN") {
        LOGI("Handling TEARDOWN request (body size=%zu)", req.body.size());
        bool teardownAudioOnly = false;
        if (!req.body.empty()) {
            std::string bodyStr(reinterpret_cast<const char*>(req.body.data()), req.body.size());
            bool hasAudioType = (bodyStr.find("\x60") != std::string::npos); // 96
            bool hasVideoType = (bodyStr.find("\x6e") != std::string::npos); // 110
            if (hasAudioType && !hasVideoType) {
                teardownAudioOnly = true;
            }
            LOGI("TEARDOWN inspection: hasAudio=%d, hasVideo=%d, teardownAudioOnly=%d",
                 hasAudioType, hasVideoType, teardownAudioOnly);
        }
        if (!teardownAudioOnly) {
            isStreaming_.store(false);
            crypto_.resetSessionCrypto();
            if (onStreamStopped_) onStreamStopped_();
        } else {
            LOGI("TEARDOWN request is audio-only; preserving active video mirroring stream.");
        }
        resp.headers["Session"] = "1";
    }

    return resp;
}

void RaopRtspServer::audioUdpLoop() {
    LOGI("Audio UDP receiver thread started (Data: %d, Control: %d)", audioDataPort_, audioControlPort_);

    audioDataFd_ = socket(AF_INET, SOCK_DGRAM, 0);
    audioControlFd_ = socket(AF_INET, SOCK_DGRAM, 0);

    if (audioDataFd_ < 0 || audioControlFd_ < 0) {
        LOGE("Failed to create audio UDP sockets: %s", strerror(errno));
        return;
    }

    int opt = 1;
    setsockopt(audioDataFd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    setsockopt(audioControlFd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    int rcvBuf = 512 * 1024;
    setsockopt(audioDataFd_, SOL_SOCKET, SO_RCVBUF, &rcvBuf, sizeof(rcvBuf));

    sockaddr_in dataAddr{};
    dataAddr.sin_family = AF_INET;
    dataAddr.sin_addr.s_addr = INADDR_ANY;
    dataAddr.sin_port = htons(static_cast<uint16_t>(audioDataPort_));

    if (bind(audioDataFd_, reinterpret_cast<sockaddr*>(&dataAddr), sizeof(dataAddr)) < 0) {
        LOGE("Failed to bind audio data UDP port %d: %s", audioDataPort_, strerror(errno));
        return;
    }

    sockaddr_in controlAddr{};
    controlAddr.sin_family = AF_INET;
    controlAddr.sin_addr.s_addr = INADDR_ANY;
    controlAddr.sin_port = htons(static_cast<uint16_t>(audioControlPort_));

    if (bind(audioControlFd_, reinterpret_cast<sockaddr*>(&controlAddr), sizeof(controlAddr)) < 0) {
        LOGE("Failed to bind audio control UDP port %d: %s", audioControlPort_, strerror(errno));
        return;
    }

    // Initialize ALAC Decoder for 16-bit 2-channel 44.1kHz stereo
    if (!alacDecoder_) {
        alacDecoder_ = alac_create(16, 2);
        if (alacDecoder_) {
            unsigned char decoderInfo[48] = {0};

            auto set_u16 = [](unsigned char* buf, uint16_t val) {
                buf[0] = static_cast<unsigned char>((val >> 8) & 0xFF);
                buf[1] = static_cast<unsigned char>(val & 0xFF);
            };
            auto set_u32 = [](unsigned char* buf, uint32_t val) {
                buf[0] = static_cast<unsigned char>((val >> 24) & 0xFF);
                buf[1] = static_cast<unsigned char>((val >> 16) & 0xFF);
                buf[2] = static_cast<unsigned char>((val >> 8) & 0xFF);
                buf[3] = static_cast<unsigned char>(val & 0xFF);
            };

            set_u32(&decoderInfo[0], 48);
            std::memcpy(&decoderInfo[4], "frma", 4);
            std::memcpy(&decoderInfo[8], "alac", 4);
            set_u32(&decoderInfo[12], 36);
            std::memcpy(&decoderInfo[16], "alac", 4);
            set_u32(&decoderInfo[20], 0);

            set_u32(&decoderInfo[24], 352); // frameLength (352 samples per frame)
            decoderInfo[28] = 0;           // compatibleVersion
            decoderInfo[29] = 16;          // bitDepth (16-bit)
            decoderInfo[30] = 40;          // pb (0x28)
            decoderInfo[31] = 10;          // mb (0x0A)
            decoderInfo[32] = 14;          // kb (0x0E)
            decoderInfo[33] = 2;           // numChannels (stereo)
            set_u16(&decoderInfo[34], 255); // maxRun
            set_u32(&decoderInfo[36], 0);   // maxFrameBytes
            set_u32(&decoderInfo[40], 0);   // avgBitRate
            set_u32(&decoderInfo[44], 44100); // sampleRate (44100 Hz)

            alac_set_info(alacDecoder_, reinterpret_cast<char*>(decoderInfo));
            LOGI("ALAC Decoder initialized successfully (max_samples_per_frame=%u, 44100 Hz, 16-bit stereo).",
                 alacDecoder_->setinfo_max_samples_per_frame);
        } else {
            LOGE("Failed to create ALAC decoder!");
        }
    }

    std::vector<uint8_t> pcmOutput(4096);
    uint8_t packetBuf[4096 + 32];
    std::memset(packetBuf, 0, sizeof(packetBuf));
    uint64_t audioPacketCount = 0;

    while (isRunning_.load()) {
        fd_set readFds;
        FD_ZERO(&readFds);
        FD_SET(audioDataFd_, &readFds);
        FD_SET(audioControlFd_, &readFds);
        int maxFd = std::max(audioDataFd_, audioControlFd_);

        timeval tv{0, 100000}; // 100 ms timeout
        int ret = select(maxFd + 1, &readFds, nullptr, nullptr, &tv);
        if (ret <= 0) {
            continue;
        }

        // Handle RTCP / Keepalive packets
        if (FD_ISSET(audioControlFd_, &readFds)) {
            sockaddr_in clientAddr{};
            socklen_t addrLen = sizeof(clientAddr);
            ssize_t cBytes = recvfrom(audioControlFd_, packetBuf, 4096, 0,
                                      reinterpret_cast<sockaddr*>(&clientAddr), &addrLen);
            (void)cBytes;
        }

        // Handle Audio RTP packets
        if (FD_ISSET(audioDataFd_, &readFds)) {
            sockaddr_in clientAddr{};
            socklen_t addrLen = sizeof(clientAddr);
            ssize_t bytesRead = recvfrom(audioDataFd_, packetBuf, 4096, 0,
                                         reinterpret_cast<sockaddr*>(&clientAddr), &addrLen);
            if (bytesRead < 12) {
                continue;
            }

            uint32_t timestamp = (static_cast<uint32_t>(packetBuf[4]) << 24) |
                                 (static_cast<uint32_t>(packetBuf[5]) << 16) |
                                 (static_cast<uint32_t>(packetBuf[6]) << 8)  |
                                 static_cast<uint32_t>(packetBuf[7]);

            size_t payloadSize = static_cast<size_t>(bytesRead - 12);
            uint8_t* payload = packetBuf + 12;

            if (payloadSize == 0 || (payloadSize == 4 && std::memcmp(payload, "\x00\x00\x00\x00", 4) == 0)) {
                continue;
            }

            // Zero-pad after payload to guarantee bitreader safe lookahead
            std::memset(payload + payloadSize, 0, 16);

            // Audio decryption using session AES key & IV (AES-128-CBC)
            crypto_.decryptAudioPayload(payload, payloadSize);

            // ALAC decoding to 16-bit PCM stereo
            if (alacDecoder_ && alacDecoder_->setinfo_max_samples_per_frame > 0) {
                int outputBytes = static_cast<int>(pcmOutput.size());
                alac_decode_frame(alacDecoder_, payload, pcmOutput.data(), &outputBytes);

                if (outputBytes > 0 && onAudioPacket_) {
                    onAudioPacket_(pcmOutput.data(), static_cast<size_t>(outputBytes), timestamp);
                    audioPacketCount++;
                    if (audioPacketCount % 100 == 1) {
                        LOGI("Audio PCM decoded & played: %d bytes (samples: %d), timestamp=%u (packet #%llu)",
                             outputBytes, outputBytes / 4, timestamp, (unsigned long long)audioPacketCount);
                    }
                }
            }
        }
    }

    if (audioDataFd_ >= 0) {
        close(audioDataFd_);
        audioDataFd_ = -1;
    }
    if (audioControlFd_ >= 0) {
        close(audioControlFd_);
        audioControlFd_ = -1;
    }

    LOGI("Audio UDP receiver thread exited.");
}

} // namespace airplay
