plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arena.voxel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arena.voxel"
        minSdk = 24
        targetSdk = 34
        versionCode = 14
        versionName = "2.0.1"
    }

    /**
     * Stable signing key, committed to the repository.
     *
     * Before this the release APK was signed with the AGP debug key, which each CI runner
     * generates at random: Android then refuses to install the new APK over the previous one
     * (INSTALL_FAILED_UPDATE_INCOMPATIBLE) and every build looked like a "package conflict".
     * One fixed key = every future APK installs as an update.
     */
    val stableKeystore = rootProject.file("keystore/voxelcraft.p12")
    signingConfigs {
        if (stableKeystore.exists()) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = "voxelcraft"
                keyAlias = "voxelcraft"
                keyPassword = "voxelcraft"
                // PKCS12 is a standard Java keystore format, so no keytool step is needed.
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (stableKeystore.exists()) {
                signingConfigs.getByName("stable")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // Same applicationId as release on purpose: debug and release are the same app, so
            // one installs over the other instead of showing two icons on the launcher.
            signingConfig = if (stableKeystore.exists()) {
                signingConfigs.getByName("stable")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    androidResources {
        // WAV music must stay uncompressed so MediaPlayer can open it straight from the APK.
        noCompress.addAll(listOf("wav", "mp3", "ogg"))
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
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation(project(":modkit"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
