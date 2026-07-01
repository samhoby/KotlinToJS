import org.gradle.plugin.compatibility.compatibility

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.1.1"
}

group = "io.github.samhoby"
version = "0.1.1"

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
}

gradlePlugin {
    website = "https://github.com/samhoby/kotlintojs"
    vcsUrl = "https://github.com/samhoby/kotlintojs.git"

    plugins {
        register("kotlinToJs") {
            id = "io.github.samhoby.kotlintojs"
            displayName = "KotlinToJS Plugin"
            description = "Generates @JsExport wrappers with automatic type conversion for Kotlin Multiplatform JS targets"
            tags = listOf("kotlin", "ksp", "js", "multiplatform", "annotation-processor")
            implementationClass = "io.github.samhoby.kotlintojs.gradle.KotlinToJsPlugin"

            compatibility {
                features {
                    configurationCache = false
                }
            }
        }
    }
}
