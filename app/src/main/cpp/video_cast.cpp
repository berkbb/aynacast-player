#include "video_cast.h"
#include "bplist_reader.h"
#include <android/log.h>
#include <sys/socket.h>
#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <sstream>

#define TAG "AirPlayVideo"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

namespace airplay {

namespace {

const char* const kPlistHeader =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
    "<plist version=\"1.0\">\n";
const char* const kPlistFooter = "</plist>\n";
// Constants UxPlay took from apsdk-public for FCUP requests ("seem to be arbitrary").
const char* const kFcupUserAgent = "AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us";
const int kFcupClientRef = 40030004;
const char* const kMasterPlaylistName = "/master.m3u8";

std::string xmlEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (char c : in) {
        switch (c) {
            case '&': out += "&amp;"; break;
            case '<': out += "&lt;"; break;
            case '>': out += "&gt;"; break;
            case '"': out += "&quot;"; break;
            default: out.push_back(c); break;
        }
    }
    return out;
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return s;
}

std::string header(const RtspRequest& req, const std::string& name) {
    std::string wanted = lower(name);
    for (const auto& kv : req.headers) {
        if (lower(kv.first) == wanted) return kv.second;
    }
    return std::string();
}

std::string pathOf(const std::string& uri) {
    std::string path = uri;
    size_t scheme = path.find("://");
    if (scheme != std::string::npos) {
        size_t slash = path.find('/', scheme + 3);
        path = slash == std::string::npos ? "/" : path.substr(slash);
    }
    size_t query = path.find('?');
    return query == std::string::npos ? path : path.substr(0, query);
}

std::string queryOf(const std::string& uri) {
    size_t query = uri.find('?');
    return query == std::string::npos ? std::string() : uri.substr(query + 1);
}

// "/scrub?position=12.5" -> 12.5 (UxPlay takes the text after '=').
double queryNumber(const std::string& uri) {
    std::string query = queryOf(uri);
    size_t eq = query.find('=');
    return std::strtod(eq == std::string::npos ? query.c_str() : query.c_str() + eq + 1, nullptr);
}

std::string real(double value) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%.6f", value);
    return buf;
}

