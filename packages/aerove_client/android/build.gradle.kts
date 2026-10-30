plugins {
    // Versions are supplied by the consuming product's fixed Android root.
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "dev.aerove.client.platform"
version = "1.0"

android {
    namespace = "dev.aerove.client.platform"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
