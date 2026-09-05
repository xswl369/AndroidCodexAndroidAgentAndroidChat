plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

repositories {
    maven("https://jitpack.io")
}

android {
    namespace = "com.xs.chat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xs.chat"
        minSdk = 23
        targetSdk = 37
          versionCode = 93
          versionName = "1.2.72"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 16KB page-size: 64-bit only (upstream 32-bit ABI libs are 4KB-aligned)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            // 签名材料优先级：环境变量签名，否则默认本地 debug keystore（与日常 debug 装机同证书，可覆盖升级）
            storeFile = file(
                System.getenv("XS_RELEASE_KEYSTORE")
                    ?: "${System.getProperty("user.home")}\\.android\\debug.keystore"
            )
            storePassword = System.getenv("XS_RELEASE_STORE_PASS") ?: "android"
            keyAlias = System.getenv("XS_RELEASE_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("XS_RELEASE_KEY_PASS") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.rhino)
    implementation(libs.luaj)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // 无线调试（ADB over Wi-Fi）内置组件
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.conscrypt:conscrypt-android:2.6.3")
    implementation("com.github.adaptech-cz:Tesseract4Android:4.8.0") {
        exclude(group = "com.github.adaptech-cz.Tesseract4Android", module = "tesseract4android-openmp")
    }
    implementation(files("libs/shizuku-api-12.2.0.aar"))
    implementation(files("libs/shizuku-provider-12.2.0.aar"))
    implementation(files("libs/shizuku-aidl-12.2.0.aar"))
    implementation(files("libs/shizuku-shared-12.2.0.aar"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}



























