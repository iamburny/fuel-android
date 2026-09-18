# Fuel Tracker UK — Android App

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
2. Backend URL is set once in `core/build.gradle.kts` (`API_BASE_URL`) to `https://api.fueltracker.uk`, the deployed prod backend — debug builds included, so they run on real hardware. Edit that field by hand to point at a local `fuel-api`.
3. Add `MAPS_API_KEY=your-key-here` to `local.properties` (gitignored) — needs a Google Cloud project with the Maps SDK for Android enabled and billing turned on
4. For push notifications, add `google-services.json` from Firebase Console
5. Build and run

## Building a release / prod build

```bash
# Play Store artifact
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab (signed, prod backend, R8-minified)

# Sideloadable APK for on-device testing (Play does not accept this)
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Both are signed with the upload keystore described by `keystore.properties` (gitignored — see `keystore.properties.example`). Without that file the build falls back to the debug keystore so it still compiles; that fallback is **not** valid for Play. Verify before uploading:

```bash
keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
# Expect Owner: CN=Fuel Tracker UK, not CN=Android Debug
```

Bump `versionCode` in `app/build.gradle.kts` for every Play upload — Play rejects a versionCode it has already seen. See CLAUDE.md's "Release / prod builds" for the Maps-key and R8 caveats.

Install the **phone** app on a device: `adb install app-release.apk`. This is all you need to test the phone UI.

**Testing the projected car (Android Auto) experience:** a sideloaded Car App Library app **cannot** run on a real car head unit — Android Auto's "Unknown sources" setting does not apply to template apps; that's a platform restriction, not a config you can toggle around. Test it with the **Desktop Head Unit (DHU)** instead:

1. Install the phone app, and in Android Auto settings enable Developer settings (tap the version ~10×) + "Unknown sources", then tap **"Start head unit server"**.
2. Connect the phone to your computer (USB debugging on) and run:
   ```bash
   adb forward tcp:5277 tcp:5277
   "$ANDROID_HOME/extras/google/auto/desktop-head-unit"   # desktop-head-unit.exe on Windows
   ```
3. The app appears in the DHU's app launcher.

To run on an actual car head unit, the app must be published to a Google Play track and pass the Android Auto app-quality review.

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
