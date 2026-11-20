plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.fastbrowser.gologin"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Stable signing (from v1.0.6): the committed android/app/debug.keystore
    // uses the standard public Android debug credentials (android /
    // androiddebugkey) — the same ones every local debug.keystore uses.
    // It is a DEBUG key: anyone can reproduce this signature, so it buys
    // install-over-update continuity, not trust. That is the intended
    // trade-off for this app: it is a personal browser distributed as a
    // GitHub release APK, not a Play Store app, and keeping the same
    // certificate is what lets v2.0.1 install over v2.0.0 in place
    // (a new key would force every user to uninstall first).
    // Since v2.0.1 the RELEASE build is signed with this same keystore
    // (signingConfigs.release below) — that is also what makes the release
    // build install over the debug-signed v2.0.0.
    signingConfigs {
        named("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.fastbrowser.gologin"
        // minSdk 26: per-profile WebViews (AndroidX WebKit MULTI_PROFILE /
        // ProfileStore) need Android 8.0+.
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // Deliberately the committed debug keystore (see the
            // signingConfigs comment): same certificate as every build
            // since v1.0.6, so release updates install in place.
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    // Per-profile WebViews (MULTI_PROFILE, ProxyController) + legacy
    // DataStore/Tink migration (one-time upgrade from v1.0.7).
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Explicit (not just transitive via DataStore): NativeChannels uses
    // CoroutineScope/Dispatchers/flow.first directly.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.crypto.tink:tink-android:1.24.0")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
