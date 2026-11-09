import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.impl.ArtifactsImpl
import com.android.build.api.variant.DeviceTestBuilder
import com.android.build.api.variant.ScopedArtifacts
import com.android.build.gradle.internal.scope.InternalArtifactType
import groovy.json.JsonOutput
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.process.ExecOperations
import java.util.Properties

plugins {
    id("com.android.application")
}

val android = extensions.getByType<com.android.build.api.dsl.ApplicationExtension>()
val androidComponents = extensions.getByType<com.android.build.api.variant.ApplicationAndroidComponentsExtension>()

// Both build roots use this module; product files stay beside app/, not android/.
val productRoot = if (rootProject.name == "AEROVE Lite Flutter") rootProject.projectDir.parentFile else rootProject.projectDir
val flutterHost = rootProject.projectDir == productRoot.resolve("android")
val platformAppRoot = productRoot.resolve("app")
val sharedAndroidSourceRoot = productRoot.resolve("../../packages/aerove_client/android-shared/src/main/kotlin")

// Scoped-artifact wiring adapted from android/gradle-recipes, getScopedArtifacts,
// Copyright 2023 The Android Open Source Project, licensed under Apache-2.0.
// https://www.apache.org/licenses/LICENSE-2.0
@CacheableTask
abstract class GenerateAndroidTestAppKeepRules @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Classpath abstract val appJars: ListProperty<RegularFile>
    @get:Classpath abstract val appDirectories: ListProperty<Directory>
    @get:Classpath abstract val testJars: ListProperty<RegularFile>
    @get:Classpath abstract val testDirectories: ListProperty<Directory>
    @get:Classpath abstract val testRuntimeJars: ListProperty<RegularFile>
    @get:Classpath abstract val testRuntimeDirectories: ListProperty<Directory>
    @get:Classpath abstract val testResourceJar: RegularFileProperty
    @get:Classpath abstract val libraries: ConfigurableFileCollection
    @get:Classpath abstract val r8Classpath: ConfigurableFileCollection
    @get:Input abstract val pythonCommand: Property<String>
    @get:Input abstract val javaCommand: Property<String>
    @get:Input abstract val productNamespace: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val generator: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val traceHelper: RegularFileProperty
    @get:OutputFile abstract val rules: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun generate() {
        val manifest = temporaryDir.resolve("inputs.json")
        val resourceJar = testResourceJar.get().asFile
        manifest.writeText(JsonOutput.toJson(mapOf(
            "java" to javaCommand.get(),
            "r8Classpath" to r8Classpath.files.map { it.absolutePath },
            "traceHelper" to traceHelper.get().asFile.absolutePath,
            "javaCompiler" to listOf(File(File(javaCommand.get()).parentFile,
                if (System.getProperty("os.name").startsWith("Windows")) "javac.exe" else "javac").absolutePath),
            "productNamespaces" to listOf(productNamespace.get()),
            // AGP's PROJECT scope also contains dependency resource classes in this generated jar.
            "sources" to (testJars.get().map { it.asFile }.filter { it != resourceJar }.map { it.absolutePath } +
                testDirectories.get().map { it.asFile.absolutePath }),
            "sourceDependencies" to (listOf(resourceJar.absolutePath) + testRuntimeJars.get().map { it.asFile.absolutePath } +
                testRuntimeDirectories.get().map { it.asFile.absolutePath }),
            "targets" to (appJars.get().map { it.asFile.absolutePath } +
                appDirectories.get().map { it.asFile.absolutePath }),
            "libraries" to libraries.files.map { it.absolutePath },
        )))
        execOperations.exec {
            commandLine(pythonCommand.get(), generator.get().asFile.absolutePath,
                "--manifest", manifest.absolutePath,
                "--output", rules.get().asFile.absolutePath,
                "--report", report.get().asFile.absolutePath)
        }.assertNormalExitValue()
    }
}

