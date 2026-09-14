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
        versionCode = 8
        versionName = "0.8-widget-network"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