bool sendAll(int fd, const std::string& data) {
    size_t sent = 0;
    while (sent < data.size()) {
        ssize_t n = send(fd, data.data() + sent, data.size() - sent, MSG_NOSIGNAL);
        if (n > 0) {
            sent += static_cast<size_t>(n);
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

std::string replaceAll(const std::string& text, const std::string& from, const std::string& to) {
    if (from.empty()) return text;
    std::string out;
    size_t start = 0;
    size_t pos;
    while ((pos = text.find(from, start)) != std::string::npos) {
        out.append(text, start, pos - start);
        out += to;
        start = pos + from.size();
    }
    out.append(text, start, std::string::npos);
    return out;
}

bool endsWith(const std::string& s, const std::string& suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

std::string attribute(const std::string& text, size_t from, const std::string& name) {
    size_t pos = text.find(name + "=\"", from);
    if (pos == std::string::npos) return std::string();
    pos += name.size() + 2;
    size_t end = text.find('"', pos);
    return end == std::string::npos ? std::string() : text.substr(pos, end - pos);
}

std::string httpDate() {
    char buf[64];
    std::time_t now = std::time(nullptr);
    std::tm tm{};
    gmtime_r(&now, &tm);
    std::strftime(buf, sizeof(buf), "%a, %d %b %Y %H:%M:%S GMT", &tm);
    return buf;
}

} // namespace

void VideoCastHandler::setCallbacks(Callbacks callbacks) {
    std::lock_guard<std::mutex> lock(mutex_);
    callbacks_ = std::move(callbacks);
}

void VideoCastHandler::setLocalPrefix(const std::string& prefix) {
    std::lock_guard<std::mutex> lock(mutex_);
    localPrefix_ = prefix;
}

void VideoCastHandler::setMacAddress(const std::string& mac) {
    std::lock_guard<std::mutex> lock(mutex_);
    macAddress_ = mac;
}

std::vector<std::string> VideoCastHandler::findMediaUris(const std::string& master, const std::string& prefix) {
    std::vector<std::string> uris;
    if (prefix.empty()) return uris;
    size_t pos = master.find(prefix);
    while (pos != std::string::npos) {
        size_t end = master.find("m3u8", pos);
        if (end == std::string::npos) break;
        end += 4; // "m3u8" inclusive
        uris.push_back(master.substr(pos, end - pos));
        pos = master.find(prefix, end);
    }
    return uris;
}

bool VideoCastHandler::expandCondensedPlaylist(const std::string& playlist, std::string& out) {
    size_t firstChunk = playlist.find("#EXTINF");
    size_t tag = playlist.find("#YT-EXT-CONDENSED-URL");
    if (tag == std::string::npos || firstChunk == std::string::npos) {
        out = playlist;
        return true;
    }
    if (tag > firstChunk) return false;

    std::string baseUri = attribute(playlist, tag, "BASE-URI");
    std::string paramList = attribute(playlist, tag, "PARAMS");
    std::string prefix = attribute(playlist, tag, "PREFIX");
    if (baseUri.empty() || paramList.empty() || prefix.empty()) return false;

    std::vector<std::string> params;
    std::stringstream paramStream(paramList);
    for (std::string param; std::getline(paramStream, param, ',');) params.push_back(param);
    if (params.empty()) return false;

    // Header (everything before the first chunk, tag included) is kept verbatim.
    out.assign(playlist, 0, firstChunk);
    size_t lineStart = firstChunk;
    while (lineStart < playlist.size()) {
        size_t lineEnd = playlist.find('\n', lineStart);
        size_t next = lineEnd == std::string::npos ? playlist.size() : lineEnd + 1;
        std::string line = playlist.substr(lineStart, next - lineStart);
        std::string ending;
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) {
            ending.insert(ending.begin(), line.back());
            line.pop_back();
        }

        if (!line.empty() && line[0] != '#' && line.compare(0, prefix.size(), prefix) == 0) {
            // "s/v1/v2/v3" with PARAMS="p1,p2,p3" -> BASE-URI/p1/v1/p2/v2/p3/v3
            std::string rest = line.substr(prefix.size());
            std::string expanded = baseUri + "/";
            for (size_t i = 0; i < params.size(); ++i) {
                expanded += params[i] + "/";
                size_t slash = rest.find('/');
                if (i + 1 == params.size() || slash == std::string::npos) {
                    expanded += rest;
                    rest.clear();
                    break;
                }
                expanded += rest.substr(0, slash + 1);
                rest = rest.substr(slash + 1);
            }
            out += expanded;
        } else {
            out += line;
        }
        out += ending;
        lineStart = next;
    }
    return true;
}

void VideoCastHandler::handle(const RtspRequest& req, RtspResponse& resp) {
    resp.protocol = "HTTP/1.1";
    std::string sessionId = header(req, "X-Apple-Session-ID");
    std::string path = pathOf(req.uri);
    const std::string& method = req.method;
    bool isPlaylistRequest = method == "GET" && sessionId.empty() && endsWith(path, ".m3u8");

    if (isPlaylistRequest) {
        // The local player fetching the playlists we rewrote (UxPlay http_handler_hls).
        servePlaylist(path, resp);
        return;
    }
    resp.headers["Server"] = "AirTunes/220.68";

    if (method == "GET" && path == "/server-info") {
        std::string mac;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            mac = macAddress_;
        }
        std::ostringstream ss;
        ss << kPlistHeader << "<dict>\n"
           << "  <key>deviceid</key><string>" << xmlEscape(mac) << "</string>\n"
           << "  <key>features</key><integer>" << 0x27F << "</integer>\n"
           << "  <key>macAddress</key><string>" << xmlEscape(mac) << "</string>\n"
           << "  <key>model</key><string>AppleTV3,2</string>\n"
           << "  <key>osBuildVersion</key><string>12B435</string>\n"
           << "  <key>protovers</key><string>1.0</string>\n"
           << "  <key>srcvers</key><string>220.68</string>\n"
           << "  <key>vv</key><integer>2</integer>\n"
           << "</dict>\n" << kPlistFooter;
        std::string body = ss.str();
        resp.headers["Content-Type"] = "text/x-apple-plist+xml";
        resp.body.assign(body.begin(), body.end());
    } else if (method == "POST" && path == "/reverse") {
        resp.statusCode = 101;
        resp.statusMessage = "Switching Protocols";
        resp.headers["Connection"] = "Upgrade";
        resp.headers["Upgrade"] = "PTTH/1.0";
        resp.becomeReverse = true;
    } else if (method == "POST" && path == "/play") {
        handlePlay(req, resp, sessionId);
    } else if (method == "POST" && path == "/scrub") {
        double position = queryNumber(req.uri);
        Callbacks cb;
        bool deferred;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cb = callbacks_;
            // Still fetching YouTube playlists: the player does not exist yet, so start there.
            deferred = fetchingPlaylists_;
            if (deferred) startPosition_ = position < 0.0 ? 0.0 : position;
        }
        LOGI("Sender seeks to %.2f s", position);
        if (!deferred && cb.seek) cb.seek(position);
    } else if (method == "GET" && path == "/scrub") {
        PlaybackInfo info;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            info = playback_;
        }
        std::string body = "duration: " + real(info.duration) + "\nposition: " + real(info.position) + "\n";
        resp.headers["Content-Type"] = "text/parameters";
        resp.body.assign(body.begin(), body.end());
    } else if (method == "POST" && path == "/rate") {
        double rate = queryNumber(req.uri);
        Callbacks cb;
        bool deferred;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cb = callbacks_;
            deferred = fetchingPlaylists_;
            if (deferred && rate >= 0.0) {
                hasPendingRate_ = true;
                pendingRate_ = static_cast<float>(rate);
            }
        }
        if (!deferred && cb.rate && rate >= 0.0) cb.rate(static_cast<float>(rate));
    } else if (method == "POST" && path == "/stop") {
        Callbacks cb;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            cb = callbacks_;
            stopHandled_ = true;
            fetchingPlaylists_ = false;
            outstandingFcupId_ = 0;
            pendingFcupUrl_.clear();
        }
        LOGI("Sender stopped video casting");
        if (cb.stop) cb.stop();
    } else if (method == "GET" && path == "/playback-info") {
        std::string body = playbackInfoPlist();
        if (!body.empty()) {
            resp.headers["Content-Type"] = "text/x-apple-plist+xml";
            resp.body.assign(body.begin(), body.end());
        }
    } else if (method == "PUT" && path == "/setProperty") {
        static const char* const kKnown[] = {"actionAtItemEnd", "selectedMediaArray", "reverseEndTime",
                                             "forwardEndTime", "interstitialEvents", "textMarkupArray",
                                             "isInterestedInDateRange"};
        std::string name = queryOf(req.uri);
        if (std::any_of(std::begin(kKnown), std::end(kKnown), [&name](const char* k) { return name == k; })) {
            std::string body = std::string(kPlistHeader) +
                               "<dict>\n  <key>errorCode</key><integer>0</integer>\n</dict>\n" + kPlistFooter;
            resp.headers["Content-Type"] = "text/x-apple-plist+xml";
            resp.body.assign(body.begin(), body.end());
        }
    } else if (method == "POST" && path == "/action") {
        handleAction(req, resp, sessionId);
    } else if (method == "POST" && path == "/fp-setup2") {
        // FairPlay-protected video is not supported (UxPlay answers the same way).
        resp.statusCode = 421;
        resp.statusMessage = "Misdirected Request";
    }
    // Anything else (POST /getProperty, ...) gets an empty 200 like UxPlay.
}

