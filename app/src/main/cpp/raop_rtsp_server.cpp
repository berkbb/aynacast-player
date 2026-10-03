#include "raop_rtsp_server.h"
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <sstream>
#include <cstring>
#include <vector>

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

    LOGI("AirPlay RTSP Server started on port %d, Video TCP on port %d", rtspPort_, videoPort_);
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

    if (serverThread_.joinable()) {
        serverThread_.join();
    }

    if (videoTcpThread_.joinable()) {
        videoTcpThread_.join();
    }

    LOGI("AirPlay RTSP & Video Servers stopped cleanly.");
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

        char clientIp[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &clientAddr.sin_addr, clientIp, sizeof(clientIp));
        LOGI("Accepted incoming video streaming TCP connection from %s:%d", clientIp, ntohs(clientAddr.sin_port));

        std::thread([this, clientFd]() {
            handleVideoClientConnection(clientFd);
        }).detach();
    }
}

void RaopRtspServer::handleVideoClientConnection(int clientFd) {
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

        LOGI("Video packet: type=0x%02x payloadLen=%u", packetType, payloadLen);

        if (payloadLen == 0) {
            continue;
        }

        if (payloadLen > 10 * 1024 * 1024) {
            LOGW("Skipping oversized video packet: invalid payloadLen=%u type=0x%02x", payloadLen, packetType);
            continue;
        }

        std::vector<uint8_t> payload(payloadLen);
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
            videoParser_.parseSpsPps(payload.data(), payload.size());
        } else if (packetType == 0x00) {
            if (crypto_.hasVideoKey()) {
                crypto_.decryptVideoPayload(payload.data(), payload.size());
            }
            videoParser_.parsePayload(payload.data(), payload.size(), pts);
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
           << "  <key>model</key><string>AppleTV3,2</string>\n"
           << "  <key>features</key><integer>1518337766</integer>\n"
           << "  <key>statusFlags</key><integer>68</integer>\n"
           << "  <key>protovers</key><string>1.0</string>\n"
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
           << "      <key>rotation</key><false/>\n"
           << "      <key>uuid</key><string>e0ff8a27-6738-3d56-8a16-cc53aacee925</string>\n"
           << "      <key>width</key><integer>" << displayWidth_ << "</integer>\n"
           << "      <key>widthPixels</key><integer>" << displayWidth_ << "</integer>\n"
           << "      <key>widthPhysical</key><integer>0</integer>\n"
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
    } else if (req.method == "ANNOUNCE") {
        resp.headers["Session"] = "1";
    } else if (req.method == "SETUP") {
        uint64_t streamConnectionId = 0;
        crypto_.handleSetup(req.body, streamConnectionId);

        resp.headers["Content-Type"] = "application/x-apple-binary-plist";
        resp.headers["Session"] = "1";

        // Apple AirPlay mirroring SETUP response binary plist
        // Schema: { "eventPort": 7000, "timingPort": 7010, "streams": [{ "dataPort": videoPort_, "type": 110 }] }
        static uint8_t baseSetupResponse[] = {
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
        baseSetupResponse[69] = static_cast<uint8_t>((vPort >> 8) & 0xFF);
        baseSetupResponse[70] = static_cast<uint8_t>(vPort & 0xFF);

        resp.body.assign(baseSetupResponse, baseSetupResponse + sizeof(baseSetupResponse));
        LOGI("Responded to SETUP with streams: dataPort=%d, type=110", videoPort_);
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
        isStreaming_.store(false);
        crypto_.resetSessionCrypto();
        if (onStreamStopped_) onStreamStopped_();
        resp.headers["Session"] = "1";
    }

    return resp;
}

} // namespace airplay
