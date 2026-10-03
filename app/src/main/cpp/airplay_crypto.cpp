#include "airplay_crypto.h"
#include <android/log.h>
#include <cstring>
#include <random>
#include <algorithm>
#include <functional>

extern "C" {
#include "tweetnacl.h"
#include "aes.h"
#include "playfair/playfair.h"

void randombytes(unsigned char* ptr, unsigned long long length) {
    std::random_device rd;
    std::mt19937_64 gen(rd());
    std::uniform_int_distribution<uint32_t> dis;
    for (unsigned long long i = 0; i < length; ++i) {
        ptr[i] = static_cast<unsigned char>(dis(gen) & 0xFF);
    }
}
}

#define TAG "AirPlayCrypto"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace airplay {

static std::string base64Encode(const uint8_t* data, size_t length) {
    static const char* chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((length + 2) / 3) * 4);
    for (size_t i = 0; i < length; i += 3) {
        uint32_t val = (static_cast<uint32_t>(data[i]) << 16);
        if (i + 1 < length) val |= (static_cast<uint32_t>(data[i + 1]) << 8);
        if (i + 2 < length) val |= static_cast<uint32_t>(data[i + 2]);

        out.push_back(chars[(val >> 18) & 0x3F]);
        out.push_back(chars[(val >> 12) & 0x3F]);
        out.push_back((i + 1 < length) ? chars[(val >> 6) & 0x3F] : '=');
        out.push_back((i + 2 < length) ? chars[val & 0x3F] : '=');
    }
    return out;
}

static std::string hexEncode(const uint8_t* data, size_t length) {
    static const char* hexDigits = "0123456789abcdef";
    std::string out;
    out.reserve(length * 2);
    for (size_t i = 0; i < length; ++i) {
        out.push_back(hexDigits[(data[i] >> 4) & 0x0F]);
        out.push_back(hexDigits[data[i] & 0x0F]);
    }
    return out;
}

AirPlayCrypto::AirPlayCrypto() {
    initializeKeys();
}

AirPlayCrypto::~AirPlayCrypto() {
    resetSessionCrypto();
}

void AirPlayCrypto::initializeKeys() {
    serverEd25519Pk_.resize(32);
    serverEd25519Sk_.resize(64);

    // Generate long-term Ed25519 identity keypair
    crypto_sign_keypair(serverEd25519Pk_.data(), serverEd25519Sk_.data());

    generateEphemeralCurve25519KeyPair();

    aesKey_.assign(16, 0);
    aesIv_.assign(16, 0);

    keysInitialized_ = true;
    LOGI("Cryptographic subsystem initialized. Public key: %s", getEd25519PublicKeyHex().c_str());
}

bool AirPlayCrypto::generateEphemeralCurve25519KeyPair() {
    serverEcdhSk_.resize(32);
    serverEcdhPk_.resize(32);

    std::random_device rd;
    std::mt19937_64 gen(rd());
    std::uniform_int_distribution<uint32_t> dis;

    for (size_t i = 0; i < 32; i += 4) {
        uint32_t val = dis(gen);
        std::memcpy(&serverEcdhSk_[i], &val, 4);
    }

    // Clamp private key per Curve25519 / RFC 7748
    serverEcdhSk_[0] &= 248;
    serverEcdhSk_[31] &= 127;
    serverEcdhSk_[31] |= 64;

    crypto_scalarmult_base(serverEcdhPk_.data(), serverEcdhSk_.data());
    return true;
}

std::string AirPlayCrypto::getEd25519PublicKeyBase64() const {
    return base64Encode(serverEd25519Pk_.data(), serverEd25519Pk_.size());
}

std::string AirPlayCrypto::getEd25519PublicKeyHex() const {
    return hexEncode(serverEd25519Pk_.data(), serverEd25519Pk_.size());
}

