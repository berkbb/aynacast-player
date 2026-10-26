import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// AGP 9 compiles Kotlin natively (built-in Kotlin); only the Compose compiler plugin is applied
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Release signing, first match wins: keystore.properties (git-ignored), AYNACAST_* environment
// variables, or the TV apps' shared keystore ~/.signing/babadogan-tv-release.p12, whose password
// is the macOS Keychain item "babadogan-tv-release-keystore".
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)
val sharedKeystore = File(System.getProperty("user.home"), ".signing/babadogan-tv-release.p12")
val releaseStoreFile = signingValue("storeFile", "AYNACAST_KEYSTORE_FILE") ?: sharedKeystore.takeIf { it.isFile }?.path
// macOS only; elsewhere the password comes from keystore.properties or the environment.
fun keychainPassword(): String? = runCatching {
    providers.exec {
        commandLine("security", "find-generic-password", "-s", "babadogan-tv-release-keystore", "-w")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrNull()?.ifEmpty { null }
val releaseStorePassword = releaseStoreFile?.let { signingValue("storePassword", "AYNACAST_KEYSTORE_PASSWORD") ?: keychainPassword() }

android {
    namespace = "com.babadogan.aynacast.tv"
    // Android 17 platform (API 37.2)
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.babadogan.aynacast.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.5.4"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions", "-O3")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = signingValue("keyAlias", "AYNACAST_KEY_ALIAS") ?: "babadogan-tv"
                keyPassword = signingValue("keyPassword", "AYNACAST_KEY_PASSWORD") ?: releaseStorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                // Keeps local builds working; debug-signed APKs cannot update installed TVs
                logger.warn("AynaCast: no release keystore (keystore.properties, AYNACAST_KEYSTORE_FILE or ~/.signing/babadogan-tv-release.p12); signing release with the debug key.")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

base {
    archivesName.set("AynaCastTV-v${android.defaultConfig.versionName}")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    
    // Android TV Compose
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // AirPlay video casting (URL / HLS playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.ui.tooling)
}
