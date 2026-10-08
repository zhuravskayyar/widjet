plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.stoneclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.stoneclock"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").map(String::toInt).getOrElse(100)
        versionName = providers.gradleProperty("appVersionName").getOrElse("0.4.0")
        testInstrumentationRunner = "dev.stoneclock.WeatherSmokeInstrumentation"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        getByName("release") {
            // Keep the existing installed app identity. CI restores this key from an encrypted secret.
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
