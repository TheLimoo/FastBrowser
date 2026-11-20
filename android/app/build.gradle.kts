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

    // Stable debug signing (from v1.0.6): the committed android/app/debug.keystore
    // uses the standard public Android debug credentials (android /
    // androiddebugkey) — the same ones every local debug.keystore uses. It is
    // a DEBUG key only: it must never sign a release, and anyone could build
    // an APK with the same signature, so it buys install-over-update, not
    // trust. It is what makes v2.0.0 install over v1.0.7 in place.
    signingConfigs {
        named("debug") {
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
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
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