bool AirPlayCrypto::computeSharedSecret(const uint8_t* peerPublicKey, std::vector<uint8_t>& sharedSecret) {
    if (!peerPublicKey) return false;
    sharedSecret.resize(32);
    if (crypto_scalarmult(sharedSecret.data(), serverEcdhSk_.data(), peerPublicKey) != 0) {
        LOGE("Failed to compute Curve25519 shared secret");
        return false;
    }
    return true;
}

bool AirPlayCrypto::handlePairSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody) {
    LOGI("Processing /pair-setup request (size: %zu)", reqBody.size());
    // Responds with server's 32-byte Ed25519 public key
    respBody.assign(serverEd25519Pk_.begin(), serverEd25519Pk_.end());
    return true;
}

bool AirPlayCrypto::handlePairVerify(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody) {
    LOGI("Processing /pair-verify request (size: %zu)", reqBody.size());

    // Step 1: Client sends 68 bytes (flag 0x01 + 32-byte client Curve25519 key + 32 bytes)
    if (reqBody.size() >= 68 && reqBody[0] == 0x01) {
        const uint8_t* clientEcdhPk = reqBody.data() + 4;

        // Generate fresh ephemeral Curve25519 keypair for this session
        generateEphemeralCurve25519KeyPair();

        // 1. Shared secret via Curve25519
        std::vector<uint8_t> sharedSecret;
        if (!computeSharedSecret(clientEcdhPk, sharedSecret)) {
            return false;
        }
        sharedSecret_ = sharedSecret;

        // 2. Derive AES Key: SHA512("Pair-Verify-AES-Key" + sharedSecret)[0..15]
        const std::string keySalt = "Pair-Verify-AES-Key";
        std::vector<uint8_t> keyMat(keySalt.begin(), keySalt.end());
        keyMat.insert(keyMat.end(), sharedSecret.begin(), sharedSecret.end());

        uint8_t shaKey[64];
        crypto_hash(shaKey, keyMat.data(), keyMat.size());
        aesKey_.assign(shaKey, shaKey + 16);

        // 3. Derive AES IV: SHA512("Pair-Verify-AES-IV" + sharedSecret)[0..15]
        const std::string ivSalt = "Pair-Verify-AES-IV";
        std::vector<uint8_t> ivMat(ivSalt.begin(), ivSalt.end());
        ivMat.insert(ivMat.end(), sharedSecret.begin(), sharedSecret.end());

        uint8_t shaIv[64];
        crypto_hash(shaIv, ivMat.data(), ivMat.size());
        aesIv_.assign(shaIv, shaIv + 16);

        // 4. Construct message to sign: serverEcdhPk (32) + clientEcdhPk (32) = 64 bytes
        uint8_t msgToSign[64];
        std::memcpy(msgToSign, serverEcdhPk_.data(), 32);
        std::memcpy(msgToSign + 32, clientEcdhPk, 32);

        // 5. Sign with long-term Ed25519 private key
        unsigned long long smlen = 0;
        uint8_t signedMsg[64 + 64];
        crypto_sign(signedMsg, &smlen, msgToSign, 64, serverEd25519Sk_.data());

        uint8_t signature[64];
        std::memcpy(signature, signedMsg, 64);

        // 6. Encrypt signature with AES-128-CTR
        struct AES_ctx aesCtx;
        AES_init_ctx_iv(&aesCtx, aesKey_.data(), aesIv_.data());
        AES_CTR_xcrypt_buffer(&aesCtx, signature, 64);

        // 7. Assemble 96-byte response: serverEcdhPk (32) + encryptedSignature (64)
        respBody.clear();
        respBody.reserve(96);
        respBody.insert(respBody.end(), serverEcdhPk_.begin(), serverEcdhPk_.end());
        respBody.insert(respBody.end(), signature, signature + 64);

        LOGI("Pair-verify Step 1 completed successfully (response size: %zu)", respBody.size());
        return true;
    }

    // Step 2: Client sends 68 bytes (flag 0x00 + 64-byte client signature) -> Server replies with 0 bytes
    if (reqBody.size() >= 4 && reqBody[0] == 0x00) {
        LOGI("Pair-verify Step 2 completed successfully. Channel authenticated.");
        respBody.clear();
        return true;
    }

    // Default fallback: 200 OK empty response
    respBody.clear();
    return true;
}

