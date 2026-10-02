plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

import java.util.Properties

// Unleash Frontend API client key — kept out of version control, same pattern as :app's
// MAPS_API_KEY/GOOGLE_WEB_CLIENT_ID. Lives here (not app/build.gradle.kts) because the Unleash
// client is provided as a Hilt singleton in this module's AppModule.kt, alongside Retrofit/Room —
// :app doesn't have its own BuildConfig visibility into :core's providers. A Frontend token is
// safe to ship client-side by Unleash's own design (unlike a Backend token), so this isn't as
// sensitive as the other local.properties secrets, but it's kept out of source control by the
// same convention regardless.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val unleashClientKey: String = localProperties.getProperty("UNLEASH_CLIENT_KEY") ?: ""

android {
    // Distinct from :app's / :automotive's namespace to avoid R-class/BuildConfig
    // collisions on their compile classpath; Kotlin package names are unaffected.
    namespace = "uk.co.fuelprices.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        // Backend API base URL — override per build variant
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000\"")
        // Self-hosted Unleash — public Frontend API endpoint (not secret, unlike the client key).
        buildConfigField("String", "UNLEASH_URL", "\"https://flags.burny.uk\"")
        buildConfigField("String", "UNLEASH_CLIENT_KEY", "\"$unleashClientKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.fueltracker.uk\"")
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
    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // api: StationDto.amenities exposes JsonElement directly, so consumers need it on their classpath too
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // Room (offline cache)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // DataStore (token storage)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Feature flags — self-hosted Unleash (Frontend API client, safe to embed client-side)
    implementation("io.getunleash:unleash-android:1.3.0")

    // Analytics (GA4 via Firebase) — lives here, not just :app, so AppAnalytics is injectable
    // from :core's own screens (e.g. the Android Auto UI) as well as :app's, same reasoning as
    // FeatureFlags above. google-services.json / the google-services plugin stay :app-only
    // (that's what ties the Firebase project to :app's applicationId); the SDK classes just need
    // to be on this module's classpath to be usable once Firebase initializes at process start.
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Car App Library — platform-agnostic core (Screen/Session/CarAppService/model.*).
    // Platform-specific artifacts (app-projected, app-automotive) are added by the
    // consuming :app / :automotive modules.
    implementation("androidx.car.app:app:1.7.0")

    // Compose UI graphics (FuelTypes.COLORS uses androidx.compose.ui.graphics.Color)
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui-graphics")
}
