plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.shawady.vault.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shawady.vault"
        minSdk = 29
        targetSdk = 35
        versionCode = 27
        versionName = "1.7.0"
    }

    // A stable signing key is required for in-app updates (Android only installs an update signed by the same key).
    // CI provides it through the K2_KEYSTORE_* environment variables; without them the default debug key is used.
    val ksPath = System.getenv("K2_KEYSTORE_FILE")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("k2") {
                storeFile = file(ksPath)
                storePassword = System.getenv("K2_KEYSTORE_PASSWORD")
                keyAlias = "k2pass"
                keyPassword = System.getenv("K2_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { signingConfigs.findByName("k2")?.let { signingConfig = it } }
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":vault-core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
