#include "airplay_crypto.h"
#include "bplist_reader.h"
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <algorithm>

extern "C" {
#include "tweetnacl.h"
#include "aes.h"
#include "playfair/playfair.h"

// Key material must come from the platform CSPRNG (bionic's arc4random is backed by getrandom()).
void randombytes(unsigned char* ptr, unsigned long long length) {
    arc4random_buf(ptr, static_cast<size_t>(length));
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

    // Generate long-term Ed25519 identity keypair (replaced by setIdentitySeed when one is stored)
    crypto_sign_keypair(serverEd25519Pk_.data(), serverEd25519Sk_.data());

    keysInitialized_ = true;
    LOGI("Cryptographic subsystem initialized. Public key: %s", getEd25519PublicKeyHex().c_str());
}

bool AirPlayCrypto::setIdentitySeed(const std::vector<uint8_t>& seed) {
    if (seed.size() != 32) return false;
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    crypto_sign_seed_keypair(serverEd25519Pk_.data(), serverEd25519Sk_.data(), seed.data());
    LOGI("Persistent identity loaded. Public key: %s", getEd25519PublicKeyHex().c_str());
    return true;
}

namespace {

constexpr size_t kMaxHandshakes = 64;

void generateEphemeralKeyPair(std::vector<uint8_t>& pk, std::vector<uint8_t>& sk) {
    sk.resize(32);
    pk.resize(32);
    randombytes(sk.data(), sk.size());
    // Clamp private key per Curve25519 / RFC 7748
    sk[0] &= 248;
    sk[31] &= 127;
    sk[31] |= 64;
    crypto_scalarmult_base(pk.data(), sk.data());
}

} // namespace

AirPlayCrypto::Handshake& AirPlayCrypto::handshakeLocked(uint64_t connectionId) {
    auto it = handshakes_.find(connectionId);
    if (it != handshakes_.end()) return it->second;
    // Connections are forgotten when they close; this only guards against unbounded growth.
    if (handshakes_.size() >= kMaxHandshakes) handshakes_.erase(handshakes_.begin());
    return handshakes_[connectionId];
}

bool AirPlayCrypto::isPairVerified(uint64_t connectionId) const {
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    auto it = handshakes_.find(connectionId);
    return it != handshakes_.end() && it->second.verified;
}

void AirPlayCrypto::forgetConnection(uint64_t connectionId) {
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    handshakes_.erase(connectionId);
}

std::string AirPlayCrypto::getEd25519PublicKeyBase64() const {
    return base64Encode(serverEd25519Pk_.data(), serverEd25519Pk_.size());
}

std::string AirPlayCrypto::getEd25519PublicKeyHex() const {
    return hexEncode(serverEd25519Pk_.data(), serverEd25519Pk_.size());
}

bool AirPlayCrypto::handlePairSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody) {
    LOGI("Processing /pair-setup request (size: %zu)", reqBody.size());
    // Responds with server's 32-byte Ed25519 public key
    respBody.assign(serverEd25519Pk_.begin(), serverEd25519Pk_.end());
    return true;
}

