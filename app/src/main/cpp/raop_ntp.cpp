#include "raop_ntp.h"
#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
#include <algorithm>
#include <cerrno>
#include <cinttypes>
#include <cstdlib>
#include <cstring>
#include <ctime>

#define TAG "AirPlayNTP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace airplay {

namespace {

constexpr int64_t kSecondNs = 1000000000LL;
constexpr uint64_t kSecondsFrom1900To1970 = 2208988800ULL;
constexpr int kBurstRequests = 8;            // UxPlay NTP_BURST_LIMIT
constexpr int64_t kBurstIntervalNs = 250000000LL;
constexpr int64_t kSteadyIntervalNs = 3 * kSecondNs;
constexpr int kReceiveTimeoutMs = 300;
constexpr size_t kSampleCount = 8;           // UxPlay RAOP_NTP_DATA_COUNT
constexpr size_t kMaxPending = 16;
constexpr int64_t kMinDelayNs = 1000000LL;   // UxPlay RAOP_NTP_S_RHO (1 ms)
constexpr double kSmoothing = 0.05;          // UxPlay RAOP_NTP_ALPHA
constexpr int64_t kResyncThresholdNs = kSecondNs; // treat larger jumps as a sender clock change

uint64_t readBe64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v = (v << 8) | p[i];
    return v;
}

void writeBe64(uint8_t* p, uint64_t v) {
    for (int i = 7; i >= 0; --i) {
        p[i] = static_cast<uint8_t>(v & 0xFF);
        v >>= 8;
    }
}

// Wall-clock NTP timestamp for the wire (the sender only echoes it back).
uint64_t realtimeNtp() {
    timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    uint64_t seconds = static_cast<uint64_t>(ts.tv_sec) + kSecondsFrom1900To1970;
    uint64_t fraction = (static_cast<uint64_t>(ts.tv_nsec) << 32) / static_cast<uint64_t>(kSecondNs);
    return (seconds << 32) | (fraction & 0xFFFFFFFFULL);
}

} // namespace

NtpClient::~NtpClient() {
    stop();
}

int64_t NtpClient::monotonicNowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * kSecondNs + ts.tv_nsec;
}

int64_t NtpClient::ntpToNs(uint64_t ntp) {
    uint64_t seconds = ntp >> 32;
    uint64_t fraction = ntp & 0xFFFFFFFFULL;
    return static_cast<int64_t>(seconds * static_cast<uint64_t>(kSecondNs) +
                                ((fraction * static_cast<uint64_t>(kSecondNs)) >> 32));
}

int NtpClient::start(const std::string& remoteIp, uint16_t remotePort, uint16_t preferredLocalPort) {
    // start() and stop() run on different connection threads (SETUP vs TEARDOWN / session end).
    std::lock_guard<std::mutex> lifecycle(lifecycleMutex_);
    stopLocked();

    in_addr addr{};
    if (remotePort == 0 || inet_pton(AF_INET, remoteIp.c_str(), &addr) != 1) {
        LOGW("NTP timing not started (remote %s:%u)", remoteIp.c_str(), remotePort);
        return -1;
    }

    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in local{};
    local.sin_family = AF_INET;
    local.sin_addr.s_addr = INADDR_ANY;
    local.sin_port = htons(preferredLocalPort);
    if (bind(fd, reinterpret_cast<sockaddr*>(&local), sizeof(local)) < 0) {
        local.sin_port = 0; // Preferred port busy: let the kernel choose one.
        if (bind(fd, reinterpret_cast<sockaddr*>(&local), sizeof(local)) < 0) {
            LOGW("NTP socket bind failed: %s", strerror(errno));
            close(fd);
            return -1;
        }
    }
    socklen_t len = sizeof(local);
    getsockname(fd, reinterpret_cast<sockaddr*>(&local), &len);
    int localPort = ntohs(local.sin_port);

    {
        std::lock_guard<std::mutex> lock(mutex_);
        pending_.clear();
        samples_.clear();
        synced_ = false;
        offsetNs_ = 0;
    }
    fd_ = fd;
    remoteAddr_ = addr.s_addr;
    remotePort_ = remotePort;
    requestCount_ = 0;
    localPort_.store(localPort);
    running_.store(true);
    thread_ = std::thread(&NtpClient::run, this);
    LOGI("NTP timing started: local port %d -> %s:%u", localPort, remoteIp.c_str(), remotePort);
    return localPort;
}