void VideoCastHandler::handlePlay(const RtspRequest& req, RtspResponse& resp, const std::string& sessionId) {
    auto reject = [&resp]() {
        resp.statusCode = 400;
        resp.statusMessage = "Bad Request";
        resp.closeConnection = true;
    };
    if (sessionId.empty()) return reject();

    std::string location;
    double start = 0.0;
    BplistValue body;
    if (parseBinaryPlist(req.body, body) && body.isDict()) {
        if (const BplistValue* v = body.find("Content-Location"); v && v->type == BplistValue::Type::String) {
            location = v->strVal;
        }
        if (const BplistValue* v = body.find("Start-Position-Seconds")) {
            if (v->type == BplistValue::Type::Real) start = v->realVal;
            else if (v->type == BplistValue::Type::Int) start = static_cast<double>(v->intVal);
        }
    } else {
        // Legacy text/parameters form: "Content-Location: URL\nStart-Position: <fraction>"
        std::string text(req.body.begin(), req.body.end());
        size_t pos = text.find("Content-Location:");
        if (pos != std::string::npos) {
            size_t begin = text.find_first_not_of(' ', pos + 17);
            size_t end = text.find_first_of("\r\n", begin);
            location = text.substr(begin, end == std::string::npos ? std::string::npos : end - begin);
        }
    }
    if (location.empty()) return reject();

    Callbacks cb;
    bool playNow = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        cb = callbacks_;
        sessionId_ = sessionId;
        stopHandled_ = false;
        startPosition_ = start < 0.0 ? 0.0 : start;
        hasPendingRate_ = false;
        outstandingFcupId_ = 0;
        outstandingFcupUrl_.clear();
        pendingFcupUrl_.clear();
        if (location.rfind("http://", 0) == 0 || location.rfind("https://", 0) == 0) {
            fetchingPlaylists_ = false;
            playNow = true;
        } else {
            size_t master = location.find(kMasterPlaylistName);
            if (master == std::string::npos) {
                LOGW("Unsupported video location scheme: %s", location.c_str());
                reject();
                return;
            }
            // YouTube: fetch the master playlist from the sender through the reverse connection.
            uriPrefix_ = location.substr(0, master);
            masterPlaylist_.clear();
            mediaUris_.clear();
            mediaPlaylists_.clear();
            nextMediaIndex_ = 0;
            fetchingPlaylists_ = true;
            if (!sendFcupRequestLocked(location)) {
                // /play can be handled before the sender's /reverse connection is attached.
                LOGI("Fetching %s once the reverse event connection is up", location.c_str());
            }
        }
    }
    LOGI("Video casting requested: %s (start %.2f s)", location.c_str(), start);
    if (playNow && cb.play) cb.play(location, start);
}

