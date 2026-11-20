plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.2+ is required by the libmihomo-android AAR facade
    // (built with Kotlin 2.4 at language/API version 2.2).
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    // Kotlin 2.0+ requires the Compose Compiler Gradle plugin when
    // compose is enabled (the old kotlinCompilerExtensionVersion
    // setting in composeOptions no longer works).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}
