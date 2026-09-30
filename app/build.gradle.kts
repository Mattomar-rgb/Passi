plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.mariani.passi"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.mariani.passi"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        getByName("debug") {
            // Chiave fissa: gli aggiornamenti si installano sopra senza perdere i dati
            storeFile = rootProject.file("keystore/passi.keystore")
            storePassword = "android"
            keyAlias = "passi"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
