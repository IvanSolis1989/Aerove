plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("dev.flutter.flutter-gradle-plugin")
}

dependencies {
    project.extra["liteComposeDependencies"] = listOf(
        compose.foundation,
        compose.material3,
        compose.materialIconsExtended,
        compose.ui,
    )
}
apply(from = "../app-common.gradle.kts")

android {
    namespace = "dev.aerove.lite"
}

flutter {
    source = "../.."
}
