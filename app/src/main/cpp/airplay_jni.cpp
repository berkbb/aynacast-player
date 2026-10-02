#include <jni.h>
#include <android/log.h>
#include <memory>
#include "raop_rtsp_server.h"

#define TAG "AirPlayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jobject g_listenerObj = nullptr;
static jmethodID g_midOnClientConnected = nullptr;
static jmethodID g_midOnStreamStarted = nullptr;
static jmethodID g_midOnStreamStopped = nullptr;
static jmethodID g_midOnSpsPpsReady = nullptr;
static jmethodID g_midOnVideoFrame = nullptr;
static jmethodID g_midOnAudioPacket = nullptr;

static std::unique_ptr<airplay::RaopRtspServer> g_server;

static JNIEnv* getJniEnv() {
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != 0) {
            return nullptr;
        }
    }
    return env;
}

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
        jobject thiz,
        jint port,
        jstring jDeviceName,
        jstring jMacAddress,
        jobject listener) {
    if (g_server && g_server->isRunning()) {
        return JNI_TRUE;
    }

    if (g_listenerObj) {
        env->DeleteGlobalRef(g_listenerObj);
        g_listenerObj = nullptr;
    }

    if (listener) {
        g_listenerObj = env->NewGlobalRef(listener);
        jclass clazz = env->GetObjectClass(listener);
        g_midOnClientConnected = env->GetMethodID(clazz, "onClientConnected", "(Ljava/lang/String;Ljava/lang/String;)V");
        g_midOnStreamStarted = env->GetMethodID(clazz, "onStreamStarted", "()V");
        g_midOnStreamStopped = env->GetMethodID(clazz, "onStreamStopped", "()V");
        g_midOnSpsPpsReady = env->GetMethodID(clazz, "onSpsPpsReady", "([B[B)V");
        g_midOnVideoFrame = env->GetMethodID(clazz, "onVideoFrame", "([BJZ)V");
        g_midOnAudioPacket = env->GetMethodID(clazz, "onAudioPacket", "([BJ)V");
    }

    g_server = std::make_unique<airplay::RaopRtspServer>();

    if (jDeviceName && jMacAddress) {
        const char* cDevName = env->GetStringUTFChars(jDeviceName, nullptr);
        const char* cMac = env->GetStringUTFChars(jMacAddress, nullptr);
        g_server->setDeviceIdentity(cDevName ? cDevName : "PhilipsTV", cMac ? cMac : "00:11:22:33:44:55");
        if (cDevName) env->ReleaseStringUTFChars(jDeviceName, cDevName);
        if (cMac) env->ReleaseStringUTFChars(jMacAddress, cMac);
    }

    g_server->setClientConnectedCallback([](const airplay::ClientInfo& info) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnClientConnected) {
            jstring jDev = e->NewStringUTF(info.deviceName.c_str());
            jstring jIp = e->NewStringUTF(info.ipAddress.c_str());
            e->CallVoidMethod(g_listenerObj, g_midOnClientConnected, jDev, jIp);
            e->DeleteLocalRef(jDev);
            e->DeleteLocalRef(jIp);
        }
    });

    g_server->setStreamStartedCallback([]() {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnStreamStarted) {
            e->CallVoidMethod(g_listenerObj, g_midOnStreamStarted);
        }
    });

    g_server->setStreamStoppedCallback([]() {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnStreamStopped) {
            e->CallVoidMethod(g_listenerObj, g_midOnStreamStopped);
        }
    });

    g_server->getVideoParser().setSpsPpsCallback([](const std::vector<uint8_t>& sps, const std::vector<uint8_t>& pps) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnSpsPpsReady) {
            jbyteArray jSps = e->NewByteArray(static_cast<jsize>(sps.size()));
            e->SetByteArrayRegion(jSps, 0, static_cast<jsize>(sps.size()), reinterpret_cast<const jbyte*>(sps.data()));

            jbyteArray jPps = e->NewByteArray(static_cast<jsize>(pps.size()));
            e->SetByteArrayRegion(jPps, 0, static_cast<jsize>(pps.size()), reinterpret_cast<const jbyte*>(pps.data()));

            e->CallVoidMethod(g_listenerObj, g_midOnSpsPpsReady, jSps, jPps);
            e->DeleteLocalRef(jSps);
            e->DeleteLocalRef(jPps);
        }
    });

    g_server->getVideoParser().setFrameCallback([](const uint8_t* data, size_t size, uint64_t pts, bool isKeyFrame) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnVideoFrame) {
            jbyteArray jFrame = e->NewByteArray(static_cast<jsize>(size));
            e->SetByteArrayRegion(jFrame, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
            e->CallVoidMethod(g_listenerObj, g_midOnVideoFrame, jFrame, static_cast<jlong>(pts), static_cast<jboolean>(isKeyFrame));
            e->DeleteLocalRef(jFrame);
        }
    });

    g_server->setAudioPacketCallback([](const uint8_t* pcm, size_t size, uint64_t pts) {
        JNIEnv* e = getJniEnv();
        if (e && g_listenerObj && g_midOnAudioPacket) {
            jbyteArray jAudio = e->NewByteArray(static_cast<jsize>(size));
            e->SetByteArrayRegion(jAudio, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(pcm));
            e->CallVoidMethod(g_listenerObj, g_midOnAudioPacket, jAudio, static_cast<jlong>(pts));
            e->DeleteLocalRef(jAudio);
        }
    });

    bool success = g_server->start(port);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_stopNativeServer(
        JNIEnv* env,
        jobject thiz) {
    if (g_server) {
        g_server->stop();
        g_server.reset();
    }
    if (g_listenerObj) {
        env->DeleteGlobalRef(g_listenerObj);
        g_listenerObj = nullptr;
    }
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
        jobject thiz,
        jbyteArray nalData,
        jlong pts,
        jboolean isKeyFrame) {
    if (!g_server) return;
    jsize len = env->GetArrayLength(nalData);
    std::vector<uint8_t> buffer(len);
    env->GetByteArrayRegion(nalData, 0, len, reinterpret_cast<jbyte*>(buffer.data()));
    g_server->getVideoParser().parsePayload(buffer.data(), buffer.size(), static_cast<uint64_t>(pts));
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
Java_com_babadogan_aynacast_tv_engine_AirPlayNativeBridge_getPublicKeyHex(JNIEnv* env, jobject thiz) {
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
        JNIEnv* env,
        jobject thiz,
        jint width,
        jint height,
        jfloat refreshRate) {
    if (g_server) {
        g_server->setDisplayResolution(static_cast<int>(width), static_cast<int>(height), static_cast<float>(refreshRate));
    }
    LOGI("Display resolution configured: %dx%d @ %.2f fps", (int)width, (int)height, (float)refreshRate);
}
