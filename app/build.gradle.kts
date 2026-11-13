plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cc.fplj.magpiemobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.fplj.magpiemobile"
        minSdk = 29
        targetSdk = 35
        versionCode = 47
        versionName = "0.1.46-workbuddy-qoder-minimal"
        buildConfigField("String", "CORE_VERSION", "\"0.1.3781-mobile.1\"")
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

    signingConfigs {
        create("fixedRelease") {
            storeFile = System.getenv("MAGPIE_SIGNING_STORE")?.let { file(it) }
            storePassword = System.getenv("MAGPIE_SIGNING_PASSWORD")
            keyAlias = "magpie"
            keyPassword = System.getenv("MAGPIE_SIGNING_PASSWORD")
            storeType = "JKS"
        }
    }
    buildTypes {
        getByName("release") {
            // This fork is distributed as a sideload build. Without a release
            // keystore, sign with the debug key so the APK stays installable.
            signingConfig = signingConfigs.getByName(
                if (!System.getenv("MAGPIE_SIGNING_STORE").isNullOrBlank()) "fixedRelease" else "debug"
            )
            isMinifyEnabled = false
            isDebuggable = false
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
    testOptions { unitTests.isIncludeAndroidResources = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
