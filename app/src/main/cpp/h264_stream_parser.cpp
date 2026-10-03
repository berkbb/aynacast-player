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

void H264StreamParser::parseSpsPps(const uint8_t* payload, size_t length) {
    if (!payload || length < 11) return;

    // Check AVCC decoder configuration record (typically payload[0] == 1)
    // payload[6..7] is big-endian 16-bit SPS length
    uint16_t spsSize = (static_cast<uint16_t>(payload[6]) << 8) | static_cast<uint16_t>(payload[7]);
    if (8 + spsSize + 3 > length) {
        LOGI("Invalid AVCC SPS size: %u (total payload length: %zu)", spsSize, length);
        return;
    }

    spsBuffer_.assign(payload + 8, payload + 8 + spsSize);

    size_t ppsOffset = 8 + spsSize;
    // payload[ppsOffset] is number of PPS (typically 1)
    uint16_t ppsSize = (static_cast<uint16_t>(payload[ppsOffset + 1]) << 8) | static_cast<uint16_t>(payload[ppsOffset + 2]);
    if (ppsOffset + 3 + ppsSize > length) {
        LOGI("Invalid AVCC PPS size: %u (offset: %zu, length: %zu)", ppsSize, ppsOffset, length);
        return;
    }

    ppsBuffer_.assign(payload + ppsOffset + 3, payload + ppsOffset + 3 + ppsSize);

    LOGI("Extracted valid SPS (%zu bytes) and PPS (%zu bytes) from AVCC sequence header!",
         spsBuffer_.size(), ppsBuffer_.size());

    // Try to log SPS width/height (profile_idc at byte 1, level at byte 3)
    // Width/height encoded in SPS bitstream — log raw for diagnostics
    if (spsBuffer_.size() >= 4) {
        LOGI("SPS profile_idc=%d level_idc=%d (raw bytes[0..3]: %02x %02x %02x %02x)",
             spsBuffer_[1], spsBuffer_[3],
             spsBuffer_[0], spsBuffer_[1], spsBuffer_[2], spsBuffer_[3]);
    }

    if (spsPpsCallback_) {
        spsPpsCallback_(spsBuffer_, ppsBuffer_);
        spsPpsNotified_ = true;
    }

    // NOTE: Do NOT forward SPS/PPS as Annex-B video frames here.
    // MediaCodec is configured with csd-0/csd-1 — sending them again as frames
    // causes codec errors on many hardware decoders.
}

void H264StreamParser::parsePayload(const uint8_t* payload, size_t length, uint64_t pts) {
    if (!payload || length < 4) return;

    static int payloadLogCount = 0;
    if (payloadLogCount++ < 10) {
        LOGI("parsePayload len=%zu: %02x %02x %02x %02x %02x %02x %02x %02x",
             length, payload[0], payload[1], payload[2], payload[3],
             (length > 4 ? payload[4] : 0), (length > 5 ? payload[5] : 0),
             (length > 6 ? payload[6] : 0), (length > 7 ? payload[7] : 0));
    }

    size_t offset = 0;
    std::vector<uint8_t> annexBBuffer;
    annexBBuffer.reserve(length + 64);

    bool containsKeyFrame = false;

    // AirPlay mirror video packets arrive as AVCC blocks (4-byte length prefixed NALUs)
    // or as Annex-B bitstream with 0x00000001 start codes.
    while (offset + 4 <= length) {
        // Read 4-byte big-endian NALU length
        uint32_t naluLen = (static_cast<uint32_t>(payload[offset]) << 24) |
                           (static_cast<uint32_t>(payload[offset + 1]) << 16) |
                           (static_cast<uint32_t>(payload[offset + 2]) << 8) |
                           (static_cast<uint32_t>(payload[offset + 3]));

        // Check if this is already an Annex B start code (0x00000001)
        if (naluLen == 1) {
            const uint8_t nalHeader = payload[offset + 4];
            uint8_t nalType = nalHeader & 0x1F;

            if (nalType == NAL_SLICE_IDR) {
                containsKeyFrame = true;
            }

            size_t nextStart = offset + 4;
            while (nextStart + 3 < length) {
                if (payload[nextStart] == 0 && payload[nextStart + 1] == 0 &&
                    payload[nextStart + 2] == 0 && payload[nextStart + 3] == 1) {
                    break;
                }
                nextStart++;
            }
            if (nextStart + 3 >= length) nextStart = length;

            annexBBuffer.insert(annexBBuffer.end(), payload + offset, payload + nextStart);
            offset = nextStart;
            continue;
        }

        // Validate AVCC nalu length
        if (naluLen == 0 || offset + 4 + naluLen > length) {
            // Fallback: pass remaining bytes with Annex-B start code
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
                LOGI("Dispatched SPS (size: %zu) and PPS (size: %zu) to MediaCodec",
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
