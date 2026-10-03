#ifndef H264_STREAM_PARSER_H
#define H264_STREAM_PARSER_H

#include <cstdint>
#include <vector>
#include <functional>

namespace airplay {

enum NalUnitType {
    NAL_UNKNOWN = 0,
    NAL_SLICE_NON_IDR = 1,
    NAL_SLICE_IDR = 5,
    NAL_SEI = 6,
    NAL_SPS = 7,
    NAL_PPS = 8,
    NAL_AUD = 9
};

struct NalUnit {
    NalUnitType type;
    const uint8_t* data;
    size_t size;
    bool isKeyFrame;
};

class H264StreamParser {
public:
    using FrameCallback = std::function<void(const uint8_t* data, size_t size, uint64_t pts, bool isKeyFrame)>;
    using SpsPpsCallback = std::function<void(const std::vector<uint8_t>& sps, const std::vector<uint8_t>& pps)>;

    H264StreamParser();
    ~H264StreamParser();

    void setFrameCallback(FrameCallback callback) { frameCallback_ = std::move(callback); }
    void setSpsPpsCallback(SpsPpsCallback callback) { spsPpsCallback_ = std::move(callback); }

    // Parses raw incoming AirPlay video payload (which can be AVCC format with 4-byte length prefixes)
    // and converts it to Annex B stream [0x00, 0x00, 0x00, 0x01, ...] for MediaCodec.
    void parsePayload(const uint8_t* payload, size_t length, uint64_t pts);
    void parseSpsPps(const uint8_t* payload, size_t length);

    void reset();

private:
    std::vector<uint8_t> spsBuffer_;
    std::vector<uint8_t> ppsBuffer_;
    std::vector<uint8_t> annexBBuffer_;
    bool spsPpsNotified_{false};

    FrameCallback frameCallback_;
    SpsPpsCallback spsPpsCallback_;
};

} // namespace airplay

#endif // H264_STREAM_PARSER_H
