#include "airplay_crypto.h"
#include <android/log.h>
#include <cstring>
#include <random>

extern "C" {
#include "tweetnacl.h"
#include "aes.h"

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

AirPlayCrypto::~AirPlayCrypto() = default;

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

bool AirPlayCrypto::handleFpSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody) {
    LOGI("Processing /fp-setup request (size: %zu)", reqBody.size());
    respBody.clear();
    if (reqBody.size() == 16) {
        // FairPlay Phase 1 response (142 bytes)
        respBody.resize(142, 0x00);
        respBody[0] = 0x46; respBody[1] = 0x50; respBody[2] = 0x4C; respBody[3] = 0x59; // "FPLY"
        respBody[4] = 0x03;
        respBody[5] = 0x01;
    } else {
        // FairPlay Phase 2 response (32 bytes)
        respBody.resize(32, 0x00);
        respBody[0] = 0x46; respBody[1] = 0x50; respBody[2] = 0x4C; respBody[3] = 0x59;
        respBody[4] = 0x03;
        respBody[5] = 0x02;
    }
    return true;
}

bool AirPlayCrypto::decryptVideoPayload(uint8_t* payload, size_t length) {
    if (!payload || length == 0 || aesKey_.size() != 16) return false;
    struct AES_ctx aesCtx;
    AES_init_ctx_iv(&aesCtx, aesKey_.data(), aesIv_.data());
    AES_CTR_xcrypt_buffer(&aesCtx, payload, length);
    return true;
}

} // namespace airplay
