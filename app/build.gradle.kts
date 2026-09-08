plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.callagent.gateway"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.callagent.gateway"
        minSdk = 26
        targetSdk = 34
        versionCode = 389
        versionName = "1.0.5"
    }

    // A release build is signed with the same debug key the debug build uses.
    // That is deliberate: it keeps the signature identical, so a release APK can
    // replace a debug one inside the Magisk module without PackageManager
    // rejecting it for a signature mismatch.  The point of building release here
    // is not secrecy, it is `debuggable=false` — ART compiles a debuggable app in
    // a deoptimizable mode with much weaker inlining, which costs real CPU in the
    // per-frame audio loops.
    signingConfigs {
        create("shared") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
