#ifndef DMAP_PARSER_H
#define DMAP_PARSER_H

#include <cstddef>
#include <cstdint>
#include <string>

namespace airplay {

struct TrackMetadata {
    std::string title;
    std::string artist;
    std::string album;
};

// Parses the DMAP ("application/x-dmap-tagged") now-playing metadata a sender posts with
// SET_PARAMETER: a list of [4-byte tag][4-byte big-endian length][value] items, usually wrapped in
// an "mlit" container. Returns true when at least one known field was found.
bool parseDmapMetadata(const uint8_t* data, size_t length, TrackMetadata& out);

} // namespace airplay

#endif // DMAP_PARSER_H
