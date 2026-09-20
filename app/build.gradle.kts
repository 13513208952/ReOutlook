plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.reoutlook"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.reoutlook"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1-alpha"
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

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
