#include "raop_rtsp_server.h"
#include <android/log.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <sstream>
#include <cstring>

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

bool RaopRtspServer::start(int rtspPort) {
    if (isRunning_.load()) return true;

    rtspPort_ = rtspPort;
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

    isRunning_.store(true);
    serverThread_ = std::thread(&RaopRtspServer::serverLoop, this);
    LOGI("AirPlay RTSP Server started on port %d", rtspPort_);
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

    if (serverThread_.joinable()) {
        serverThread_.join();
    }

    if (videoUdpThread_.joinable()) {
        videoUdpThread_.join();
    }

    LOGI("AirPlay RTSP Server stopped cleanly.");
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
                size_t bodyBytesInBuf = bytesRead - bodyStart;
                req.body.insert(req.body.end(),
                                reinterpret_cast<const uint8_t*>(buffer + bodyStart),
                                reinterpret_cast<const uint8_t*>(buffer + bytesRead));

                size_t remainingToRead = contentLength - bodyBytesInBuf;
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
    if (isStreaming_.load()) {
        isStreaming_.store(false);
        if (onStreamStopped_) onStreamStopped_();
    }
}

RtspResponse RaopRtspServer::processRtspRequest(const RtspRequest& req, const std::string& clientIp) {
    RtspResponse resp;
    resp.headers["Server"] = "AirTunes/220.68";
    resp.headers["CSeq"] = std::to_string(req.cseq);

    LOGI("RTSP Request: %s %s (CSeq: %d)", req.method.c_str(), req.uri.c_str(), req.cseq);

    if (req.method == "OPTIONS") {
        resp.headers["Public"] = "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET";
    } else if (req.method == "GET" && req.uri.find("/info") != std::string::npos) {
        std::string infoXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                              "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                              "<plist version=\"1.0\">\n"
                              "<dict>\n"
                              "  <key>deviceID</key><string>00:11:22:33:44:55</string>\n"
                              "  <key>features</key><integer>1518338039</integer>\n"
                              "  <key>model</key><string>AppleTV3,2</string>\n"
                              "  <key>protovers</key><string>1.0</string>\n"
                              "  <key>srcvers</key><string>220.68</string>\n"
                              "</dict>\n"
                              "</plist>\n";
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
        resp.headers["Transport"] = "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record;server_port=7100";
        resp.headers["Session"] = "1";
    } else if (req.method == "RECORD") {
        isStreaming_.store(true);
        videoParser_.reset();
        
        ClientInfo info;
        info.ipAddress = clientIp;
        info.deviceName = "Apple Device";
        if (onClientConnected_) onClientConnected_(info);
        if (onStreamStarted_) onStreamStarted_();

        resp.headers["Audio-Latency"] = "11025";
        resp.headers["Session"] = "1";
    } else if (req.method == "GET_PARAMETER") {
        std::string paramBody = "volume: 0.000000\r\n";
        resp.headers["Content-Type"] = "text/parameters";
        resp.headers["Session"] = "1";
        resp.body.assign(paramBody.begin(), paramBody.end());
    } else if (req.method == "SET_PARAMETER") {
        resp.headers["Session"] = "1";
    } else if (req.method == "PAUSE" || req.method == "FLUSH") {
        resp.headers["Session"] = "1";
    } else if (req.method == "TEARDOWN") {
        isStreaming_.store(false);
        if (onStreamStopped_) onStreamStopped_();
        resp.headers["Session"] = "1";
    }

    return resp;
}

} // namespace airplay
