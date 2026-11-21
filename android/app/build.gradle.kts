import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.laoda.proxy"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.laoda.proxy"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.1.5"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // v0.1.3 的教训：useLegacyPackaging = false 时，安装时不把 .so 解压到
    // /data/app/.../lib/arm64/，libmihomo 的 Clash.load(nativeLibDir)
    // 在该目录下找不到 libclash.so，启动就报 "Clash not loaded"。
    // 必须用 legacy 打包（安装时解压 .so），核心才能加载。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Mihomo core (prebuilt AAR, oviron/libmihomo-android v0.3.14 ->
// mihomo v1.19.34, bridgeABI 3). Downloaded at build time from
// GitHub Releases with SHA-256 verification, so the 47 MB binary
// is not committed to the repo.
// GPL-3.0: shipping this AAR obligates the app under GPL-3.0.
// NOTE: these declarations must come BEFORE the dependencies block,
// because the dependencies block references mihomoAar and
// downloadMihomo; Gradle Kotlin DSL evaluates the script top to
// bottom, so a reference to a val declared later is null.
val mihomoVersion = "0.3.14"
val mihomoSha256 = "7c3a68a4ef9afcb5018e167dc70fba7485652d0a0e29ba006703cf00fed9e230"
val mihomoAar = layout.buildDirectory.file("libs/libmihomo-android-v$mihomoVersion.aar")

// Download the Mihomo core AAR at build time (GitHub Actions has normal
// internet; the local VM's Java TLS is blocked by the egress proxy).
val downloadMihomo = tasks.register("downloadMihomo") {
    inputs.property("mihomoVersion", mihomoVersion)
    outputs.file(mihomoAar)
    doLast {
        val target = mihomoAar.get().asFile
        target.parentFile.mkdirs()
        val url =
            "https://github.com/oviron/libmihomo-android/releases/download/v$mihomoVersion/libmihomo-android-v$mihomoVersion.aar"
        target.outputStream().use { out ->
            uri(url).toURL().openStream().use { it.copyTo(out) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = target.inputStream().use { digest.digest(it.readBytes()) }
            .joinToString("") { "%02x".format(it) }
        require(actual == mihomoSha256) {
            "libmihomo-android AAR SHA-256 mismatch: expected $mihomoSha256, got $actual"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // themes.xml 的父主题 Theme.Material3.DayNight.NoActionBar 是
    // View 版 Material 库提供的 XML 资源，Compose Material3 不提供
    // XML 主题，所以必须显式依赖 com.google.android.material:material。
    implementation("com.google.android.material:material:1.12.0")
    // material3 只把 material-icons-core 声明为 runtime 依赖，Icons.Filled.* 编译时不可见，必须显式声明。
    implementation("androidx.compose.material:material-icons-core")
    // Folder / SwapHoriz 等图标不在 core 集里，在 extended 集里。
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation(files(mihomoAar).builtBy(downloadMihomo))
}
