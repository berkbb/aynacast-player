#ifndef RAOP_RTSP_SERVER_H
#define RAOP_RTSP_SERVER_H

#include "airplay_protocol.h"
#include "airplay_crypto.h"
#include "h264_stream_parser.h"
#include "alac.h"
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

    void setDeviceIdentity(const std::string& deviceName, const std::string& macAddress);
    void setDisplayResolution(int width, int height, float refreshRate);

    bool start(int rtspPort = 7000, int videoPort = 7100);
    void stop();
    bool isRunning() const { return isRunning_.load(); }

    void setClientConnectedCallback(ClientConnectedCallback cb) { onClientConnected_ = std::move(cb); }
    void setStreamStartedCallback(StreamStartedCallback cb) { onStreamStarted_ = std::move(cb); }
    void setStreamStoppedCallback(StreamStoppedCallback cb) { onStreamStopped_ = std::move(cb); }
    void setAudioPacketCallback(AudioPacketCallback cb) { onAudioPacket_ = std::move(cb); }

    H264StreamParser& getVideoParser() { return videoParser_; }
    AirPlayCrypto& getCrypto() { return crypto_; }

private:
    void serverLoop();
    void handleClientConnection(int clientFd, const std::string& clientIp);

    void videoTcpLoop();
    void handleVideoClientConnection(int clientFd);

    void audioUdpLoop();

    RtspResponse processRtspRequest(const RtspRequest& req, const std::string& clientIp);

    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isStreaming_{false};
    int rtspPort_{7000};
    int videoPort_{7100};
    int audioDataPort_{7011};
    int audioControlPort_{7012};
    int serverFd_{-1};
    int videoServerFd_{-1};
    int audioDataFd_{-1};
    int audioControlFd_{-1};

    std::string deviceName_{"PhilipsTV"};
    std::string macAddress_{"00:11:22:33:44:55"};
    int displayWidth_{1920};
    int displayHeight_{1080};
    float displayRefreshRate_{50.0f};

    std::thread serverThread_;
    std::thread videoTcpThread_;
    std::thread audioThread_;

    AirPlayCrypto crypto_;
    H264StreamParser videoParser_;
    alac_file* alacDecoder_{nullptr};

    ClientConnectedCallback onClientConnected_;
    StreamStartedCallback onStreamStarted_;
    StreamStoppedCallback onStreamStopped_;
    AudioPacketCallback onAudioPacket_;
};

} // namespace airplay

#endif // RAOP_RTSP_SERVER_H
