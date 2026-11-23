import java.util.Properties

plugins {
    id("com.google.devtools.ksp") version "2.3.4"
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Credentials stay outside source control. Environment > local.properties > .env.
val localSecrets = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val envSecrets = rootProject.file(".env").takeIf { it.isFile }
    ?.readLines()?.mapNotNull { line ->
        val entry = line.trim()
        if (entry.isBlank() || entry.startsWith("#") || !entry.contains('=')) null
        else entry.substringBefore('=').trim() to entry.substringAfter('=').trim()
            .removeSurrounding("\"").removeSurrounding("'")
    }?.toMap().orEmpty()
fun credential(key: String): String = providers.environmentVariable(key).orNull
    ?: localSecrets.getProperty(key) ?: envSecrets[key].orEmpty()
val telegramApiId = credential("TG_API_ID").toIntOrNull()?.takeIf { it > 0 } ?: 0
val telegramApiHash = credential("TG_API_HASH")
val acoustIdKey = credential("ACOUSTID_KEY")
require(acoustIdKey.isEmpty() || acoustIdKey.matches(Regex("[A-Za-z0-9]+"))) {
    "ACOUSTID_KEY must be alphanumeric. Check your local configuration."
}
require(telegramApiHash.isEmpty() || telegramApiHash.matches(Regex("[a-fA-F0-9]{32}"))) {
    "TG_API_HASH must contain 32 hexadecimal characters. Check your local configuration."
}

android {
    namespace = "com.ashe.cinemabox"
    // Same NDK as the bundled TDLib (r30), so both share one C++ toolchain.
    ndkVersion = "30.0.16248370"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ashe.cinemabox"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("int", "TG_API_ID", telegramApiId.toString())
        buildConfigField("String", "TG_API_HASH", "\"$telegramApiHash\"")
        buildConfigField("String", "ACOUSTID_KEY", "\"$acoustIdKey\"")
        externalNativeBuild {
            cmake {
                // TDLib already ships libc++_shared.so; a static STL avoids a second copy in the APK.
                arguments += "-DANDROID_STL=c++_static"
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets.getByName("main") {
        java.srcDir(rootProject.file("libs/tdlib/src/main/java"))
        jniLibs.srcDir(rootProject.file("libs/tdlib/jniLibs"))
    }

    // Release key lives outside the repo; its path and passwords come from .env (never committed).
    val releaseKeystore = credential("CINEBOX_KEYSTORE_PATH").takeIf { it.isNotEmpty() && file(it).isFile }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = credential("CINEBOX_KEYSTORE_PASSWORD")
            keyAlias = credential("CINEBOX_KEY_ALIAS")
            keyPassword = credential("CINEBOX_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

val verifyTdlib by tasks.registering {
    doLast {
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
            for (name in listOf("libtdjni.so", "libsslx.so", "libcryptox.so", "libc++_shared.so")) {
                val binary = rootProject.file("libs/tdlib/jniLibs/$abi/$name")
                check(binary.isFile && binary.inputStream().use {
                    it.readNBytes(4).contentEquals(byteArrayOf(127, 69, 76, 70))
                }) { "Missing TDLib binary for $abi/$name. Run scripts/setup-tdlib.ps1." }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyTdlib) }