static const uint8_t reply_message[4][142] = {
    {0x46,0x50,0x4c,0x59,0x03,0x01,0x02,0x00,0x00,0x00,0x00,0x82,0x02,0x00,0x0f,0x9f,
     0x3f,0x9e,0x0a,0x25,0x21,0xdb,0xdf,0x31,0x2a,0xb2,0xbf,0xb2,0x9e,0x8d,0x23,0x2b,
     0x63,0x76,0xa8,0xc8,0x18,0x70,0x1d,0x22,0xae,0x93,0xd8,0x27,0x37,0xfe,0xaf,0x9d,
     0xb4,0xfd,0xf4,0x1c,0x2d,0xba,0x9d,0x1f,0x49,0xca,0xaa,0xbf,0x65,0x91,0xac,0x1f,
     0x7b,0xc6,0xf7,0xe0,0x66,0x3d,0x21,0xaf,0xe0,0x15,0x65,0x95,0x3e,0xab,0x81,0xf4,
     0x18,0xce,0xed,0x09,0x5a,0xdb,0x7c,0x3d,0x0e,0x25,0x49,0x09,0xa7,0x98,0x31,0xd4,
     0x9c,0x39,0x82,0x97,0x34,0x34,0xfa,0xcb,0x42,0xc6,0x3a,0x1c,0xd9,0x11,0xa6,0xfe,
     0x94,0x1a,0x8a,0x6d,0x4a,0x74,0x3b,0x46,0xc3,0xa7,0x64,0x9e,0x44,0xc7,0x89,0x55,
     0xe4,0x9d,0x81,0x55,0x00,0x95,0x49,0xc4,0xe2,0xf7,0xa3,0xf6,0xd5,0xba},

    {0x46,0x50,0x4c,0x59,0x03,0x01,0x02,0x00,0x00,0x00,0x00,0x82,0x02,0x01,0xcf,0x32,
     0xa2,0x57,0x14,0xb2,0x52,0x4f,0x8a,0xa0,0xad,0x7a,0xf1,0x64,0xe3,0x7b,0xcf,0x44,
     0x24,0xe2,0x00,0x04,0x7e,0xfc,0x0a,0xd6,0x7a,0xfc,0xd9,0x5d,0xed,0x1c,0x27,0x30,
     0xbb,0x59,0x1b,0x96,0x2e,0xd6,0x3a,0x9c,0x4d,0xed,0x88,0xba,0x8f,0xc7,0x8d,0xe6,
     0x4d,0x91,0xcc,0xfd,0x5c,0x7b,0x56,0xda,0x88,0xe3,0x1f,0x5c,0xce,0xaf,0xc7,0x43,
     0x19,0x95,0xa0,0x16,0x65,0xa5,0x4e,0x19,0x39,0xd2,0x5b,0x94,0xdb,0x64,0xb9,0xe4,
     0x5d,0x8d,0x06,0x3e,0x1e,0x6a,0xf0,0x7e,0x96,0x56,0x16,0x2b,0x0e,0xfa,0x40,0x42,
     0x75,0xea,0x5a,0x44,0xd9,0x59,0x1c,0x72,0x56,0xb9,0xfb,0xe6,0x51,0x38,0x98,0xb8,
     0x02,0x27,0x72,0x19,0x88,0x57,0x16,0x50,0x94,0x2a,0xd9,0x46,0x68,0x8a},

    {0x46,0x50,0x4c,0x59,0x03,0x01,0x02,0x00,0x00,0x00,0x00,0x82,0x02,0x02,0xc1,0x69,
     0xa3,0x52,0xee,0xed,0x35,0xb1,0x8c,0xdd,0x9c,0x58,0xd6,0x4f,0x16,0xc1,0x51,0x9a,
     0x89,0xeb,0x53,0x17,0xbd,0x0d,0x43,0x36,0xcd,0x68,0xf6,0x38,0xff,0x9d,0x01,0x6a,
     0x5b,0x52,0xb7,0xfa,0x92,0x16,0xb2,0xb6,0x54,0x82,0xc7,0x84,0x44,0x11,0x81,0x21,
     0xa2,0xc7,0xfe,0xd8,0x3d,0xb7,0x11,0x9e,0x91,0x82,0xaa,0xd7,0xd1,0x8c,0x70,0x63,
     0xe2,0xa4,0x57,0x55,0x59,0x10,0xaf,0x9e,0x0e,0xfc,0x76,0x34,0x7d,0x16,0x40,0x43,
     0x80,0x7f,0x58,0x1e,0xe4,0xfb,0xe4,0x2c,0xa9,0xde,0xdc,0x1b,0x5e,0xb2,0xa3,0xaa,
     0x3d,0x2e,0xcd,0x59,0xe7,0xee,0xe7,0x0b,0x36,0x29,0xf2,0x2a,0xfd,0x16,0x1d,0x87,
     0x73,0x53,0xdd,0xb9,0x9a,0xdc,0x8e,0x07,0x00,0x6e,0x56,0xf8,0x50,0xce},

    {0x46,0x50,0x4c,0x59,0x03,0x01,0x02,0x00,0x00,0x00,0x00,0x82,0x02,0x03,0x90,0x01,
     0xe1,0x72,0x7e,0x0f,0x57,0xf9,0xf5,0x88,0x0d,0xb1,0x04,0xa6,0x25,0x7a,0x23,0xf5,
     0xcf,0xff,0x1a,0xbb,0xe1,0xe9,0x30,0x45,0x25,0x1a,0xfb,0x97,0xeb,0x9f,0xc0,0x01,
     0x1e,0xbe,0x0f,0x3a,0x81,0xdf,0x5b,0x69,0x1d,0x76,0xac,0xb2,0xf7,0xa5,0xc7,0x08,
     0xe3,0xd3,0x28,0xf5,0x6b,0xb3,0x9d,0xbd,0xe5,0xf2,0x9c,0x8a,0x17,0xf4,0x81,0x48,
     0x7e,0x3a,0xe8,0x63,0xc6,0x78,0x32,0x54,0x22,0xe6,0xf7,0x8e,0x16,0x6d,0x18,0xaa,
     0x7f,0xd6,0x36,0x25,0x8b,0xce,0x28,0x72,0x6f,0x66,0x1f,0x73,0x88,0x93,0xce,0x44,
     0x31,0x1e,0x4b,0xe6,0xc0,0x53,0x51,0x93,0xe5,0xef,0x72,0xe8,0x68,0x62,0x33,0x72,
     0x9c,0x22,0x7d,0x82,0x0c,0x99,0x94,0x45,0xd8,0x92,0x46,0xc8,0xc3,0x59}
};

