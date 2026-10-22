plugins {
    id("com.android.application")
}

android {
    namespace = "dev.aerove.lite.renderlab"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aerove.lite.renderlab"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
