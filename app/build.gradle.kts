plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.nasir.videosaver"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.nasir.videosaver"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
