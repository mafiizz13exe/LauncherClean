plugins {
    id("com.android.application")
}

android {
    namespace = "com.mafiizz.apkinstaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mafiizz.apkinstaller"
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "1.3"
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

dependencies {
    implementation("androidx.core:core:1.13.1")
}