// Flutter's black-box runner is packaged by the independent android-qa build.
// Keeping its device tests here would couple test compilation to production R8.
if (flutterHost) {
    androidComponents.beforeVariants { variant ->
        variant.enableAndroidTest = false
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val androidTest = variant.deviceTests[DeviceTestBuilder.ANDROID_TEST_TYPE]
    val platformTests = if (flutterHost) rootProject.findProject(":platform-qa") else null
    if (androidTest == null && platformTests == null) return@onVariants
    val infer = tasks.register<GenerateAndroidTestAppKeepRules>(
        "generate${variant.name.replaceFirstChar { it.uppercase() }}${if (androidTest == null) "PlatformTest" else "AndroidTest"}AppKeepRules",
    ) {
        generator.set(productRoot.resolve("tool/generate_android_test_keep_rules.py"))
        traceHelper.set(productRoot.resolve("tool/AndroidTestAppAbiTrace.java"))
        productNamespace.set(variant.namespace)
        pythonCommand.set(providers.gradleProperty("litePython").orElse("python3"))
        javaCommand.set(File(System.getProperty("java.home"),
            "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").absolutePath)
        // Reuse the exact R8 already loaded by AGP, without another dependency/version.
        r8Classpath.from(providers.provider {
            File(Class.forName("com.android.tools.r8.R8", false,
                com.android.build.gradle.AppPlugin::class.java.classLoader)
                .protectionDomain.codeSource.location.toURI())
        })
        // Platform definitions precede compile-only stubs, matching AGP's ordered library lookup.
        libraries.from(androidComponents.sdkComponents.bootClasspath,
            variant.compileClasspath)
        if (androidTest != null) libraries.from(androidTest.compileClasspath)
        rules.set(layout.buildDirectory.file("intermediates/androidTestAppKeepRules/${variant.name}/rules.pro"))
        report.set(layout.buildDirectory.file("intermediates/androidTestAppKeepRules/${variant.name}/inputs.json"))
    }
    // Public scoped artifacts carry the real compiler dependencies, before R8.
    variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(infer).toGet(
        ScopedArtifact.CLASSES, GenerateAndroidTestAppKeepRules::appJars,
        GenerateAndroidTestAppKeepRules::appDirectories,
    )
    if (androidTest != null) {
        infer.configure {
            testResourceJar.set((androidTest.artifacts as ArtifactsImpl).get(InternalArtifactType.COMPILE_AND_RUNTIME_R_CLASS_JAR))
        }
        androidTest.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(infer).toGet(
            ScopedArtifact.CLASSES, GenerateAndroidTestAppKeepRules::testJars,
            GenerateAndroidTestAppKeepRules::testDirectories,
        )
        androidTest.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(infer).toGet(
            ScopedArtifact.CLASSES, GenerateAndroidTestAppKeepRules::testRuntimeJars,
            GenerateAndroidTestAppKeepRules::testRuntimeDirectories,
        )
    } else {
        // AGP compiles tests against pre-R8 classes, then applies the same App mapping in Test R8.
        val platformProject = checkNotNull(platformTests)
        platformProject.plugins.withId("com.android.test") {
            // Test R8 needs the target's pre-R8 dependency types without packaging them in the runner.
            platformProject.dependencies.add("compileOnly", variant.compileClasspath)
            val testComponents = platformProject.extensions.getByType<
                com.android.build.api.variant.TestAndroidComponentsExtension>()
            testComponents.onVariants(testComponents.selector().withBuildType("release")) { testVariant ->
                // Match shared module variants before AGP filters dependencies already provided by the target.
                testVariant.runtimeConfiguration.shouldResolveConsistentlyWith(variant.runtimeConfiguration)
                infer.configure {
                    libraries.from(testComponents.sdkComponents.bootClasspath, testVariant.compileClasspath)
                    testResourceJar.set((testVariant.artifacts as ArtifactsImpl).get(InternalArtifactType.COMPILE_AND_RUNTIME_R_CLASS_JAR))
                }
                testVariant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT).use(infer).toGet(
                    ScopedArtifact.CLASSES, GenerateAndroidTestAppKeepRules::testJars,
                    GenerateAndroidTestAppKeepRules::testDirectories,
                )
                testVariant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(infer).toGet(
                    ScopedArtifact.CLASSES, GenerateAndroidTestAppKeepRules::testRuntimeJars,
                    GenerateAndroidTestAppKeepRules::testRuntimeDirectories,
                )
            }
        }
    }
    variant.proguardFiles.add(infer.flatMap { it.rules })
}

