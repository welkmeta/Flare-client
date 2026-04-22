plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

layout.buildDirectory.set(file("build"))

android {
    namespace = "flare.client.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "flare.client.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.1.6"
        renderscriptTargetApi = 31
        renderscriptSupportModeEnabled = true

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("path")
            storePassword = "password"
            keyAlias = "release"
            keyPassword = "password"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }

        getByName("debug") {
            applicationIdSuffix = ".test"
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
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.blurview)
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.kix)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
