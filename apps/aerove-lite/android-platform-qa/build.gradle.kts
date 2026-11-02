import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.tasks.compile.JavaCompile
import com.android.build.gradle.internal.feature.BundleAllClasses

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

val productRoot = rootDir.parentFile
val releaseKeystore = providers.environmentVariable("AEROVE_LITE_KEYSTORE_PATH")
val releasePassword = providers.environmentVariable("AEROVE_LITE_KEYSTORE_PASSWORD")
val selectedTests = listOf(
    "dev/aerove/lite/backgroundpolicy/VpnUserStateDeviceTest.kt",
    "dev/aerove/lite/data/LiteYamlRuntimeTest.kt",
    "dev/aerove/lite/editor/EditorDocumentAndroidTest.kt",
    "dev/aerove/lite/images/GroupIconRenderingTest.kt",
    "dev/aerove/lite/localization/ApplicationLocaleAndroidTest.kt",
    "dev/aerove/lite/overrides/SmartConfigMeowScriptAndroidTest.kt",
    "dev/aerove/lite/regexp2/IsolatedClientHelperTest.kt",
    "dev/aerove/lite/RuleFileNativeTest.kt",
    "dev/aerove/lite/scan/AlbumQrDecoderTest.kt",
    "dev/aerove/lite/scan/ScanSessionTest.kt",
    "dev/aerove/lite/FlutterProductionUiTest.kt",
)
// Only existing test sources enter this runner; target production classes stay in :app.
val platformSources = layout.buildDirectory.dir("generated/platformSources").get().asFile
val preparePlatformSources = tasks.register<Sync>("preparePlatformSources") {
    from(productRoot.resolve("app/src/androidTest/java")) { selectedTests.forEach { include(it) } }
    into(platformSources)
    doFirst { selectedTests.forEach { check(productRoot.resolve("app/src/androidTest/java/$it").isFile) } }
}

android {
    namespace = "dev.aerove.lite.platform.test"
    targetProjectPath = ":app"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    defaultConfig {
        testApplicationId = "dev.aerove.lite.platform.test"
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "dev.aerove.lite.platformqa.AcceptanceCheckpointRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.srcDir(platformSources)
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
        create("release") {
            signingConfig = signingConfigs.getByName("liteRelease")
            isDebuggable = false
            isMinifyEnabled = true
        }
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    sourceSets.getByName("main").kotlin.srcDir(platformSources)
}
androidComponents.beforeVariants { it.enable = it.buildType == "release" }

// Existing internal component tests remain friends of the actual current target compilation.
val appCompileJar = project(":app").tasks.named<BundleAllClasses>("bundleReleaseClassesToCompileJar")
tasks.withType<KotlinCompile>().configureEach {
    dependsOn(preparePlatformSources, appCompileJar)
    friendPaths.from(appCompileJar.flatMap { it.outputJar })
}
tasks.withType<JavaCompile>().configureEach {
    dependsOn(preparePlatformSources)
}
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        check(releaseKeystore.isPresent && file(releaseKeystore.get()).isFile &&
            !releasePassword.orNull.isNullOrEmpty()) { "Existing Lite release signer is required" }
    }
}

dependencies {
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test:core:1.6.1")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("junit:junit:4.13.2")
    // Existing target coordinates, variant-aware compile-only; no target production classes are packaged.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.google.zxing:core:3.5.3")
    compileOnly("io.github.rosemoe:editor:0.23.7")
    compileOnly("io.github.rosemoe:language-textmate:0.23.7")
    compileOnly("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    compileOnly("androidx.core:core-ktx:1.19.0")
}
