import hoshi.build.PreparePaddleOcrTask
import hoshi.build.PrepareSherpaTask
import java.io.File as JFile
import java.nio.file.Paths as JPaths
import java.util.Properties as JProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val rustProjectDir = file("src/main/rust/hoshiepub")
val uniffiOutDir = layout.buildDirectory.dir("generated/source/uniffi/main/kotlin").get().asFile
val rustDebugJniLibsDir = layout.buildDirectory.dir("jniLibs/debug").get().asFile
val rustReleaseJniLibsDir = layout.buildDirectory.dir("jniLibs/release").get().asFile
val isWindowsHost = System.getProperty("os.name").lowercase().contains("win")
val cargoExeName = if (isWindowsHost) "cargo.exe" else "cargo"
fun resolveCargo(): String {
    val candidates = listOfNotNull(
        System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }
            ?.let { JPaths.get(it, "bin", cargoExeName).toString() },
        System.getenv("HOME")?.takeIf { it.isNotBlank() }
            ?.let { JPaths.get(it, ".cargo", "bin", cargoExeName).toString() },
        System.getenv("USERPROFILE")?.takeIf { it.isNotBlank() }
            ?.let { JPaths.get(it, ".cargo", "bin", cargoExeName).toString() },
    )
    for (candidate in candidates) {
        if (JFile(candidate).isFile) return candidate
    }
    // Fall back to PATH lookup (rustup installers put cargo on PATH).
    return cargoExeName
}
val cargo = resolveCargo()
val sherpaOnnxArtifact = "com.k2fsa.sherpa:sherpa-onnx:${libs.versions.sherpaOnnx.get()}@aar"
val sherpaOnnxArchive by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val configuredNdkVersion = "29.0.14206865"
fun resolveAndroidNdkHome(): String {
    System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { return it }
    val localSdkDir: String? = try {
        val props = JProperties()
        val localProps = rootProject.file("local.properties")
        if (localProps.isFile) {
            localProps.inputStream().use { props.load(it) }
            props.getProperty("sdk.dir")?.takeIf { value: String -> value.isNotBlank() }
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
    val sdkRoots = listOfNotNull(
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() },
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() },
        localSdkDir,
        System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?.let { JPaths.get(it, "Android", "Sdk").toString() },
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            ?.let { JPaths.get(it, "AppData", "Local", "Android", "Sdk").toString() },
        System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            ?.let { JPaths.get(it, "Library", "Android", "sdk").toString() },
    )
    for (sdkRoot in sdkRoots) {
        val versioned = JPaths.get(sdkRoot, "ndk", configuredNdkVersion).toString()
        if (JFile(versioned).isDirectory) return versioned
    }
    // Legacy macOS fallback preserved for existing local setups.
    return "/opt/homebrew/share/android-ndk"
}
val androidNdkHome = resolveAndroidNdkHome()
val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseAbi = providers.gradleProperty("releaseAbi").getOrElse("arm64-v8a")
require(releaseAbi in listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull?.toIntOrNull()
if (providers.gradleProperty("releaseVersionCode").isPresent && releaseVersionCode == null) {
    throw GradleException("releaseVersionCode must be an integer.")
}
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val isReleaseSigningRequested = releaseSigningValues.any { !it.isNullOrBlank() }
val isReleaseSigningConfigured = releaseSigningValues.all { !it.isNullOrBlank() } &&
    releaseKeystorePath?.let { file(it).isFile } == true

if (isReleaseSigningRequested && !isReleaseSigningConfigured) {
    throw GradleException(
        "Release signing requires ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, " +
            "ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD, and the keystore file must exist."
    )
}

val hostLibExtension = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "dylib"
    System.getProperty("os.name").lowercase().contains("win") -> "dll"
    else -> "so"
}

