plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.equinox.v05"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.equinox.v05"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.5-step03"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
