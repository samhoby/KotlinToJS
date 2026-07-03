plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false

    id("org.sonarqube") version "7.3.1.8318"
    id("org.jetbrains.kotlinx.kover") version "0.9.8"
}

sonar {
    properties {
        property("sonar.projectKey", "samhoby_KotlinToJS")
        property("sonar.organization", "samhoby")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.coverage.jacoco.xmlReportPaths", "build/reports/kover/report.xml")
    }
}

dependencies {
    kover(project(":processor"))
}

subprojects {
    group = "io.github.samhoby"
}
