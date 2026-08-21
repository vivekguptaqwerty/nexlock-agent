import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// Release signing credentials: env vars take priority; local.properties is a fallback so you
// don't have to export them every session. local.properties is already git-ignored — never
// put real passwords in build.gradle.kts itself.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun signingProp(envName: String, localKey: String): String? =
    System.getenv(envName) ?: localProps.getProperty(localKey)

android {
    namespace = "com.nexlock.agent"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.nexlock.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.0.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = signingProp("AGENT_KEYSTORE_PATH", "AGENT_KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
            storeFile = file(keystorePath)
            storePassword = signingProp("AGENT_STORE_PASSWORD", "AGENT_STORE_PASSWORD")
            keyAlias = "upload"
            keyPassword = signingProp("AGENT_KEY_PASSWORD", "AGENT_KEY_PASSWORD")

            if (storePassword.isNullOrBlank() || keyPassword.isNullOrBlank()) {
                logger.warn(
                    "Release signing credentials not found. Set AGENT_STORE_PASSWORD and " +
                        "AGENT_KEY_PASSWORD as environment variables, or add them as plain " +
                        "key=value lines to local.properties (already git-ignored)."
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Networking & Serialization
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Firebase Cloud Messaging (Phase 3 — push-based command delivery)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Encrypted token storage (Phase 3 — Agent hardening)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Location tracking (Phase 4) — fused GPS/Wi-Fi/cell provider
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
