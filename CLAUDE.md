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

# Build release APKs
./gradlew assembleRelease

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
- **UI layer** (`app/.../ui/`): Jetpack Compose with Material 3, phone-only. Bottom navigation with 3 tabs: Nearby, Prices, Favourites. Detail screen overlays without bottom bar. ViewModels expose `StateFlow<UiState>` with loading/data/error states.
- **Shared components** (`app/.../ui/components/`): Reusable `BarChart` and `LineChart` Compose Canvas composables, plus `FuelMapView` — a Google Maps Compose (`com.google.maps.android:maps-compose`) wrapper for the Nearby and Detail screens. Requires `MAPS_API_KEY` in `local.properties`.
- **Fuel type constants** (`FuelTypes` in `core/.../data/api/Models.kt`): Canonical list of all 6 fuel types (E10, E5, B7_STANDARD, B7_PREMIUM, B10, HVO) with short/long labels and colors. Use `FuelTypes.ALL`, `FuelTypes.shortLabel()`, `FuelTypes.longLabel()`, `FuelTypes.color()`. The phone UI should generally go through `fuelLabel()` (`app/.../ui/theme/FuelLabelStyle.kt`) instead of calling `shortLabel`/`longLabel` directly — it respects the user's short/long name preference (`UserPreferencesStore`, toggled on the Preferences screen) via `LocalUseLongFuelNames`, set once at the root in `Navigation.kt`.
- **User preferences** (`core/.../data/repository/UserPreferencesStore.kt`): DataStore-backed (same pattern as `TokenStore`) — preferred fuel type, MPG, tank capacity (litres), and the long-fuel-names toggle. Edited on the phone's Preferences screen (4th bottom nav tab); the car's `NearbyStationsScreen` reads MPG/tank capacity to sort by estimated net saving (see `FuelCostCalculator.estimateNetSavingsPounds()` in `core/.../util/`) instead of plain distance once both are set.
- **FCM** (`app/.../FcmService.kt`): Firebase Cloud Messaging service registered for price alerts (stub implementation). Phone-only, not present in `:automotive`.
- **Android Auto / Automotive OS** (`core/.../car/`): `FuelCarAppService` (`@AndroidEntryPoint CarAppService`, registered under the `POI` category) → `FuelCarSession` → `NearbyStationsScreen` (`PlaceListMapTemplate`) → `StationDetailScreen` (`PaneTemplate` + navigate action) / `DataNoticeScreen` (Fair Use compliance notice). The `<service>` declaration, car permissions, and `minCarApiLevel` all live in `core/src/main/AndroidManifest.xml` and merge into both `:app` and `:automotive`. `:app` adds `androidx.car.app:app-projected` for phone-projected Android Auto; `:automotive` adds `androidx.car.app:app-automotive` plus the library's `CarAppActivity` as its launcher entry (required on Automotive OS — there's no external host app the way there is for phone projection).

## Key Configuration

- **API base URL**: Set per build type in `core/build.gradle.kts` (`API_BASE_URL` BuildConfig field, consumed by `:core`'s `AppModule`). Debug defaults to `http://10.0.2.2:8000` (emulator localhost).
- **Maps**: Phone UI uses the Google Maps SDK (`FuelMapView`) — requires `MAPS_API_KEY=...` in `local.properties` (gitignored, never commit a real key) with billing enabled on the Google Cloud project. The car experience is unaffected — it uses the host-rendered map in `PlaceListMapTemplate`, no key needed there.
- **Firebase**: Requires `google-services.json` in `app/` for push notifications.
- **SDK targets**: compileSdk 35, targetSdk 35, JVM target 17. `:app` and `:core` use minSdk 29; `:automotive` requires minSdk 29 too (`androidx.car.app:app-automotive`'s floor). minSdk was raised from 26 to 29 app-wide to support this.
- **Testing the car experience**: `:app` (with `:core`) can be tested via the Desktop Head Unit against a real/projected Android Auto phone. `:automotive` can be deployed straight to the Android Automotive OS emulator (Tools → AVD Manager → Automotive hardware profile) — no phone or DHU needed; set Run configuration's Launch Option to "Nothing" since the OS launches the app via `CarAppActivity`, not Studio.

## Fair Use Policy Compliance

The app must comply with the Aggregator Fair Use Policy:
- Display all prices unmodified with original timestamps
- Show the Gov discrepancy report link on every price screen
- Never filter or manipulate data to favour any supplier
- Include data attribution notice on all price views
