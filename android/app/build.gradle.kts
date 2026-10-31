plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pinepi.thinksmart.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pinepi.thinksmart.launcher"
        // Dedicated Android 8.1.0 appliance, distributed by local installation.
        minSdk = 27
        targetSdk = 27
        versionCode = 23
        versionName = "0.8.1"
    }

    buildFeatures { compose = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        // Intentional target for the dedicated Android 8.1.0 device.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Stable API-27 compatible UI stack; retain the existing SDK 35 / Kotlin 2.1 toolchain.
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
