pluginManagement {
    // Versions live in gradle.properties so the build script has no magic strings.
    val kotlinVersion: String by settings
    val shadowVersion: String by settings

    plugins {
        kotlin("jvm") version kotlinVersion
        id("com.gradleup.shadow") version shadowVersion
    }

    repositories {
        // Maven Central is listed first on purpose: the plugin markers for the
        // Shadow plugin are published there too, and it is often reachable when
        // the Gradle Plugin Portal is not.
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "RegionPatch"
