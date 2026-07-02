plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android") version libs.versions.kotlin.gradle.get()
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "eu.kanade.tachiyomi.animeextension"

    defaultConfig {
        // Extension metadata
        // ★ session 46: name changed to "AniKoto 180" to match the published v16.5
        // (source ID = MD5("anikoto 180/en/11") — preserves saved anime for v16.5 users).
        val extName = "AniKoto 180"
        // ★ session 49 (v16.9): extClass is now the FULL class path (no leading dot).
        // The applicationId was changed to ...anikoto180 (to distinguish from other publishers
        // using "anikoto"), but the source code package stays at ...anikoto (no need to move files).
        // The loader does `packageName + extClass` when extClass starts with "." — that would look
        // for ...anikoto180.Anikoto which doesn't exist. Using the full path (no dot) makes the
        // loader use it as-is → finds the class at ...anikoto.Anikoto. Verified in the loader source:
        // SHARED/REFERENCE_HUB/aniyomi-app/.../AnimeExtensionLoader.kt:297-301.
        val extClass = "eu.kanade.tachiyomi.animeextension.en.anikoto.Anikoto"
        val extVersionCode = 9  // v16.9 (session 49: change package to ...anikoto180 — distinguish from other publishers)
        val extVersionId = 11    // ★ STABLE — do NOT bump with versionCode.
                                  // The source id = MD5("anikoto 180/en/$extVersionId"). Bumping this orphans saved anime.
                                  // Only change if the site's URL structure breaks (domain change).
        val isNsfw = false

        // ★ session 49: applicationId changed from ...anikoto to ...anikoto180.
        // Reason: other publishers use "anikoto" as their package — "180" distinguishes ours.
        // The full applicationId is now: eu.kanade.tachiyomi.animeextension.en.anikoto180
        // ⚠️ Users on the old ...anikoto package must UNINSTALL before installing this —
        // Android treats different package names as different apps (no direct update).
        applicationIdSuffix = "en.anikoto180"

        // ★ ext-lib 16: versionName MUST start with "16." (loader rejects <12 or >16)
        versionCode = extVersionCode
        versionName = "16.$extVersionCode"

        // ★ session 49: filename uses anikoto180 to match the new package name
        base.archivesName.set("aniyomi-en.anikoto180-v$versionName")

        // Manifest placeholders (filled into common/AndroidManifest.xml)
        // ★ session 46: appName = extName (no "Aniyomi: " prefix — matches published v16.5)
        manifestPlaceholders["appName"] = extName
        manifestPlaceholders["extClass"] = extClass
        manifestPlaceholders["nsfw"] = if (isNsfw) "1" else "0"
        manifestPlaceholders["versionId"] = extVersionId.toString()

        // SDK versions
        minSdk = 21
        targetSdk = 34
        compileSdk = 34
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Release signing config.
    //
    // ⚠️  The keystore (anikoto-release.jks) and its passwords are NOT published
    //     in this repository — they are private to the maintainer
    //     (Confused-Creature-180). If lost, installed extensions cannot be
    //     updated in place (users must uninstall + reinstall).
    //
    // To build a signed release locally, provide the keystore + credentials
    // via environment variables (or a local, git-ignored gradle.properties):
    //
    //   ANIKOTO_KEYSTORE_PATH     = absolute path to anikoto-release.jks
    //   ANIKOTO_KEYSTORE_PASSWORD = keystore password
    //   ANIKOTO_KEY_ALIAS         = key alias  (default: anikoto)
    //   ANIKOTO_KEY_PASSWORD      = key password
    //
    // If the env vars are unset (e.g. for a fork build), the release build
    // type below falls back to the debug signing config so the build still
    // succeeds — the resulting APK will simply be signed with Android's
    // debug key (NOT installable as an update to the official extension).
    // ─────────────────────────────────────────────────────────────────────────
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANIKOTO_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = System.getenv("ANIKOTO_KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("ANIKOTO_KEY_ALIAS") ?: "anikoto"
                keyPassword = System.getenv("ANIKOTO_KEY_PASSWORD") ?: ""
            }
        }
    }

    // Java 17 compat (ext-lib v16 jar is Java 17 bytecode)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Source sets: standard AGP layout (src/main/kotlin + src/main/java)
    // Extension code + ext-lib stubs are both in src/main/kotlin/
    sourceSets {
        getByName("main") {
            manifest.srcFile(rootProject.file("common/AndroidManifest.xml"))
            res.srcDirs("res")
            assets.srcDirs("assets")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            // Use the release signing config only when the keystore env vars are set.
            // Otherwise fall back to debug signing (fork builds / CI without secrets).
            signingConfig = if (System.getenv("ANIKOTO_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                rootProject.file("common/proguard-rules.pro"),
            )
        }
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    // ★ The ext-lib v16 stubs — compileOnly so they're NOT in the APK at runtime.
    // The app provides the real classes via its classloader.
    // Without this, the stubs' "throw Exception("Stub!")" bodies would execute at runtime.
    compileOnly(project(":stubs"))

    // AndroidX preference (needed by ConfigurableAnimeSource stub)
    compileOnly("androidx.preference:preference:1.2.1")

    // All deps are compileOnly (provided by the Aniyomi app at runtime)
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
