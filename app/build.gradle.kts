plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.abrignoni.knowndatalogger"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.abrignoni.knowndatalogger"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    // Release signing reads the keystore from the builder's own ~/.gradle/gradle.properties, never
    // from the repo. Without those four settings a release build comes out unsigned
    // (app-release-unsigned.apk), never signed with the debug key.
    val storeFile = providers.gradleProperty("KDL_RELEASE_STORE_FILE").orNull
    val storePassword = providers.gradleProperty("KDL_RELEASE_STORE_PASSWORD").orNull
    val keyAlias = providers.gradleProperty("KDL_RELEASE_KEY_ALIAS").orNull
    val keyPassword = providers.gradleProperty("KDL_RELEASE_KEY_PASSWORD").orNull
    val canSign = listOf(storeFile, storePassword, keyAlias, keyPassword).all { !it.isNullOrBlank() }
    signingConfigs {
        if (canSign) create("release") {
            this.storeFile = file(storeFile!!)
            this.storePassword = storePassword
            this.keyAlias = keyAlias
            this.keyPassword = keyPassword
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