void VideoCastHandler::handleAction(const RtspRequest& req, RtspResponse& resp, const std::string& sessionId) {
    BplistValue body;
    if (!parseBinaryPlist(req.body, body) || !body.isDict()) return;
    const BplistValue* type = body.find("type");
    if (!type || type->strVal != "unhandledURLResponse") return; // playlistRemove / playlistInsert: 200

    const BplistValue* params = body.find("params");
    const BplistValue* data = params ? params->find("FCUP_Response_Data") : nullptr;
    const BplistValue* url = params ? params->find("FCUP_Response_URL") : nullptr;
    const BplistValue* requestId = params ? params->find("FCUP_Response_RequestID") : nullptr;

    // FCUP_Response_StatusCode is not an HTTP status (real senders send 0 on success), so the
    // reply is judged by its content, as UxPlay does.
    std::string playlist;
    bool valid = data && data->type == BplistValue::Type::Data;
    if (valid) {
        playlist.assign(data->dataVal.begin(), data->dataVal.end());
        valid = playlist.rfind("#EXTM3U", 0) == 0;
        if (valid && playlist.back() != '\n') playlist.push_back('\n');
    }

    Callbacks cb;
    std::string playUrl;
    double start = 0.0;
    bool hasRate = false;
    float rate = 0.0f;
    bool abort = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        // Only the reply to the request in flight counts. A late reply for an earlier video
        // (the user picked another one mid-fetch) must not be stored as this video's playlist.
        bool matches = outstandingFcupId_ != 0 &&
            (requestId && requestId->type == BplistValue::Type::Int
                 ? requestId->intVal == outstandingFcupId_
                 : url && url->type == BplistValue::Type::String && url->strVal == outstandingFcupUrl_);
        if (sessionId != sessionId_ || !fetchingPlaylists_ || !matches) {
            LOGW("Ignoring a playlist reply that belongs to no current request");
            resp.statusCode = 400;
            resp.statusMessage = "Bad Request";
            return;
        }
        outstandingFcupId_ = 0;
        cb = callbacks_;
        bool awaitingMaster = masterPlaylist_.empty();

        if (!valid) {
            if (awaitingMaster) {
                // Without the master playlist there is nothing to play: tell the sender instead of
                // leaving it waiting forever.
                LOGW("The sender could not provide the master playlist; stopping");
                fetchingPlaylists_ = false;
                stopHandled_ = true;
                sendStoppedEventLocked();
                abort = true;
            } else {
                // One variant missing: the player skips it (404) and uses the others.
                LOGW("The sender could not provide media playlist %zu", nextMediaIndex_);
            }
        } else if (awaitingMaster) {
            mediaUris_ = findMediaUris(playlist, uriPrefix_);
            mediaPlaylists_.assign(mediaUris_.size(), std::string());
            masterPlaylist_ = replaceAll(playlist, uriPrefix_, localPrefix_);
            nextMediaIndex_ = 0;
            LOGI("Master playlist received: %zu media playlists to fetch", mediaUris_.size());
        } else if (nextMediaIndex_ > 0 && nextMediaIndex_ <= mediaPlaylists_.size()) {
            mediaPlaylists_[nextMediaIndex_ - 1] = playlist;
        }

        if (!abort) {
            if (nextMediaIndex_ < mediaUris_.size()) {
                sendFcupRequestLocked(mediaUris_[nextMediaIndex_++]);
            } else {
                fetchingPlaylists_ = false;
                playUrl = localPrefix_ + kMasterPlaylistName;
                start = startPosition_;
                hasRate = hasPendingRate_;
                rate = pendingRate_;
                hasPendingRate_ = false;
            }
        }
    }
    if (abort) {
        if (cb.closeConnections) cb.closeConnections();
        return;
    }
    if (!playUrl.empty()) {
        LOGI("All playlists fetched; starting playback of %s", playUrl.c_str());
        if (cb.play) cb.play(playUrl, start);
        if (hasRate && cb.rate) cb.rate(rate);
    }
}

