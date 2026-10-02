#ifndef AIRPLAY_CRYPTO_H
#define AIRPLAY_CRYPTO_H

#include <cstdint>
#include <vector>
#include <string>

namespace airplay {

class AirPlayCrypto {
public:
    AirPlayCrypto();
    ~AirPlayCrypto();

    void initializeKeys();

    // Curve25519 Key exchange
    bool generateCurve25519KeyPair(std::vector<uint8_t>& publicKey, std::vector<uint8_t>& privateKey);
    bool computeSharedSecret(const std::vector<uint8_t>& peerPublicKey,
                            const std::vector<uint8_t>& privateKey,
                            std::vector<uint8_t>& sharedSecret);

    // ChaCha20-Poly1305 Decryption for H.264 streams
    bool decryptPayload(const uint8_t* cipherText, size_t cipherLen,
                        const uint8_t* nonce, size_t nonceLen,
                        uint8_t* plainText, size_t* plainLen);

    // FairPlay / Pair verification simulator for open-source receivers
    bool handlePairSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    bool handlePairVerify(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    bool handleFpSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);

    const std::vector<uint8_t>& getSessionAesKey() const { return sessionKey_; }

private:
    std::vector<uint8_t> localPrivateKey_;
    std::vector<uint8_t> localPublicKey_;
    std::vector<uint8_t> sessionKey_;
    bool keysInitialized_{false};
};

} // namespace airplay

#endif // AIRPLAY_CRYPTO_H
