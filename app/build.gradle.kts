plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.fastbrowser.gologin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fastbrowser.gologin"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // v1.0.6: STABLE debug signing. Before this, CI signed every debug APK
    // with the fresh ephemeral debug keystore on each GitHub runner, so
    // every build had a different signature and Android refused to update —
    // the user had to uninstall (losing servers/settings) for each version.
    // The committed app/debug.keystore uses the standard public Android
    // debug credentials (android / androiddebugkey) — the same ones every
    // local `debug.keystore` uses. It is a DEBUG key only: it must never
    // sign a release, and anyone could build an APK with the same
    // signature, so it buys install-over-update, not trust. v1.0.6 itself
    // still needs ONE last uninstall (v1.0.5 was signed with a runner
    // keystore that cannot be matched); every update after v1.0.6 installs
    // in place with data kept.
    signingConfigs {
        named("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // v1.0.7: R8 shrinking in the release build type only. CI still
            // builds the debug APK (assembleDebug), so this does not change
            // what the user installs — but a hand-built release is now
            // actually shrunk. proguard-rules.pro did not exist before
            // v1.0.7 (the block referenced it but minify was off); it is
            // created in this version, so enabling this cannot fail on the
            // missing file.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // v1.0.5: needed so Settings → About can read the real version
        // (BuildConfig.VERSION_NAME) instead of a hardcoded string.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // v1.0.7: Tink AES256-GCM for stored server passwords (CryptoManager).
    implementation("com.google.crypto.tink:tink-android:1.24.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
