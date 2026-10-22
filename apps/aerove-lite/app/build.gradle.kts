import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val product = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseKeystore = providers.environmentVariable("AEROVE_LITE_KEYSTORE_PATH")
val releasePassword = providers.environmentVariable("AEROVE_LITE_KEYSTORE_PASSWORD")

android {
    namespace = "dev.aerove.lite"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = product.getProperty("applicationId")
        minSdk = 33
        targetSdk = 37
        versionCode = product.getProperty("versionCode").toInt()
        versionName = product.getProperty("version")
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
    sourceSets.getByName("main").java.srcDir("../third_party/material-color-utilities/src")
    sourceSets.getByName("main").assets.srcDir("src/main/java/dev/aerove/lite/editor/assets")
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { jniLibs.useLegacyPackaging = false }
    testBuildType = "release"
    signingConfigs {
        create("liteRelease") {
            if (releaseKeystore.isPresent) storeFile = file(releaseKeystore.get())
            storePassword = releasePassword.orNull
            keyAlias = "aerove-lite"
            keyPassword = releasePassword.orNull
            storeType = "PKCS12"
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "+debug"
        }
        release {
            signingConfig = signingConfigs.getByName("liteRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

val verifyNativeCore = tasks.register("verifyNativeCore") {
    val abi = providers.gradleProperty("liteAbi").getOrElse("arm64-v8a")
    val library = layout.projectDirectory.file("src/main/jniLibs/$abi/libaerove_core.so")
    inputs.file(library)
    doLast {
        check(abi in setOf("arm64-v8a", "x86_64")) { "Unsupported native ABI: $abi" }
        check(library.asFile.isFile) { "Build the actual Rust core with apps/aerove-lite/native/build.py before packaging" }
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
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.github.rosemoe:editor:0.23.7")
    implementation("io.github.rosemoe:language-textmate:0.23.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestRuntimeOnly("androidx.tracing:tracing:1.2.0")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.38.0")
    androidTestImplementation("org.jetbrains.compose.ui:ui-test-junit4:1.12.0")
}
