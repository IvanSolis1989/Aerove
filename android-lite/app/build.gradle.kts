plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

android {
    namespace = "dev.aerove.lite"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "dev.aerove.lite"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += providers.gradleProperty("liteAbi").getOrElse("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { jniLibs.useLegacyPackaging = false }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "+debug"
        }
    }
}

val verifyNativeCore by tasks.registering {
    val abi = providers.gradleProperty("liteAbi").getOrElse("arm64-v8a")
    val library = layout.projectDirectory.file("src/main/jniLibs/$abi/libaerove_core.so")
    inputs.file(library)
    doLast {
        check(abi in setOf("arm64-v8a", "x86_64")) { "Unsupported native ABI: $abi" }
        check(library.asFile.isFile) { "Build the actual Rust core with android-lite/native/build.py before packaging" }
        val header = library.asFile.inputStream().use { it.readNBytes(20) }
        val machine = if (abi == "arm64-v8a") 183 else 62
        check(header.size == 20 && header.sliceArray(0..5).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1)) &&
            (header[18].toInt() and 0xff) == machine && header[19].toInt() == 0) { "Wrong Rust core ELF ABI" }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(verifyNativeCore)
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.ui)
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
