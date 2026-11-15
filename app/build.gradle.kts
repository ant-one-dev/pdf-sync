import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signature release : keystore.properties à la racine (jamais commité).
// Sans ce fichier on signe avec la clé de debug.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.orbisrobotics.pdfsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orbisrobotics.pdfsync"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Deux applis séparées : Expert (présentateur) et Client (participants)
    flavorDimensions += "role"
    productFlavors {
        create("expert") {
            dimension = "role"
            applicationIdSuffix = ".expert"
            minSdk = 29 // création d'un groupe Wi-Fi Direct nommé : Android 10+
            manifestPlaceholders["appName"] = "Expert"
            buildConfigField("boolean", "IS_EXPERT", "true")
        }
        create("client") {
            dimension = "role"
            applicationIdSuffix = ".client"
            manifestPlaceholders["appName"] = "Client"
            buildConfigField("boolean", "IS_EXPERT", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

// pas de dépendance : PdfRenderer, Wi-Fi Direct et les sockets sont dans le SDK
dependencies {}
