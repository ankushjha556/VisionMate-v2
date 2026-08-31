plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// (Historical note: if you ever read properties from local.properties here,
//  import java.util.Properties at the TOP of this file. Calling
//  `java.util.Properties()` inline fails with "Unresolved reference: util"
//  because the Kotlin DSL intercepts the bare `java` accessor.)

android {
    namespace = "com.ankushjha.visionmate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ankushjha.visionmate"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "2.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Convenience signing so `assembleRelease` produces an installable
            // APK out of the box. Replace with your own keystore before
            // publishing to Play Store.
            signingConfig = signingConfigs.create("release") {
                val ksFile = file("visionmate-release.keystore")
                if (ksFile.exists()) {
                    storeFile = ksFile
                    storePassword = "visionmate2026"
                    keyAlias = "visionmate"
                    keyPassword = "visionmate2026"
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Never compress model files — TFLite mmaps them directly from assets.
    androidResources {
        noCompress += listOf("tflite", "json", "txt")
    }
}

dependencies {
    // Core UI
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX — live camera feed + frame analysis
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // TensorFlow Lite — on-device inference (YOLO, MiDaS, CLIP, caption decoder)
    implementation(libs.tensorflow.lite)

    // ML Kit — on-device OCR (Latin + Devanagari/Hindi scripts), bundled = offline
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.devanagari)
}
