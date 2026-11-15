import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releasePropertiesFile = rootProject.file("keystore.properties")
val releaseProperties = Properties().apply {
    if (releasePropertiesFile.isFile) releasePropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "ro.camara.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "ro.camara.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "1.2.13"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        if (releasePropertiesFile.isFile) {
            create("release") {
                fun required(name: String): String = requireNotNull(releaseProperties.getProperty(name)?.takeIf(String::isNotBlank)) {
                    "Completează $name în android/keystore.properties."
                }
                storeFile = rootProject.file(required("storeFile"))
                keyAlias = required("keyAlias")
                require(!keyAlias.equals("androiddebugkey", ignoreCase = true)) { "Release-ul cere o cheie proprie." }
                storePassword = required("storePassword")
                keyPassword = required("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.icons)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlin.coroutines)
    implementation(libs.kotlin.json)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.android.core)
    implementation(libs.zxing)
    implementation(libs.litert)
    testImplementation(libs.junit)
    debugImplementation(libs.compose.ui.tooling)
}