bool AirPlayCrypto::handlePairVerify(uint64_t connectionId, const std::vector<uint8_t>& reqBody,
                                     std::vector<uint8_t>& respBody) {
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    LOGI("Processing /pair-verify request (size: %zu)", reqBody.size());
    Handshake& hs = handshakeLocked(connectionId);

    // Step 1: Client sends 68 bytes (flag 0x01 + 32-byte client Curve25519 key + 32 bytes)
    if (reqBody.size() >= 68 && reqBody[0] == 0x01) {
        const uint8_t* clientEcdhPk = reqBody.data() + 4;
        hs.verified = false;
        hs.clientEcdhPk.assign(clientEcdhPk, clientEcdhPk + 32);
        hs.clientEd25519Pk.assign(reqBody.data() + 36, reqBody.data() + 68);

        // Generate fresh ephemeral Curve25519 keypair for this handshake
        generateEphemeralKeyPair(hs.serverEcdhPk, hs.serverEcdhSk);

        // 1. Shared secret via Curve25519
        std::vector<uint8_t> sharedSecret(32);
        if (crypto_scalarmult(sharedSecret.data(), hs.serverEcdhSk.data(), clientEcdhPk) != 0) {
            LOGE("Failed to compute Curve25519 shared secret");
            hs.sharedSecret.clear();
            return false;
        }
        hs.sharedSecret = sharedSecret;

        // 2. Derive AES Key: SHA512("Pair-Verify-AES-Key" + sharedSecret)[0..15]
        const std::string keySalt = "Pair-Verify-AES-Key";
        std::vector<uint8_t> keyMat(keySalt.begin(), keySalt.end());
        keyMat.insert(keyMat.end(), sharedSecret.begin(), sharedSecret.end());

        uint8_t shaKey[64];
        crypto_hash(shaKey, keyMat.data(), keyMat.size());
        hs.aesKey.assign(shaKey, shaKey + 16);

        // 3. Derive AES IV: SHA512("Pair-Verify-AES-IV" + sharedSecret)[0..15]
        const std::string ivSalt = "Pair-Verify-AES-IV";
        std::vector<uint8_t> ivMat(ivSalt.begin(), ivSalt.end());
        ivMat.insert(ivMat.end(), sharedSecret.begin(), sharedSecret.end());

        uint8_t shaIv[64];
        crypto_hash(shaIv, ivMat.data(), ivMat.size());
        hs.aesIv.assign(shaIv, shaIv + 16);

        // 4. Construct message to sign: serverEcdhPk (32) + clientEcdhPk (32) = 64 bytes
        uint8_t msgToSign[64];
        std::memcpy(msgToSign, hs.serverEcdhPk.data(), 32);
        std::memcpy(msgToSign + 32, clientEcdhPk, 32);

        // 5. Sign with long-term Ed25519 private key
        unsigned long long smlen = 0;
        uint8_t signedMsg[64 + 64];
        crypto_sign(signedMsg, &smlen, msgToSign, 64, serverEd25519Sk_.data());

        uint8_t signature[64];
        std::memcpy(signature, signedMsg, 64);

        // 6. Encrypt signature with AES-128-CTR
        struct AES_ctx aesCtx;
        AES_init_ctx_iv(&aesCtx, hs.aesKey.data(), hs.aesIv.data());
        AES_CTR_xcrypt_buffer(&aesCtx, signature, 64);

        // 7. Assemble 96-byte response: serverEcdhPk (32) + encryptedSignature (64)
        respBody.clear();
        respBody.reserve(96);
        respBody.insert(respBody.end(), hs.serverEcdhPk.begin(), hs.serverEcdhPk.end());
        respBody.insert(respBody.end(), signature, signature + 64);

        LOGI("Pair-verify Step 1 completed successfully (response size: %zu)", respBody.size());
        return true;
    }

    // Step 2: Client sends 68 bytes (flag 0x00 + 64-byte encrypted signature) -> Server replies with 0 bytes.
    // As in UxPlay's pairing_session_finish, the signature over (client ECDH key || server ECDH key) is
    // verified with the Ed25519 key the client presented in step 1.
    if (reqBody.size() >= 4 && reqBody[0] == 0x00) {
        respBody.clear();
        if (reqBody.size() < 68 || hs.clientEd25519Pk.size() != 32 || hs.clientEcdhPk.size() != 32 ||
            hs.sharedSecret.size() != 32) {
            LOGE("Pair-verify step 2 without a valid step 1; rejecting client.");
            return false;
        }

        uint8_t signature[64];
        std::memcpy(signature, reqBody.data() + 4, 64);
        struct AES_ctx aesCtx;
        AES_init_ctx_iv(&aesCtx, hs.aesKey.data(), hs.aesIv.data());
        // One "fake round": the first 64 keystream bytes encrypted the server's step-1 signature.
        uint8_t skipped[64] = {0};
        AES_CTR_xcrypt_buffer(&aesCtx, skipped, sizeof(skipped));
        AES_CTR_xcrypt_buffer(&aesCtx, signature, sizeof(signature));

        // crypto_sign_open expects the signed message as signature || message.
        uint8_t signedMsg[128];
        std::memcpy(signedMsg, signature, 64);
        std::memcpy(signedMsg + 64, hs.clientEcdhPk.data(), 32);
        std::memcpy(signedMsg + 96, hs.serverEcdhPk.data(), 32);
        uint8_t opened[128];
        unsigned long long openedLen = 0;
        if (crypto_sign_open(opened, &openedLen, signedMsg, sizeof(signedMsg), hs.clientEd25519Pk.data()) != 0) {
            LOGE("Incorrect pair-verify signature; rejecting client.");
            hs.verified = false;
            return false;
        }
        hs.verified = true;
        LOGI("Pair-verify Step 2 completed successfully. Channel authenticated.");
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

bool AirPlayCrypto::handleFpSetup(uint64_t connectionId, const std::vector<uint8_t>& reqBody,
                                  std::vector<uint8_t>& respBody) {
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    LOGI("Processing /fp-setup request (size: %zu)", reqBody.size());
    Handshake& hs = handshakeLocked(connectionId);
    respBody.clear();
    if (reqBody.size() == 16) {
        // FairPlay Phase 1 (142 bytes)
        int mode = reqBody[14] % 4;
        respBody.assign(reply_message[mode], reply_message[mode] + 142);
        hs.fpKeyMsg.clear();
        LOGI("FairPlay SAP Step 1 OK (mode: %d, response size: 142)", mode);
        return true;
    } else if (reqBody.size() >= 164) {
        // FairPlay Phase 2 handshake (32 bytes response). Byte 12 selects one of the 4 key tables
        // playfair_decrypt indexes later, so it must be validated here (UxPlay checks byte 4).
        if (reqBody[4] != 0x03 || reqBody[12] >= 4) {
            LOGE("Rejecting malformed FairPlay key message");
            return false;
        }
        hs.fpKeyMsg.assign(reqBody.begin(), reqBody.begin() + 164);
        static const uint8_t fpHeader[12] = {0x46, 0x50, 0x4c, 0x59, 0x03, 0x01, 0x04, 0x00, 0x00, 0x00, 0x00, 0x14};
        respBody.assign(fpHeader, fpHeader + 12);
        respBody.insert(respBody.end(), reqBody.begin() + 144, reqBody.begin() + 164);
        LOGI("FairPlay SAP Step 2 Handshake OK (saved 164 bytes keymsg, response size: 32)");
        return true;
    }
    return false;
}

namespace {

// Extracts the FairPlay-wrapped key (ekey), its IV (eiv) and the mirroring stream connection ID
// from a SETUP body. The connection ID is either top-level or inside a type-110 (video) stream entry.
bool parseBplistSetup(const std::vector<uint8_t>& data,
                      std::vector<uint8_t>& outEkey,
                      std::vector<uint8_t>& outEiv,
                      uint64_t& outStreamConnectionId) {
    BplistValue root;
    if (!parseBinaryPlist(data, root) || !root.isDict()) return false;

    if (const BplistValue* ekey = root.find("ekey"); ekey && ekey->type == BplistValue::Type::Data) {
        outEkey = ekey->dataVal;
    }
    if (const BplistValue* eiv = root.find("eiv"); eiv && eiv->type == BplistValue::Type::Data) {
        outEiv = eiv->dataVal;
    }
    outStreamConnectionId = root.intFor("streamConnectionID", outStreamConnectionId);

    if (const BplistValue* streams = root.find("streams"); streams && streams->isArray()) {
        for (const BplistValue& stream : streams->arrayVal) {
            // Type 110 is Video Screen Mirroring. Only type 110 uses AES-CTR video decryption!
            uint64_t streamConnId = stream.intFor("streamConnectionID", 0);
            if (stream.intFor("type", 0) == 110 && streamConnId != 0) {
                outStreamConnectionId = streamConnId;
            }
        }
    }
    return !outEkey.empty() || outStreamConnectionId != 0;
}

} // anonymous namespace

bool AirPlayCrypto::hasVideoKey() const {
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    return hasVideoKey_;
}

bool AirPlayCrypto::handleSetup(uint64_t connectionId, const std::vector<uint8_t>& reqBody,
                                uint64_t& outStreamConnectionId) {
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    LOGI("Processing SETUP request (body size: %zu)", reqBody.size());
    Handshake& hs = handshakeLocked(connectionId);

    std::vector<uint8_t> ekey;
    std::vector<uint8_t> eiv;
    uint64_t parsedId = 0;
    parseBplistSetup(reqBody, ekey, eiv, parsedId);

    // Only search raw bytes for ekey if bplist parser missed it
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

    if (ekey.size() == 72 && hs.fpKeyMsg.size() >= 164) {
        LOGI("Found 72-byte ekey in SETUP and valid FairPlay handshake! Decrypting session AES key...");
        uint8_t rawAesKey[16] = {0};
        playfair_decrypt(hs.fpKeyMsg.data(), ekey.data(), rawAesKey);

        // Audio/session AES key derivation matching UxPlay:
        // If pairing ecdh_secret exists (bit 27 ON):
        //   aeskey = SHA-512(rawAesKey(16) + sharedSecret(32))[0..15]
        // Else (legacy pairing disabled / bit 27 OFF):
        //   aeskey = rawAesKey (16 bytes, unhashed!)
        uint8_t finalAesKey[16] = {0};
        if (hs.sharedSecret.size() == 32) {
            LOGI("Hashing FairPlay rawAesKey with Curve25519 sharedSecret (32 bytes)...");
            uint8_t eaesMat[48] = {0};
            std::memcpy(eaesMat, rawAesKey, 16);
            std::memcpy(eaesMat + 16, hs.sharedSecret.data(), 32);
            uint8_t eaesHash[64] = {0};
            crypto_hash(eaesHash, eaesMat, 48);
            std::memcpy(finalAesKey, eaesHash, 16);
        } else {
            LOGI("Using pure FairPlay rawAesKey (no pairing secret, bit 27 disabled).");
            std::memcpy(finalAesKey, rawAesKey, 16);
        }

        sessionAesKey_.assign(finalAesKey, finalAesKey + 16);
        hasSessionAesKey_ = true;
        if (!eiv.empty() && eiv.size() >= 16) {
            sessionAesIv_.assign(eiv.begin(), eiv.begin() + 16);
            LOGI("Saved session AES IV (16 bytes).");
        }
        LOGI("Saved session AES key (16 bytes).");
    }

    if (parsedId == 0) {
        LOGI("SETUP request has no video streamConnectionID (likely audio/control stream, size: %zu). Preserving active video AES state.", reqBody.size());
        return true;
    }

    // If this streamConnectionID is identical to the current active video stream, preserve cipher state
    if (hasVideoKey_ && parsedId == streamConnectionId_) {
        LOGI("SETUP streamConnectionID %llu matches current active video stream. Preserving active AES-CTR cipher state.",
             (unsigned long long)parsedId);
        outStreamConnectionId = parsedId;
        return true;
    }

    streamConnectionId_ = parsedId;
    outStreamConnectionId = parsedId;
    LOGI("Identified streamConnectionID from SETUP bplist: %llu", (unsigned long long)streamConnectionId_);

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

        LOGI("Video AES key established for streamConnectionID: %llu",
             (unsigned long long)streamConnectionId_);
        return true;
    }

    LOGI("SETUP processed (ekey size: %zu, streamConnectionId: %llu, hasSessionAesKey: %d)",
         ekey.size(), (unsigned long long)streamConnectionId_, hasSessionAesKey_);
    return true;
}

bool AirPlayCrypto::decryptVideoPayload(uint8_t* payload, size_t length) {
    if (!payload || length == 0) return false;

    std::lock_guard<std::mutex> lock(cryptoMutex_);
    if (!hasVideoKey_ || !videoAesCtx_) {
        return false;
    }

    struct AES_ctx* ctx = static_cast<struct AES_ctx*>(videoAesCtx_);

    // Continuous stateful AES-CTR stream decryption (matching UxPlay / AirPlayServer mirror_buffer)
    // 1. Process remainder from previous packet
    int offset = 0;
    if (nextDecryptCount_ > 0) {
        int countToXor = std::min(nextDecryptCount_, static_cast<int>(length));
        for (int i = 0; i < countToXor; ++i) {
            payload[i] = (payload[i] ^ og_[(16 - nextDecryptCount_) + i]);
        }

        if (static_cast<int>(length) <= nextDecryptCount_) {
            nextDecryptCount_ -= length;
            return true;
        }
        offset = nextDecryptCount_;
        nextDecryptCount_ = 0;
    }

    // 2. Process aligned 16-byte blocks
    int remaining = static_cast<int>(length) - offset;
    int encryptlen = (remaining / 16) * 16;
    if (encryptlen > 0) {
        AES_CTR_xcrypt_buffer(ctx, payload + offset, encryptlen);
    }

    // 3. Process remaining unaligned bytes (< 16) and preserve keystream
    int restlen = remaining % 16;
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
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    sessionAesKey_.clear();
    sessionAesIv_.clear();
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

void AirPlayCrypto::resetVideoCipher() {
    std::lock_guard<std::mutex> lock(cryptoMutex_);
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
}

bool AirPlayCrypto::hasAudioKey() const {
    std::lock_guard<std::mutex> lock(cryptoMutex_);
    return hasSessionAesKey_ && !sessionAesIv_.empty();
}

bool AirPlayCrypto::decryptAudioPayload(uint8_t* payload, size_t length) {
    if (!payload || length == 0) return false;

    std::lock_guard<std::mutex> lock(cryptoMutex_);
    if (!hasSessionAesKey_) {
        return false;
    }

    const uint8_t* ivPtr = sessionAesIv_.size() >= 16 ? sessionAesIv_.data() : nullptr;
    if (!ivPtr) {
        return false;
    }

    size_t encryptedLen = (length / 16) * 16;
    if (encryptedLen > 0) {
        struct AES_ctx ctx;
        AES_init_ctx_iv(&ctx, sessionAesKey_.data(), ivPtr);
        AES_CBC_decrypt_buffer(&ctx, payload, encryptedLen);
    }
    return true;
}

} // namespace airplay

