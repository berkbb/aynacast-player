import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 compiles Kotlin natively (built-in Kotlin); only the Compose compiler plugin is applied
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

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
        versionCode = 3
        versionName = "1.3.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
