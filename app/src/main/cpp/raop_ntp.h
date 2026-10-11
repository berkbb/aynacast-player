#ifndef RAOP_NTP_H
#define RAOP_NTP_H

#include <atomic>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

namespace airplay {

// Queries the sender's clock with AirPlay NTP timing requests (as UxPlay's raop_ntp.c does) and
// maps sender timestamps onto the local CLOCK_MONOTONIC timeline, which Android's
// System.nanoTime(), MediaCodec render times and AudioTrack timestamps share.
class NtpClient {
public:
    NtpClient() = default;
    ~NtpClient();

    NtpClient(const NtpClient&) = delete;
    NtpClient& operator=(const NtpClient&) = delete;

    // Starts timing exchanges with remoteIp:remotePort. Binds preferredLocalPort when possible
    // (otherwise an ephemeral port) and returns the bound local port, or -1 on failure.
    int start(const std::string& remoteIp, uint16_t remotePort, uint16_t preferredLocalPort);
    void stop();

    bool isSynced() const;
    // Local UDP port of the running timing exchange, or 0 when stopped.
    int localPort() const { return localPort_.load(); }
    // Sender clock (ns, NTP epoch) -> local monotonic ns. Returns 0 while no offset is known.
    int64_t remoteToLocal(int64_t remoteNs) const;
    int64_t offsetNs() const;

    static int64_t monotonicNowNs();
    // Converts a 64-bit NTP timestamp (32.32 fixed point) to nanoseconds.
    static int64_t ntpToNs(uint64_t ntp);

private:
    struct PendingRequest {
        uint64_t wireTime;
        int64_t sentMonotonicNs;
    };
    struct Sample {
        int64_t offsetNs;
        int64_t delayNs;
    };

    void run();
    void sendRequest();
    void handleResponse(const uint8_t* packet, size_t length, int64_t receivedMonotonicNs);

    void stopLocked();

    std::mutex lifecycleMutex_; // serialises start()/stop(); never taken by the timing thread
    std::atomic<bool> running_{false};
    std::thread thread_;
    int fd_{-1};
    uint32_t remoteAddr_{0}; // network byte order
    uint16_t remotePort_{0};
    std::atomic<int> localPort_{0};
    int requestCount_{0};

    mutable std::mutex mutex_;
    std::deque<PendingRequest> pending_;
    std::deque<Sample> samples_;
    bool synced_{false};
    int64_t offsetNs_{0};
};

} // namespace airplay

#endif // RAOP_NTP_H
