pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Lets Gradle auto-download a JDK 17 if app/build.gradle.kts's
// `kotlin { jvmToolchain(17) }` can't find one already installed/detected
// on your machine (e.g. if Android Studio's bundled JDK is a different
// version). Without this, a missing JDK 17 fails with "No matching
// toolchain" instead of just fetching one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "todo-ai"
include(":app")
