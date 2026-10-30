import java.util.Properties

val productRoot = rootDir.parentFile
val product = Properties().apply {
    productRoot.resolve("version.properties").inputStream().use(::load)
}
val expectedVersion = "${product.getProperty("version")}+${product.getProperty("versionCode")}"
val declaredVersion = Regex("(?m)^version: ([^\\r\\n]+)$")
    .find(productRoot.resolve("pubspec.yaml").readText())?.groupValues?.get(1)
check(declaredVersion == expectedVersion) {
    "Lite pubspec version must match version.properties; run python android/sync_product.py --write"
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Flutter and the preserved native root never share Gradle intermediates.
layout.buildDirectory.set(productRoot.resolve("build/flutter-host"))
subprojects {
    layout.buildDirectory.set(
        if (name == "app") productRoot.resolve("build/app")
        else productRoot.resolve("build/flutter-host/$name"),
    )
}

// Keep Java/Kotlin targets aligned with the existing Standard Flutter host.
gradle.projectsEvaluated {
    allprojects {
        tasks.withType<JavaCompile>().configureEach {
            sourceCompatibility = JavaVersion.VERSION_17.toString()
            targetCompatibility = JavaVersion.VERSION_17.toString()
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory, productRoot.resolve("build/app"))
}
