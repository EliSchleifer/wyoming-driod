plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.wyomingdroid"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.wyomingdroid"
        // LineageOS 15.1 == Android 8.1 == API 27. minSdk 26 lets us use
        // adaptive icons and notification channels with no legacy fallbacks.
        minSdk = 26
        // Kept at 28 on purpose: targeting 29+ would pull in the stricter
        // foreground-service-type enforcement that only matters on newer
        // Android than the target device, and complicates a sideloaded build.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
