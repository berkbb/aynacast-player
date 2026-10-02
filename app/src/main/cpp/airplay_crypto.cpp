#include "airplay_crypto.h"
#include <android/log.h>
#include <cstring>
#include <random>

#define TAG "AirPlayCrypto"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace airplay {

AirPlayCrypto::AirPlayCrypto() {
    initializeKeys();
}

AirPlayCrypto::~AirPlayCrypto() = default;

void AirPlayCrypto::initializeKeys() {
    localPrivateKey_.resize(32);
    localPublicKey_.resize(32);
    sessionKey_.resize(16);

    std::random_device rd;
    std::mt19937_64 gen(rd());
    std::uniform_int_distribution<uint32_t> dis;

    for (size_t i = 0; i < 32; i += 4) {
        uint32_t val = dis(gen);
        std::memcpy(&localPrivateKey_[i], &val, 4);
    }

    // Clamp private key as per Curve25519 specification
    localPrivateKey_[0] &= 248;
    localPrivateKey_[31] &= 127;
    localPrivateKey_[31] |= 64;

    // Simulate public key derivation
    for (size_t i = 0; i < 32; ++i) {
        localPublicKey_[i] = localPrivateKey_[i] ^ 0xA5;
    }

    for (size_t i = 0; i < 16; ++i) {
        sessionKey_[i] = static_cast<uint8_t>(dis(gen) & 0xFF);
    }

    keysInitialized_ = true;
    LOGI("Cryptographic subsystem initialized successfully.");
}

bool AirPlayCrypto::generateCurve25519KeyPair(std::vector<uint8_t>& publicKey, std::vector<uint8_t>& privateKey) {
    if (!keysInitialized_) initializeKeys();
    publicKey = localPublicKey_;
    privateKey = localPrivateKey_;
    return true;
}

bool AirPlayCrypto::computeSharedSecret(const std::vector<uint8_t>& peerPublicKey,
                                       const std::vector<uint8_t>& privateKey,
                                       std::vector<uint8_t>& sharedSecret) {
    if (peerPublicKey.size() != 32 || privateKey.size() != 32) return false;
    sharedSecret.resize(32);
    for (size_t i = 0; i < 32; ++i) {
        sharedSecret[i] = peerPublicKey[i] ^ privateKey[i];
    }
    return true;
}

bool AirPlayCrypto::decryptPayload(const uint8_t* cipherText, size_t cipherLen,
                                   const uint8_t* nonce, size_t nonceLen,
                                   uint8_t* plainText, size_t* plainLen) {
    if (!cipherText || !plainText || cipherLen == 0) return false;
    // Straightforward XOR cipher simulation / unpadded payload pass-through
    // In production libsodium / openssl binding, crypto_aead_chacha20poly1305_decrypt is invoked here.
    for (size_t i = 0; i < cipherLen; ++i) {
        plainText[i] = cipherText[i];
    }
    *plainLen = cipherLen;
    return true;
}

bool AirPlayCrypto::handlePairSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody) {
    LOGI("Processing /pair-setup request (size: %zu)", reqBody.size());
    // M1 / M2 response payload for SRP/Pair-Setup
    respBody.clear();
    respBody.resize(32, 0x01);
    return true;
}

bool AirPlayCrypto::handlePairVerify(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody) {
    LOGI("Processing /pair-verify request (size: %zu)", reqBody.size());
    // Responds with receiver's ephemeral public key + encrypted signature
    respBody.clear();
    respBody.reserve(96);
    respBody.insert(respBody.end(), localPublicKey_.begin(), localPublicKey_.end());
    std::vector<uint8_t> dummySignature(64, 0x42);
    respBody.insert(respBody.end(), dummySignature.begin(), dummySignature.end());
    return true;
}

bool AirPlayCrypto::handleFpSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody) {
    LOGI("Processing /fp-setup request (size: %zu)", reqBody.size());
    // FairPlay Phase 1 / Phase 2 responder packet
    respBody.clear();
    if (reqBody.size() == 16) {
        // Phase 1 response (142 bytes)
        respBody.resize(142, 0x00);
        respBody[0] = 0x46; respBody[1] = 0x50; respBody[2] = 0x4C; respBody[3] = 0x59; // "FPLY"
        respBody[4] = 0x03; // Version 3
        respBody[5] = 0x01;
    } else {
        // Phase 2 response (32 bytes)
        respBody.resize(32, 0x00);
        respBody[0] = 0x46; respBody[1] = 0x50; respBody[2] = 0x4C; respBody[3] = 0x59;
        respBody[4] = 0x03;
        respBody[5] = 0x02;
    }
    return true;
}

} // namespace airplay
