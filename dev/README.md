# AniKoto 180 — Extension Source

This folder contains the full Gradle source for the **AniKoto 180** Aniyomi
extension (v16.9, versionCode 9).

> **Up one level:** [../README.md](../README.md) — project overview, install
> instructions, credits, license.

---

## Project layout

```
dev/
├── build.gradle.kts          ← Root build file (plugins only)
├── settings.gradle.kts       ← Module includes (:stubs + :src:en:anikoto)
├── gradle.properties         ← Gradle + Kotlin + Android flags
├── gradlew, gradlew.bat      ← Gradle wrapper scripts
├── gradle/
│   ├── libs.versions.toml    ← Dependency + plugin versions
│   ├── kei.versions.toml     ← (Legacy keiyoushi versions — kept for reference)
│   └── wrapper/              ← Gradle wrapper jar + properties
├── common/
│   ├── AndroidManifest.xml   ← Shared manifest (placeholders filled per-build)
│   └── proguard-rules.pro    ← R8 keep rules (preserves $$serializer classes)
├── stubs/                    ← ext-lib v16 compile-only stubs
│   ├── build.gradle.kts
│   └── src/main/kotlin/...   ← eu.kanade.tachiyomi.* (animesource, network, util)
└── src/en/anikoto/           ← The AniKoto extension module
    ├── build.gradle.kts      ← Build config + signing config
    ├── res/                  ← Launcher icons (mdpi → xxxhdpi)
    └── src/main/kotlin/eu/kanade/tachiyomi/animeextension/en/anikoto/
        ├── Anikoto.kt              ← Main source class
        ├── AnikotoDto.kt           ← DTOs + mapper response parser
        ├── AnikotoFilters.kt       ← Catalog filters (genres, sort, year, etc.)
        ├── AnikotoLog.kt           ← Logcat-only logger
        ├── AnikotoRC4.kt           ← RC4 stream cipher (for mapper API)
        ├── AnikotoSettings.kt      ← 4-category preferences
        ├── EpisodeMeta.kt          ← Episode URL fragment encoder/decoder
        ├── metadata/
        │   └── EpisodeMetadataFetcher.kt   ← Multi-source metadata enrichment
        ├── smartsearch/
        │   └── SmartSearch.kt      ← AI-powered descriptive search (Google AI Search)
        └── video/
            ├── AnikotoExtractors.kt  ← Video server extractors
            ├── LocalProxyServer.kt   ← On-device 127.0.0.1 proxy (PNG strip + LRU cache)
            ├── WebViewFetcher.kt     ← Chrome-WebView fetcher (Cloudflare WAF bypass)
            └── Models.kt             ← AudioStream, HosterTask, etc.
```

---

## Prerequisites

- **JDK 17** (the ext-lib v16 jar is Java 17 bytecode)
- **Android SDK** with:
  - `platforms;android-34`
  - `build-tools;34.0.0`
  - `platform-tools`
- **Gradle 8.x** (the included wrapper handles this — just use `./gradlew`)

Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) to your SDK install:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
# or, on macOS: ~/Library/Android/sdk
# or, on Linux: ~/Android/Sdk
```

---

## Building

### Debug build (no signing needed — for testing)

```bash
cd dev
./gradlew :src:en:anikoto:assembleDebug
# → src/en/anikoto/build/outputs/apk/debug/aniyomi-en.anikoto180-v16.9-debug.apk
```

### Release build (signed — needs the maintainer's keystore)

The release signing config in
[`src/en/anikoto/build.gradle.kts`](src/en/anikoto/build.gradle.kts) reads the
keystore path + credentials from environment variables:

| Variable | Purpose |
|---|---|
| `ANIKOTO_KEYSTORE_PATH` | Absolute path to `anikoto-release.jks` |
| `ANIKOTO_KEYSTORE_PASSWORD` | Keystore password |
| `ANIKOTO_KEY_ALIAS` | Key alias (default: `anikoto`) |
| `ANIKOTO_KEY_PASSWORD` | Key password |

```bash
cd dev
export ANIKOTO_KEYSTORE_PATH=/secure/path/anikoto-release.jks
export ANIKOTO_KEYSTORE_PASSWORD='...'
export ANIKOTO_KEY_ALIAS=anikoto
export ANIKOTO_KEY_PASSWORD='...'

./gradlew :src:en:anikoto:assembleRelease
# → src/en/anikoto/build/outputs/apk/release/aniyomi-en.anikoto180-v16.9-release.apk
```

> **If the env vars are unset**, the release build type falls back to your local
> debug signing key. The APK will install and run, but will **not** be installable
> as an update to the official AniKoto 180 — it will appear as a separate app
> with a different signature.

### Verifying a signed release

```bash
APK=src/en/anikoto/build/outputs/apk/release/aniyomi-en.anikoto180-v16.9-release.apk
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose --print-certs "$APK"
# Expected SHA-256: B4:67:CA:64:0B:A7:9C:C0:91:D4:A9:99:00:56:70:89:95:0B:C8:27:4E:F6:4D:8F:56:2B:25:90:4A:61:6A:5A
```

---

## Critical identity rules

These three values define the extension's identity in the Aniyomi app. **Do not
change them** without a deliberate architecture decision — changing any of them
orphans saved anime for existing users.

| Field | Value | Why it's stable |
|---|---|---|
| `override val name` | `"AniKoto 180"` | Source ID = `MD5("anikoto 180/en/11")` |
| `versionId` (manifest) | `11` | Part of the source ID hash |
| `signingKeyFingerprint` | `b467ca64…6a5a` | Aniyomi validates this against `repo.json` |

The `applicationId` (`…anikoto180`) does **not** affect the source ID, but
changing it requires users to uninstall the old package before installing the
new one (Android treats different package names as different apps).

---

## Publishing a new version

This repository uses **direct-APK hosting** (see
[ADR-02](https://github.com/Confused-Creature-180/aniyomi-extensions/blob/main/docs/disclaimer.html)):

1. Bump `extVersionCode` in [`src/en/anikoto/build.gradle.kts`](src/en/anikoto/build.gradle.kts).
2. Build a signed release APK.
3. Copy it to `/apk/` at the repo root (and update `index.min.json` on the
   `repo` branch via the publishing scripts).
4. Commit + push to `main`.

The `repo` branch (which Aniyomi fetches) is updated separately via the Git
Database API — it uses orphan commits with no shared history to `main`.

---

## Credits

Episode metadata enrichment and Smart Search are powered by third-party
services. **If you fork this project, please retain the credits** — see
[the root README's Credits section](../README.md#-credits--data-sources) and
[*The Curse of the Uncredited Fork*](../README.md#-the-curse-of-the-uncredited-fork).
