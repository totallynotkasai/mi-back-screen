plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.backscreen.wallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.backscreen.wallpaper"
        minSdk = 33 // needed for the system photo picker (no storage permission)
        targetSdk = 35
        versionCode = 5
        versionName = "1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Material 3 UI components (Google's design system). No network code.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Shizuku client library: talks to the Shizuku app you installed. No network code.
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
}
