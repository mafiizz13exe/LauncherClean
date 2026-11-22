plugins {
    id("com.android.application")
}

android {
    namespace = "com.mafiizz.apkinstaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mafiizz.apkinstaller"
        minSdk = 21
        // targetSdk 28: no Fire TV (Fire OS 5/7/8) o app consegue listar a pasta Downloads sem o "acesso a todos os arquivos"
        targetSdk = 28
        versionCode = 2
        versionName = "1.1"
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
