plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.waterrower.tracker"
    compileSdk = 36

    val envVersionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull() ?: 1
    val envVersionName = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"

    val signingStoreFile = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
    val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    val hasReleaseSigning = signingStoreFile != null &&
        signingStorePassword != null &&
        signingKeyAlias != null &&
        signingKeyPassword != null &&
        file(signingStoreFile).exists()

    defaultConfig {
        applicationId = "com.waterrower.tracker"
        minSdk = 28
        targetSdk = 35
        versionCode = envVersionCode
        versionName = envVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.health.connect)

    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
