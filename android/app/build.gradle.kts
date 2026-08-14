import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val repositoryRoot = rootDir.parentFile
val nativeNdkDirectory = androidComponents.sdkComponents.ndkDirectory
val generatedNativeOutput = layout.buildDirectory.dir("generated/native-libs")
val generatedProfileAssets = layout.buildDirectory.dir("generated/profile-assets")
val generatedLegalAssets = layout.buildDirectory.dir("generated/legal-assets")
val generatedOfflineLegalAssets = layout.buildDirectory.dir("generated/offline-legal-assets")
val generatedTranslationResources = layout.buildDirectory.dir("generated/android-translations/res")
val slicerRuntimeBuilder = repositoryRoot.resolve("native/slicer-runtime/build.sh")
val slicerRuntimeOutput = repositoryRoot.resolve(
    "build/native-slicer/output/arm64-v8a/libprusaslicer-jni.so",
)
val orcaProfileRoot = repositoryRoot.resolve(
    "build/native-slicer/source/app/src/main/cpp/orcaslicer/resources/profiles",
)
val profileCatalogGenerator = repositoryRoot.resolve("tools/generate_profile_catalog.py")
val offlineLicenseGenerator = repositoryRoot.resolve("tools/generate_offline_licenses.py")
val androidTranslationGenerator = repositoryRoot.resolve("tools/generate_android_translations.py")
val nativeLicensePolicy = repositoryRoot.resolve("tools/native_license_policy.py")
val slicerVersionsFile = repositoryRoot.resolve("native/slicer-runtime/versions.env")
val lockedSlicerVersions = slicerVersionsFile.readLines()
    .filter { line -> '=' in line && !line.trimStart().startsWith('#') }
    .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
val orcaEngineRevision = checkNotNull(lockedSlicerVersions["SLICER_ENGINE_COMMIT"]) {
    "SLICER_ENGINE_COMMIT is missing from ${slicerVersionsFile.absolutePath}"
}
val defaultAndroidStrings = projectDir.resolve("src/main/res/values/strings.xml")
val orcaTranslationRoot = repositoryRoot.resolve("localization/i18n")
val generatedProfileCatalog = generatedProfileAssets.map { it.file("profile_catalog_v29.bin") }
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

val buildSlicerRuntime = tasks.register<Exec>("buildSlicerRuntime") {
    group = "build"
    description = "Builds the pinned slicer runtime from source for arm64-v8a."
    workingDir(repositoryRoot)
    doFirst {
        environment("ANDROID_NDK_HOME", nativeNdkDirectory.get().asFile.absolutePath)
    }
    commandLine(slicerRuntimeBuilder.absolutePath)
    inputs.file(slicerRuntimeBuilder)
    inputs.file(repositoryRoot.resolve("native/slicer-runtime/versions.env"))
    inputs.file(repositoryRoot.resolve("native/slicer-runtime/runtime.patch"))
    inputs.dir(repositoryRoot.resolve("native/slicer-runtime/overlay"))
    inputs.file(repositoryRoot.resolve(".gitmodules"))
    inputs.property("androidNdkVersion", "28.2.13676358")
    outputs.file(slicerRuntimeOutput)
}

val prepareNativeRuntime = tasks.register<Sync>("prepareNativeRuntime") {
    group = "build"
    description = "Stages source-built 16 KB-compatible native libraries."
    dependsOn(buildSlicerRuntime)
    into(generatedNativeOutput.map { it.dir("arm64-v8a") })
    from(slicerRuntimeOutput)
    from(ndkSharedRuntime)
}

