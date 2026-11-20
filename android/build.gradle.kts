plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.2+ is required by the libmihomo-android AAR facade
    // (built with Kotlin 2.4 at language/API version 2.2).
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("com.google.devtools.ksp") version "2.2.0-1.0.26" apply false
}
