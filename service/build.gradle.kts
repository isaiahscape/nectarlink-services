// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.nectarlink.services"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.nectarlink.services"
        minSdk = 26
        targetSdk = 37
        versionCode = (findProperty("services.versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("services.version") as String?) ?: "0.1.0"
    }

    val keystorePath = System.getenv("NECTARLINK_SERVICES_KEYSTORE")
        ?: System.getenv("NECTARLINK_KEYSTORE")
    val keystore = keystorePath?.let(::file)?.takeIf { it.exists() }
    val keystorePassword = System.getenv("NECTARLINK_SERVICES_KEYSTORE_PASSWORD")
        ?: System.getenv("NECTARLINK_KEYSTORE_PASSWORD")
    val keyAliasName = System.getenv("NECTARLINK_SERVICES_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "nectarlink"

    signingConfigs {
        if (keystore != null && !keystorePassword.isNullOrEmpty()) {
            create("release") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystore != null && !keystorePassword.isNullOrEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":client"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
