import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val repositoryRoot = rootDir.parentFile
val nativeNdkDirectory = androidComponents.sdkComponents.ndkDirectory
val generatedNativeOutput = layout.buildDirectory.dir("generated/native-libs")
val bootstrapRuntime = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libprusaslicer-jni.so")
val ndkSharedRuntime = nativeNdkDirectory.map { ndk ->
    val prebuiltRoot = ndk.asFile.resolve("toolchains/llvm/prebuilt")
    val candidates = prebuiltRoot.listFiles()
        ?.filter { hostDirectory ->
            hostDirectory.resolve(
                "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
            ).isFile
        }
        .orEmpty()

    check(candidates.size == 1) {
        "Expected one Android NDK host toolchain under ${prebuiltRoot.absolutePath}, " +
            "found ${candidates.size}."
    }
    candidates.single().resolve(
        "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
    )
}

val prepareNativeRuntime = tasks.register<Sync>("prepareNativeRuntime") {
    group = "build"
    description = "Stages the slicer bootstrap with the pinned NDK's 16 KB-compatible C++ runtime."
    into(generatedNativeOutput.map { it.dir("arm64-v8a") })
    from(bootstrapRuntime)
    from(ndkSharedRuntime)
}

val buildRustNative = tasks.register<Exec>("buildRustNative") {
    group = "build"
    description = "Builds the Rust JNI library for arm64-v8a."
    workingDir(repositoryRoot.resolve("rust/duckyslicer-jni"))
    dependsOn(prepareNativeRuntime)
    doFirst {
        environment("ANDROID_NDK_HOME", nativeNdkDirectory.get().asFile.absolutePath)
    }
    commandLine(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "-P",
        "26",
        "-o",
        generatedNativeOutput.get().asFile.absolutePath,
        "build",
        "--release",
        "--locked",
    )
    inputs.files(
        fileTree(repositoryRoot.resolve("rust/duckyslicer-jni")) {
            include("Cargo.toml", "Cargo.lock", "build.rs", "src/**/*.rs")
        },
        fileTree(repositoryRoot.resolve("native/duckyslicer_bridge")) {
            include("include/**/*.h", "src/**/*.cpp")
        },
    )
    outputs.file(generatedNativeOutput.map { it.file("arm64-v8a/libduckyslicer.so") })
}

tasks.named("preBuild").configure {
    dependsOn(buildRustNative)
}

android {
    namespace = "com.ashcastle.duckyslicer"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.ashcastle.duckyslicer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    sourceSets.getByName("main").jniLibs.directories.apply {
        clear()
        add(generatedNativeOutput.get().asFile.absolutePath)
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20251224")
}
