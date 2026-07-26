import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
}

// Maps SDK API key — kept out of version control. Add MAPS_API_KEY=... to local.properties.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val mapsApiKey: String = localProperties.getProperty("MAPS_API_KEY") ?: ""
// Release uses a separately-restricted key (lock it to the Play app-signing SHA-1 + applicationId
// in Cloud Console). Falls back to the debug key if unset, so a release build still renders maps
// locally when you haven't configured a release key.
val mapsApiKeyRelease: String = localProperties.getProperty("MAPS_API_KEY_RELEASE") ?: mapsApiKey
// The Google Web OAuth client ID, used as `serverClientId` for Credential Manager sign-in. Add
// GOOGLE_WEB_CLIENT_ID=... to local.properties (gitignored). Empty until you create the OAuth client.
val googleWebClientId: String = localProperties.getProperty("GOOGLE_WEB_CLIENT_ID") ?: ""

// Upload/release signing — kept out of version control. Create keystore.properties (see
// keystore.properties.example) with storeFile/storePassword/keyAlias/keyPassword. When absent
// (e.g. a fresh clone, or CI without secrets) the release build falls back to the debug keystore
// so it still builds — but a Play-uploadable AAB requires the real keystore.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "uk.co.fuelprices"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.fueltracker.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4"

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Real upload keystore when configured (required for Play). Falls back to the debug
            // keystore so a release build still succeeds without keystore.properties (sideload
            // testing / fresh clones) — that fallback is NOT valid for Play distribution.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // Use the release-restricted Maps key for release builds (debug builds keep the
            // defaultConfig key above).
            manifestPlaceholders["MAPS_API_KEY"] = mapsApiKeyRelease
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
    // Shared data/di/util/car code
    implementation(project(":core"))

    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Maps (Google Maps SDK — requires MAPS_API_KEY in local.properties + billing enabled)
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:maps-compose:6.4.1")

    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Google Sign-In via Credential Manager (yields a Google ID token for the backend)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Android Auto phone projection (Car App Library)
    implementation("androidx.car.app:app-projected:1.7.0")
}
