import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val repositoryRoot = rootDir.parentFile
val nativeOutput = layout.projectDirectory.dir("src/main/jniLibs").asFile
val nativeNdkDirectory = androidComponents.sdkComponents.ndkDirectory

val buildRustNative = tasks.register<Exec>("buildRustNative") {
    group = "build"
    description = "Builds the Rust JNI library for arm64-v8a."
    workingDir(repositoryRoot.resolve("rust/duckyslicer-jni"))
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
        nativeOutput.absolutePath,
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
    outputs.file(nativeOutput.resolve("arm64-v8a/libduckyslicer.so"))
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
}
