import java.util.Properties // "java" alone is the Java plugin's extension in a build script

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Personal release signing: the keystore and its passwords are read from android/keystore.properties,
// which is NOT in the repo (see keystore.properties.example). Only whoever holds that key can sign a
// Yunus release — that is what makes a build "yours". Without the file, a release build is unsigned
// (install it with `assembleDebug` instead, or create the keystore first — see README).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// The trial's unlock codes are made from a secret only the author holds: android/license.properties
// (NOT in the repo, see license.properties.example), also read by tools/yunus_kod.py. Without the
// file the secret is empty and the build has no trial at all (see License.kt).
val licensePropsFile = rootProject.file("license.properties")
val licenseSecret = Properties().apply {
    if (licensePropsFile.exists()) licensePropsFile.inputStream().use { load(it) }
}.getProperty("secret", "").trim()

android {
    namespace = "app.yunus.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.yunus.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.1"
        buildConfigField("String", "LICENSE_SECRET", "\"${licenseSecret.replace("\\", "").replace("\"", "")}\"")
    }
    buildFeatures { buildConfig = true }

    // Test build: the web prototype in ../viewer is the app's assets, served to a WebView.
    sourceSets["main"].assets.srcDir("../../viewer")
    androidResources {
        // Leave out the unsplit fallback models (Draco: the shipped WebView has no decoder for them) and leftovers the page doesn't load: unused fonts and
        // images, and the Draco decoder (only the Draco fallback model needed it; the shipped model is plain).
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!dolphin.glb:!byd_seal.glb:!byd_atto_3.glb:!byd_atto_2.glb:!byd_seal_u.glb:!byd_seal_u_dmi.glb:!byd_tang.glb:!byd_han.glb:!kinex_nav_style.json:!index.html:!ElmsSans*:!seat.png:!*_close.webp:!<dir>draco:!kinex_hal_api_service.jar"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (keystorePropsFile.exists()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Release: the code is shrunk and obfuscated by R8 (so a copy of the APK is hard to read or
        // reverse) and signed with the personal key above. proguard-rules.pro keeps the few things
        // reached by name (the shell helper launched by app_process, the WebView JS bridge), so the
        // obfuscation does not break them. If a release build ever misbehaves on the car, set
        // isMinifyEnabled = false here to rule R8 out, then re-enable with the right keep rule.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
}

// Name the APK after the app and version.
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "yunus-${versionName}.apk"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
