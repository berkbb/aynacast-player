#ifndef RAOP_RTSP_SERVER_H
#define RAOP_RTSP_SERVER_H

#include "airplay_protocol.h"
#include "airplay_crypto.h"
#include "h264_stream_parser.h"
#include "alac.h"
#include "audio_rtp_buffer.h"
#include "raop_ntp.h"
#include "video_cast.h"
#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace airplay {

struct BplistValue;

// Audio payload formats forwarded to the Java layer. ALAC is decoded natively and therefore
// reaches Java as PCM; AAC variants are forwarded as encoded access units for MediaCodec.
enum class AudioCodec : int {
    STOPPED = -1,
    PCM_S16 = 0,
    ALAC = 2,
    AAC_LC = 4,
    AAC_ELD = 8
};

class RaopRtspServer {
public:
    using ClientConnectedCallback = std::function<void(const ClientInfo& client)>;
    using StreamStartedCallback = std::function<void()>;
    using StreamStoppedCallback = std::function<void()>;
    using VideoStoppedCallback = std::function<void()>;
    using AudioPacketCallback = std::function<void(const uint8_t* data, size_t size, uint64_t pts)>;
    using AudioConfigCallback = std::function<void(int codec, int sampleRate, int channels, int samplesPerFrame)>;
    using VolumeCallback = std::function<void(float volumeDb)>;
    using MetadataCallback = std::function<void(const std::string& title, const std::string& artist, const std::string& album)>;
    // Empty data means "no artwork" (image/none).
    using CoverArtCallback = std::function<void(const uint8_t* data, size_t size)>;
    using ProgressCallback = std::function<void(int64_t positionMs, int64_t durationMs)>;
    using PinStartCallback = std::function<void()>;
    // PIN pairing step (1..3) computed by the Java SRP implementation. Step 1: in = {} -> out = {B, salt};
    // step 2: in = {A, M1} -> out = {M2}; step 3: in = {epk, authTag, serverPk} -> out = {epk, authTag, clientPk}.
    // Returns false when the step fails (wrong PIN / bad tag).
    using PinSetupCallback = std::function<bool(int step, const std::string& user,
                                                const std::vector<std::vector<uint8_t>>& in,
                                                std::vector<std::vector<uint8_t>>& out)>;

    RaopRtspServer();
    ~RaopRtspServer();

    void setDeviceIdentity(const std::string& deviceName, const std::string& macAddress);
    void setDisplayResolution(int width, int height, float refreshRate);
    // DNS-SD TXT records (wire format) returned for GET /info qualifiers txtAirPlay / txtRAOP.
    void setTxtRecords(const std::vector<uint8_t>& airplayTxt, const std::vector<uint8_t>& raopTxt);

    bool start(int rtspPort = 7000, int videoPort = 7100);
    void stop();
    bool isRunning() const { return isRunning_.load(); }

    // Callbacks must be installed before start(); they are invoked from server worker threads.
    void setClientConnectedCallback(ClientConnectedCallback cb) { onClientConnected_ = std::move(cb); }
    void setStreamStartedCallback(StreamStartedCallback cb) { onStreamStarted_ = std::move(cb); }
    void setStreamStoppedCallback(StreamStoppedCallback cb) { onStreamStopped_ = std::move(cb); }
    // The mirroring video stream ended while the session (and possibly audio) continues.
    void setVideoStoppedCallback(VideoStoppedCallback cb) { onVideoStopped_ = std::move(cb); }
    void setAudioPacketCallback(AudioPacketCallback cb) { onAudioPacket_ = std::move(cb); }
    void setAudioConfigCallback(AudioConfigCallback cb) { onAudioConfig_ = std::move(cb); }
    void setVolumeCallback(VolumeCallback cb) { onVolumeChanged_ = std::move(cb); }
    // Now-playing information posted by the sender with SET_PARAMETER (audio streaming).
    void setMetadataCallback(MetadataCallback cb) { onMetadata_ = std::move(cb); }
    void setCoverArtCallback(CoverArtCallback cb) { onCoverArt_ = std::move(cb); }
    void setProgressCallback(ProgressCallback cb) { onProgress_ = std::move(cb); }
    void setPinStartCallback(PinStartCallback cb) { onPinStart_ = std::move(cb); }
    void setPinSetupCallback(PinSetupCallback cb) { onPinSetup_ = std::move(cb); }

    // AirPlay video casting (URL / YouTube HLS). closeConnections is supplied by the server itself.
    void setVideoCastCallbacks(VideoCastHandler::Callbacks callbacks);
    void updateVideoCastPlayback(const VideoCastHandler::PlaybackInfo& info) { videoCast_.updatePlayback(info); }

    // On-screen PIN pairing (UxPlay -pin): unregistered clients must pair with the PIN first.
    void setPinRequired(bool required);
    // "Stop streaming" on the TV: ends the current mirroring / audio session and closes the
    // sender's control connection. The receiver keeps running and advertising.
    void disconnectSender();
    void setPairedClients(const std::vector<std::vector<uint8_t>>& ed25519Keys);

