# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fuel Prices UK — a native Kotlin Android app for viewing UK fuel prices from the Government Fuel Finder scheme. Package `uk.co.fuelprices`. Three-module Gradle project:

- **`:app`** — the phone application (Compose UI, MainActivity, FCM, Android Auto phone-projection).
- **`:core`** — Android library shared by `:app` and `:automotive`: data layer, DI, `util/`, and the `car/` package (Car App Library screens). Gradle namespace `uk.co.fuelprices.core` (deliberately distinct from `:app`'s `uk.co.fuelprices` to avoid `BuildConfig`/R-class collisions on the consumers' classpath — Kotlin package names under `uk.co.fuelprices.*` are unaffected).
- **`:automotive`** — separate installable APK for Android Automotive OS (in-car systems), built around the library-provided `CarAppActivity` bridging into `:core`'s `FuelCarAppService`. No Compose UI of its own.

## Build Commands

```bash
# Build all modules
./gradlew assembleDebug

# Build a specific module
./gradlew :app:assembleDebug
./gradlew :automotive:assembleDebug

# Build release APKs (see "Release / prod builds" below)
./gradlew assembleRelease
# Signed release phone APK lands at app/build/outputs/apk/release/app-release.apk

# Run all unit tests
./gradlew test

# Run a single test class
./gradlew testDebugUnitTest --tests "uk.co.fuelprices.SomeTestClass"

# Run instrumented tests (requires emulator/device)
./gradlew connectedAndroidTest

# Lint check
./gradlew lint

# Clean build
./gradlew clean
```

## Architecture

**MVVM** with an API-first data strategy and Room fallback for offline use.

