plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    // A fixed debug keystore is committed on purpose: every CI build then carries
    // the same signature, so a new APK installs over the old one and keeps its data.
    // This is the standard, non-secret debug key - never use it for a Play Store release.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // All three schemes, because some OEM installers reject v2-only APKs.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    // The APK we actually install is "release": not debuggable, which phone
    // installers and Play Protect treat with less suspicion than a debug build.
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
