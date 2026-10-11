// Host test harness: runs the AirPlay core and prints its callbacks as "EVT ..." lines for driver.py.
#include "raop_rtsp_server.h"
#include <chrono>
#include <cstdio>
#include <ctime>
#include <cstdio>
#include <iostream>
#include <string>
using namespace airplay;
static long long nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
int main() {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    RaopRtspServer server;
    server.setDeviceIdentity("Salon & <Mutfak> TV", "AA:BB:CC:DD:EE:FF");
    server.setClientConnectedCallback([](const ClientInfo& c) { printf("EVT connected name=%s ip=%s\n", c.deviceName.c_str(), c.ipAddress.c_str()); });
    server.setStreamStartedCallback([]() { printf("EVT started\n"); });
    server.setStreamStoppedCallback([]() { printf("EVT stopped\n"); });
    server.setVideoStoppedCallback([]() { printf("EVT videostopped\n"); });
    server.setAudioConfigCallback([](int codec, int sr, int ch, int spf) { printf("EVT audioconfig codec=%d sr=%d ch=%d spf=%d\n", codec, sr, ch, spf); });
    server.setAudioPacketCallback([](const uint8_t*, size_t n, uint64_t pts) { printf("EVT audiopacket size=%zu pts=%llu now=%lld\n", n, (unsigned long long)pts, nowNs()); });
    server.setVolumeCallback([](float db) { printf("EVT volume %.2f\n", db); });
    server.getVideoParser().setSpsPpsCallback([](const std::vector<uint8_t>& s, const std::vector<uint8_t>& p) { printf("EVT spspps sps=%zu pps=%zu\n", s.size(), p.size()); });
    server.getVideoParser().setFrameCallback([](const uint8_t*, size_t n, uint64_t pts, bool key) { printf("EVT frame size=%zu key=%d pts=%llu now=%lld\n", n, key ? 1 : 0, (unsigned long long)pts, nowNs()); });
    server.setMetadataCallback([](const std::string& t, const std::string& a, const std::string& al) { printf("EVT metadata title=%s|artist=%s|album=%s\n", t.c_str(), a.c_str(), al.c_str()); });
    server.setCoverArtCallback([](const uint8_t*, size_t n) { printf("EVT coverart size=%zu\n", n); });
    server.setProgressCallback([](int64_t pos, int64_t dur) { printf("EVT progress pos=%lld dur=%lld\n", (long long)pos, (long long)dur); });
    server.setPinStartCallback([]() { printf("EVT pinstart\n"); });
    server.setPinSetupCallback([](int step, const std::string& user, const std::vector<std::vector<uint8_t>>& in,
                                  std::vector<std::vector<uint8_t>>& out) -> bool {
        // Stub of the Java SRP side: fixed values let the driver check routing and plist encoding.
        printf("EVT pinsetup step=%d user=%s\n", step, user.c_str());
        if (step == 1) { out = {std::vector<uint8_t>(256, 0xBB), std::vector<uint8_t>(16, 0x5A)}; return true; }
        if (step == 2) {
            if (in.size() < 2 || in[1] != std::vector<uint8_t>(20, 0x01)) return false;
            out = {std::vector<uint8_t>(20, 0x02)};
            return true;
        }
        if (step == 3) {
            // The stub takes "epk" as the client's Ed25519 key in the clear, so the driver can
            // register a key it holds the private half of.
            if (in.size() != 3 || in[0].size() != 32 || in[2].size() != 32) return false;
            out = {std::vector<uint8_t>(32, 0x03), std::vector<uint8_t>(16, 0x04), in[0]};
            return true;
        }
        return false;
    });
    VideoCastHandler::Callbacks cast;
    cast.play = [](const std::string& url, double start) { printf("EVT castplay url=%s start=%.2f\n", url.c_str(), start); };
    cast.seek = [](double pos) { printf("EVT castseek %.2f\n", pos); };
    cast.rate = [](float rate) { printf("EVT castrate %.2f\n", rate); };
    cast.stop = []() { printf("EVT caststop\n"); };
    server.setVideoCastCallbacks(cast);
    server.setTxtRecords({5, 'a', '=', 'b', 'c', 'd'}, {3, 'x', '=', '1'});
    if (!server.start(17000, 17100)) { printf("START_FAILED\n"); return 1; }
    printf("READY\n");
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line == "PIN ON" || line == "PIN OFF") {
            server.setPinRequired(line == "PIN ON");
            printf("ACK\n");
            continue;
        }
        if (line.rfind("CASTSTATE ", 0) == 0) {
            VideoCastHandler::PlaybackInfo info;
            int state = 0, ready = 0;
            float rate = 0;
            std::sscanf(line.c_str() + 10, "%d %lf %lf %lf %f %d", &state, &info.duration, &info.position,
                        &info.buffered, &rate, &ready);
            info.state = static_cast<VideoCastHandler::PlayerState>(state);
            info.rate = rate;
            info.readyToPlay = ready != 0;
            server.updateVideoCastPlayback(info);
            printf("ACK\n");
            continue;
        }
        if (line == "STOP") {
            auto t0 = std::chrono::steady_clock::now();
            server.stop();
            auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
            printf("STOPPED %lld\n", (long long)ms);
            break;
        }
    }
    return 0;
}
