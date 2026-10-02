#ifndef AIRPLAY_PROTOCOL_H
#define AIRPLAY_PROTOCOL_H

#include <cstdint>
#include <string>
#include <vector>
#include <unordered_map>

namespace airplay {

enum class SessionState {
    IDLE,
    PAIRING,
    CONNECTED,
    STREAMING,
    PAUSED,
    DISCONNECTED
};

enum class MediaType {
    VIDEO_H264,
    AUDIO_ALAC,
    AUDIO_AAC,
    AUDIO_PCM
};

struct ClientInfo {
    std::string deviceId;
    std::string deviceName;
    std::string clientModel;
    std::string ipAddress;
    int clientPort{0};
};

struct VideoFrame {
    uint8_t* data{nullptr};
    size_t length{0};
    uint64_t pts{0}; // Presentation timestamp in microseconds
    bool isKeyFrame{false};
};

struct AudioPacket {
    uint8_t* data{nullptr};
    size_t length{0};
    uint64_t timestamp{0};
    int sampleRate{44100};
    int channels{2};
};

struct RtspRequest {
    std::string method;
    std::string uri;
    std::string version;
    std::unordered_map<std::string, std::string> headers;
    std::vector<uint8_t> body;
    int cseq{0};
};

struct RtspResponse {
    int statusCode{200};
    std::string statusMessage{"OK"};
    std::unordered_map<std::string, std::string> headers;
    std::vector<uint8_t> body;
    
    std::string serialize() const;
};

} // namespace airplay

#endif // AIRPLAY_PROTOCOL_H
