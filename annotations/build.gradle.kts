plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)

    jvm()
    js {
        browser()
        nodejs()
    }
    // Apple targets only compile on macOS hosts; on other hosts Gradle skips them with a warning.
    iosArm64()
    iosSimulatorArm64()
    iosX64()
}
