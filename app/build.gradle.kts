plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.danefinlay.ttsutil"
    compileSdk = 32
    buildToolsVersion = "32.0.0"

    defaultConfig {
        applicationId = "com.danefinlay.ttsutil"
        minSdk = 16
        targetSdk = 30
        versionCode = 8
        versionName = "4.0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.5.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("com.google.android.material:material:1.9.0")
    implementation("org.jetbrains.anko:anko-sdk15:0.9.1")
    implementation("org.jetbrains.anko:anko-support-v4:0.9.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21")
}