    H264StreamParser& getVideoParser() { return videoParser_; }
    AirPlayCrypto& getCrypto() { return crypto_; }

    // Feeds an Annex-B/AVCC payload through the video parser (used for simulated frames).
    void feedVideoPayload(const uint8_t* data, size_t size, uint64_t pts);

private:
    struct Worker {
        int fd;
        std::shared_ptr<std::atomic<bool>> done;
        std::thread thread;
    };

    void acceptLoop(int listenFd, bool isVideo);
    void spawnWorker(int fd, std::function<void()> body);
    void reapFinishedWorkers();
    void stopAllWorkers();

    void handleClientConnection(int clientFd, const std::string& clientIp, uint64_t connectionId);
    bool readRtspRequest(int clientFd, std::string& pending, RtspRequest& req,
                         const std::function<bool()>& shouldAbort);
    void handleVideoClientConnection(int clientFd);

    void audioUdpLoop();
    void handleAudioDataPacket(uint8_t* packet, size_t length);
    void handleAudioControlPacket(uint8_t* packet, size_t length);
    void drainAudioBuffer();
    void requestAudioResend(int controlFd);
    int64_t audioLocalTimeLocked(uint32_t rtpTime);
    void configureAudioStream(const BplistValue* stream);
    void handlePairSetupPin(const RtspRequest& req, RtspResponse& resp);
    void stopAudioStream();
    void stopVideoStream();

    RtspResponse processRtspRequest(const RtspRequest& req, const std::string& clientIp, uint64_t connectionId,
                                    int clientFd);
    // Ends the session (streams, timing, keys). Requires sessionMutex_.
    void endSessionLocked(const char* reason);
    // False when PIN pairing is on and this connection has not completed pair-verify.
    bool pinAllows(uint64_t connectionId);

    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isStreaming_{false};
    std::atomic<uint64_t> nextConnectionId_{0};
    // The connection that sent the session SETUP owns the session: requests from any other
    // connection may not change or end it, and a new session SETUP takes it over. SETUP, RECORD,
    // TEARDOWN and session end are serialised by sessionMutex_.
    std::mutex sessionMutex_;
    std::atomic<uint64_t> sessionConnection_{0};
    int sessionConnectionFd_{-1};

    int rtspPort_{7000};
    int videoPort_{7100};
    int audioDataPort_{7011};
    int audioControlPort_{7012};
    int serverFd_{-1};
    int videoServerFd_{-1};

    // Identity, display and sender metadata (written from JNI / RTSP threads).
    std::mutex configMutex_;
    std::string deviceName_{"PhilipsTV"};
    std::string macAddress_{"00:11:22:33:44:55"};
    std::string clientName_;
    int displayWidth_{1920};
    int displayHeight_{1080};
    float displayRefreshRate_{50.0f};
    float volumeDb_{0.0f};
    std::string sessionClientIp_;
    std::vector<uint8_t> airplayTxt_;
    std::vector<uint8_t> raopTxt_;
    bool pinRequired_{false};
    std::vector<std::vector<uint8_t>> pairedClients_;
    std::string pinUser_;

    std::thread serverThread_;
    std::thread videoTcpThread_;
    std::thread audioThread_;

    std::mutex workersMutex_;
    std::vector<Worker> workers_;

    // Only one mirroring video connection is active; a new one replaces the previous.
    std::mutex videoMutex_;
    int activeVideoFd_{-1};
    std::mutex videoParserMutex_;

    AirPlayCrypto crypto_;
    H264StreamParser videoParser_;
    NtpClient ntp_;
    VideoCastHandler videoCast_;
    // Connections of the video casting session (requests carrying X-Apple-Session-ID).
    std::mutex castMutex_;
    std::set<int> castFds_;
    // Local monotonic time minus sender time of the first video packet (UxPlay video_arrival_offset).
    std::atomic<int64_t> videoArrivalOffsetNs_{0};

    std::mutex audioMutex_;
    AudioCodec audioCodec_{AudioCodec::STOPPED};
    alac_file* alacDecoder_{nullptr};
    AudioRtpBuffer audioBuffer_;
    std::vector<uint8_t> alacInput_;
    std::vector<uint8_t> pcmOutput_;
    int audioSampleRate_{44100};
    uint16_t audioRemoteControlPort_{0};
    uint16_t resendSeq_{0};
    bool audioSynced_{false};
    uint32_t rtpSync_{0};
    int64_t remoteSyncNs_{0};

    ClientConnectedCallback onClientConnected_;
    StreamStartedCallback onStreamStarted_;
    StreamStoppedCallback onStreamStopped_;
    VideoStoppedCallback onVideoStopped_;
    AudioPacketCallback onAudioPacket_;
    AudioConfigCallback onAudioConfig_;
    VolumeCallback onVolumeChanged_;
    MetadataCallback onMetadata_;
    CoverArtCallback onCoverArt_;
    ProgressCallback onProgress_;
    PinStartCallback onPinStart_;
    PinSetupCallback onPinSetup_;
};

} // namespace airplay

#endif // RAOP_RTSP_SERVER_H
