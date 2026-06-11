plugins {
    kotlin("jvm") version "2.3.20"
}

dependencies {
    implementation(libs.ksp.symbol.processing.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(kotlin("test"))
    testImplementation("dev.zacsweers.kctfork:core:0.12.1")
    testImplementation("dev.zacsweers.kctfork:ksp:0.12.1")
    testImplementation("com.google.devtools.ksp:symbol-processing:2.3.4")
}

group = "pt"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}