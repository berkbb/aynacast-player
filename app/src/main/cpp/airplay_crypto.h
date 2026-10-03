#ifndef AIRPLAY_CRYPTO_H
#define AIRPLAY_CRYPTO_H

#include <cstdint>
#include <vector>
#include <string>
#include <mutex>

namespace airplay {

class AirPlayCrypto {
public:
    AirPlayCrypto();
    ~AirPlayCrypto();

    void initializeKeys();

    // Long-term Ed25519 identity key
    const std::vector<uint8_t>& getEd25519PublicKey() const { return serverEd25519Pk_; }
    std::string getEd25519PublicKeyBase64() const;
    std::string getEd25519PublicKeyHex() const;

    // Ephemeral Curve25519 ECDH
    bool generateEphemeralCurve25519KeyPair();
    bool computeSharedSecret(const uint8_t* peerPublicKey, std::vector<uint8_t>& sharedSecret);

    // Pair Setup / Pair Verify / FairPlay Handshake / Setup
    bool handlePairSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    bool handlePairVerify(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    bool handleFpSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    bool handleSetup(const std::vector<uint8_t>& reqBody, uint64_t& outStreamConnectionId);

    // Video stream decryption
    bool hasVideoKey() const;
    bool decryptVideoPayload(uint8_t* payload, size_t length);

    // Audio stream decryption (AES-128-CBC)
    bool hasAudioKey() const;
    bool decryptAudioPayload(uint8_t* payload, size_t length);

    void resetSessionCrypto();

private:
    mutable std::mutex cryptoMutex_;

    std::vector<uint8_t> serverEd25519Pk_;
    std::vector<uint8_t> serverEd25519Sk_;

    std::vector<uint8_t> serverEcdhPk_;
    std::vector<uint8_t> serverEcdhSk_;

    std::vector<uint8_t> sharedSecret_;
    std::vector<uint8_t> fpKeyMsg_;

    std::vector<uint8_t> aesKey_;
    std::vector<uint8_t> aesIv_;

    std::vector<uint8_t> sessionAesKey_;
    std::vector<uint8_t> sessionAesIv_;
    bool hasSessionAesKey_{false};
    uint64_t streamConnectionId_{0};

    std::vector<uint8_t> videoAesKey_;
    std::vector<uint8_t> videoAesIv_;
    bool hasVideoKey_{false};

    // Continuous stateful AES-CTR context across frames (matches UxPlay mirror_buffer)
    void* videoAesCtx_{nullptr};
    int nextDecryptCount_{0};
    uint8_t og_[16]{0};

    bool keysInitialized_{false};
};

} // namespace airplay

#endif // AIRPLAY_CRYPTO_H
