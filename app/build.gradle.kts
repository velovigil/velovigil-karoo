plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.velovigil.karoo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.velovigil.karoo"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Karoo Extension SDK
    implementation("io.hammerhead:karoo-ext:1.1.8")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Glance for data field widgets
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}
