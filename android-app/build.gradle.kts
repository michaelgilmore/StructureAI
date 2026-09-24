// Top-level build file — just declares plugin versions so app/build.gradle.kts
// can apply them without repeating a version. See ../docs/architecture.md §07
// for what this app is and why it's built this way.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Kotlin 2.0+ moved the Compose compiler into the Kotlin toolchain
    // itself — this plugin is what replaces the old
    // composeOptions.kotlinCompilerExtensionVersion mechanism. Required
    // whenever buildFeatures.compose = true with a 2.x Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("androidx.room") version "2.6.1" apply false
    // Version must track the Kotlin version above exactly — see
    // https://github.com/google/ksp/releases for the compatibility table
    // if you bump Kotlin later and this stops resolving.
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
