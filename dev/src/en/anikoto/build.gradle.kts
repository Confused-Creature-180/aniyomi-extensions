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
        // ★ session 58 / v16.12 RELEASE: the test builds were v16.12 (s56) and v16.13 (s57);
        // per the user's explicit decision the PUBLISHED release takes the number 16.12
        // (do NOT go to 16.13/16.14 — "leave 16.13, stick with 16.12 as the release one").
        // Smart Search fixes: [{[ ]}] bracket title convention on both engines (S0 lenient
        // extractor), widened legacy-engine strategies (fixes "no anime title could be read"
        // on '…looking for is X (Japanese title: …)' answers), Test-Connection fix
        // (thinkingConfig only for gemini-2.5* — 3.x models rejected it with HTTP 400),
        // default model = Gemini 3.1 Flash Lite (top of list, no "Recommended" label),
        // defaults: Smart Search ON + engine Google AI Search, "Copy response" toggle
        // (default OFF; query+title on success, query+error+raw on failure), Details
        // section rewritten to usage instructions.
        // ★ session 60 / v16.13 (TEST BUILD): stream-resolution + metadata hardening —
        // CDN-candidate loop now verifies master AND variants before accepting a candidate
        // and falls through to the next candidate otherwise (fixes "no resolved streams"
        // on fresh episodes, e.g. Exiled Heavy Knight ep-12); bcdn tried first (pure-OkHttp
        // path); per-variant WebView fallback; dedup of identical server entries
        // (Vidstream-2/1beta/HD-2 share one data-id); metadata: no more poisoned empty
        // cache, parallel sources, short-timeout client, AniList OkHttp-first + cached-id
        // outage fallback, Jikan 429 retry, 25s enrich ceiling; server picker refreshed.
        // ★ session 61 / v16.14 (RELEASE): WebView URL fix + quality-list robustness —
        // (1) getAnimeUrl overridden: "Open in WebView" on an anime previously opened
        //     baseUrl + bare-slug → site 404 page ("random URL" bug); now /watch/<slug>.
        // (2) animeSlug() normalization at every anime-url entry point (details/episodes/
        //     WebView/search parsing) — domain-independent, tolerant of old persisted shapes.
        // (3) parseSearchItem slug extraction hardened against foreign-domain listing links.
        // (4) CDN-candidate loop: partial variant loads (transient CDN failures) no longer
        //     lock in a truncated quality list — remaining candidates are tried and the
        //     fullest result wins; clean loads and single-variant masters keep the fast path.
        // ★ session 62 / v16.15 (RELEASE): preferred-domain logic fix + richer-ladder scan —
        // (1) baseUrl was `by lazy` → the preference was read ONCE per process, so changing
        //     Settings → Playback → Preferred domain had NO effect (browsing AND "Open in
        //     WebView" stayed on the old domain until force-stop). Now a live getter that
        //     re-reads on every access — domain switches apply to the very next request.
        // (2) Thin-ladder richness scan: a full candidate win with ≤2 variants no longer ends
        //     the s-candidate scan — remaining CDNs are probed (seen-master dedup keeps the
        //     cost at ~1 getSources call each) and the RICHEST full result wins. Live-verified
        //     2026-10-07: some shows (Sakamoto Days, Beyblade X) are 1080p-only at source on
        //     EVERY candidate/endpoint; other shows list 1080/720/360. A settings note
        //     ("About missing qualities") explains the source limitation to users, and the
        //     preferred-server list gains the current live name "Vidstream-1".
        // ★ session 63 (v16.16) — yuzono-reference alignment + WebView URL root fix:
        // (1) anime.url now stored as the SITE PATH "/watch/<slug>" (the yuzono/anikototheme
        //     reference behavior) instead of the bare slug. Live-verified 2026-10-07: the
        //     site 404s every non-/watch/ path, so any app-side construction of
        //     baseUrl + "/" + anime.url (forks that bypass getAnimeUrl) produced the exact
        //     user-reported bad WebView URL (e.g. https://anikoto.cz/<slug>). With the site
        //     path, EVERY construction lands on the real page; older persisted shapes
        //     (bare slug, /watch/…, full URLs) still normalize via animeSlug/animeWatchPath.
        // (2) Fresh per-request document headers (docHeaders()) — the base class `headers`
        //     val is lazy and froze the Referer on the FIRST domain, so after a
        //     Preferred-domain switch request URLs were correct but the Referer leaked the
        //     old domain. Popular/latest/search/details now build headers each request.
        // (3) Mapper pipeline un-deaded: the live mapper names servers WITHOUT a trailing
        //     dash ("Kiwi") — the old parser required "endsWith('-')" and matched nothing;
        //     mapper tokens are FULL player URLs but were fed through /ajax/server?get=.
        //     Now: parser accepts both key shapes, skips status/error/message, maps names
        //     gogoanime→Vidstream / anivibe→Vibe-Stream / kiwi*→Kiwi-Stream (yuzono
        //     parity), ALL streaming mapper servers are surfaced, tokens are used directly
        //     as embed URLs, mewcdn HOST_MAP is honored, and a direct-m3u8 Flow C handles
        //     plain-HLS mapper entries.
        // (4) MegaPlay CDN HMAC token (yuzono parity): decrypted getSources m3u8 URLs get
        //     the same ?token= signature the site's own player appends (secret from the
        //     yuzono maintainers; live A/B 2026-10-07 shows no behavioral difference TODAY
        //     — future-proofing against the CDN starting to require it).
        // Resolutions note: single-quality episodes (Sakamoto Days etc.) are a SOURCE
        //     limitation — re-verified 2026-10-07 across every server × CDN candidate ×
        //     endpoint × both domains, with and without the CDN token; the site's other
        //     qualities are download-only pahe links (settings note updated).
        // ★ v16.13 = the FIRST dist-repo release after v16.12 (Confused-Creature-180
        //     dist repo numbering; user's explicit instruction, session 64). Per the
        //     session-58 precedent, internal test-build numbers are NOT reserved —
        //     16.13-test…16.16 existed only on the build repo; the public dist line
        //     jumps 16.12 → 16.13 and carries ALL of their fixes.
        //     Side effect (documented, session-58 precedent): devices on the 16.14-16.16
        //     test builds (codes 14-16) are not offered the 16.13 in-app update — they
        //     sideload the identical-code APK or wait for the next dist release.
        val extVersionCode = 13
        val extVersionId = 11    // ★ STABLE — do NOT bump with versionCode. See EXTENSIONS/anikoto/MEMORY/sites/getsources-migration-and-id-analysis.md §2.
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

    // ★ session 46: release signing config — uses anikoto-release.jks (Confused_Creature / 180)
    // The keystore must stay at DEVELOPMENT_CODE/anikoto-release.jks for all future releases.
    // If lost, users must uninstall the old extension before installing a new one (signature mismatch).
    //
    // ★ Security: the password is read ONLY from the KEYSTORE_PASSWORD env var (no hardcoded
    // fallback). In CI it's set from the GitHub Actions secret. For local builds, export it:
    //   export KEYSTORE_PASSWORD=...   (see EXTENSIONS/anikoto/DEV/keystore-info.txt, gitignored)
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("anikoto-release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "anikoto"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
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
            signingConfig = signingConfigs.getByName("release")
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
