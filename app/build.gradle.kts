plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.codexlimits"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.codexlimits"
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "0.4-proof"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
