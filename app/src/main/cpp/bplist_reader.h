#ifndef BPLIST_READER_H
#define BPLIST_READER_H

#include <cstddef>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace airplay {

// Minimal, bounds-checked reader for Apple binary property lists ("bplist00").
// Supports the object types AirPlay senders use in RTSP bodies:
// bool, integer, real, ASCII/UTF-16 string, data, array and dictionary.
struct BplistValue {
    enum class Type { None, Bool, Int, Real, String, Data, Array, Dict };

    Type type{Type::None};
    bool boolVal{false};
    uint64_t intVal{0};
    double realVal{0.0};
    std::string strVal;
    std::vector<uint8_t> dataVal;
    std::vector<BplistValue> arrayVal;
    std::vector<std::pair<std::string, BplistValue>> dictVal;

    bool isDict() const { return type == Type::Dict; }
    bool isArray() const { return type == Type::Array; }

    // Dictionary lookup; returns nullptr when absent or when this value is not a dictionary.
    const BplistValue* find(const std::string& key) const;

    // Integer value of a dictionary entry, or fallback when missing / not an integer.
    uint64_t intFor(const std::string& key, uint64_t fallback) const;
};

// Parses a binary plist. Returns false when the buffer is not a valid bplist00 document.
bool parseBinaryPlist(const uint8_t* data, size_t size, BplistValue& out);

inline bool parseBinaryPlist(const std::vector<uint8_t>& data, BplistValue& out) {
    return parseBinaryPlist(data.data(), data.size(), out);
}

// Encodes the binary plist { key: <data>, ... } (GET /info txtAirPlay, /pair-setup-pin replies).
std::vector<uint8_t> encodeBinaryPlistDataDict(const std::vector<std::pair<std::string, std::vector<uint8_t>>>& entries);

inline std::vector<uint8_t> encodeBinaryPlistDataDict(const std::string& key, const std::vector<uint8_t>& value) {
    return encodeBinaryPlistDataDict({{key, value}});
}

} // namespace airplay

#endif // BPLIST_READER_H
