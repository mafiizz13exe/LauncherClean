plugins {
    id("com.android.application")
}

android {
    namespace = "com.mafiizz.alarmeforte"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mafiizz.alarmeforte"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

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
