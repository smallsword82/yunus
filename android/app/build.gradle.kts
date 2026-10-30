plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.yunus.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.yunus.launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-test"
    }

    // Test build: the web prototype in ../viewer is the app's assets, served to a WebView.
    sourceSets["main"].assets.srcDir("../../viewer")
    androidResources {
        // Leave out the unsplit fallback model and leftovers the page doesn't load: unused fonts and
        // images, and the Draco decoder (only the Draco fallback model needed it; the shipped model is plain).
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!dolphin.glb:!kinex_nav_style.json:!index.html:!ElmsSans*:!seat.png:!*_close.webp:!<dir>draco:!kinex_hal_api_service.jar"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
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
