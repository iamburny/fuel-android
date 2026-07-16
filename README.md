# Fuel Prices UK — Android App

Native Kotlin Android app for viewing UK fuel prices from the Government Fuel Finder scheme.

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Hilt** for dependency injection
- **Retrofit** + **Kotlinx Serialization** for networking
- **Room** for offline caching
- **Google Maps SDK** (`maps-compose`) for map display — requires an API key
- **Firebase Cloud Messaging** for price drop alerts
- **DataStore** for auth token persistence and user preferences
- **Car App Library** for Android Auto (phone projection) and Android Automotive OS (in-car systems)

## Setup

1. Open in Android Studio (Ladybug or newer)
2. Set your backend URL in `core/build.gradle.kts` (`API_BASE_URL`)
3. Add `MAPS_API_KEY=your-key-here` to `local.properties` (gitignored) — needs a Google Cloud project with the Maps SDK for Android enabled and billing turned on
4. For push notifications, add `google-services.json` from Firebase Console
5. Build and run

## Architecture

Three Gradle modules:

```
app/          → Phone application: Compose UI, MainActivity, FCM, Android Auto phone-projection
  ui/
    screens/    → Compose screens (Nearby map, Prices, Favourites, Preferences, Station detail)
    components/ → Shared BarChart/LineChart/FuelMapView composables
    theme/      → Material 3 theming

core/         → Shared library (consumed by :app and :automotive)
  data/
    api/        → Retrofit service + DTOs (mirrors backend REST API)
    db/         → Room database for offline cache
    repository/ → Single source of truth (API-first, Room fallback)
  di/           → Hilt modules
  util/         → Location helper
  car/          → Car App Library screens (Android Auto / Automotive OS POI experience)

automotive/   → Separate installable APK for Android Automotive OS (in-car systems)
```

## Fair Use Policy Compliance

Per the Aggregator Fair Use Policy, this app:

- Displays all prices unmodified with original timestamps
- Shows the Gov discrepancy report link on every price screen
- Never filters or manipulates data to favour any supplier
- Includes a data attribution notice on all price views
