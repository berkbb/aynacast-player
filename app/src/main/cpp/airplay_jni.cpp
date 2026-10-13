#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <vector>
#include "raop_rtsp_server.h"

#define TAG "AirPlayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jobject g_listenerObj = nullptr;
static jmethodID g_midOnClientConnected = nullptr;
static jmethodID g_midOnStreamStarted = nullptr;
static jmethodID g_midOnStreamStopped = nullptr;
static jmethodID g_midOnVideoStopped = nullptr;
static jmethodID g_midOnSpsPpsReady = nullptr;
static jmethodID g_midOnVideoFrame = nullptr;
static jmethodID g_midOnAudioPacket = nullptr;
static jmethodID g_midOnAudioConfig = nullptr;
static jmethodID g_midOnVolumeChanged = nullptr;
static jmethodID g_midOnNowPlayingMetadata = nullptr;
static jmethodID g_midOnCoverArt = nullptr;
static jmethodID g_midOnPlaybackProgress = nullptr;
static jmethodID g_midOnPinStart = nullptr;
static jmethodID g_midOnPinSetup = nullptr;
static jmethodID g_midOnRsa = nullptr;
static jmethodID g_midOnClassicAuth = nullptr;
static jmethodID g_midOnVideoCastPlay = nullptr;
static jmethodID g_midOnVideoCastSeek = nullptr;
static jmethodID g_midOnVideoCastRate = nullptr;
static jmethodID g_midOnVideoCastStop = nullptr;

// Serializes server start/stop issued from Java.
static std::mutex g_serverMutex;
static std::unique_ptr<airplay::RaopRtspServer> g_server;
// Persistent Ed25519 identity seed, applied to every server instance (see setIdentitySeed).
static std::vector<uint8_t> g_identitySeed;

namespace {

// Native server threads attach themselves to the VM on their first callback. ART aborts when an
// attached thread exits without detaching, so a thread-local guard detaches it on thread exit.
struct JniThreadAttachment {
    bool attached = false;
    ~JniThreadAttachment() {
        if (attached && g_vm) {
            g_vm->DetachCurrentThread();
        }
    }
};

thread_local JniThreadAttachment t_attachment;

JNIEnv* getJniEnv() {
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
        t_attachment.attached = true;
    } else if (res != JNI_OK) {
        return nullptr;
    }
    return env;
}

// A Java exception left pending on a native thread would abort the next JNI call, so log and clear it.
void clearPendingException(JNIEnv* env, const char* callbackName) {
    if (env->ExceptionCheck()) {
        LOGE("Java callback %s threw an exception", callbackName);
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

jmethodID findMethod(JNIEnv* env, jclass clazz, const char* name, const char* signature) {
    jmethodID mid = env->GetMethodID(clazz, name, signature);
    if (!mid) {
        env->ExceptionClear();
        LOGE("Listener method %s%s not found", name, signature);
    }
    return mid;
}

// NewStringUTF expects Modified UTF-8; senders' metadata is standard UTF-8 (emoji, CJK), so build
// the string through java.lang.String(byte[], "UTF-8") instead.
jstring newJavaString(JNIEnv* env, const std::string& utf8) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(utf8.size()));
    if (!bytes) {
        env->ExceptionClear();
        return env->NewStringUTF("");
    }
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(utf8.size()), reinterpret_cast<const jbyte*>(utf8.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(stringClass);
    if (!result || env->ExceptionCheck()) {
        env->ExceptionClear();
        return env->NewStringUTF("");
    }
    return result;
}

// Standard UTF-8 bytes of a Java string (GetStringUTFChars would return Modified UTF-8).
std::string toUtf8(JNIEnv* env, jstring value) {
    if (!value) return std::string();
    jclass stringClass = env->GetObjectClass(value);
    jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, getBytes, charset));
    std::string out;
    if (bytes && !env->ExceptionCheck()) {
        jsize len = env->GetArrayLength(bytes);
        out.resize(static_cast<size_t>(len));
        env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(&out[0]));
    }
    env->ExceptionClear();
    if (bytes) env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return out;
}

