plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("aerove.lite-app-common") apply false
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
apply(plugin = "aerove.lite-app-common")

android {
    namespace = "dev.aerove.lite"
}

flutter {
    source = "../.."
}