void VideoCastHandler::servePlaylist(const std::string& path, RtspResponse& resp) {
    std::string body;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (path == kMasterPlaylistName) {
            found = !masterPlaylist_.empty();
            body = masterPlaylist_;
        } else {
            for (size_t i = 0; i < mediaUris_.size(); ++i) {
                if (mediaUris_[i].find(path) != std::string::npos && !mediaPlaylists_[i].empty()) {
                    found = expandCondensedPlaylist(mediaPlaylists_[i], body);
                    break;
                }
            }
        }
    }
    if (!found) {
        resp.statusCode = 404;
        resp.statusMessage = "Not Found";
        return;
    }
    resp.headers["Access-Control-Allow-Headers"] = "Content-type";
    resp.headers["Access-Control-Allow-Origin"] = "*";
    resp.headers["Date"] = httpDate();
    resp.headers["Content-Type"] = "application/x-mpegURL; charset=utf-8";
    resp.body.assign(body.begin(), body.end());
}

std::string VideoCastHandler::playbackInfoPlist() {
    PlaybackInfo info;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        info = playback_;
    }
    // Until the duration is known UxPlay answers with an empty 200.
    if (!info.readyToPlay || info.duration <= 0.0) return std::string();
    double loaded = std::max(0.0, info.buffered - info.position);
    std::ostringstream ss;
    ss << kPlistHeader << "<dict>\n"
       << "  <key>duration</key><real>" << real(info.duration) << "</real>\n"
       << "  <key>loadedTimeRanges</key>\n  <array>\n    <dict>\n"
       << "      <key>duration</key><real>" << real(loaded) << "</real>\n"
       << "      <key>start</key><real>" << real(info.position) << "</real>\n"
       << "    </dict>\n  </array>\n"
       << "  <key>playbackBufferEmpty</key><integer>" << (loaded <= 0.0 ? 1 : 0) << "</integer>\n"
       << "  <key>playbackBufferFull</key><integer>0</integer>\n"
       << "  <key>playbackLikelyToKeepUp</key><integer>1</integer>\n"
       << "  <key>position</key><real>" << real(info.position) << "</real>\n"
       << "  <key>rate</key><real>" << real(info.rate) << "</real>\n"
       << "  <key>readyToPlay</key><integer>1</integer>\n"
       << "  <key>seekableTimeRanges</key>\n  <array>\n    <dict>\n"
       << "      <key>duration</key><real>" << real(info.duration) << "</real>\n"
       << "      <key>start</key><real>" << real(0.0) << "</real>\n"
       << "    </dict>\n  </array>\n"
       << "</dict>\n" << kPlistFooter;
    return ss.str();
}