val generateOrcaProfileCatalog = tasks.register<Exec>("generateOrcaProfileCatalog") {
    group = "build"
    description = "Normalizes and validates the pinned OrcaSlicer profile catalog."
    dependsOn(buildSlicerRuntime)
    workingDir(repositoryRoot)
    commandLine(
        "python3",
        profileCatalogGenerator.absolutePath,
        orcaProfileRoot.absolutePath,
        generatedProfileCatalog.get().asFile.absolutePath,
        orcaEngineRevision,
    )
    inputs.file(profileCatalogGenerator)
    inputs.dir(orcaProfileRoot)
    inputs.property("profileSchemaVersion", 29)
    inputs.property("orcaRevision", orcaEngineRevision)
    outputs.file(generatedProfileCatalog)
    outputs.upToDateWhen {
        val expected = generatedProfileCatalog.get().asFile
        expected.parentFile.listFiles()?.none { candidate ->
            candidate != expected &&
                candidate.name.startsWith("profile_catalog_v") &&
                candidate.extension in setOf("json", "bin")
        } ?: true
    }
    doFirst {
        val expected = generatedProfileCatalog.get().asFile
        expected.parentFile.listFiles()?.filter { candidate ->
            candidate != expected &&
                candidate.name.startsWith("profile_catalog_v") &&
                candidate.extension in setOf("json", "bin")
        }?.forEach { obsolete ->
            check(obsolete.delete()) { "Could not remove obsolete profile catalog: $obsolete" }
        }
    }
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

fun registerDependencyInventory(variant: String) = tasks.register(
    "write${variant.replaceFirstChar { it.uppercase() }}DependencyInventory",
) {
    group = "verification"
    description = "Writes the resolved $variant runtime dependencies for release metadata."
    val inventory = layout.buildDirectory.file("reports/dependencies/$variant.txt")
    outputs.file(inventory)
    doLast {
        val coordinates = configurations
            .getByName("${variant}RuntimeClasspath")
            .incoming
            .resolutionResult
            .allComponents
            .mapNotNull { component ->
                (component.id as? ModuleComponentIdentifier)?.let { id ->
                    "${id.group}:${id.module}:${id.version}"
                }
            }
            .distinct()
            .sorted()
        val output = inventory.get().asFile
        output.parentFile.mkdirs()
        output.writeText(coordinates.joinToString(separator = "\n", postfix = "\n"))
    }
}

val debugDependencyInventory = registerDependencyInventory("debug")
val releaseDependencyInventory = registerDependencyInventory("release")

fun registerOfflineLicenseBundle(variant: String, dependencyInventory: TaskProvider<*>) =
    tasks.register<Exec>("generate${variant.replaceFirstChar { it.uppercase() }}OfflineLicenseBundle") {
        group = "build"
        description = "Packages reviewed $variant dependency licenses for offline viewing."
        dependsOn(buildRustNative, dependencyInventory)
        val inventory = layout.buildDirectory.file("reports/dependencies/$variant.txt")
        val output = generatedOfflineLegalAssets.map {
            it.file("$variant/legal/THIRD_PARTY_LICENSES.txt")
        }
        workingDir(repositoryRoot)
        commandLine(
            "python3",
            offlineLicenseGenerator.absolutePath,
            inventory.get().asFile.absolutePath,
            nativeNdkDirectory.get().asFile.absolutePath,
            output.get().asFile.absolutePath,
        )
        inputs.file(offlineLicenseGenerator)
        inputs.file(nativeLicensePolicy)
        inputs.file(repositoryRoot.resolve("THIRD_PARTY_NOTICES.md"))
        inputs.file(repositoryRoot.resolve("native/slicer-runtime/versions.env"))
        inputs.file(repositoryRoot.resolve("rust/duckyslicer-jni/Cargo.lock"))
        inputs.file(inventory)
        inputs.property("androidNdkVersion", "28.2.13676358")
        outputs.file(output)
    }

val generateDebugOfflineLicenseBundle =
    registerOfflineLicenseBundle("debug", debugDependencyInventory)
val generateReleaseOfflineLicenseBundle =
    registerOfflineLicenseBundle("release", releaseDependencyInventory)

val generateAndroidTranslations = tasks.register<Exec>("generateAndroidTranslations") {
    group = "build"
    description = "Generates exact Android translations from the inherited Orca catalogs."
    workingDir(repositoryRoot)
    commandLine(
        "python3",
        androidTranslationGenerator.absolutePath,
        defaultAndroidStrings.absolutePath,
        orcaTranslationRoot.absolutePath,
        generatedTranslationResources.get().asFile.absolutePath,
    )
    inputs.file(androidTranslationGenerator)
    inputs.file(defaultAndroidStrings)
    inputs.files(fileTree(orcaTranslationRoot) { include("*/OrcaSlicer_*.po") })
    inputs.property("orcaAndroidLocalePolicy", 1)
    outputs.dir(generatedTranslationResources)
}

val prepareOpenSourceNotices = tasks.register<Sync>("prepareOpenSourceNotices") {
    group = "build"
    description = "Packages the privacy policy, project license, and notices for offline viewing."
    into(generatedLegalAssets)
    from(repositoryRoot.resolve("PRIVACY.md")) {
        into("legal")
    }
    from(repositoryRoot.resolve("LICENSE.txt")) {
        into("legal")
        rename { "AGPL-3.0.txt" }
    }
    from(repositoryRoot.resolve("THIRD_PARTY_NOTICES.md")) {
        into("legal")
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildRustNative, generateAndroidTranslations, prepareOpenSourceNotices)
}

android {
    namespace = "com.ashcastle.duckyslicer"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.ashcastle.duckyslicer"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("duckyslicer.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("duckyslicer.versionName").orNull ?: "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "ORCA_ENGINE_REVISION",
            "\"$orcaEngineRevision\"",
        )
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    androidResources {
        localeFilters += listOf(
            "en", "ko", "ca", "cs", "de", "es", "fr", "hu", "it", "ja", "lt",
            "nl", "pl", "pt-rBR", "ru", "sv", "th", "tr", "uk", "vi", "zh-rCN",
            "zh-rTW",
        )
    }

    val releaseKeystoreFile = providers.environmentVariable("DUCKYSLICER_KEYSTORE_FILE").orNull
    val releaseStorePassword = providers.environmentVariable("DUCKYSLICER_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("DUCKYSLICER_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("DUCKYSLICER_KEY_PASSWORD").orNull
    val releaseSigningAvailable = listOf(
        releaseKeystoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            providers.gradleProperty("duckyslicer.debugApplicationIdSuffix").orNull
                ?.takeIf { it.isNotBlank() }
                ?.let { applicationIdSuffix = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.debugSymbolLevel = "FULL"
            signingConfig = signingConfigs.findByName("release")
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

    lint {
        // Generated Orca overlays are intentionally partial; missing mobile-only copy
        // falls back to the complete default resource and is checked by our verifier.
        disable += "MissingTranslation"
    }

    sourceSets.getByName("main").jniLibs.directories.apply {
        clear()
        add(generatedNativeOutput.get().asFile.absolutePath)
    }
    sourceSets.getByName("main").assets.directories.add(
        generatedProfileAssets.get().asFile.absolutePath,
    )
    sourceSets.getByName("main").assets.directories.add(
        generatedLegalAssets.get().asFile.absolutePath,
    )
    sourceSets.getByName("main").res.directories.add(
        generatedTranslationResources.get().asFile.absolutePath,
    )
    sourceSets.getByName("debug").assets.directories.add(
        generatedOfflineLegalAssets.get().dir("debug").asFile.absolutePath,
    )
    sourceSets.getByName("release").assets.directories.add(
        generatedOfflineLegalAssets.get().dir("release").asFile.absolutePath,
    )
    sourceSets.getByName("androidTest").assets.directories.add(
        repositoryRoot.resolve("tests/data/test_stl/ASCII").absolutePath,
    )
    sourceSets.getByName("androidTest").assets.directories.add(
        repositoryRoot.resolve("qualification/corpus").absolutePath,
    )

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.configureEach {
    if (name.contains("resources", ignoreCase = true) || name.contains("lint", ignoreCase = true)) {
        dependsOn(generateAndroidTranslations)
    }
    if (name.contains("assets", ignoreCase = true) || name.contains("lint", ignoreCase = true)) {
        dependsOn(generateOrcaProfileCatalog)
    }
    if (name.contains("debug", ignoreCase = true) &&
        (name.contains("assets", ignoreCase = true) || name.contains("lint", ignoreCase = true))
    ) {
        dependsOn(generateDebugOfflineLicenseBundle)
    }
    if (name.contains("release", ignoreCase = true) &&
        (name.contains("assets", ignoreCase = true) || name.contains("lint", ignoreCase = true))
    ) {
        dependsOn(generateReleaseOfflineLicenseBundle)
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20251224")
}
