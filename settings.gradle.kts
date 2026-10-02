// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    // Use the version catalog to manage dependencies and plugins.
    // The version catalog is defined in `gradle/libs.versions.toml`.
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    repositories {
        mavenCentral()
        google()
    }
}

// Include the `backdrop` subproject in the build.
include(":backdrop")

rootProject.name = "backdrop"
