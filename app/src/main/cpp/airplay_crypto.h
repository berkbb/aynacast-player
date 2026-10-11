#ifndef AIRPLAY_CRYPTO_H
#define AIRPLAY_CRYPTO_H

#include <cstdint>
#include <map>
#include <vector>
#include <string>
#include <mutex>

namespace airplay {

class AirPlayCrypto {
public:
    AirPlayCrypto();
    ~AirPlayCrypto();

    void initializeKeys();
    // Derives the long-term Ed25519 identity from a stored 32-byte seed, so the receiver keeps the
    // same "pk" across restarts and senders paired with the PIN stay paired. Call before serving.
    bool setIdentitySeed(const std::vector<uint8_t>& seed);

    // Long-term Ed25519 identity key
    const std::vector<uint8_t>& getEd25519PublicKey() const { return serverEd25519Pk_; }
    std::string getEd25519PublicKeyBase64() const;
    std::string getEd25519PublicKeyHex() const;

    // Pair Setup / Pair Verify / FairPlay Handshake / Setup. Handshake state is kept per RTSP
    // connection (like UxPlay's raop_conn), so one sender's handshake cannot disturb another's.
    bool handlePairSetup(const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    // Returns false when the client's step-2 signature does not verify; the connection must then be closed.
    bool handlePairVerify(uint64_t connectionId, const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    bool handleFpSetup(uint64_t connectionId, const std::vector<uint8_t>& reqBody, std::vector<uint8_t>& respBody);
    // Derives the session keys from this connection's handshake.
    bool handleSetup(uint64_t connectionId, const std::vector<uint8_t>& reqBody, uint64_t& outStreamConnectionId);
    // True once this connection completed pair-verify with a valid signature.
    bool isPairVerified(uint64_t connectionId) const;
    void forgetConnection(uint64_t connectionId);

    // Video stream decryption
    bool hasVideoKey() const;
    bool decryptVideoPayload(uint8_t* payload, size_t length);

    // Audio stream decryption (AES-128-CBC)
    bool hasAudioKey() const;
    bool decryptAudioPayload(uint8_t* payload, size_t length);

    void resetSessionCrypto();
    // The mirrored video stream ended (TEARDOWN of stream 110) but the session goes on: the next
    // video SETUP starts a new AES-CTR stream even if it reuses the streamConnectionID (as UxPlay).
    void resetVideoCipher();

private:
    // One connection's pair-verify and FairPlay state.
    struct Handshake {
        std::vector<uint8_t> serverEcdhPk;
        std::vector<uint8_t> serverEcdhSk;
        std::vector<uint8_t> sharedSecret;
        // Pair-verify step 1 keys of the client, needed to verify its step-2 signature.
        std::vector<uint8_t> clientEcdhPk;
        std::vector<uint8_t> clientEd25519Pk;
        std::vector<uint8_t> aesKey;
        std::vector<uint8_t> aesIv;
        std::vector<uint8_t> fpKeyMsg;
        bool verified{false};
    };
    Handshake& handshakeLocked(uint64_t connectionId);

    mutable std::mutex cryptoMutex_;

    std::vector<uint8_t> serverEd25519Pk_;
    std::vector<uint8_t> serverEd25519Sk_;

    std::map<uint64_t, Handshake> handshakes_;

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