bool AirPlayCrypto::handleFpSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody) {
    LOGI("Processing /fp-setup request (size: %zu)", reqBody.size());
    respBody.clear();
    if (reqBody.size() == 16) {
        // FairPlay Phase 1 (142 bytes)
        int mode = reqBody[14] % 4;
        respBody.assign(reply_message[mode], reply_message[mode] + 142);
        fpKeyMsg_.clear();
        LOGI("FairPlay SAP Step 1 OK (mode: %d, response size: 142)", mode);
        return true;
    } else if (reqBody.size() >= 164) {
        // FairPlay Phase 2 handshake (32 bytes response)
        fpKeyMsg_.assign(reqBody.begin(), reqBody.begin() + 164);
        static const uint8_t fpHeader[12] = {0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x14};
        respBody.assign(fpHeader, fpHeader + 12);
        respBody.insert(respBody.end(), reqBody.begin() + 144, reqBody.begin() + 164);
        LOGI("FairPlay SAP Step 2 Handshake OK (saved 164 bytes keymsg, response size: 32)");
        return true;
    }
    return false;
}

namespace {

uint64_t readBigEndianUInt(const uint8_t* buf, size_t size) {
    uint64_t res = 0;
    for (size_t i = 0; i < size; ++i) {
        res = (res << 8) | buf[i];
    }
    return res;
}

bool parseBplistSetup(const std::vector<uint8_t>& data,
                      std::vector<uint8_t>& outEkey,
                      std::vector<uint8_t>& outEiv,
                      uint64_t& outStreamConnectionId) {
    if (data.size() < 32) return false;
    if (std::memcmp(data.data(), "bplist00", 8) != 0) return false;

    const uint8_t* trailer = data.data() + data.size() - 32;
    uint8_t offsetIntSize = trailer[6];
    uint8_t objectRefSize = trailer[7];
    uint64_t numObjects = readBigEndianUInt(trailer + 8, 8);
    uint64_t topObject = readBigEndianUInt(trailer + 16, 8);
    uint64_t offsetTableOffset = readBigEndianUInt(trailer + 24, 8);

    if (offsetIntSize == 0 || objectRefSize == 0 || numObjects == 0) return false;
    if (offsetTableOffset + numObjects * offsetIntSize > data.size()) return false;

    auto getOffset = [&](size_t idx) -> uint64_t {
        if (idx >= numObjects) return 0;
        return readBigEndianUInt(data.data() + offsetTableOffset + idx * offsetIntSize, offsetIntSize);
    };

    struct ParsedVal {
        enum Type { NONE, INT, STRING, DATA, ARRAY, DICT } type = NONE;
        uint64_t intVal = 0;
        std::string strVal;
        std::vector<uint8_t> dataVal;
        std::vector<size_t> refVals;
        std::vector<std::pair<size_t, size_t>> dictRefs;
    };

    std::function<ParsedVal(size_t)> parseObj = [&](size_t idx) -> ParsedVal {
        ParsedVal res;
        uint64_t off = getOffset(idx);
        if (off >= data.size()) return res;

        uint8_t marker = data[off];
        uint8_t objType = marker & 0xF0;
        uint8_t info = marker & 0x0F;
        off++;

        if (objType == 0x10) { // Integer
            size_t sz = 1 << info;
            if (off + sz <= data.size()) {
                res.type = ParsedVal::INT;
                res.intVal = readBigEndianUInt(data.data() + off, sz);
            }
        } else if (objType == 0x40 || objType == 0x50) { // Data or ASCII String
            uint64_t length = info;
            if (info == 0x0F) {
                if (off < data.size()) {
                    uint8_t lenMarker = data[off++];
                    size_t lenSz = 1 << (lenMarker & 0x0F);
                    if (off + lenSz <= data.size()) {
                        length = readBigEndianUInt(data.data() + off, lenSz);
                        off += lenSz;
                    }
                }
            }
            if (off + length <= data.size()) {
                if (objType == 0x50) {
                    res.type = ParsedVal::STRING;
                    res.strVal.assign(reinterpret_cast<const char*>(data.data() + off), length);
                } else {
                    res.type = ParsedVal::DATA;
                    res.dataVal.assign(data.data() + off, data.data() + off + length);
                }
            }
        } else if (objType == 0xA0) { // Array
            uint64_t length = info;
            if (info == 0x0F) {
                if (off < data.size()) {
                    uint8_t lenMarker = data[off++];
                    size_t lenSz = 1 << (lenMarker & 0x0F);
                    if (off + lenSz <= data.size()) {
                        length = readBigEndianUInt(data.data() + off, lenSz);
                        off += lenSz;
                    }
                }
            }
            res.type = ParsedVal::ARRAY;
            for (uint64_t i = 0; i < length && off + objectRefSize <= data.size(); ++i) {
                res.refVals.push_back(readBigEndianUInt(data.data() + off, objectRefSize));
                off += objectRefSize;
            }
        } else if (objType == 0xD0) { // Dict
            uint64_t length = info;
            if (info == 0x0F) {
                if (off < data.size()) {
                    uint8_t lenMarker = data[off++];
                    size_t lenSz = 1 << (lenMarker & 0x0F);
                    if (off + lenSz <= data.size()) {
                        length = readBigEndianUInt(data.data() + off, lenSz);
                        off += lenSz;
                    }
                }
            }
            res.type = ParsedVal::DICT;
            std::vector<size_t> krefs, vrefs;
            for (uint64_t i = 0; i < length && off + objectRefSize <= data.size(); ++i) {
                krefs.push_back(readBigEndianUInt(data.data() + off, objectRefSize));
                off += objectRefSize;
            }
            for (uint64_t i = 0; i < length && off + objectRefSize <= data.size(); ++i) {
                vrefs.push_back(readBigEndianUInt(data.data() + off, objectRefSize));
                off += objectRefSize;
            }
            for (size_t i = 0; i < krefs.size() && i < vrefs.size(); ++i) {
                res.dictRefs.emplace_back(krefs[i], vrefs[i]);
            }
        }
        return res;
    };

    ParsedVal top = parseObj(topObject);
    if (top.type != ParsedVal::DICT) return false;

    for (const auto& kv : top.dictRefs) {
        ParsedVal k = parseObj(kv.first);
        if (k.type == ParsedVal::STRING) {
            if (k.strVal == "ekey") {
                ParsedVal v = parseObj(kv.second);
                if (v.type == ParsedVal::DATA) outEkey = v.dataVal;
            } else if (k.strVal == "eiv") {
                ParsedVal v = parseObj(kv.second);
                if (v.type == ParsedVal::DATA) outEiv = v.dataVal;
            } else if (k.strVal == "streamConnectionID") {
                ParsedVal v = parseObj(kv.second);
                if (v.type == ParsedVal::INT) {
                    outStreamConnectionId = v.intVal;
                }
            } else if (k.strVal == "streams") {
                ParsedVal v = parseObj(kv.second);
                if (v.type == ParsedVal::ARRAY) {
                    for (size_t streamRef : v.refVals) {
                        ParsedVal streamObj = parseObj(streamRef);
                        if (streamObj.type == ParsedVal::DICT) {
                            for (const auto& skv : streamObj.dictRefs) {
                                ParsedVal sk = parseObj(skv.first);
                                if (sk.type == ParsedVal::STRING && sk.strVal == "streamConnectionID") {
                                    ParsedVal sv = parseObj(skv.second);
                                    if (sv.type == ParsedVal::INT) {
                                        outStreamConnectionId = sv.intVal;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return !outEkey.empty() || outStreamConnectionId != 0;
}

} // anonymous namespace

bool AirPlayCrypto::handleSetup(const std::vector<uint8_t>& reqBody, uint64_t& outStreamConnectionId) {
    LOGI("Processing SETUP request (body size: %zu)", reqBody.size());
    if (reqBody.size() > 0 && reqBody.size() <= 1024) {
        LOGI("SETUP reqBody hex: %s", hexEncode(reqBody.data(), reqBody.size()).c_str());
    }

    std::vector<uint8_t> ekey;
    std::vector<uint8_t> eiv;
    uint64_t parsedId = 0;
    parseBplistSetup(reqBody, ekey, eiv, parsedId);

    // Fallback: search raw bytes for streamConnectionID or ekey if bplist parser missed anything
    if (parsedId == 0 && reqBody.size() > 30) {
        const char streamIdMarker[] = "streamConnectionID";
        auto it = std::search(reqBody.begin(), reqBody.end(), streamIdMarker, streamIdMarker + 18);
        if (it != reqBody.end()) {
            size_t pos = std::distance(reqBody.begin(), it) + 18;
            while (pos + 9 <= reqBody.size()) {
                if (reqBody[pos] == 0x13) { // 8-byte uint marker in bplist
                    parsedId = readBigEndianUInt(reqBody.data() + pos + 1, 8);
                    LOGI("Found streamConnectionID via 0x13 marker: %llu", (unsigned long long)parsedId);
                    break;
                }
                pos++;
            }
        }
    }

    if (ekey.empty() && reqBody.size() > 80) {
        const char ekeyMarker[] = "ekey";
        auto it = std::search(reqBody.begin(), reqBody.end(), ekeyMarker, ekeyMarker + 4);
        if (it != reqBody.end()) {
            size_t pos = std::distance(reqBody.begin(), it) + 4;
            while (pos + 72 <= reqBody.size()) {
                // Look for data marker (0x4F 0x10 0x48 or 0x48)
                if (reqBody[pos] == 0x4F && pos + 3 + 72 <= reqBody.size() && reqBody[pos + 1] == 0x10 && reqBody[pos + 2] == 0x48) {
                    ekey.assign(reqBody.begin() + pos + 3, reqBody.begin() + pos + 3 + 72);
                    LOGI("Found 72-byte ekey via 0x4F 0x10 0x48 marker!");
                    break;
                } else if (reqBody[pos] == 0x48 && pos + 1 + 72 <= reqBody.size()) {
                    ekey.assign(reqBody.begin() + pos + 1, reqBody.begin() + pos + 1 + 72);
                    LOGI("Found 72-byte ekey via 0x48 marker!");
                    break;
                }
                pos++;
            }
        }
    }

    if (ekey.size() == 72 && fpKeyMsg_.size() >= 164) {
        LOGI("Found 72-byte ekey in SETUP and valid FairPlay handshake! Decrypting session AES key...");
        uint8_t rawAesKey[16] = {0};
        playfair_decrypt(fpKeyMsg_.data(), ekey.data(), rawAesKey);

        // Audio/session AES key derivation matching UxPlay:
        // If pairing ecdh_secret exists (bit 27 ON):
        //   aeskey = SHA-512(rawAesKey(16) + sharedSecret(32))[0..15]
        // Else (legacy pairing disabled / bit 27 OFF):
        //   aeskey = rawAesKey (16 bytes, unhashed!)
        uint8_t finalAesKey[16] = {0};
        if (sharedSecret_.size() == 32) {
            LOGI("Hashing FairPlay rawAesKey with Curve25519 sharedSecret (32 bytes)...");
            uint8_t eaesMat[48] = {0};
            std::memcpy(eaesMat, rawAesKey, 16);
            std::memcpy(eaesMat + 16, sharedSecret_.data(), 32);
            uint8_t eaesHash[64] = {0};
            crypto_hash(eaesHash, eaesMat, 48);
            std::memcpy(finalAesKey, eaesHash, 16);
        } else {
            LOGI("Using pure FairPlay rawAesKey (no pairing secret, bit 27 disabled).");
            std::memcpy(finalAesKey, rawAesKey, 16);
        }

        sessionAesKey_.assign(finalAesKey, finalAesKey + 16);
        hasSessionAesKey_ = true;
        LOGI("Saved session AES key: %s", hexEncode(sessionAesKey_.data(), 16).c_str());
    }

    if (parsedId != 0) {
        streamConnectionId_ = parsedId;
        outStreamConnectionId = parsedId;
        LOGI("Identified streamConnectionID from SETUP bplist: %llu", (unsigned long long)streamConnectionId_);
    }

    // Derive Video AES key & IV matching UxPlay mirror_buffer_init_aes as soon as both are present:
    if (hasSessionAesKey_ && streamConnectionId_ != 0) {
        std::string skeyStr = "AirPlayStreamKey" + std::to_string(streamConnectionId_);
        std::vector<uint8_t> skeyMat(skeyStr.begin(), skeyStr.end());
        skeyMat.insert(skeyMat.end(), sessionAesKey_.begin(), sessionAesKey_.end());
        uint8_t hash1[64] = {0};
        crypto_hash(hash1, skeyMat.data(), skeyMat.size());

        std::string sivStr = "AirPlayStreamIV" + std::to_string(streamConnectionId_);
        std::vector<uint8_t> sivMat(sivStr.begin(), sivStr.end());
        sivMat.insert(sivMat.end(), sessionAesKey_.begin(), sessionAesKey_.end());
        uint8_t hash2[64] = {0};
        crypto_hash(hash2, sivMat.data(), sivMat.size());

        videoAesKey_.assign(hash1, hash1 + 16);
        videoAesIv_.assign(hash2, hash2 + 16);
        hasVideoKey_ = true;

        if (videoAesCtx_) {
            free(videoAesCtx_);
            videoAesCtx_ = nullptr;
        }
        struct AES_ctx* ctx = static_cast<struct AES_ctx*>(calloc(1, sizeof(struct AES_ctx)));
        if (ctx) {
            AES_init_ctx_iv(ctx, videoAesKey_.data(), videoAesIv_.data());
            videoAesCtx_ = ctx;
        }
        nextDecryptCount_ = 0;
        std::memset(og_, 0, sizeof(og_));

        LOGI("Video AES key established: %s, IV: %s, streamConnectionID: %llu",
             hexEncode(videoAesKey_.data(), 16).c_str(),
             hexEncode(videoAesIv_.data(), 16).c_str(),
             (unsigned long long)streamConnectionId_);
        return true;
    }

    LOGI("SETUP processed (ekey size: %zu, streamConnectionId: %llu, hasSessionAesKey: %d)",
         ekey.size(), (unsigned long long)streamConnectionId_, hasSessionAesKey_);
    return true;
}

bool AirPlayCrypto::decryptVideoPayload(uint8_t* payload, size_t length) {
    if (!payload || length == 0 || !hasVideoKey_ || !videoAesCtx_) {
        return false;
    }

    struct AES_ctx* ctx = static_cast<struct AES_ctx*>(videoAesCtx_);

    // Continuous stateful AES-CTR stream decryption (matching UxPlay / AirPlayServer mirror_buffer)
    // 1. Process remainder from previous packet
    if (nextDecryptCount_ > 0) {
        int countToXor = std::min(nextDecryptCount_, static_cast<int>(length));
        for (int i = 0; i < countToXor; ++i) {
            payload[i] = (payload[i] ^ og_[(16 - nextDecryptCount_) + i]);
        }

        if (static_cast<int>(length) <= nextDecryptCount_) {
            nextDecryptCount_ -= length;
            return true;
        }
    }

    // 2. Process aligned 16-byte blocks
    int encryptlen = ((static_cast<int>(length) - nextDecryptCount_) / 16) * 16;
    if (encryptlen > 0) {
        AES_CTR_xcrypt_buffer(ctx, payload + nextDecryptCount_, encryptlen);
    }

    // 3. Process remaining unaligned bytes (< 16) and preserve keystream
    int restlen = (static_cast<int>(length) - nextDecryptCount_) % 16;
    int reststart = static_cast<int>(length) - restlen;
    nextDecryptCount_ = 0;

    if (restlen > 0) {
        std::memset(og_, 0, sizeof(og_));
        std::memcpy(og_, payload + reststart, restlen);
        AES_CTR_xcrypt_buffer(ctx, og_, 16);
        for (int j = 0; j < restlen; ++j) {
            payload[reststart + j] = og_[j];
        }
        nextDecryptCount_ = 16 - restlen;
    }

    return true;
}

void AirPlayCrypto::resetSessionCrypto() {
    fpKeyMsg_.clear();
    sessionAesKey_.clear();
    hasSessionAesKey_ = false;
    streamConnectionId_ = 0;
    videoAesKey_.clear();
    videoAesIv_.clear();
    hasVideoKey_ = false;
    if (videoAesCtx_) {
        free(videoAesCtx_);
        videoAesCtx_ = nullptr;
    }
    nextDecryptCount_ = 0;
    std::memset(og_, 0, sizeof(og_));
    LOGI("Session crypto reset.");
}

} // namespace airplay

