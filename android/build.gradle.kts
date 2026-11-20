plugins {
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.2+ is required by the libmihomo-android AAR facade
    // (built with Kotlin 2.4 at language/API version 2.2).
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    // Kotlin 2.0+ requires the Compose Compiler Gradle plugin when
    // compose is enabled (the old kotlinCompilerExtensionVersion
    // setting in composeOptions no longer works).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    // Room 注解处理改用 KAPT，不用 KSP：Room 2.6.1 的 KSP 生成器对
    // suspend DAO 方法生成的 Java 实现类用 Continuation<Integer>，
    // 与接口声明的 Continuation<? super Integer> 擦除冲突、互不覆盖
    // （运行 #33 的 30 个 javac 错误全是这个原因）。KAPT 处理
    // Kotlin 源码，不生成 Java 实现类，没有这个问题。
    id("org.jetbrains.kotlin.kapt") version "2.2.0" apply false
}
