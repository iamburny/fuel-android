# Fuel Prices UK — Android App

Native Kotlin Android app for viewing UK fuel prices from the Government Fuel Finder scheme.

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Hilt** for dependency injection
- **Retrofit** + **Kotlinx Serialization** for networking
- **Room** for offline caching
- **Google Maps Compose** for map display
- **Firebase Cloud Messaging** for price drop alerts
- **DataStore** for auth token persistence

## Setup

1. Open in Android Studio (Ladybug or newer)
2. Add your Google Maps API key in `AndroidManifest.xml`
3. Set your backend URL in `app/build.gradle.kts` (`API_BASE_URL`)
4. For push notifications, add `google-services.json` from Firebase Console
5. Build and run

## Architecture

```
data/
  api/        → Retrofit service + DTOs (mirrors backend REST API)
  db/         → Room database for offline cache
  repository/ → Single source of truth (API-first, Room fallback)
di/           → Hilt modules
ui/
  screens/    → Compose screens (Nearby map, Station detail)
  theme/      → Material 3 theming
util/         → Location helper
```

## Fair Use Policy Compliance

Per the Aggregator Fair Use Policy, this app:

- Displays all prices unmodified with original timestamps
- Shows the Gov discrepancy report link on every price screen
- Never filters or manipulates data to favour any supplier
- Includes a data attribution notice on all price views
