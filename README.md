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
2. Backend URL is set per build type in `core/build.gradle.kts` (`API_BASE_URL`): debug → `http://10.0.2.2:8000` (emulator → host localhost), release → `https://api.fueltracker.uk` (deployed prod backend)
3. Add `MAPS_API_KEY=your-key-here` to `local.properties` (gitignored) — needs a Google Cloud project with the Maps SDK for Android enabled and billing turned on
4. For push notifications, add `google-services.json` from Firebase Console
5. Build and run

## Building a release / prod APK

```bash
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk (signed, prod backend, R8-minified)
```

The release build is signed with the **debug keystore** so it installs via sideload for on-device testing, and its cert SHA-1 matches the one the Maps API key is registered against. Swap in a real upload keystore in `app/build.gradle.kts` before any Play Store release.

Install on a device: `adb install app-release.apk`.

**Testing the projected car experience on a real Android Auto head unit:** enable Android Auto **Developer settings** (tap the version number ~10× in Android Auto settings) and turn on **"Unknown sources"** — a sideloaded build won't otherwise appear on the head unit. The phone screens work regardless.

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
