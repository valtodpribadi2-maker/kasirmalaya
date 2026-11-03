plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "id.my.vallsite.kasir"
    compileSdk = 34

    defaultConfig {
        applicationId = "id.my.vallsite.kasir"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Alamat yang dibuka aplikasi. Ganti di sini kalau URL kasir berubah.
        buildConfigField("String", "START_URL", "\"https://kasir.vallsite.my.id/kasir/\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Ditandatangani dengan kunci debug supaya APK release langsung bisa dipasang (sideload).
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