void NtpClient::stop() {
    std::lock_guard<std::mutex> lifecycle(lifecycleMutex_);
    stopLocked();
}

void NtpClient::stopLocked() {
    bool wasRunning = running_.exchange(false);
    if (thread_.joinable()) thread_.join();
    localPort_.store(0);
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
    if (wasRunning) LOGI("NTP timing stopped.");
}

bool NtpClient::isSynced() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return synced_;
}

int64_t NtpClient::offsetNs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return offsetNs_;
}

int64_t NtpClient::remoteToLocal(int64_t remoteNs) const {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!synced_ || remoteNs == 0) return 0;
    int64_t local = remoteNs - offsetNs_;
    return local > 0 ? local : 0;
}

void NtpClient::run() {
    int64_t nextSend = monotonicNowNs();
    uint8_t buffer[128];
    while (running_.load()) {
        int64_t now = monotonicNowNs();
        if (now >= nextSend) {
            sendRequest();
            ++requestCount_;
            nextSend = now + (requestCount_ <= kBurstRequests ? kBurstIntervalNs : kSteadyIntervalNs);
        }
        int waitMs = static_cast<int>(std::min<int64_t>((nextSend - now) / 1000000LL + 1, kReceiveTimeoutMs));
        pollfd pfd{fd_, POLLIN, 0};
        if (poll(&pfd, 1, std::max(waitMs, 1)) <= 0 || !(pfd.revents & POLLIN)) continue;

        ssize_t n = recv(fd_, buffer, sizeof(buffer), 0);
        int64_t received = monotonicNowNs();
        if (n >= 32) {
            handleResponse(buffer, static_cast<size_t>(n), received);
        }
    }
}

void NtpClient::sendRequest() {
    uint8_t request[32] = {0x80, 0xd2, 0x00, 0x07};
    uint64_t wireTime = realtimeNtp();
    writeBe64(request + 24, wireTime);

    sockaddr_in remote{};
    remote.sin_family = AF_INET;
    remote.sin_addr.s_addr = remoteAddr_;
    remote.sin_port = htons(remotePort_);

    int64_t sent = monotonicNowNs();
    if (sendto(fd_, request, sizeof(request), 0, reinterpret_cast<sockaddr*>(&remote), sizeof(remote)) < 0) {
        LOGW("NTP request send failed: %s", strerror(errno));
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    pending_.push_back({wireTime, sent});
    while (pending_.size() > kMaxPending) pending_.pop_front();
}

void NtpClient::handleResponse(const uint8_t* packet, size_t length, int64_t receivedMonotonicNs) {
    if (length < 32) return;
    uint64_t origin = readBe64(packet + 8);
    int64_t t2 = ntpToNs(readBe64(packet + 16)); // sender receive time
    int64_t t3 = ntpToNs(readBe64(packet + 24)); // sender transmit time

    std::lock_guard<std::mutex> lock(mutex_);
    auto it = std::find_if(pending_.begin(), pending_.end(),
                           [origin](const PendingRequest& r) { return r.wireTime == origin; });
    if (it == pending_.end()) return; // Not an answer to one of our requests (or a duplicate).
    int64_t t1 = it->sentMonotonicNs;
    pending_.erase(it);
    int64_t t4 = receivedMonotonicNs;

    // Offset of the sender clock relative to local monotonic time, and the round-trip delay.
    int64_t offset = ((t2 - t1) + (t3 - t4)) / 2;
    int64_t delay = std::max<int64_t>((t4 - t1) - (t3 - t2), kMinDelayNs);

    samples_.push_back({offset, delay});
    while (samples_.size() > kSampleCount) samples_.pop_front();

    // Like an NTP clock filter: the sample with the smallest delay carries the least jitter.
    const Sample& best = *std::min_element(samples_.begin(), samples_.end(),
                                           [](const Sample& a, const Sample& b) { return a.delayNs < b.delayNs; });
    if (!synced_ || std::llabs(best.offsetNs - offsetNs_) > kResyncThresholdNs) {
        offsetNs_ = best.offsetNs;
        if (!synced_) LOGI("NTP synchronized with sender (round trip %.2f ms)", static_cast<double>(best.delayNs) / 1e6);
        synced_ = true;
    } else {
        offsetNs_ += static_cast<int64_t>(kSmoothing * static_cast<double>(best.offsetNs - offsetNs_));
    }
}

} // namespace airplay
