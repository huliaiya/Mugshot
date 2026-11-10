plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mugshot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.Mugshot.huli"
        minSdk = 26
        targetSdk = 35
        versionCode = 6

        // 版本号 = 构建时的 Git 短哈希（CI 传 -PgitHash=<sha8>，本地默认 dev）
        val gitHash = (project.findProperty("gitHash") as String?)?.takeIf { it.isNotBlank() } ?: "dev"
        versionName = gitHash
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")

        // 更新检查用的发布版本号（tag 构建传 -PgitTag=<x.y.z>，非 tag 构建为 0.0.0）
        val gitTag = (project.findProperty("gitTag") as String?)?.takeIf { it.isNotBlank() } ?: "0.0.0"
        buildConfigField("String", "GIT_TAG", "\"$gitTag\"")
    }

    signingConfigs {
        // 仓库内置发布签名（PKCS12），保证各版本签名一致，可覆盖安装
        create("release") {
            storeFile = file("mugshot-release.keystore")
            storeType = "PKCS12"
            storePassword = "mugshot2026"
            keyAlias = "com.Mugshot.huli"
            keyPassword = "mugshot2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    buildFeatures {
        buildConfig = true
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
