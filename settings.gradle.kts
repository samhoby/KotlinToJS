rootProject.name = "KotlinToJS"

include(":annotations")
include(":processor")
include(":gradle-plugin")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
