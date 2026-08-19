plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.habitamu.mouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.habitamu.mouse"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.7"
    }

    signingConfigs {
        // Every build - local or on CI - is signed with this one key. Android refuses to install
        // an update whose signature differs from the installed app, and a build machine that has
        // no key of its own generates a fresh debug key on every run, which broke updating.
        // This is a sideloading key for a personal app, not a Play Store upload key.
        create("shared") {
            storeFile = file("mouse-signing.jks")
            storePassword = "mousekeystore"
            keyAlias = "mouse"
            keyPassword = "mousekeystore"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
