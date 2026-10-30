plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

extra["liteComposeDependencies"] = listOf(compose.foundation, compose.material3, compose.materialIconsExtended, compose.ui)
apply(from = "../android/app-common.gradle.kts")
