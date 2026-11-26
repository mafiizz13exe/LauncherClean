plugins {
    id("com.android.application")
}

android {
    namespace = "com.mafiizz.doomtv"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.mafiizz.doomtv"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild { ndkBuild { arguments += listOf("APP_PLATFORM=android-21") } }
    }

    externalNativeBuild { ndkBuild { path = file("src/main/jni/Android.mk") } }

    // WADs sem compressão: abre mais rápido e copia direto
    androidResources { noCompress += listOf("wad") }

    signingConfigs {
        create("fixa") {
            storeFile = file("../../app/launcher.keystore")
            storePassword = "cleanlauncher"
            keyAlias = "launcher"
            keyPassword = "cleanlauncher"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