jbyteArray toByteArray(JNIEnv* env, const uint8_t* data, size_t size) {
    jbyteArray array = env->NewByteArray(static_cast<jsize>(size));
    if (!array) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
    return array;
}

void stopServerLocked(JNIEnv* env) {
    if (g_server) {
        // stop() joins every server thread, so no callback can be running once it returns.
        g_server->stop();
        g_server.reset();
    }
    if (g_listenerObj) {
        env->DeleteGlobalRef(g_listenerObj);
        g_listenerObj = nullptr;
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    LOGI("AirPlay JNI loaded.");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_initNativeServer(
        JNIEnv* env,
        jobject thiz,
        jint port,
        jstring jDeviceName,
        jstring jMacAddress,
        jobject listener);

extern "C" JNIEXPORT jboolean JNICALL
Java_com_airplay_tv_engine_AirPlayNativeBridge_initNativeServer(
        JNIEnv* env,
        jobject thiz,
        jint port,
        jstring jDeviceName,
        jstring jMacAddress,
        jobject listener) {
    return Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_initNativeServer(env, thiz, port, jDeviceName, jMacAddress, listener);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_initNativeServer(
        JNIEnv* env,
        jobject /*thiz*/,
        jint port,
        jstring jDeviceName,
        jstring jMacAddress,
        jobject listener) {
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (g_server && g_server->isRunning()) {
        return JNI_TRUE;
    }
    stopServerLocked(env);

    if (listener) {
        g_listenerObj = env->NewGlobalRef(listener);
        jclass clazz = env->GetObjectClass(listener);
        g_midOnClientConnected = findMethod(env, clazz, "onClientConnected", "(Ljava/lang/String;Ljava/lang/String;)V");
        g_midOnStreamStarted = findMethod(env, clazz, "onStreamStarted", "()V");
        g_midOnStreamStopped = findMethod(env, clazz, "onStreamStopped", "()V");
        g_midOnVideoStopped = findMethod(env, clazz, "onVideoStopped", "()V");
        g_midOnSpsPpsReady = findMethod(env, clazz, "onSpsPpsReady", "([B[B)V");
        g_midOnVideoFrame = findMethod(env, clazz, "onVideoFrame", "([BJZ)V");
        g_midOnAudioPacket = findMethod(env, clazz, "onAudioPacket", "([BJ)V");
        g_midOnAudioConfig = findMethod(env, clazz, "onAudioConfig", "(IIII)V");
        g_midOnVolumeChanged = findMethod(env, clazz, "onVolumeChanged", "(F)V");
        g_midOnNowPlayingMetadata = findMethod(env, clazz, "onNowPlayingMetadata",
                                               "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        g_midOnCoverArt = findMethod(env, clazz, "onCoverArt", "([B)V");
        g_midOnPlaybackProgress = findMethod(env, clazz, "onPlaybackProgress", "(JJ)V");
        g_midOnPinStart = findMethod(env, clazz, "onPinStart", "()V");
        g_midOnPinSetup = findMethod(env, clazz, "onPinSetup", "(ILjava/lang/String;[[B)[[B");
        g_midOnRsa = findMethod(env, clazz, "onRsa", "(I[B)[B");
        g_midOnClassicAuth = findMethod(env, clazz, "onClassicAuth",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z");
        g_midOnVideoCastPlay = findMethod(env, clazz, "onVideoCastPlay", "(Ljava/lang/String;D)V");
        g_midOnVideoCastSeek = findMethod(env, clazz, "onVideoCastSeek", "(D)V");
        g_midOnVideoCastRate = findMethod(env, clazz, "onVideoCastRate", "(F)V");
        g_midOnVideoCastStop = findMethod(env, clazz, "onVideoCastStop", "()V");
        env->DeleteLocalRef(clazz);
    }

    g_server = std::make_unique<airplay::RaopRtspServer>();
    if (!g_identitySeed.empty()) g_server->getCrypto().setIdentitySeed(g_identitySeed);

    if (jDeviceName && jMacAddress) {
        // setDeviceIdentity keeps its defaults for empty values.
        g_server->setDeviceIdentity(toUtf8(env, jDeviceName), toUtf8(env, jMacAddress));
    }

    g_server->setClientConnectedCallback([](const airplay::ClientInfo& info) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnClientConnected) {
            jstring jDev = newJavaString(e, info.deviceName); // may contain emoji
            jstring jIp = newJavaString(e, info.ipAddress);
            e->CallVoidMethod(g_listenerObj, g_midOnClientConnected, jDev, jIp);
            clearPendingException(e, "onClientConnected");
            e->DeleteLocalRef(jDev);
            e->DeleteLocalRef(jIp);
        }
    });

    g_server->setStreamStartedCallback([]() {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnStreamStarted) {
            e->CallVoidMethod(g_listenerObj, g_midOnStreamStarted);
            clearPendingException(e, "onStreamStarted");
        }
    });

    g_server->setStreamStoppedCallback([]() {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnStreamStopped) {
            e->CallVoidMethod(g_listenerObj, g_midOnStreamStopped);
            clearPendingException(e, "onStreamStopped");
        }
    });

    g_server->setVideoStoppedCallback([]() {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnVideoStopped) {
            e->CallVoidMethod(g_listenerObj, g_midOnVideoStopped);
            clearPendingException(e, "onVideoStopped");
        }
    });

    g_server->getVideoParser().setSpsPpsCallback([](const std::vector<uint8_t>& sps, const std::vector<uint8_t>& pps) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnSpsPpsReady) {
            jbyteArray jSps = toByteArray(e, sps.data(), sps.size());
            jbyteArray jPps = toByteArray(e, pps.data(), pps.size());
            if (jSps && jPps) {
                e->CallVoidMethod(g_listenerObj, g_midOnSpsPpsReady, jSps, jPps);
                clearPendingException(e, "onSpsPpsReady");
            }
            if (jSps) e->DeleteLocalRef(jSps);
            if (jPps) e->DeleteLocalRef(jPps);
        }
    });

    g_server->getVideoParser().setFrameCallback([](const uint8_t* data, size_t size, uint64_t pts, bool isKeyFrame) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnVideoFrame) {
            jbyteArray jFrame = toByteArray(e, data, size);
            if (!jFrame) return;
            e->CallVoidMethod(g_listenerObj, g_midOnVideoFrame, jFrame, static_cast<jlong>(pts), static_cast<jboolean>(isKeyFrame));
            clearPendingException(e, "onVideoFrame");
            e->DeleteLocalRef(jFrame);
        }
    });

    g_server->setAudioPacketCallback([](const uint8_t* data, size_t size, uint64_t pts) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnAudioPacket) {
            jbyteArray jAudio = toByteArray(e, data, size);
            if (!jAudio) return;
            e->CallVoidMethod(g_listenerObj, g_midOnAudioPacket, jAudio, static_cast<jlong>(pts));
            clearPendingException(e, "onAudioPacket");
            e->DeleteLocalRef(jAudio);
        }
    });

    g_server->setAudioConfigCallback([](int codec, int sampleRate, int channels, int samplesPerFrame) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnAudioConfig) {
            e->CallVoidMethod(g_listenerObj, g_midOnAudioConfig,
                              static_cast<jint>(codec), static_cast<jint>(sampleRate),
                              static_cast<jint>(channels), static_cast<jint>(samplesPerFrame));
            clearPendingException(e, "onAudioConfig");
        }
    });

    g_server->setVolumeCallback([](float volumeDb) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnVolumeChanged) {
            e->CallVoidMethod(g_listenerObj, g_midOnVolumeChanged, static_cast<jfloat>(volumeDb));
            clearPendingException(e, "onVolumeChanged");
        }
    });

    g_server->setMetadataCallback([](const std::string& title, const std::string& artist, const std::string& album) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnNowPlayingMetadata) {
            jstring jTitle = newJavaString(e, title);
            jstring jArtist = newJavaString(e, artist);
            jstring jAlbum = newJavaString(e, album);
            e->CallVoidMethod(g_listenerObj, g_midOnNowPlayingMetadata, jTitle, jArtist, jAlbum);
            clearPendingException(e, "onNowPlayingMetadata");
            e->DeleteLocalRef(jTitle);
            e->DeleteLocalRef(jArtist);
            e->DeleteLocalRef(jAlbum);
        }
    });

    g_server->setCoverArtCallback([](const uint8_t* data, size_t size) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnCoverArt) {
            jbyteArray jImage = toByteArray(e, data, size);
            if (!jImage) return;
            e->CallVoidMethod(g_listenerObj, g_midOnCoverArt, jImage);
            clearPendingException(e, "onCoverArt");
            e->DeleteLocalRef(jImage);
        }
    });

    g_server->setProgressCallback([](int64_t positionMs, int64_t durationMs) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnPlaybackProgress) {
            e->CallVoidMethod(g_listenerObj, g_midOnPlaybackProgress,
                              static_cast<jlong>(positionMs), static_cast<jlong>(durationMs));
            clearPendingException(e, "onPlaybackProgress");
        }
    });

    g_server->setPinStartCallback([]() {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnPinStart) {
            e->CallVoidMethod(g_listenerObj, g_midOnPinStart);
            clearPendingException(e, "onPinStart");
        }
    });

    g_server->setRsaCallback([](int mode, const std::vector<uint8_t>& in, std::vector<uint8_t>& out) -> bool {
        JNIEnv* e = getJniEnv();
        if (!e || !g_listenerObj || !g_midOnRsa) return false;
        jbyteArray jIn = toByteArray(e, in.data(), in.size());
        auto jOut = static_cast<jbyteArray>(e->CallObjectMethod(g_listenerObj, g_midOnRsa, static_cast<jint>(mode), jIn));
        clearPendingException(e, "onRsa");
        e->DeleteLocalRef(jIn);
        if (!jOut) return false;
        jsize len = e->GetArrayLength(jOut);
        out.resize(static_cast<size_t>(len));
        e->GetByteArrayRegion(jOut, 0, len, reinterpret_cast<jbyte*>(out.data()));
        e->DeleteLocalRef(jOut);
        return true;
    });

    g_server->setClassicAuthCallback([](const airplay::RaopRtspServer::ClassicAuth& auth) -> bool {
        JNIEnv* e = getJniEnv();
        if (!e || !g_listenerObj || !g_midOnClassicAuth) return false;
        const std::string* fields[] = {&auth.senderId, &auth.username, &auth.realm, &auth.nonce,
                                       &auth.method, &auth.uri, &auth.response};
        jstring js[7];
        for (int i = 0; i < 7; ++i) js[i] = newJavaString(e, *fields[i]);
        jboolean ok = e->CallBooleanMethod(g_listenerObj, g_midOnClassicAuth, js[0], js[1], js[2], js[3], js[4], js[5], js[6]);
        clearPendingException(e, "onClassicAuth");
        for (jstring s : js) e->DeleteLocalRef(s);
        return ok == JNI_TRUE;
    });

    g_server->setPinSetupCallback([](int step, const std::string& user,
                                     const std::vector<std::vector<uint8_t>>& in,
                                     std::vector<std::vector<uint8_t>>& out) -> bool {
        JNIEnv* e = getJniEnv();
        if (!e || !g_listenerObj || !g_midOnPinSetup) return false;
        jclass byteArrayClass = e->FindClass("[B");
        jobjectArray jIn = e->NewObjectArray(static_cast<jsize>(in.size()), byteArrayClass, nullptr);
        for (size_t i = 0; i < in.size(); ++i) {
            jbyteArray item = toByteArray(e, in[i].data(), in[i].size());
            e->SetObjectArrayElement(jIn, static_cast<jsize>(i), item);
            e->DeleteLocalRef(item);
        }
        jstring jUser = newJavaString(e, user);
        auto jOut = static_cast<jobjectArray>(e->CallObjectMethod(g_listenerObj, g_midOnPinSetup,
                                                                  static_cast<jint>(step), jUser, jIn));
        clearPendingException(e, "onPinSetup");
        bool ok = jOut != nullptr;
        if (ok) {
            jsize count = e->GetArrayLength(jOut);
            for (jsize i = 0; i < count; ++i) {
                auto item = static_cast<jbyteArray>(e->GetObjectArrayElement(jOut, i));
                std::vector<uint8_t> bytes;
                if (item) {
                    jsize len = e->GetArrayLength(item);
                    bytes.resize(static_cast<size_t>(len));
                    e->GetByteArrayRegion(item, 0, len, reinterpret_cast<jbyte*>(bytes.data()));
                    e->DeleteLocalRef(item);
                }
                out.push_back(std::move(bytes));
            }
            e->DeleteLocalRef(jOut);
        }
        e->DeleteLocalRef(jUser);
        e->DeleteLocalRef(jIn);
        e->DeleteLocalRef(byteArrayClass);
        return ok;
    });

    airplay::VideoCastHandler::Callbacks castCallbacks;
    castCallbacks.play = [](const std::string& url, double start) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnVideoCastPlay) {
            jstring jUrl = newJavaString(e, url);
            e->CallVoidMethod(g_listenerObj, g_midOnVideoCastPlay, jUrl, static_cast<jdouble>(start));
            clearPendingException(e, "onVideoCastPlay");
            e->DeleteLocalRef(jUrl);
        }
    };
    castCallbacks.seek = [](double position) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnVideoCastSeek) {
            e->CallVoidMethod(g_listenerObj, g_midOnVideoCastSeek, static_cast<jdouble>(position));
            clearPendingException(e, "onVideoCastSeek");
        }
    };
    castCallbacks.rate = [](float rate) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnVideoCastRate) {
            e->CallVoidMethod(g_listenerObj, g_midOnVideoCastRate, static_cast<jfloat>(rate));
            clearPendingException(e, "onVideoCastRate");
        }
    };
    castCallbacks.stop = []() {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnVideoCastStop) {
            e->CallVoidMethod(g_listenerObj, g_midOnVideoCastStop);
            clearPendingException(e, "onVideoCastStop");
        }
    };
    g_server->setVideoCastCallbacks(std::move(castCallbacks));

    bool success = g_server->start(port);
    if (!success) {
        LOGE("AirPlay native server failed to start on port %d", static_cast<int>(port));
        stopServerLocked(env);
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_stopNativeServer(
        JNIEnv* env,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_serverMutex);
    stopServerLocked(env);
    LOGI("AirPlay Native Server stopped from JNI.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_airplay_tv_engine_AirPlayNativeBridge_stopNativeServer(
        JNIEnv* env,
        jobject thiz) {
    Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_stopNativeServer(env, thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_feedSimulatedNalFrame(
        JNIEnv* env,
        jobject /*thiz*/,
        jbyteArray nalData,
        jlong pts,
        jboolean /*isKeyFrame*/) {
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (!g_server || !nalData) return;
    jsize len = env->GetArrayLength(nalData);
    std::vector<uint8_t> buffer(static_cast<size_t>(len));
    env->GetByteArrayRegion(nalData, 0, len, reinterpret_cast<jbyte*>(buffer.data()));
    g_server->feedVideoPayload(buffer.data(), buffer.size(), static_cast<uint64_t>(pts));
}

extern "C" JNIEXPORT void JNICALL
Java_com_airplay_tv_engine_AirPlayNativeBridge_feedSimulatedNalFrame(
        JNIEnv* env,
        jobject thiz,
        jbyteArray nalData,
        jlong pts,
        jboolean isKeyFrame) {
    Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_feedSimulatedNalFrame(env, thiz, nalData, pts, isKeyFrame);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_getPublicKeyHex(JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (!g_server) {
        airplay::AirPlayCrypto tempCrypto;
        return env->NewStringUTF(tempCrypto.getEd25519PublicKeyHex().c_str());
    }
    return env->NewStringUTF(g_server->getCrypto().getEd25519PublicKeyHex().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_airplay_tv_engine_AirPlayNativeBridge_getPublicKeyHex(JNIEnv* env, jobject thiz) {
    return Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_getPublicKeyHex(env, thiz);
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_setDisplayResolution(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint width,
        jint height,
        jfloat refreshRate) {
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (g_server) {
        g_server->setDisplayResolution(static_cast<int>(width), static_cast<int>(height), static_cast<float>(refreshRate));
    }
    LOGI("Display resolution configured: %dx%d @ %.2f fps", (int)width, (int)height, (float)refreshRate);
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_setTxtRecords(
        JNIEnv* env,
        jobject /*thiz*/,
        jbyteArray airplayTxt,
        jbyteArray raopTxt) {
    auto toVector = [env](jbyteArray array) {
        std::vector<uint8_t> out;
        if (!array) return out;
        jsize len = env->GetArrayLength(array);
        out.resize(static_cast<size_t>(len));
        env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte*>(out.data()));
        return out;
    };
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (g_server) {
        g_server->setTxtRecords(toVector(airplayTxt), toVector(raopTxt));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_setIdentitySeed(
        JNIEnv* env,
        jobject /*thiz*/,
        jbyteArray seed) {
    std::vector<uint8_t> value;
    if (seed && env->GetArrayLength(seed) == 32) {
        value.resize(32);
        env->GetByteArrayRegion(seed, 0, 32, reinterpret_cast<jbyte*>(value.data()));
    }
    std::lock_guard<std::mutex> lock(g_serverMutex);
    g_identitySeed = std::move(value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_setPinRequired(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jboolean required) {
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (g_server) g_server->setPinRequired(required == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_disconnectSender(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (g_server) g_server->disconnectSender();
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_setPairedClients(
        JNIEnv* env,
        jobject /*thiz*/,
        jobjectArray keys) {
    std::vector<std::vector<uint8_t>> clients;
    jsize count = keys ? env->GetArrayLength(keys) : 0;
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(keys, i));
        if (!item) continue;
        jsize len = env->GetArrayLength(item);
        std::vector<uint8_t> key(static_cast<size_t>(len));
        env->GetByteArrayRegion(item, 0, len, reinterpret_cast<jbyte*>(key.data()));
        env->DeleteLocalRef(item);
        clients.push_back(std::move(key));
    }
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (g_server) g_server->setPairedClients(clients);
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_setVideoCastPlayback(
        JNIEnv* /*env*/,
        jobject /*thiz*/,
        jint state,
        jdouble duration,
        jdouble position,
        jdouble buffered,
        jfloat rate,
        jboolean readyToPlay) {
    airplay::VideoCastHandler::PlaybackInfo info;
    info.state = static_cast<airplay::VideoCastHandler::PlayerState>(state);
    info.duration = duration;
    info.position = position;
    info.buffered = buffered;
    info.rate = rate;
    info.readyToPlay = readyToPlay == JNI_TRUE;
    std::lock_guard<std::mutex> lock(g_serverMutex);
    if (g_server) g_server->updateVideoCastPlayback(info);
}
