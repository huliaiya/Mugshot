plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mugshot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mugshot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // CI provides release signing via secrets; fall back to debug key locally.
        create("release") {
            val storePath = System.getenv("MUGSHOT_KEYSTORE") ?: ""
            if (storePath.isNotEmpty()) {
                storeFile = file(storePath)
                storePassword = System.getenv("MUGSHOT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MUGSHOT_KEY_ALIAS") ?: "mugshot"
                keyPassword = System.getenv("MUGSHOT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("MUGSHOT_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
