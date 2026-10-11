#include "bplist_reader.h"
#include <cstring>

namespace airplay {

namespace {

constexpr int kMaxDepth = 16;
// Upper bound on visited objects so self-referencing documents cannot explode recursively.
constexpr uint32_t kMaxVisits = 8192;
// Objects can be referenced many times, so the parsed tree can be far larger than the document
// (8000 references to one 64 KB blob = 512 MB). Cap what the parse may copy and allocate.
constexpr uint64_t kBudgetPerDocumentByte = 4;
constexpr uint64_t kBaseBudgetBytes = 1024 * 1024;

uint64_t readBigEndian(const uint8_t* buf, size_t size) {
    uint64_t res = 0;
    for (size_t i = 0; i < size; ++i) {
        res = (res << 8) | buf[i];
    }
    return res;
}

void appendUtf8(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back(static_cast<char>(cp));
    } else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    } else {
        out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
    }
}

class BplistParser {
public:
    BplistParser(const uint8_t* data, size_t size)
        : data_(data), size_(size), budget_(static_cast<uint64_t>(size) * kBudgetPerDocumentByte + kBaseBudgetBytes) {}

    bool parse(BplistValue& out) {
        if (size_ < 8 + 32 || std::memcmp(data_, "bplist00", 8) != 0) return false;

        const uint8_t* trailer = data_ + size_ - 32;
        offsetIntSize_ = trailer[6];
        objectRefSize_ = trailer[7];
        numObjects_ = readBigEndian(trailer + 8, 8);
        uint64_t topObject = readBigEndian(trailer + 16, 8);
        offsetTableOffset_ = readBigEndian(trailer + 24, 8);

        if (offsetIntSize_ == 0 || offsetIntSize_ > 8 || objectRefSize_ == 0 || objectRefSize_ > 8) return false;
        if (numObjects_ == 0 || topObject >= numObjects_) return false;
        if (offsetTableOffset_ >= size_ || numObjects_ > (size_ - offsetTableOffset_) / offsetIntSize_) return false;

        return parseObject(topObject, 0, out);
    }

private:
    bool charge(uint64_t bytes) const {
        if (bytes > budget_) return false;
        budget_ -= bytes;
        return true;
    }

    bool objectOffset(uint64_t index, uint64_t& offset) const {
        if (index >= numObjects_) return false;
        offset = readBigEndian(data_ + offsetTableOffset_ + index * offsetIntSize_, offsetIntSize_);
        return offset < size_;
    }

    // Reads the element count that follows a marker whose low nibble is 0xF.
    bool readLength(uint8_t info, uint64_t& off, uint64_t& length) const {
        length = info;
        if (info != 0x0F) return true;
        if (off >= size_) return false;
        uint8_t lenMarker = data_[off++];
        if ((lenMarker & 0xF0) != 0x10) return false;
        size_t lenSize = static_cast<size_t>(1) << (lenMarker & 0x0F);
        if (lenSize > 8 || off + lenSize > size_) return false;
        length = readBigEndian(data_ + off, lenSize);
        off += lenSize;
        return true;
    }