val product = Properties().apply {
    productRoot.resolve("version.properties").inputStream().use { load(it) }
}
val releaseKeystore = providers.environmentVariable("AEROVE_LITE_KEYSTORE_PATH")
val releasePassword = providers.environmentVariable("AEROVE_LITE_KEYSTORE_PASSWORD")
val clientHelperLicenseDirectory = layout.buildDirectory.dir("generated/clientHelperLicenses")
val prepareClientHelperLicenses = tasks.register<Sync>("prepareClientHelperLicenses") {
    from(productRoot.resolve("regexp2/licenses")) { into("licenses/client-helper") }
    from(productRoot.resolve("regexp2/dependencies.json")) { into("licenses/client-helper") }
    into(clientHelperLicenseDirectory)
}

val candidateIdentityDirectory = layout.buildDirectory.dir("generated/candidateIdentity")
val prepareCandidateIdentity = tasks.register<Exec>("prepareCandidateIdentity") {
    val producer = productRoot.resolve("tool/candidate_identity.py")
    inputs.file(producer)
    val abi = providers.gradleProperty("liteAbi").getOrElse("arm64-v8a")
    val source = providers.gradleProperty("sourceSha").getOrElse(providers.environmentVariable("GITHUB_SHA").getOrElse(""))
    inputs.property("abi", abi)
    inputs.property("source", source)
    listOf("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_NUMBER", "GITHUB_RUN_ATTEMPT").forEach {
        inputs.property(it, providers.environmentVariable(it).getOrElse(""))
    }
    outputs.dir(candidateIdentityDirectory)
    commandLine("python3", producer.path, "--abi", abi, "--source", source,
        "--output", candidateIdentityDirectory.get().file("aerove/candidate_identity.json").asFile.path)
}

val legacySourceTasks = if (flutterHost) {
    emptyMap<String, TaskProvider<Sync>>()
} else {
    mapOf(
        "main" to tasks.register<Sync>("prepareLegacyMainSources") {
            from(listOf(platformAppRoot.resolve("src/main/java"), platformAppRoot.resolve("src/main/kotlin"), sharedAndroidSourceRoot)) {
                include("**/*.java", "**/*.kt")
                exclude("**/FlutterMainActivity.kt", "**/MeowFlutterHost.kt", "**/MeowAppPlugin.kt")
            }
            into(layout.buildDirectory.dir("generated/legacySources/main"))
        },
        "test" to tasks.register<Sync>("prepareLegacyTestSources") {
            from(listOf(platformAppRoot.resolve("src/test/java"), platformAppRoot.resolve("src/test/kotlin"))) {
                include("**/*.java", "**/*.kt")
                exclude("**/MeowAppPluginTest.kt")
            }
            into(layout.buildDirectory.dir("generated/legacySources/test"))
        },
    )
}

