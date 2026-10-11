#include "dmap_parser.h"
#include <cstring>

namespace airplay {

namespace {

constexpr int kMaxDepth = 4;

bool isContainer(const char* tag) {
    return std::memcmp(tag, "mlit", 4) == 0 || std::memcmp(tag, "mlcl", 4) == 0 ||
           std::memcmp(tag, "cmst", 4) == 0;
}

bool parseItems(const uint8_t* data, size_t length, TrackMetadata& out, int depth) {
    bool found = false;
    size_t offset = 0;
    while (offset + 8 <= length) {
        const char* tag = reinterpret_cast<const char*>(data + offset);
        uint32_t itemLength = (static_cast<uint32_t>(data[offset + 4]) << 24) |
                              (static_cast<uint32_t>(data[offset + 5]) << 16) |
                              (static_cast<uint32_t>(data[offset + 6]) << 8) |
                              static_cast<uint32_t>(data[offset + 7]);
        offset += 8;
        if (itemLength > length - offset) break; // Truncated item: stop safely
        const uint8_t* value = data + offset;

        if (isContainer(tag)) {
            if (depth < kMaxDepth) found |= parseItems(value, itemLength, out, depth + 1);
        } else if (std::memcmp(tag, "minm", 4) == 0) {
            out.title.assign(reinterpret_cast<const char*>(value), itemLength);
            found = true;
        } else if (std::memcmp(tag, "asar", 4) == 0) {
            out.artist.assign(reinterpret_cast<const char*>(value), itemLength);
            found = true;
        } else if (std::memcmp(tag, "asal", 4) == 0) {
            out.album.assign(reinterpret_cast<const char*>(value), itemLength);
            found = true;
        }
        offset += itemLength;
    }
    return found;
}

} // namespace

bool parseDmapMetadata(const uint8_t* data, size_t length, TrackMetadata& out) {
    if (!data) return false;
    out = TrackMetadata{};
    return parseItems(data, length, out, 0);
}

} // namespace airplay
