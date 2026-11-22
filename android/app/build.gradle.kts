plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
}
import java.util.Properties

// Release signing credentials live in ../private/keys (git-ignored) or MTV_* env vars.
val signing = Properties().apply {
    val f = rootProject.file("../private/keys/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun sig(k: String, env: String): String? = System.getenv(env) ?: signing.getProperty(k)

android {
    namespace = "com.mytv.launcher"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.tinteractive.mytv"   // Play-compatible id; the code package stays com.mytv.launcher
        minSdk = 26
        targetSdk = 36
        // Version: <year>.<month>.<build>; the code follows from it (2026.11.1 -> 2026110001), always increasing.
        val mytvVersion = "2026.11.6"
        versionName = mytvVersion
        versionCode = mytvVersion.split('.').map { it.toInt() }.let { (y, m, b) -> y * 1_000_000 + m * 10_000 + b }
    }
    // Two editions from one code base, same package id and signing key:
    //  - github: everything, incl. the own updater, the TV's own adb (Clean up, Google TV home switch, screensaver
    //    "start after", sleep timer), WRITE_SECURE_SETTINGS and adb setup commands (Provision). Code in src/github.
    //  - play:   the same app without those; src/play holds small stand-ins, so the menus hide them.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            // GitHub repo whose releases carry the APK and update.json (empty = updates off). Override: -PupdateRepo=owner/name
            buildConfigField("String", "UPDATE_REPO", "\"" + (project.findProperty("updateRepo") ?: "tinteractive/MyTV") + "\"")
        }
        create("play") { dimension = "store" }
    }
    signingConfigs {
        create("release") {
            sig("storeFile", "MTV_STORE_FILE")?.let {
                storeFile = file(it)
                storePassword = sig("storePassword", "MTV_STORE_PASSWORD")
                keyAlias = sig("keyAlias", "MTV_KEY_ALIAS")
                keyPassword = sig("keyPassword", "MTV_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed only when a key is configured; without one (a fork, CI) the release APK is simply unsigned.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    lint {
        // Everything lint reports as an error stops the build (CI runs lint for both editions).
        abortOnError = true
        checkDependencies = false
        lintConfig = file("lint.xml")
        // Versions are kept current by Dependabot, not by failing a build.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    // Robolectric tests read the app's own resources (texts) like the real app does.
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets["main"].apply { kotlin.srcDirs("src/main/kotlin") }
    sourceSets["github"].apply { kotlin.srcDirs("src/github/kotlin") }
    sourceSets["play"].apply { kotlin.srcDirs("src/play/kotlin") }
    sourceSets["testGithub"].apply { kotlin.srcDirs("src/testGithub/kotlin") }
    androidResources {
        noCompress += listOf("jpg", "webp")
        // Only MyTV's own languages (libraries ship dozens more, which would half-translate other languages).
        // Resource-folder names, one per values-* folder with texts (LanguagesTest checks they match).
        localeFilters += listOf(
            "en", "nl", "fr", "de", "es", "pt", "pt-rBR", "it", "pl", "tr", "in", "ru", "hi", "ko", "th", "vi", "ar",
            "zh-rCN", "zh-rTW", "uk", "sv", "nb", "da", "fi", "cs", "sk", "hu", "ro", "el", "iw", "hr", "sr", "bg", "fa",
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Installs the Compose libraries' baseline profiles on sideloaded installs (no Play Store to do it).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.google.zxing:core:3.5.4")
    // Reads the orientation of photos sent from a phone; safer than the platform's own reader on older Android.
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    // Android in a plain JVM test (HomeState: overlay stack, focus, profiles, PIN); never part of the app itself.
    testImplementation("org.robolectric:robolectric:4.17")
}

// Static analysis of the Kotlin sources (both editions). Existing findings are in detekt-baseline.xml; new ones fail.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(file("detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom("src/main/kotlin", "src/github/kotlin", "src/play/kotlin", "src/test/kotlin", "src/testGithub/kotlin")
    parallel = true
}
