import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.a11ylab.prototype"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.a11ylab.prototype"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Stamped at each Gradle sync/build so the overlay can show proof of which build is running.
        buildConfigField(
            "String",
            "BUILD_TIMESTAMP",
            "\"${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // MediaSessionCompat + MediaStyle notification, so the reading controls surface on the lock screen.
    implementation("androidx.media:media:1.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // On-device language detection, used to pick the right TTS voice for the read-screen feature.
    implementation("com.google.mlkit:language-id:17.0.6")
}
