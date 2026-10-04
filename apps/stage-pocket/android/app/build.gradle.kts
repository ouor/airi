import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import java.net.URI
import java.util.Properties

val minSdkVersion: Int by rootProject.extra
val compileSdkVersion: Int by rootProject.extra
val targetSdkVersion: Int by rootProject.extra
val androidxAppCompatVersion: String by rootProject.extra
val androidxCoordinatorLayoutVersion: String by rootProject.extra
val coreSplashScreenVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val androidxJunitVersion: String by rootProject.extra
val androidxEspressoCoreVersion: String by rootProject.extra
val okhttpVersion: String by rootProject.extra
val orgJsonVersion: String by rootProject.extra
val onnxruntimeVersion: String by rootProject.extra
val sherpaOnnxVersion: String by rootProject.extra

val androidMinSdk = minSdkVersion
val androidCompileSdk = compileSdkVersion
val androidTargetSdk = targetSdkVersion
val appVersionProperties = Properties().apply {
    rootProject.file("app-version.properties").inputStream().use(::load)
}
val androidVersionName = appVersionProperties.getProperty("AIRI_VERSION_NAME")
    ?: error("AIRI_VERSION_NAME is missing in android/app-version.properties")
val androidVersionCode = appVersionProperties.getProperty("AIRI_VERSION_CODE")
    ?.toIntOrNull()
    ?: error("AIRI_VERSION_CODE is missing or invalid in android/app-version.properties")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


// NOTICE:
// sherpa-onnx (offline ASR) comes from its GitHub release instead of the JitPack AAR.
// The AAR ships a shared libonnxruntime.so with versioned symbols (ONNX Runtime 1.28.2) that collides
// with the one from onnxruntime-android (Supertonic TTS), so `libsherpa-onnx-jni.so` fails to load.
// The `android-static-link-onnxruntime` build links ONNX Runtime inside `libsherpa-onnx-jni.so`.
// The Kotlin API classes come from `classes.jar` in the release AAR of the same version.
// Remove this when sherpa-onnx publishes a static-link AAR to a Maven repository.
val sherpaOnnxDir = layout.buildDirectory.dir("sherpa-onnx/$sherpaOnnxVersion").get().asFile
val sherpaOnnxJniLibsDir = File(sherpaOnnxDir, "jniLibs")
val sherpaOnnxClassesJar = File(sherpaOnnxDir, "classes.jar")
val sherpaOnnxReleaseUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion"

val downloadSherpaOnnx by tasks.registering {
    description = "Downloads the sherpa-onnx static-link native libraries and Kotlin API classes."
    outputs.dir(sherpaOnnxDir)
    onlyIf { !sherpaOnnxClassesJar.exists() || !File(sherpaOnnxJniLibsDir, "arm64-v8a/libsherpa-onnx-jni.so").exists() }

    doLast {
        val archives = File(sherpaOnnxDir, "archives").apply { mkdirs() }

        fun download(name: String): File {
            val target = File(archives, name)
            if (!target.exists()) {
                logger.lifecycle("Downloading $name")
                val part = File(archives, "$name.part")
                URI("$sherpaOnnxReleaseUrl/$name").toURL().openStream().use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                part.renameTo(target)
            }
            return target
        }

        val nativeArchive = download("sherpa-onnx-v$sherpaOnnxVersion-android-static-link-onnxruntime.tar.bz2")
        val aar = download("sherpa-onnx-$sherpaOnnxVersion.aar")

        project.copy {
            from(project.tarTree(project.resources.bzip2(nativeArchive)))
            // The x86 build in this archive still needs a shared libonnxruntime.so.
            include("**/arm64-v8a/*.so", "**/armeabi-v7a/*.so", "**/x86_64/*.so")
            eachFile { relativePath = RelativePath(true, *relativePath.segments.takeLast(2).toTypedArray()) }
            includeEmptyDirs = false
            into(sherpaOnnxJniLibsDir)
        }
        project.copy {
            from(project.zipTree(aar))
            include("classes.jar")
            into(sherpaOnnxDir)
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(downloadSherpaOnnx) }

android {
    namespace = "ai.moeru.airi_pocket"
    compileSdk = androidCompileSdk

    defaultConfig {
        applicationId = "ai.moeru.airi_pocket"
        minSdk = androidMinSdk
        targetSdk = androidTargetSdk
        versionCode = androidVersionCode
        versionName = androidVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(sherpaOnnxJniLibsDir)
        }
    }
    androidResources {
        // Files and dirs to omit from the packaged assets dir, modified to accommodate modern web apps.
        // Default: https://android.googlesource.com/platform/frameworks/base/+/282e181b58cf72b6ca770dc7ca5f91f135444502/tools/aapt/AaptAssets.cpp#61
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

repositories {
    flatDir {
        dirs("../capacitor-cordova-android-plugins/src/main/libs", "libs")
    }
}

dependencies {
    implementation(fileTree(mapOf("include" to listOf("*.jar"), "dir" to "libs")))
    implementation("androidx.appcompat:appcompat:$androidxAppCompatVersion")
    implementation("androidx.coordinatorlayout:coordinatorlayout:$androidxCoordinatorLayoutVersion")
    implementation("androidx.core:core-splashscreen:$coreSplashScreenVersion")
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxruntimeVersion")
    implementation(files(sherpaOnnxClassesJar).builtBy(downloadSherpaOnnx))
    implementation(project(":capacitor-android"))
    testImplementation("junit:junit:$junitVersion")
    testImplementation("org.json:json:$orgJsonVersion")
    androidTestImplementation("androidx.test.ext:junit:$androidxJunitVersion")
    androidTestImplementation("androidx.test.espresso:espresso-core:$androidxEspressoCoreVersion")
    implementation(project(":capacitor-cordova-android-plugins"))
}

apply(from = "capacitor.build.gradle")

try {
    val servicesJson = file("google-services.json")
    if (servicesJson.isFile && servicesJson.length() > 0) {
        apply(plugin = "com.google.gms.google-services")
    }
} catch (error: Exception) {
    logger.info("google-services.json not found, google-services plugin not applied. Push Notifications won't work")
}