    bool parseObject(uint64_t index, int depth, BplistValue& out) const {
        if (depth > kMaxDepth || ++visits_ > kMaxVisits) return false;
        uint64_t off = 0;
        if (!objectOffset(index, off)) return false;

        uint8_t marker = data_[off++];
        uint8_t objType = marker & 0xF0;
        uint8_t info = marker & 0x0F;

        switch (objType) {
            case 0x00:
                if (info == 0x08 || info == 0x09) {
                    out.type = BplistValue::Type::Bool;
                    out.boolVal = (info == 0x09);
                }
                return true;
            case 0x10: {
                size_t sz = static_cast<size_t>(1) << info;
                if (sz > 16 || off + sz > size_) return false;
                // 16-byte integers keep their low 64 bits.
                out.type = BplistValue::Type::Int;
                out.intVal = readBigEndian(data_ + off + (sz > 8 ? sz - 8 : 0), sz > 8 ? 8 : sz);
                return true;
            }
            case 0x20: {
                size_t sz = static_cast<size_t>(1) << info;
                if (off + sz > size_) return false;
                out.type = BplistValue::Type::Real;
                if (sz == 4) {
                    uint32_t bits = static_cast<uint32_t>(readBigEndian(data_ + off, 4));
                    float f;
                    std::memcpy(&f, &bits, sizeof(f));
                    out.realVal = f;
                } else if (sz == 8) {
                    uint64_t bits = readBigEndian(data_ + off, 8);
                    double d;
                    std::memcpy(&d, &bits, sizeof(d));
                    out.realVal = d;
                }
                return true;
            }
            case 0x40:
            case 0x50:
            case 0x60: {
                uint64_t length = 0;
                if (!readLength(info, off, length)) return false;
                // Compare before multiplying: a UTF-16 length near 2^63 would wrap around.
                if (length > (objType == 0x60 ? (size_ - off) / 2 : size_ - off)) return false;
                uint64_t byteLen = (objType == 0x60) ? length * 2 : length;
                if (!charge(byteLen)) return false;
                if (objType == 0x40) {
                    out.type = BplistValue::Type::Data;
                    out.dataVal.assign(data_ + off, data_ + off + byteLen);
                } else if (objType == 0x50) {
                    out.type = BplistValue::Type::String;
                    out.strVal.assign(reinterpret_cast<const char*>(data_ + off), byteLen);
                } else {
                    // UTF-16BE (Apple's encoding for any non-ASCII string, e.g. "Berk’s iPhone"
                    // or Turkish names) converted to UTF-8; lone surrogates become U+FFFD.
                    out.type = BplistValue::Type::String;
                    out.strVal.reserve(length);
                    for (uint64_t i = 0; i < length; ++i) {
                        uint32_t cp = static_cast<uint32_t>(readBigEndian(data_ + off + i * 2, 2));
                        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
                            uint32_t low = static_cast<uint32_t>(readBigEndian(data_ + off + (i + 1) * 2, 2));
                            if (low >= 0xDC00 && low <= 0xDFFF) {
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                                ++i;
                            }
                        }
                        if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
                        appendUtf8(out.strVal, cp);
                    }
                }
                return true;
            }
            case 0xA0: {
                uint64_t count = 0;
                if (!readLength(info, off, count)) return false;
                if (count > (size_ - off) / objectRefSize_ || count > kMaxVisits) return false;
                if (!charge(count * sizeof(BplistValue))) return false;
                out.type = BplistValue::Type::Array;
                out.arrayVal.resize(count);
                for (uint64_t i = 0; i < count; ++i) {
                    uint64_t ref = readBigEndian(data_ + off + i * objectRefSize_, objectRefSize_);
                    if (!parseObject(ref, depth + 1, out.arrayVal[i])) return false;
                }
                return true;
            }
            case 0xD0: {
                uint64_t count = 0;
                if (!readLength(info, off, count)) return false;
                if (count > (size_ - off) / (2u * objectRefSize_) || count > kMaxVisits) return false;
                if (!charge(count * sizeof(std::pair<std::string, BplistValue>))) return false;
                out.type = BplistValue::Type::Dict;
                out.dictVal.resize(count);
                for (uint64_t i = 0; i < count; ++i) {
                    uint64_t keyRef = readBigEndian(data_ + off + i * objectRefSize_, objectRefSize_);
                    uint64_t valRef = readBigEndian(data_ + off + (count + i) * objectRefSize_, objectRefSize_);
                    BplistValue key;
                    if (!parseObject(keyRef, depth + 1, key)) return false;
                    out.dictVal[i].first = key.strVal;
                    if (!parseObject(valRef, depth + 1, out.dictVal[i].second)) return false;
                }
                return true;
            }
            default:
                // Unsupported types (dates, UIDs, sets) are skipped rather than failing the whole document.
                return true;
        }
    }

    const uint8_t* data_;
    size_t size_;
    uint8_t offsetIntSize_{0};
    uint8_t objectRefSize_{0};
    uint64_t numObjects_{0};
    uint64_t offsetTableOffset_{0};
    mutable uint32_t visits_{0};
    mutable uint64_t budget_;
};

} // namespace