android {
    namespace = "moe.antimony.hoshi"
    ndkVersion = configuredNdkVersion
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "moe.antimony.hoshi"
        minSdk = 26
        targetSdk = 36
        versionCode = 10400
        versionName = "1.4.0"
        releaseVersionCode?.let { versionCode = it }
        releaseVersionName?.let { versionName = it }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                targets += listOf("hoshidicts_jni")
            }
        }
    }

    if (isReleaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Hoshi Debug"
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appLabel"] = "Hoshi Reader"
            ndk {
                abiFilters += listOf(releaseAbi)
            }
            if (isReleaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        disable += "DirectSystemCurrentTimeMillisUsage"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    sourceSets["main"].java.directories.add(uniffiOutDir.absolutePath)
    sourceSets["debug"].jniLibs.directories.add(rustDebugJniLibsDir.absolutePath)
    sourceSets["release"].jniLibs.directories.add(rustReleaseJniLibsDir.absolutePath)
}

val prepareSherpa by tasks.registering(PrepareSherpaTask::class) {
    archive.set(layout.file(sherpaOnnxArchive.elements.map { it.single().asFile }))
    releaseBaseUrl.set("https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/transcription-sherpa-${libs.versions.sherpaOnnx.get()}-633c2432")
    outputDirectory.set(layout.buildDirectory.dir("generated/sherpa"))
    assetsDirectory.set(outputDirectory.dir("assets"))
}
val preparePaddleOcr by tasks.registering(PreparePaddleOcrTask::class) {
    detUrl.set("https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx")
    detSha256.set("d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e")
    detBytes.set(9880512L)
    recUrl.set("https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx")
    recSha256.set("5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634")
    recBytes.set(21159378L)
    recConfigUrl.set("https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.yml")
    recConfigSha256.set("ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1")
    recConfigBytes.set(150579L)
    assetPath.set("ocr/paddle")
    recConfigFileName.set("inference.yml")
    outputDirectory.set(layout.buildDirectory.dir("generated/paddleocr"))
    assetsDirectory.set(outputDirectory.dir("assets"))
}
// Manga-shelf PDFs: PP-OCRv6_manga v0.2 FP32 (tiny det + small rec fine-tuned on manga).
val paddleOcrMangaRevision = "8ee29e094b6aac77dd067c70805438782fa16ab0"
val preparePaddleOcrManga by tasks.registering(PreparePaddleOcrTask::class) {
    detUrl.set("https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/$paddleOcrMangaRevision/det/manga_det_v0.2.onnx")
    detSha256.set("d132078c46e292b226fb5a2ca52a7612ad319262dfdf493e7d8e3be435295978")
    detBytes.set(1816954L)
    recUrl.set("https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/$paddleOcrMangaRevision/rec/manga_rec_v0.2.onnx")
    recSha256.set("de12c84c63e62c80339e882e675983d886670dcb6f0147e1ed041afd6fa81888")
    recBytes.set(21167540L)
    recConfigUrl.set("https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/$paddleOcrMangaRevision/ppocrv6_dict.txt")
    recConfigSha256.set("b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d")
    recConfigBytes.set(74947L)
    assetPath.set("ocr/paddle-manga")
    recConfigFileName.set("dict.txt")
    outputDirectory.set(layout.buildDirectory.dir("generated/paddleocr-manga"))
    assetsDirectory.set(outputDirectory.dir("assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(preparePaddleOcr, PreparePaddleOcrTask::getAssetsDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(preparePaddleOcrManga, PreparePaddleOcrTask::getAssetsDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(prepareSherpa, PrepareSherpaTask::getAssetsDirectory)
}

dependencies {
    implementation(files(prepareSherpa.map { it.outputDirectory.file("bindings.jar").get() }))
    sherpaOnnxArchive(sherpaOnnxArtifact)
    implementation(libs.jsoup)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.color.utilities)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.ankidroid.api)
    implementation(libs.google.dagger.hilt.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.serialization.json)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    ksp(libs.androidx.hilt.compiler)
    ksp(libs.google.dagger.hilt.android.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("net.java.dev.jna:jna:${libs.versions.jna.get()}@jar")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val buildRustHost by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    outputs.file(rustProjectDir.resolve("target/debug/libhoshiepub.$hostLibExtension"))

    commandLine(cargo, "build", "--lib")
}

val generateUniffiKotlin by tasks.registering(Exec::class) {
    dependsOn(buildRustHost)
    workingDir = rustProjectDir

    val hostLibPath = rustProjectDir.resolve("target/debug/libhoshiepub.$hostLibExtension")

    inputs.file(hostLibPath)
    inputs.file(rustProjectDir.resolve("uniffi.toml"))
    inputs.file(rustProjectDir.resolve("Cargo.toml"))
    inputs.file(rustProjectDir.resolve("Cargo.lock"))
    outputs.dir(uniffiOutDir)

    commandLine(
        cargo,
        "run",
        "--features",
        "bindgen",
        "--bin",
        "uniffi-bindgen",
        "--",
        "generate",
        "--library",
        hostLibPath.absolutePath,
        "--config",
        rustProjectDir.resolve("uniffi.toml").absolutePath,
        "--language",
        "kotlin",
        "--out-dir",
        uniffiOutDir.absolutePath,
        "--no-format",
    )
}

val buildRustAndroidDebug by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    environment("ANDROID_NDK_HOME", androidNdkHome)
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.property("androidNdkHome", androidNdkHome)
    outputs.files(
        rustDebugJniLibsDir.resolve("arm64-v8a/libhoshiepub.so"),
        rustDebugJniLibsDir.resolve("x86_64/libhoshiepub.so"),
    )

    commandLine(
        cargo,
        "ndk",
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "-o",
        rustDebugJniLibsDir.absolutePath,
        "build",
        "--lib",
    )
}

val buildRustAndroidRelease by tasks.registering(Exec::class) {
    workingDir = rustProjectDir
    environment("ANDROID_NDK_HOME", androidNdkHome)
    inputs.files(
        rustProjectDir.resolve("Cargo.toml"),
        rustProjectDir.resolve("Cargo.lock"),
        rustProjectDir.resolve("uniffi.toml"),
    )
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.property("androidNdkHome", androidNdkHome)
    inputs.property("releaseAbi", releaseAbi)
    outputs.file(rustReleaseJniLibsDir.resolve("$releaseAbi/libhoshiepub.so"))

    commandLine(
        cargo,
        "ndk",
        "-t",
        releaseAbi,
        "-o",
        rustReleaseJniLibsDir.absolutePath,
        "build",
        "--lib",
        "--release",
    )
}

tasks.named("preBuild") {
    dependsOn(generateUniffiKotlin)
    dependsOn(prepareSherpa)
    dependsOn(preparePaddleOcr)
    dependsOn(preparePaddleOcrManga)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiKotlin)
    source(uniffiOutDir)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(buildRustHost)
    systemProperty("jna.library.path", rustProjectDir.resolve("target/debug").absolutePath)
}

afterEvaluate {
    tasks.named("preDebugBuild") {
        dependsOn(buildRustAndroidDebug)
    }
    tasks.named("preReleaseBuild") {
        dependsOn(buildRustAndroidRelease)
    }
}
