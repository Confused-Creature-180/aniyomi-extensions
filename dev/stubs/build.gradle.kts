plugins {
    alias(libs.plugins.android.library)
    id("org.jetbrains.kotlin.android") version libs.versions.kotlin.gradle.get()
}

android {
    namespace = "eu.kanade.tachiyomi.stubs"

    defaultConfig {
        minSdk = 21
        compileSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // All deps are compileOnly — the stubs only need them at compile time.
    // The app provides the real implementations at runtime.
    compileOnly("androidx.preference:preference:1.2.1")
    compileOnly(libs.coroutines.core)
    compileOnly(libs.coroutines.android)
    compileOnly(libs.injekt.core)
    compileOnly(libs.rxjava)
    compileOnly(libs.kotlin.protobuf)
    compileOnly(libs.kotlin.json)
    compileOnly(libs.kotlin.json.okio)
    compileOnly(libs.jsoup)
    compileOnly(libs.okhttp)
    compileOnly(libs.quickjs)
}
