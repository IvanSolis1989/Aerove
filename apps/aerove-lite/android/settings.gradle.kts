pluginManagement {
    val flutterSdkPath = run {
        val properties = java.util.Properties()
        val local = file("local.properties")
        if (local.isFile) local.inputStream().use(properties::load)
        properties.getProperty("flutter.sdk") ?: System.getenv("FLUTTER_ROOT")
            ?: error("Set flutter.sdk in android/local.properties or FLUTTER_ROOT")
    }
    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
}

rootProject.name = "AEROVE Lite Flutter"
include(":app")
