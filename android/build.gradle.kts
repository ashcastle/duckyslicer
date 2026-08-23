plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.test") version "9.3.1" apply false
    // 1.5 is the first line compatible with AGP 9's new DSL. Keep this build-only
    // plug-in pinned until the corresponding stable release is available.
    id("androidx.baselineprofile") version "1.5.0-rc01" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

val buildToolSecurityPins = mapOf(
    "org.apache.commons:commons-lang3" to "3.18.0",
    "org.apache.httpcomponents:httpclient" to "4.5.14",
    "org.apache.httpcomponents:httpmime" to "4.5.14",
)

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val identity = "${requested.group}:${requested.name}"
            when {
                requested.group == "io.netty" -> {
                    useVersion("4.1.137.Final")
                    because("align Android build tooling to the vulnerability-free Netty line")
                }
                requested.group == "org.bouncycastle" -> {
                    useVersion("1.84")
                    because("align Android build tooling to the vulnerability-free Bouncy Castle line")
                }
                identity in buildToolSecurityPins -> {
                    useVersion(buildToolSecurityPins.getValue(identity))
                    because("keep Android build tooling above its reviewed security floor")
                }
            }
        }
    }
}
