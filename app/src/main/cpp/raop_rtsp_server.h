#ifndef RAOP_RTSP_SERVER_H
#define RAOP_RTSP_SERVER_H

#include "airplay_protocol.h"
#include "airplay_crypto.h"
#include "h264_stream_parser.h"
#include <atomic>
#include <thread>
#include <functional>
#include <string>

namespace airplay {

class RaopRtspServer {
public:
    using ClientConnectedCallback = std::function<void(const ClientInfo& client)>;
    using StreamStartedCallback = std::function<void()>;
    using StreamStoppedCallback = std::function<void()>;
    using AudioPacketCallback = std::function<void(const uint8_t* pcmData, size_t size, uint64_t pts)>;

    RaopRtspServer();
    ~RaopRtspServer();

    bool start(int rtspPort = 7000);
    void stop();
    bool isRunning() const { return isRunning_.load(); }

    void setClientConnectedCallback(ClientConnectedCallback cb) { onClientConnected_ = std::move(cb); }
    void setStreamStartedCallback(StreamStartedCallback cb) { onStreamStarted_ = std::move(cb); }
    void setStreamStoppedCallback(StreamStoppedCallback cb) { onStreamStopped_ = std::move(cb); }
    void setAudioPacketCallback(AudioPacketCallback cb) { onAudioPacket_ = std::move(cb); }

    H264StreamParser& getVideoParser() { return videoParser_; }

private:
    void serverLoop();
    void handleClientConnection(int clientFd, const std::string& clientIp);
    void handleVideoUdpLoop(int videoUdpPort);
    
    RtspResponse processRtspRequest(const RtspRequest& req, const std::string& clientIp);

    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isStreaming_{false};
    int rtspPort_{7000};
    int serverFd_{-1};

    std::thread serverThread_;
    std::thread videoUdpThread_;

    AirPlayCrypto crypto_;
    H264StreamParser videoParser_;

    ClientConnectedCallback onClientConnected_;
    StreamStartedCallback onStreamStarted_;
    StreamStoppedCallback onStreamStopped_;
    AudioPacketCallback onAudioPacket_;
};

} // namespace airplay

#endif // RAOP_RTSP_SERVER_H
