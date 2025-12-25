plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.project.stopwastingtime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.project.stopwastingtime"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api" // Add this line
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.compose.runtime:runtime-livedata:1.7.0-beta01")
    implementation("com.google.accompanist:accompanist-drawablepainter:0.34.0")
    // For creating tabs
    implementation("androidx.compose.material3:material3-android:1.2.1")
// Helps with creating mutable state lists for Compose
    implementation("androidx.compose.runtime:runtime-saveable:1.7.0-beta01")
    implementation("com.google.code.gson:gson:2.10.1")

    // For scheduling the daily reset task
    implementation("androidx.work:work-runtime-ktx:2.9.0")

// For making the background service lifecycle-aware
    implementation("androidx.lifecycle:lifecycle-service:2.9.2")

    // Add this line for Accompanist Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
}