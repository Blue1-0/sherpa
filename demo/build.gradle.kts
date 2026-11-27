plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.blue.voice.demo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.blue.voice.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "META-INF/**"
        }
    }
}

dependencies {
    implementation(libs.coreKtxLib)
    implementation(libs.lifecycleRuntimeLib)
    implementation(libs.activityComposeLib)
    implementation(platform(libs.androidxComposeBom))
    implementation(libs.composeUi)
    implementation(libs.composeUiGraphics)
    implementation(libs.composeUiToolingPreview)
    implementation(libs.composeMaterial3)

    implementation(project(":lib_asr"))
    implementation(project(":lib_tts"))
}
