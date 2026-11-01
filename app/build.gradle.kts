plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing / versioning come from the environment (CI), see .github/workflows/release.yml.
// Without them, release builds fall back to the debug key and the default version.
val releaseKeystore = providers.environmentVariable("TELLO_KEYSTORE_FILE").orNull
val ciVersionCode = providers.environmentVariable("TELLO_VERSION_CODE").orNull?.toInt()
val ciVersionName = providers.environmentVariable("TELLO_VERSION_NAME").orNull

android {
    namespace = "com.drone.tellopilot"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.drone.tellopilot"
        minSdk = 30 // MediaFormat.KEY_LOW_LATENCY, WifiLock low latency
        targetSdk = 37
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("TELLO_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TELLO_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TELLO_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Side-loaded app: signed with the release key in CI, else the debug key so it can be installed directly.
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:control"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
}
