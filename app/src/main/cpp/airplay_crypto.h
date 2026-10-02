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

    // Long-term Ed25519 identity key
    const std::vector<uint8_t>& getEd25519PublicKey() const { return serverEd25519Pk_; }
    std::string getEd25519PublicKeyBase64() const;
    std::string getEd25519PublicKeyHex() const;

    // Ephemeral Curve25519 ECDH
    bool generateEphemeralCurve25519KeyPair();
    bool computeSharedSecret(const uint8_t* peerPublicKey, std::vector<uint8_t>& sharedSecret);

    // Pair Setup / Pair Verify / FairPlay Handshake
    bool handlePairSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    bool handlePairVerify(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    bool handleFpSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);

    // Video stream decryption
    bool decryptVideoPayload(uint8_t* payload, size_t length);

private:
    std::vector<uint8_t> serverEd25519Pk_;
    std::vector<uint8_t> serverEd25519Sk_;

    std::vector<uint8_t> serverEcdhPk_;
    std::vector<uint8_t> serverEcdhSk_;

    std::vector<uint8_t> aesKey_;
    std::vector<uint8_t> aesIv_;

    bool keysInitialized_{false};
};

} // namespace airplay

#endif // AIRPLAY_CRYPTO_H
