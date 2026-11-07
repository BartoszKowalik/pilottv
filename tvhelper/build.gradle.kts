plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Pomocnik instalowany na dekoderze: uruchamia aplikację po nazwie pakietu (link pilottv://launch?app=…).
// Wersja i podpis jak w module app.
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
val ciRefName = System.getenv("GITHUB_REF_NAME")
val appVersionName = if (ciRefName != null && ciRefName.startsWith("v")) ciRefName.removePrefix("v") else "0.1.0-dev"
val keystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "pl.pilottv.helper"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.pilottv.helper"
        minSdk = 21
        targetSdk = 35
        versionCode = ciRunNumber
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        checkReleaseBuilds = false
    }
}