android.apply {
    namespace = "dev.aerove.lite"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = product.getProperty("applicationId")
        minSdk = 33
        targetSdk = 37
        versionCode = product.getProperty("versionCode").toInt()
        versionName = product.getProperty("version")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            if (flutterHost) abiFilters.clear()
            abiFilters += providers.gradleProperty("liteAbi").getOrElse("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    listOf("main", "test", "androidTest").forEach { name ->
        sourceSets.getByName(name).setRoot(platformAppRoot.resolve("src/$name").path)
    }
    sourceSets.getByName("main").java.srcDir(productRoot.resolve("third_party/material-color-utilities/src"))
    if (flutterHost) {
        // Flutter generates its plugin registrant in the conventional host tree.
        sourceSets.getByName("main").java.srcDir(projectDir.resolve("src/main/java"))
        listOf("debug", "release", "profile").forEach { name ->
            sourceSets.maybeCreate(name).manifest.srcFile(projectDir.resolve("src/main/AndroidManifest.xml"))
        }
    }
    if (!flutterHost) {
        listOf("main", "test").forEach { name ->
            val sources = legacySourceTasks.getValue(name).map { it.destinationDir }
            sourceSets.getByName(name).java.setSrcDirs(listOf(sources))
            sourceSets.getByName(name).kotlin.setSrcDirs(listOf(sources))
        }
        sourceSets.getByName("main").java.srcDir(productRoot.resolve("third_party/material-color-utilities/src"))
        sourceSets.getByName("androidTest").kotlin.srcDir(platformAppRoot.resolve("src/androidTest/java"))
    }
    sourceSets.getByName("main").assets.srcDir(platformAppRoot.resolve("src/main/java/dev/aerove/lite/editor/assets"))
    if (flutterHost) sourceSets.getByName("main").java.srcDir(sharedAndroidSourceRoot)
    sourceSets.getByName("main").assets.srcDir(clientHelperLicenseDirectory.get().asFile)
    sourceSets.getByName("main").assets.srcDir(candidateIdentityDirectory.get().asFile)
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging {
        jniLibs.useLegacyPackaging = false
        jniLibs.excludes += "**/libaerove_core.so"
        jniLibs.keepDebugSymbols += setOf("**/libaerove_filter_helper.so", "**/libaerove_filter_jni.so", "**/libaerove_meow_core.so")
    }
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), platformAppRoot.resolve("proguard-rules.pro"))
        }
    }
}

if (!flutterHost) {
    tasks.matching {
        it.name.startsWith("compile") &&
            (it.name.endsWith("Kotlin") || it.name.endsWith("JavaWithJavac"))
    }.configureEach {
        dependsOn(legacySourceTasks.getValue("main"))
        if (name.contains("UnitTest")) dependsOn(legacySourceTasks.getValue("test"))
    }
}

val verifyNativeCore = tasks.register("verifyNativeCore") {
    val abi = providers.gradleProperty("liteAbi").getOrElse("arm64-v8a")
    val library = platformAppRoot.resolve("src/main/jniLibs/$abi/libaerove_meow_core.so")
    val nativeRoot = productRoot.resolve("meow-native")
    val receipt = nativeRoot.resolve("build/$abi-receipt.json")
    val verifier = productRoot.resolve("tool/verify_apk.py")
    inputs.files(library, receipt, verifier, nativeRoot.resolve("Cargo.lock"),
        nativeRoot.resolve("Cargo.toml"), nativeRoot.resolve("source.json"), nativeRoot.resolve("build-policy.json"))
    doLast {
        check(abi in setOf("arm64-v8a", "x86_64")) { "Unsupported native ABI: $abi" }
        check(library.isFile && receipt.isFile) {
            "Build with meow-native/build.py --target TRIPLE --ndk PATH and inject the original ABI receipt before packaging"
        }
        val verification = """
            import os, sys
            from pathlib import Path
            sys.path.insert(0, sys.argv[1])
            from verify_apk import validate_native_receipt, valid_elf64_shared_object, exported_functions, CORE_JNI_SYMBOLS
            root, receipt, library, abi = Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4]), sys.argv[5]
            image = library.read_bytes()
            workflow = {key: os.environ[name] for key, name in [('workflowSourceSha','GITHUB_SHA'), ('workflowRun','GITHUB_RUN_ID'), ('workflowAttempt','GITHUB_RUN_ATTEMPT')]} if os.environ.get('GITHUB_ACTIONS') == 'true' else None
            validate_native_receipt(receipt, abi=abi, image=image, root=root, workflow=workflow)
            if not valid_elf64_shared_object(image, 183 if abi == 'arm64-v8a' else 62):
                raise ValueError('Meow ELF ABI/16KiB alignment invalid')
            missing = set(CORE_JNI_SYMBOLS) - exported_functions(image)
            if missing:
                raise ValueError('Missing Meow JNI exports: ' + ','.join(sorted(missing)))
        """.trimIndent()
        providers.exec {
            commandLine(providers.gradleProperty("litePython").getOrElse("python3"), "-c", verification,
                verifier.parentFile.absolutePath, nativeRoot.absolutePath, receipt.absolutePath,
                library.absolutePath, abi)
        }.result.get().assertNormalExitValue()
    }
}
val verifyClientHelper = tasks.register("verifyClientHelper") {
    val abi = providers.gradleProperty("liteAbi").getOrElse("arm64-v8a")
    val libraries = listOf("libaerove_filter_helper.so", "libaerove_filter_jni.so")
        .map { platformAppRoot.resolve("src/main/jniLibs/$abi/$it") }
    inputs.files(libraries)
    doLast {
        check(abi in setOf("arm64-v8a", "x86_64")) { "Unsupported client helper ABI: $abi" }
        val machine = if (abi == "arm64-v8a") 183 else 62
        libraries.forEach { library ->
            check(library.isFile) { "Build the isolated client helper before packaging: ${library.name}" }
            val header = library.inputStream().use { it.readNBytes(20) }
            check(header.size == 20 && header.sliceArray(0..5).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1)) &&
                (header[18].toInt() and 0xff) == machine && header[19].toInt() == 0) { "Wrong client helper ELF ABI" }
        }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach {
    dependsOn(verifyNativeCore, verifyClientHelper)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(prepareClientHelperLicenses, prepareCandidateIdentity)
}

