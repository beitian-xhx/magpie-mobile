plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cc.fplj.magpiemobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.fplj.magpiemobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4-models-ux"
        buildConfigField("String", "CORE_VERSION", "\"0.1.3188-mobile.3\"")
        buildConfigField("String", "GIT_SHA", "\"" + (System.getenv("GITHUB_SHA")?.take(8) ?: "local") + "\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { abortOnError = false }
}

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
