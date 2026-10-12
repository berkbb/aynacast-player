#ifndef VIDEO_CAST_H
#define VIDEO_CAST_H

#include "airplay_protocol.h"
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace airplay {

// AirPlay video casting (an app's AirPlay button handing over a media URL), following UxPlay's
// HLS support: HTTP/1.1 requests on the AirPlay port (/play, /scrub, /rate, /stop, /playback-info,
// /reverse, /action, ...). YouTube's "mlhls://" playlists are fetched from the sender through the
// reverse connection (FCUP requests) and served to the local player as http://localhost:<port>/...
class VideoCastHandler {
public:
    enum class PlayerState : int { STOPPED = 0, LOADING = 1, PLAYING = 2, PAUSED = 3 };

    struct PlaybackInfo {
        PlayerState state{PlayerState::STOPPED};
        double duration{0.0};
        double position{0.0};
        double buffered{0.0};
        float rate{0.0f};
        bool readyToPlay{false};
    };

    struct Callbacks {
        std::function<void(const std::string& url, double startSeconds)> play;
        std::function<void(double positionSeconds)> seek;
        std::function<void(float rate)> rate;
        std::function<void()> stop;
        // Closes every connection of the casting session (the "stopped" signal UxPlay relies on).
        std::function<void()> closeConnections;
    };

    void setCallbacks(Callbacks callbacks);
    // e.g. "http://localhost:7000" — where the local player fetches rewritten playlists.
    void setLocalPrefix(const std::string& prefix);
    void setMacAddress(const std::string& mac);

    // Handles one HTTP/1.1 request. Sets resp.becomeReverse for POST /reverse.
    void handle(const RtspRequest& req, RtspResponse& resp);

    // The connection switched to PTTH (events from the receiver to the sender).
    void attachReverseConnection(int fd, const std::string& sessionId);
    void detachReverseConnection(int fd);

    // Player state from the Java side; a local stop (end of media, Back) notifies the sender.
    void updatePlayback(const PlaybackInfo& info);

    // All connections of the casting session are gone.
    void onSessionClosed();

    // Expands YouTube "#YT-EXT-CONDENSED-URL" media playlists. Returns false when the tag is malformed.
    static bool expandCondensedPlaylist(const std::string& playlist, std::string& out);
    // Every "<prefix>...m3u8" URI in a master playlist, in text order (UxPlay create_media_uri_table).
    static std::vector<std::string> findMediaUris(const std::string& master, const std::string& prefix);

private:
    void handlePlay(const RtspRequest& req, RtspResponse& resp, const std::string& sessionId);
    void handleAction(const RtspRequest& req, RtspResponse& resp, const std::string& sessionId);
    void servePlaylist(const std::string& path, RtspResponse& resp);
    std::string playbackInfoPlist();
    bool sendFcupRequestLocked(const std::string& url, size_t index);
    void requestMediaPlaylistsLocked();
    bool sendEventLocked(const std::string& xmlPlist);
    void sendStoppedEventLocked();
    void sendStateEventLocked(const char* state);

    std::mutex mutex_;
    Callbacks callbacks_;
    std::string localPrefix_{"http://localhost:7000"};
    std::string macAddress_;

    int reverseFd_{-1};
    std::string reverseSessionId_;
    std::string sessionId_;          // X-Apple-Session-ID of the current /play

    // YouTube FCUP state
    std::string uriPrefix_;          // e.g. "mlhls://localhost"
    std::string masterPlaylist_;     // rewritten to localPrefix_
    std::vector<std::string> mediaUris_;
    std::vector<std::string> mediaPlaylists_;
    size_t nextMediaIndex_{0};
    int64_t fcupRequestId_{0};       // never reset, so a late reply for an earlier video never matches
    struct FcupRequest {
        size_t index;                // into mediaUris_, or kMasterFcupIndex
        std::string url;
    };
    static constexpr size_t kMasterFcupIndex = SIZE_MAX;
    std::map<int64_t, FcupRequest> outstandingFcup_; // requests in flight, by request ID
    std::string pendingFcupUrl_;     // to send once the reverse connection exists
    size_t pendingFcupIndex_{kMasterFcupIndex};
    double startPosition_{0.0};
    bool hasPendingRate_{false};     // /rate received while the playlists were still being fetched
    float pendingRate_{0.0f};
    bool fetchingPlaylists_{false};

    PlaybackInfo playback_;
    // The sender already knows playback stopped (it asked, it is gone, or we told it), so a
    // later STOPPED from the player must not send another "stopped" event.
    bool stopHandled_{false};
};

} // namespace airplay

#endif // VIDEO_CAST_H