// Lint reads source assets directly, without passing through mergeAssets.
tasks.withType<com.android.build.gradle.internal.lint.LintModelWriterTask>().configureEach {
    dependsOn(prepareClientHelperLicenses, prepareCandidateIdentity)
    dependsOn(legacySourceTasks.values)
}
tasks.withType<com.android.build.gradle.internal.lint.AndroidLintAnalysisTask>().configureEach {
    dependsOn(prepareClientHelperLicenses, prepareCandidateIdentity)
    dependsOn(legacySourceTasks.values)
}

val composeDependencies = project.extra["liteComposeDependencies"] as List<*>
dependencies {
    add("implementation", "androidx.core:core-ktx:1.19.0")
    add("implementation", "androidx.activity:activity-compose:1.13.0")
    add("implementation", "androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    add("implementation", "androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    composeDependencies.forEach { add("implementation", it!!) }
    add("implementation", "androidx.compose.ui:ui") { version { strictly("1.13.0-beta01") } }
    constraints {
        add("implementation", "androidx.concurrent:concurrent-futures-ktx:1.2.0")
    }
    add("implementation", "io.github.kyant0:backdrop:2.0.1")
    add("implementation", "io.github.kyant0:shapes:1.2.1")
    add("implementation", "com.squareup.okhttp3:okhttp:4.12.0")
    add("implementation", "com.caverock:androidsvg-aar:1.4")
    add("implementation", "org.yaml:snakeyaml:2.4")
    add("implementation", "androidx.camera:camera-camera2:1.5.3")
    add("implementation", "androidx.camera:camera-lifecycle:1.5.3")
    add("implementation", "androidx.camera:camera-view:1.5.3")
    add("implementation", "com.google.zxing:core:3.5.3")
    add("implementation", "io.github.rosemoe:editor:0.23.7")
    add("implementation", "io.github.rosemoe:language-textmate:0.23.7")
    add("testImplementation", "junit:junit:4.13.2")
    add("testImplementation", "com.squareup.okhttp3:mockwebserver:4.12.0")
    add("testImplementation", "com.squareup.okhttp3:okhttp-tls:4.12.0")
    add("testImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    add("testImplementation", "org.json:json:20250517")
    add("androidTestImplementation", "com.squareup.okhttp3:mockwebserver:4.12.0")
    add("androidTestImplementation", "androidx.test:runner:1.6.2")
    add("androidTestImplementation", "androidx.test.ext:junit:1.2.1")
    add("androidTestImplementation", "androidx.test:core:1.6.1")
    add("androidTestRuntimeOnly", "androidx.tracing:tracing:1.2.0")
    add("androidTestImplementation", "com.google.errorprone:error_prone_annotations:2.38.0")
    add("androidTestImplementation", "org.jetbrains.compose.ui:ui-test-junit4:1.12.0")
}

tasks.withType<Test>().configureEach {
    listOf("resource.fixture", "sourceProof89.fixture", "nativeMigration.fixture").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}

tasks.register<JavaExec>("prepareNativeMigrationFixture") {
    dependsOn("compileReleaseUnitTestKotlin")
    mainClass.set("org.junit.runner.JUnitCore")
    classpath(providers.provider { tasks.named<Test>("testReleaseUnitTest").get().classpath })
    args("dev.aerove.lite.fixtures.NativeMigrationInteropFixtureTest")
    doFirst {
        val output = providers.systemProperty("nativeMigration.fixture").orNull
            ?: error("prepareNativeMigrationFixture requires -DnativeMigration.fixture")
        check(output.isNotBlank()) { "nativeMigration.fixture must not be blank" }
        systemProperty("nativeMigration.fixture", output)
    }
}


tasks.register<JavaExec>("prepareFreshRuntimeReplayFixture") {
    dependsOn("compileReleaseUnitTestKotlin")
    mainClass.set("dev.aerove.lite.flutter.FreshRuntimeReplayFixtureKt")
    classpath(providers.provider { tasks.named<Test>("testReleaseUnitTest").get().classpath })
    doFirst {
        val input = providers.systemProperty("freshRuntime.fixture").orNull
            ?: error("prepareFreshRuntimeReplayFixture requires -DfreshRuntime.fixture")
        check(input.isNotBlank()) { "freshRuntime.fixture must not be blank" }
        systemProperty("freshRuntime.fixture", input)
    }
}

tasks.register<JavaExec>("prepareNativeBuilderFixture") {
    dependsOn("compileReleaseUnitTestKotlin")
    mainClass.set("dev.aerove.lite.fixtures.NativeBuilderInteropFixtureKt")
    classpath(providers.provider { tasks.named<Test>("testReleaseUnitTest").get().classpath })
    doFirst {
        val output = providers.systemProperty("nativeBuilder.fixture").orNull
            ?: error("prepareNativeBuilderFixture requires -DnativeBuilder.fixture")
        check(output.isNotBlank()) { "nativeBuilder.fixture must not be blank" }
        check(!System.getenv("AEROVE_FILTER_HELPER").isNullOrBlank()) { "The real host filter helper is required" }
        systemProperty("nativeBuilder.fixture", output)
    }
}

tasks.register<JavaExec>("prepareResource89NativeFixture") {
    dependsOn("compileReleaseUnitTestKotlin")
    mainClass.set("dev.aerove.lite.fixtures.NativeResourceFixture89Kt")
    classpath(providers.provider { tasks.named<Test>("testReleaseUnitTest").get().classpath })
    doFirst {
        val output = file(providers.gradleProperty("resource89.output").get())
        check(output.mkdirs() || output.isDirectory)
        checkNotNull(System.getenv("AEROVE_FILTER_HELPER")) { "The real host filter helper is required" }
        val suffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val java = File(System.getProperty("java.home"), "bin/java$suffix").absolutePath
        output.resolve("profile-bridge.json").writeText(JsonOutput.toJson(mapOf(
            "executable" to java,
            "args" to listOf("-cp", classpath.asPath, "dev.aerove.lite.flutter.ProfileProtocolFixture89Kt"),
        )))
        setArgs(listOf(output.resolve("native").absolutePath, output.resolve("native-fixture.json").absolutePath))
    }
}
