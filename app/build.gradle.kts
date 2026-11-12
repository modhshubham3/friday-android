plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.friday.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.fridayvoice.app"
        minSdk = 26
        targetSdk = 36
        // CI passes -PvCode / -PvName so every published build is newer than the last;
        // the in-app updater only offers a build with a higher versionCode.
        versionCode = (findProperty("vCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("vName") as String?) ?: "dev"

        // The wake word engine ships a native library per CPU type; this phone is 64-bit ARM.
        ndk { abiFilters += listOf("arm64-v8a") }
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
            // v1 is skipped by the build tool anyway because minSdk is 24+.
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
    implementation("ai.picovoice:porcupine-android:4.0.2") // on-device wake word, audio never leaves the phone
}
