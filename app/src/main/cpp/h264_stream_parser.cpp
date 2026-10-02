#include "h264_stream_parser.h"
#include <android/log.h>
#include <cstring>

#define TAG "H264Parser"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace airplay {

H264StreamParser::H264StreamParser() = default;
H264StreamParser::~H264StreamParser() = default;

void H264StreamParser::reset() {
    spsBuffer_.clear();
    ppsBuffer_.clear();
    spsPpsNotified_ = false;
}

void H264StreamParser::parsePayload(const uint8_t* payload, size_t length, uint64_t pts) {
    if (!payload || length < 4) return;

    size_t offset = 0;
    std::vector<uint8_t> annexBBuffer;
    annexBBuffer.reserve(length + 64);

    bool containsKeyFrame = false;

    // AirPlay mirror packets typically arrive as AVCC blocks (length-prefixed NALUs)
    // or as raw Annex B bitstream. We handle both dynamically.
    while (offset + 4 <= length) {
        // Read 4-byte big-endian NALU length
        uint32_t naluLen = (static_cast<uint32_t>(payload[offset]) << 24) |
                           (static_cast<uint32_t>(payload[offset + 1]) << 16) |
                           (static_cast<uint32_t>(payload[offset + 2]) << 8) |
                           (static_cast<uint32_t>(payload[offset + 3]));

        // Check if this is already an Annex B start code (0x00000001)
        if (naluLen == 1) {
            // Annex B start code detected directly
            const uint8_t nalHeader = payload[offset + 4];
            uint8_t nalType = nalHeader & 0x1F;

            if (nalType == NAL_SLICE_IDR) {
                containsKeyFrame = true;
            }

            // Find next start code or end
            size_t nextStart = offset + 4;
            while (nextStart + 3 < length) {
                if (payload[nextStart] == 0 && payload[nextStart + 1] == 0 &&
                    payload[nextStart + 2] == 0 && payload[nextStart + 3] == 1) {
                    break;
                }
                nextStart++;
            }
            if (nextStart + 3 >= length) nextStart = length;

            size_t nalTotalSize = nextStart - offset;
            annexBBuffer.insert(annexBBuffer.end(), payload + offset, payload + nextStart);
            offset = nextStart;
            continue;
        }

        // Validate AVCC nalu length sanity
        if (naluLen == 0 || offset + 4 + naluLen > length) {
            // Fallback: pass rest of payload with Annex B prefix
            const uint8_t startCode[] = {0x00, 0x00, 0x00, 0x01};
            annexBBuffer.insert(annexBBuffer.end(), startCode, startCode + 4);
            annexBBuffer.insert(annexBBuffer.end(), payload + offset, payload + length);
            break;
        }

        offset += 4;
        const uint8_t* nalData = payload + offset;
        uint8_t nalType = nalData[0] & 0x1F;

        if (nalType == NAL_SPS) {
            spsBuffer_.assign(nalData, nalData + naluLen);
        } else if (nalType == NAL_PPS) {
            ppsBuffer_.assign(nalData, nalData + naluLen);
            if (!spsBuffer_.empty() && spsPpsCallback_ && !spsPpsNotified_) {
                spsPpsCallback_(spsBuffer_, ppsBuffer_);
                spsPpsNotified_ = true;
                LOGI("Dispatched initial SPS (size: %zu) and PPS (size: %zu) to MediaCodec",
                     spsBuffer_.size(), ppsBuffer_.size());
            }
        } else if (nalType == NAL_SLICE_IDR) {
            containsKeyFrame = true;
        }

        // Emit standard Annex B 4-byte start code
        const uint8_t startCode[] = {0x00, 0x00, 0x00, 0x01};
        annexBBuffer.insert(annexBBuffer.end(), startCode, startCode + 4);
        annexBBuffer.insert(annexBBuffer.end(), nalData, nalData + naluLen);

        offset += naluLen;
    }

    if (!annexBBuffer.empty() && frameCallback_) {
        frameCallback_(annexBBuffer.data(), annexBBuffer.size(), pts, containsKeyFrame);
    }
}

} // namespace airplay
