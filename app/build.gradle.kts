plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cc.fplj.magpiemobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.fplj.magpiemobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-p0"
    }

    // P0-2 关键：ELF PIE 可执行载荷以“提取式”native 库打包，
    // 安装后从 applicationInfo.nativeLibraryDir 以绝对路径 exec。
    // keepDebugSymbols 避免 AGP strip 改写载荷。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/*.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
}
