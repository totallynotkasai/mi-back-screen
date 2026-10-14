import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. Each setting comes from an environment variable of the same name or, failing
// that, from local.properties (never committed). Without them, assembleRelease builds an unsigned
// APK, as on CI. The key is a password-protected copy of the debug key the earlier releases were
// signed with, so a release installs over them and keeps the user's settings.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun signingSetting(name: String): String? = System.getenv(name) ?: localProperties.getProperty(name)

android {
    namespace = "com.backscreen.wallpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.backscreen.wallpaper"
        minSdk = 35 // Android 15 and up; the one phone it's for runs Android 16 (HyperOS 3)
        targetSdk = 36
        versionCode = 9
        versionName = "2.0.2"
    }

    signingConfigs {
        val storeFile = signingSetting("MBS_STORE_FILE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = signingSetting("MBS_STORE_PASSWORD")
                keyAlias = signingSetting("MBS_KEY_ALIAS")
                keyPassword = signingSetting("MBS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off: Shizuku's newProcess is called by reflection and would need a keep rule.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // "Use the KTX extension" (60 warnings) would drown the useful ones, and edit {} would
        // hide the deliberate commit() calls.
        disable += setOf("UseKtx", "KtxExtensionAvailable")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric
        // Robolectric at SDK 36 sets up each app's file descriptors through a JDK class that Java
        // keeps to itself unless told otherwise.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Material 3 UI components (Google's design system). No network code.
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")

    // Picks the charging animation's Auto colour from the wallpaper image, on the phone. No network code.
    implementation("androidx.palette:palette:1.0.0")

    // Shizuku client library: talks to the Shizuku app you installed. No network code.
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")

    testImplementation("junit:junit:4.13.2")
    // Activity and fragment tests on the JVM (rotation, Recents, dialogs).
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.test.ext:junit-ktx:1.3.0")
}
