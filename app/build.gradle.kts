import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xniperbuilds.downloader"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // TT fork: its own app identity (installs side by side with Riplox).
        // The code package stays com.xniperbuilds.downloader — only the applicationId differs.
        applicationId = "com.xniperbuilds.riploxtt"
        minSdk = 26
        targetSdk = 36
        // v1.1.1 = ads + AIRLOCK v3 reliability (escort FGS, watchdog v2, save-beats,
        // safe startup cleanup, bg-setup guide) — a port of the parent Riplox fixes.
        // Separate lineage from the open-source no-ads GitHub release v1.0.0 (vc1).
        // v1.1.2 = back-port of device-verified engine fixes: embed-thumbnail/metadata
        // removed (the "stuck at finishing" hang), engine channel moved to NIGHTLY, worker
        // self-heal (update the engine and retry when a stale extractor fails), and a
        // friendly rate-limit error.
        // v1.1.3 = NEW TIKTOK EXTRACTOR — TikTok put its video data behind a UA/JS gate, and
        // youtubedl-android ships no curl_cffi (impersonation is impossible), so yt-dlp on a
        // phone could not extract at all ("the download never starts"). Media info now comes
        // from a plain desktop-UA HTTP fetch, with the phone's own WebView as a safety net
        // and yt-dlp as a last-resort fallback; the bytes arrive over HttpURLConnection.
        // Ships alongside Connect TikTok (login/cookies) and quality chips that actually
        // work (TikTok is vertical — selection is by width, not height).
        // v1.1.4 = "a new version is out" notice — Play In-App Updates on a Play build
        // (FLEXIBLE download plus our own "Restart to finish" card; full-screen IMMEDIATE
        // when urgent). There is deliberately no update check on sideload/GitHub builds.
        // ⚠️ Play policy: an app installed from Play may not self-update or pull an APK,
        // so this path is gated on the install source (AppUpdates.kt).
        versionCode = 6
        versionName = "1.1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AdMob IDs come from the build type — Google's TEST ids on a QA build, the real
        // ones on release. These used to be swapped BY HAND in three places (and clicking
        // your own real ads gets an AdMob ban), so that mistake is no longer possible.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-8029174313177489~6045986402"
        buildConfigField("String", "AD_BANNER_ID", "\"ca-app-pub-8029174313177489/7721461931\"")
        buildConfigField("String", "AD_INTERSTITIAL_ID", "\"ca-app-pub-8029174313177489/8209115310\"")

        ndk {
            // Launch: arm64 + 32-bit (so it still installs on older phones)
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Release signing — credentials live in local.properties (gitignored; they never reach
    // the repo). Contributors without the key fall back to debug signing, so the build
    // still runs for them.
    val localProps = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { localProps.load(it) }
    val riploxKs: String? = localProps.getProperty("RIPLOXTT_KS")
    signingConfigs {
        if (riploxKs != null) {
            create("release") {
                storeFile = file(riploxKs)
                storePassword = localProps.getProperty("RIPLOXTT_KS_PASS")
                keyAlias = localProps.getProperty("RIPLOXTT_KEY_ALIAS")
                keyPassword = localProps.getProperty("RIPLOXTT_KEY_PASS")
                // Older/OEM installers (MIUI and friends) reject a v2-only APK, so keep
                // v1 (JAR) signing on as well and sideloading works on every phone.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = if (riploxKs != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        // QA build — identical to release, but with a DIFFERENT applicationId and Google's
        // TEST ad ids.
        // ⚠️ Why: the copy installed from Play (closed testing) is signed with the Play App
        // Signing key, so a local APK sharing its applicationId fails with
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE. The `.qa` suffix lets the QA build sit
        // alongside the Play one (nothing has to be uninstalled, so existing downloads and
        // history stay safe). Test ad ids mean there is no way to click your own real ads
        // (AdMob invalid traffic).
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += listOf("release")
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "AD_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
            buildConfigField("String", "AD_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // The engine (Python/yt-dlp) needs real files on disk — legacy packaging on.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // yt-dlp engine (youtubedl-android — same rules as parent Riplox; no aria2c in the TT app)
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // Thumbnail (notification) + video-frame thumbnails in the Recent list
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // Background download queue — survives app close / reboot + auto-retry
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // AdMob — bottom banner + interstitial (Ads.kt)
    implementation("com.google.android.gms:play-services-ads:24.4.0")

    // Play In-App Updates — the "a new version is out" notice (AppUpdates.kt).
    // Only works on an app installed from Play; sideload/GitHub builds run no update check
    // at all. 2.1.0 is the latest release of this artifact (verified on Maven).
    implementation("com.google.android.play:app-update:2.1.0")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // Settings/category ke proper icons (emoji ki jagah)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