const BplistValue* BplistValue::find(const std::string& key) const {
    if (type != Type::Dict) return nullptr;
    for (const auto& kv : dictVal) {
        if (kv.first == key) return &kv.second;
    }
    return nullptr;
}

uint64_t BplistValue::intFor(const std::string& key, uint64_t fallback) const {
    const BplistValue* v = find(key);
    return (v && v->type == Type::Int) ? v->intVal : fallback;
}

bool parseBinaryPlist(const uint8_t* data, size_t size, BplistValue& out) {
    if (!data) return false;
    out = BplistValue{};
    return BplistParser(data, size).parse(out);
}

namespace {

// Object header with an inline count (< 15) or a following integer object holding the count.
void appendMarker(std::vector<uint8_t>& out, uint8_t type, size_t count) {
    if (count < 15) {
        out.push_back(static_cast<uint8_t>(type | count));
        return;
    }
    out.push_back(static_cast<uint8_t>(type | 0x0F));
    if (count <= 0xFF) {
        out.push_back(0x10);
        out.push_back(static_cast<uint8_t>(count));
    } else if (count <= 0xFFFF) {
        out.push_back(0x11);
        out.push_back(static_cast<uint8_t>(count >> 8));
        out.push_back(static_cast<uint8_t>(count));
    } else {
        out.push_back(0x12);
        for (int shift = 24; shift >= 0; shift -= 8) out.push_back(static_cast<uint8_t>(count >> shift));
    }
}

void appendBigEndian(std::vector<uint8_t>& out, uint64_t value, size_t bytes) {
    for (size_t i = bytes; i > 0; --i) out.push_back(static_cast<uint8_t>(value >> ((i - 1) * 8)));
}

} // namespace

std::vector<uint8_t> encodeBinaryPlistDataDict(const std::vector<std::pair<std::string, std::vector<uint8_t>>>& entries) {
    // Object 0 is the dictionary, then all keys (1..n), then all values (n+1..2n).
    size_t count = entries.size();
    size_t numObjects = 1 + 2 * count;
    std::vector<uint8_t> out = {'b', 'p', 'l', 'i', 's', 't', '0', '0'};
    std::vector<uint64_t> offsets;
    offsets.reserve(numObjects);
    size_t refSize = numObjects <= 0xFF ? 1 : 2;

    offsets.push_back(out.size());
    appendMarker(out, 0xD0, count);
    for (size_t i = 0; i < count; ++i) appendBigEndian(out, 1 + i, refSize);
    for (size_t i = 0; i < count; ++i) appendBigEndian(out, 1 + count + i, refSize);

    for (const auto& entry : entries) {
        offsets.push_back(out.size());
        appendMarker(out, 0x50, entry.first.size());
        out.insert(out.end(), entry.first.begin(), entry.first.end());
    }
    for (const auto& entry : entries) {
        offsets.push_back(out.size());
        appendMarker(out, 0x40, entry.second.size());
        out.insert(out.end(), entry.second.begin(), entry.second.end());
    }

    uint64_t offsetTableOffset = out.size();
    size_t offsetSize = offsetTableOffset <= 0xFF ? 1 : (offsetTableOffset <= 0xFFFF ? 2 : 4);
    for (uint64_t offset : offsets) appendBigEndian(out, offset, offsetSize);

    // Trailer: 6 unused bytes, offset int size, object ref size, object count, top object, table offset.
    out.insert(out.end(), 6, 0);
    out.push_back(static_cast<uint8_t>(offsetSize));
    out.push_back(static_cast<uint8_t>(refSize));
    appendBigEndian(out, numObjects, 8);
    appendBigEndian(out, 0, 8);
    appendBigEndian(out, offsetTableOffset, 8);
    return out;
}

} // namespace airplay
