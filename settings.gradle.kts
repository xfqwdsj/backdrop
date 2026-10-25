pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":backdrop")

// Non-published performance harnesses; library consumers depend only on :backdrop.
include(
    ":benchmarks:android-app",
    ":benchmarks:android-macro",
    ":benchmarks:jvm",
    ":benchmarks:scenarios",
)

rootProject.name = "backdrop"