- **Data layer** (`core/.../data/`): `FuelPricesApi` (Retrofit) defines all REST endpoints. `FuelRepository` is the single source of truth — calls the API first, caches results in Room, falls back to cache on network failure. `TokenStore` persists JWT via DataStore.
- **DI** (`core/.../di/AppModule.kt`): Hilt provides singletons for OkHttpClient (with JWT interceptor + debug logging), Retrofit, Room database, and the API service. Base URL comes from `:core`'s own `BuildConfig.API_BASE_URL` (set in `core/build.gradle.kts`, not `app/build.gradle.kts`).
- **UI layer** (`app/.../ui/`): Jetpack Compose with Material 3, phone-only. Bottom navigation with 4 tabs: Nearby, Prices, Favourites, Preferences (labelled "Settings"). Detail screen overlays without bottom bar. ViewModels expose `StateFlow<UiState>` with loading/data/error states.
  - **Nearby screen** (`ui/screens/map/`): map pins follow the user's dragged viewport (loaded via `FuelRepository.getStationsInBounds` → the backend's `GET /api/stations/bounds`), while the bottom station list stays anchored to GPS. The camera only auto-centres on GPS once, on the first fix (tracked by `cameraRecenterToken`); a `MyLocation` FAB (bottom-start, clear of the map's zoom controls) reappears whenever the user has dragged away, to recentre. Tapping the top-right fuel-type pill cycles through `FuelTypes.ALL`.
  - **Detail screen** (`ui/screens/detail/`): when MPG + tank capacity are set, shows an estimated one-way fuel cost to drive there (distance computed client-side via `haversineMiles` against current location, since `GET /api/stations/{id}` returns no `distance_miles`). Every price row shows a signed delta vs the national average for that fuel type (`DetailViewModel` fetches `getNationalAverages()` unconditionally).
- **Shared components** (`app/.../ui/components/`): Reusable `BarChart` and `LineChart` Compose Canvas composables, plus `FuelMapView` — a Google Maps Compose (`com.google.maps.android:maps-compose`) wrapper for the Nearby and Detail screens. Requires `MAPS_API_KEY` in `local.properties`. `FuelMapView` hoists camera control out to callers via a `recenterKey` (a one-shot jump trigger — never force-recentres on recomposition, so it doesn't fight dragging) and an `onCameraIdle: (LatLngBounds) -> Unit` callback that fires only after a genuine drag.
- **Fuel type constants** (`FuelTypes` in `core/.../data/api/Models.kt`): Canonical list of all 6 fuel types (E10, E5, B7_STANDARD, B7_PREMIUM, B10, HVO) with short/long labels and colors. Use `FuelTypes.ALL`, `FuelTypes.shortLabel()`, `FuelTypes.longLabel()`, `FuelTypes.color()`. The phone UI should generally go through `fuelLabel()` (`app/.../ui/theme/FuelLabelStyle.kt`) instead of calling `shortLabel`/`longLabel` directly — it respects the user's short/long name preference (`UserPreferencesStore`, toggled on the Preferences screen) via `LocalUseLongFuelNames`, set once at the root in `Navigation.kt`.
- **User preferences** (`core/.../data/repository/UserPreferencesStore.kt`): DataStore-backed (same pattern as `TokenStore`) — preferred fuel type, MPG, tank capacity (litres), and the long-fuel-names toggle. Edited on the phone's Preferences screen (4th bottom nav tab) or the car's own `CarPreferencesScreen`; the car's `NearbyStationsScreen` reads MPG/tank capacity to sort by estimated net saving (see `FuelCostCalculator.estimateNetSavingsPounds()` in `core/.../util/`) instead of plain distance once both are set. `FuelCostCalculator` also exposes `estimateDriveCostPounds()` (standalone one-way drive cost, used by the phone Detail screen) and a public `haversineMiles()` (shared distance helper, also used by `FuelRepository`).
- **FCM** (`app/.../FcmService.kt`): Firebase Cloud Messaging service registered for price alerts (stub implementation). Phone-only, not present in `:automotive`.
- **Android Auto / Automotive OS** (`core/.../car/`): `FuelCarAppService` (`@AndroidEntryPoint CarAppService`, registered under the `POI` category) → `FuelCarSession` → `NearbyStationsScreen` (`PlaceListMapTemplate`) → `StationDetailScreen` (`PaneTemplate` + navigate action; per-row national-average delta; inline "Data source" attribution row) or `CarPreferencesScreen` (`PaneTemplate` with a long-names `Toggle` + a "Change" action → `FuelTypePickerScreen`, a single-select `ListTemplate`). The car needs its own preferences screen because the standalone Automotive OS app has no pairing to the phone's DataStore. The `<service>` declaration, car permissions, and `minCarApiLevel` all live in `core/src/main/AndroidManifest.xml` and merge into both `:app` and `:automotive`. `:app` adds `androidx.car.app:app-projected` for phone-projected Android Auto; `:automotive` adds `androidx.car.app:app-automotive` plus the library's `CarAppActivity` as its launcher entry (required on Automotive OS — there's no external host app the way there is for phone projection).
  - **Car map is non-interactive by design**: `PlaceListMapTemplate`'s map is entirely host-rendered from `Place` metadata — no pan/zoom and no tappable pins are exposed by the template (verified against the Car App Library source). The driver opens a station by tapping its **list row**, not a pin. An interactive map would require switching to the navigation-category templates + a self-rendered map surface (`SurfaceCallback`), a deliberate non-goal for a POI app.
  - **No web links in the car**: Android Automotive OS denies templated car apps permission to launch a browser (`SecurityException` on `startActivity(ACTION_VIEW)`). There is therefore no discrepancy-report link on the car screens (it was removed); reporting is phone-only. `StationDetailScreen` still carries the required data-attribution notice inline.

## Key Configuration

- **API base URL**: Set per build type in `core/build.gradle.kts` (`API_BASE_URL` BuildConfig field, consumed by `:core`'s `AppModule`). Debug = `http://10.0.2.2:8000` (emulator → host localhost); release = `https://api.fueltracker.uk` (the deployed prod backend, see the `fuel-api` repo).
- **Maps**: Phone UI uses the Google Maps SDK (`FuelMapView`) — requires `MAPS_API_KEY=...` in `local.properties` (gitignored, never commit a real key) with billing enabled on the Google Cloud project. The car experience is unaffected — it uses the host-rendered map in `PlaceListMapTemplate`, no key needed there.
- **Firebase**: Requires `google-services.json` in `app/` for push notifications.
- **SDK targets**: compileSdk 35, targetSdk 35, JVM target 17. `:app` and `:core` use minSdk 29; `:automotive` requires minSdk 29 too (`androidx.car.app:app-automotive`'s floor). minSdk was raised from 26 to 29 app-wide to support this.
- **Testing the car experience**: `:app` (with `:core`) is tested via the **Desktop Head Unit (DHU)** against a physical Android-Auto phone that has the app installed — **not** on a real car head unit. This is a hard platform rule: Car App Library *template* apps **cannot be sideloaded onto a real head unit**; Android Auto's "Unknown sources" developer setting explicitly does not apply to template apps (only to media/messaging/parked apps). An unpublished template app appears on a physical car only after Play Store distribution + Android Auto app-quality review. DHU setup: phone Android Auto → Developer settings → "Start head unit server"; then `adb forward tcp:5277 tcp:5277` and run `<SDK>/extras/google/auto/desktop-head-unit(.exe)`. `:automotive` (Automotive OS) is different — it deploys straight to the Android Automotive OS emulator (Tools → AVD Manager → Automotive hardware profile), no phone or DHU needed; set the Run config's Launch Option to "Nothing" since the OS launches the app via `CarAppActivity`.

## Release / prod builds

`./gradlew :app:assembleRelease` produces a signed, installable APK at `app/build/outputs/apk/release/app-release.apk` (points at the prod backend, R8-minified).

- **Signing**: the release build type is signed with the **debug keystore** (`signingConfig = signingConfigs.getByName("debug")` in `app/build.gradle.kts`). This is deliberate for sideload testing on a real phone — the debug cert's SHA-1 matches the one the `MAPS_API_KEY` is registered against, so the map still renders. **This must be swapped for a real upload/release keystore before any Play Store distribution.**
- **R8 / ProGuard**: minification is on for release. `app/proguard-rules.pro` holds the kotlinx.serialization keep rules (without them the generated `$serializer` classes are stripped and every API response fails to deserialize at runtime — the APK installs but crashes), plus defensive rules for the API DTOs, the Retrofit interface, and the `car/` entry points. Always smoke-test a release build against a live backend after touching serialization or DI.
- **Sideloading**: `adb install app-release.apk` installs the **phone** app fine for testing the phone UI directly. It does **not** make the app appear on a real Android Auto car head unit — Car App Library template apps can't be sideloaded there (see "Testing the car experience" above); use the DHU, or publish to Play. Enabling Android Auto Developer settings + "Unknown sources" is still required for the DHU to load the unpublished build, just not sufficient for a physical head unit.

## Fair Use Policy Compliance

The app must comply with the Aggregator Fair Use Policy:
- Display all prices unmodified with original timestamps
- Never filter or manipulate data to favour any supplier
- Include a data attribution notice on all price views (phone: footer on price screens; car: inline "Data source" row on `StationDetailScreen`)
- Provide the Gov discrepancy-report link on price screens **on the phone** (Detail/Prices screens). The car app cannot open web links (Automotive OS blocks it), so the link is intentionally absent there — reporting is phone-only.
