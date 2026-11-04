import java.util.Properties

plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
}

val qaDriver = providers.gradleProperty("qaDriver").orElse("production").get()
check(qaDriver in setOf("production", "surface-pixel-diagnostic")) {
    "Unknown qaDriver; use production or surface-pixel-diagnostic"
}
val surfaceDiagnostic = qaDriver == "surface-pixel-diagnostic"
if (surfaceDiagnostic) {
    check(providers.gradleProperty("android.builtInKotlin").orNull == "false") {
        "The Java Surface diagnostic requires the existing android.builtInKotlin=false"
    }
} else {
    pluginManager.apply("org.jetbrains.kotlin.android")
}

val productRoot = rootDir.parentFile
val product = Properties().apply {
    productRoot.resolve("version.properties").inputStream().use(::load)
}
val targetApplicationId = product.getProperty("applicationId")
check(targetApplicationId == "dev.aerove.lite") { "The black-box runner targets AEROVE Lite only" }
val releaseKeystore = providers.environmentVariable("AEROVE_LITE_KEYSTORE_PATH")
val releasePassword = providers.environmentVariable("AEROVE_LITE_KEYSTORE_PASSWORD")
val driverSource = when (qaDriver) {
    "production" -> productRoot.resolve("app/src/androidTest/java/dev/aerove/lite/FlutterProductionUiTest.kt")
    "surface-pixel-diagnostic" -> rootDir.resolve("src/main/java/dev/aerove/lite/SurfacePixelDiagnosticTest.java")
    else -> error("Unknown qaDriver; use production or surface-pixel-diagnostic")
}
val prepareDriverSource = tasks.register<Sync>("prepareDriverSource") {
    from(driverSource)
    into(layout.buildDirectory.dir("generated/driverSource/dev/aerove/lite"))
    doFirst {
        check(driverSource.isFile) { "Integrate the selected independent QA driver before building the runner" }
    }
}
val verifyDriverSigning = tasks.register("verifyDriverSigning") {
    doLast {
        check(releaseKeystore.isPresent && file(releaseKeystore.get()).isFile &&
            !releasePassword.orNull.isNullOrEmpty()) {
            "The runner must use the existing independent Lite signing identity"
        }
    }
}

android {
    namespace = "dev.aerove.lite.test"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    defaultConfig {
        applicationId = "$targetApplicationId.test"
        minSdk = 33
        targetSdk = 37
        versionCode = product.getProperty("versionCode").toInt()
        versionName = product.getProperty("version")
        manifestPlaceholders["targetApplicationId"] = targetApplicationId
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").java.setSrcDirs(
        listOf(layout.buildDirectory.dir("generated/driverSource")),
    )
    if (surfaceDiagnostic) {
        useLibrary("android.test.runner")
        useLibrary("android.test.base")
        useLibrary("android.test.mock")
        sourceSets.getByName("main").manifest.srcFile("src/surfaceDiagnostic/AndroidManifest.xml")
    }
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
        release {
            signingConfig = signingConfigs.getByName("liteRelease")
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

if (!surfaceDiagnostic) {
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension> {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        sourceSets.getByName("main").kotlin.setSrcDirs(
            listOf(layout.buildDirectory.dir("generated/driverSource")),
        )
    }
}

tasks.matching {
    it.name.startsWith("compile") &&
        (it.name.endsWith("Kotlin") || it.name.endsWith("JavaWithJavac"))
}.configureEach {
    dependsOn(prepareDriverSource)
}
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    dependsOn(verifyDriverSigning)
}

androidComponents.beforeVariants { variant ->
    variant.enable = variant.buildType == "release"
    variant.enableAndroidTest = false
}

// These are the existing runner coordinates; no product classes or Compose graph.
dependencies {
    if (!surfaceDiagnostic) {
        implementation("androidx.test:runner:1.6.2")
        implementation("junit:junit:4.13.2")
    }
}