bool VideoCastHandler::sendFcupRequestLocked(const std::string& url) {
    if (reverseFd_ < 0) {
        pendingFcupUrl_ = url;
        return false;
    }
    int64_t requestId = ++fcupRequestId_;
    std::ostringstream ss;
    ss << kPlistHeader << "<dict>\n"
       << "  <key>request</key>\n  <dict>\n"
       << "    <key>FCUP_Response_ClientInfo</key><integer>1</integer>\n"
       << "    <key>FCUP_Response_ClientRef</key><integer>" << kFcupClientRef << "</integer>\n"
       << "    <key>FCUP_Response_Headers</key>\n    <dict>\n"
       << "      <key>User-Agent</key><string>" << xmlEscape(kFcupUserAgent) << "</string>\n"
       << "      <key>X-Playback-Session-Id</key><string>" << xmlEscape(sessionId_) << "</string>\n"
       << "    </dict>\n"
       << "    <key>FCUP_Response_RequestID</key><integer>" << requestId << "</integer>\n"
       << "    <key>FCUP_Response_URL</key><string>" << xmlEscape(url) << "</string>\n"
       << "    <key>sessionID</key><integer>1</integer>\n"
       << "  </dict>\n"
       << "  <key>sessionID</key><integer>1</integer>\n"
       << "  <key>type</key><string>unhandledURLRequest</string>\n"
       << "</dict>\n" << kPlistFooter;
    LOGI("FCUP request #%lld for %s", static_cast<long long>(requestId), url.c_str());
    if (!sendEventLocked(ss.str())) {
        pendingFcupUrl_ = url; // retried if the sender opens a new reverse connection
        return false;
    }
    outstandingFcupId_ = requestId;
    outstandingFcupUrl_ = url;
    pendingFcupUrl_.clear();
    return true;
}

void VideoCastHandler::sendStoppedEventLocked() {
    sendEventLocked(std::string(kPlistHeader) +
                    "<dict>\n  <key>category</key><string>video</string>\n"
                    "  <key>sessionID</key><integer>1</integer>\n"
                    "  <key>state</key><string>stopped</string>\n</dict>\n" + kPlistFooter);
}

bool VideoCastHandler::sendEventLocked(const std::string& xmlPlist) {
    if (reverseFd_ < 0) return false;
    std::string sid = sessionId_.empty() ? reverseSessionId_ : sessionId_;
    std::string message = "POST /event HTTP/1.1\r\n"
                          "X-Apple-Session-ID: " + sid + "\r\n"
                          "Content-Type: text/x-apple-plist+xml\r\n"
                          "Content-Length: " + std::to_string(xmlPlist.size()) + "\r\n\r\n" + xmlPlist;
    return sendAll(reverseFd_, message);
}

void VideoCastHandler::attachReverseConnection(int fd, const std::string& sessionId) {
    std::lock_guard<std::mutex> lock(mutex_);
    reverseFd_ = fd;
    reverseSessionId_ = sessionId;
    LOGI("Reverse event connection established");
    if (fetchingPlaylists_ && outstandingFcupId_ == 0 && !pendingFcupUrl_.empty()) {
        std::string url = pendingFcupUrl_;
        sendFcupRequestLocked(url);
    }
}

void VideoCastHandler::detachReverseConnection(int fd) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (reverseFd_ == fd) reverseFd_ = -1;
}

void VideoCastHandler::updatePlayback(const PlaybackInfo& info) {
    Callbacks cb;
    bool endedLocally = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        PlayerState previous = playback_.state;
        playback_ = info;
        if (previous != PlayerState::STOPPED && info.state == PlayerState::STOPPED) {
            endedLocally = !stopHandled_;
            stopHandled_ = true; // cleared only by the next /play
            if (endedLocally) {
                // End of media or Back on the TV: tell the sender (AirPlay spec event), then close.
                sendStoppedEventLocked();
            }
            cb = callbacks_;
        }
    }
    if (endedLocally) {
        LOGI("Video casting ended on the receiver; closing the casting session");
        if (cb.closeConnections) cb.closeConnections();
    }
}

void VideoCastHandler::onSessionClosed() {
    Callbacks cb;
    bool wasActive;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        wasActive = playback_.state != PlayerState::STOPPED || fetchingPlaylists_;
        fetchingPlaylists_ = false;
        outstandingFcupId_ = 0;
        pendingFcupUrl_.clear();
        stopHandled_ = true; // The sender is gone: no event to send back.
        cb = callbacks_;
    }
    if (wasActive && cb.stop) {
        LOGI("Casting connections closed; stopping playback");
        cb.stop();
    }
}

} // namespace airplay
