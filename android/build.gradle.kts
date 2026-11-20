plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.2+ is required by the libmihomo-android AAR facade
    // (built with Kotlin 2.4 at language/API version 2.2).
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    // Kotlin 2.0+ requires the Compose Compiler Gradle plugin when
    // compose is enabled (the old kotlinCompilerExtensionVersion
    // setting in composeOptions no longer works).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    // Room 2.7.2 + KSP：Room 2.6.1 与 Kotlin 2.2.0 两头不兼容——
    // KSP 生成器对 suspend DAO 方法生成的 Continuation<Integer>
    // 与接口的 Continuation<? super Integer> 擦除冲突（运行 #33）；
    // KAPT 内嵌的 kotlinx-metadata-jvm 只支持 metadata 2.0.0，
    // 读不了 Kotlin 2.2.0 生成的 2.2.0（运行 #35）。
    // Room 2.7.2 修了这两处。Kotlin 不能降（AAR 要求 2.2+）。
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}
