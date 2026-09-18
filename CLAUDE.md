# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fuel Tracker UK (fueltracker.uk) — a native Kotlin Android app for viewing UK fuel prices from the Government Fuel Finder scheme. Package `uk.co.fuelprices` (unchanged from the original name). Three-module Gradle project:

- **`:app`** — the phone application (Compose UI, MainActivity, FCM, Android Auto phone-projection).
- **`:core`** — Android library shared by `:app` and `:automotive`: data layer, DI, `util/`, and the `car/` package (Car App Library screens). Gradle namespace `uk.co.fuelprices.core` (deliberately distinct from `:app`'s `uk.co.fuelprices` to avoid `BuildConfig`/R-class collisions on the consumers' classpath — Kotlin package names under `uk.co.fuelprices.*` are unaffected).
- **`:automotive`** — separate installable APK for Android Automotive OS (in-car systems), built around the library-provided `CarAppActivity` bridging into `:core`'s `FuelCarAppService`. No Compose UI of its own.

> **For a detailed, user-facing feature reference — every phone screen, the car experience, data/offline behaviour, accounts, and the net-savings/drive-cost logic — see [`FEATURES.md`](FEATURES.md).** This section covers architecture; `FEATURES.md` covers *what the app does*.

## Cross-platform parity & backward compatibility

This app has a SwiftUI sibling, `../fuel-ios` — the two are expected to move together. **Any
user-facing feature or bug fix here must land on `fuel-ios` too, in the same effort — not as a
follow-up**, even when the request only mentions Android/Kotlin-specific things. Before starting,
check `../fuel-ios` for the equivalent code path and current behavior; don't assume either platform
already has the fix — verify field by field (this app's `NearbyViewModel.kt` already had a
favourite-toggle race fix iOS was missing, but had the exact same missing re-entrancy guard as iOS
in `DetailViewModel.kt` — only checking both directly surfaced which was which). The car/Automotive
surfaces (`:automotive`, `core/.../car/`) are a deliberate exception — they have no iOS equivalent.

`fuel-api` (the shared backend) also serves `fuel-web` and every already-installed copy of this app
that hasn't updated yet — Play Store rollout means old APKs keep hitting the current backend for
weeks after a new release. Don't assume a backend contract change is safe just because this app's
current code handles it; see `../fuel-api/CLAUDE.md`'s backward-compatibility section, and keep this
app's own parsing tolerant of fields the backend might add or change later (a missing/unrecognized
field shouldn't crash deserialization — see the kotlinx.serialization keep-rules note under
"R8 / ProGuard" below).

## Build Commands

```bash
# Build all modules
./gradlew assembleDebug

# Build a specific module
./gradlew :app:assembleDebug
./gradlew :automotive:assembleDebug

# Build the Play Store artifact (see "Release / prod builds" below)
./gradlew :app:bundleRelease
# Signed AAB lands at app/build/outputs/bundle/release/app-release.aab

# Build a release APK instead — for sideload testing, not accepted by Play
./gradlew :app:assembleRelease
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
  - **Nearby screen** (`ui/screens/map/`): map pins follow the user's dragged viewport (loaded via `FuelRepository.getStationsInBounds` → the backend's `GET /api/stations/bounds`). The camera only auto-centres on GPS once, on the first fix (tracked by `cameraRecenterToken`); a `MyLocation` FAB (bottom-start, clear of the map's zoom controls) reappears whenever the user has dragged away, to recentre. Tapping the top-right fuel-type pill cycles through `FuelTypes.ALL`. The top-bar toggle button (a pound-in-circle icon, `R.drawable.ic_sterling_circle` — mirroring the iOS app's `sterlingsign.circle`; `contentDescription` "Cheapest prices") opens the single slide-up panel: while `searchQuery.length < 2` its list is the same stations currently pinned on the map, client-side sorted ascending by price for the selected fuel type via `NearbyUiState.cheapestSortedStations()` — no second network call, so it can never drift from what's actually on screen, and it re-tracks the viewport live after a drag. Stations with no price for the selected fuel type drop out of this list; an explicit empty state ("No nearby stations currently report a `<FuelType>` price.") covers the zero-results case. Once `searchQuery.length >= 2` the list swaps to server search results (`FuelRepository.searchStations`), unaffected by the above. Tapping any row (list or map pin) navigates to Detail — there is no separate "focus the pin" behaviour. "Report a price discrepancy" (`DataAttributionNotice`) is the last row of the list. A one-time coach-mark points at the toggle button on first appearance only, shown once ever (`UserPreferencesStore.hasSeenNearbyCheapestTooltip` / `markNearbyCheapestTooltipSeen()`), and only while the panel is closed. It's built on Material 3's `TooltipBox`/`TooltipState` (`isPersistent = true`) but with a custom `SpeechBubbleTooltip` in place of `PlainTooltip` — a speech-bubble `Surface` (`rememberSpeechBubbleShape()`) with a tail pointing up at the anchor, positioned *below* the button via a custom `PopupPositionProvider` (`rememberBelowAnchorTooltipPositionProvider()`; Material 3 only ships an above-with-below-fallback provider). That provider clamps the bubble inside the window, so a bubble anchored near a screen edge is *not* centred under its anchor — it therefore also publishes the anchor's centre, in popup-content coordinates, through a small `SpeechBubbleTailState`, and the shape draws the tail there rather than at the bubble's own midpoint (a centred tail points at empty map on any clamped bubble). The same pair backs the fuel-type pill's coach-mark; that pill is wrapped in `Box(Modifier.align(Alignment.TopEnd))` because `TooltipBox` passes its `modifier` to an *inner* anchor box, which silently swallows a `BoxScope.align` and leaves the pill top-left. It persists on screen until dismissed — tapping the bubble itself, tapping anywhere else (`Popup`'s outside-tap dismiss), or opening the panel — rather than auto-hiding after a timeout. The seen-flag is persisted on the tooltip's true→false visibility transition (observed via `snapshotFlow { tooltipState.isVisible }` in `NearbyScreen`), not merely on `TooltipState.show()` returning — persistent tooltips' `show()` never returns on a normal dismiss (Material3 1.3.1's `TooltipState.dismiss()` only flips its internal transition state; it doesn't resume `show()`'s suspended continuation), so observing `isVisible` directly is what makes "shown once ever" actually work end-to-end.
  - **Detail screen** (`ui/screens/detail/`): when MPG + tank capacity are set, shows an estimated one-way fuel cost to drive there (distance computed client-side via `haversineMiles` against current location, since `GET /api/stations/{id}` returns no `distance_miles`). Every price row shows a signed delta vs the national average for that fuel type (`DetailViewModel` fetches `getNationalAverages()` unconditionally).
- **Shared components** (`app/.../ui/components/`): Reusable `BarChart` and `LineChart` Compose Canvas composables, plus `FuelMapView` — a Google Maps Compose (`com.google.maps.android:maps-compose`) wrapper for the Nearby and Detail screens. Requires `MAPS_API_KEY` in `local.properties`. `FuelMapView` hoists camera control out to callers via a `recenterKey` (a one-shot jump trigger — never force-recentres on recomposition, so it doesn't fight dragging) and an `onCameraIdle: (LatLngBounds) -> Unit` callback that fires only after a genuine drag.
- **Fuel type constants** (`FuelTypes` in `core/.../data/api/Models.kt`): Canonical list of all 6 fuel types (E10, E5, B7_STANDARD, B7_PREMIUM, B10, HVO) with short/long labels and colors. Use `FuelTypes.ALL`, `FuelTypes.shortLabel()`, `FuelTypes.longLabel()`, `FuelTypes.color()`. The phone UI should generally go through `fuelLabel()` (`app/.../ui/theme/FuelLabelStyle.kt`) instead of calling `shortLabel`/`longLabel` directly — it respects the user's short/long name preference (`UserPreferencesStore`, toggled on the Preferences screen) via `LocalUseLongFuelNames`, set once at the root in `Navigation.kt`.
- **User preferences** (`core/.../data/repository/UserPreferencesStore.kt`): DataStore-backed (same pattern as `TokenStore`) — preferred fuel type, MPG, tank capacity (litres), and the long-fuel-names toggle. Edited on the phone's Preferences screen (4th bottom nav tab) or the car's own `CarPreferencesScreen`; the car's `NearbyStationsScreen` reads MPG/tank capacity to sort by estimated net saving (see `FuelCostCalculator.estimateNetSavingsPounds()` in `core/.../util/`) instead of plain distance once both are set. `FuelCostCalculator` also exposes `estimateDriveCostPounds()` (standalone one-way drive cost, used by the phone Detail screen) and a public `haversineMiles()` (shared distance helper, also used by `FuelRepository`).
- **FCM** (`app/.../FcmService.kt`): Firebase Cloud Messaging service for price-drop alerts. Registers the token with the backend and **displays notifications** on the `price_alerts` channel (created in `FuelApp.onCreate`); tapping one deep-links to the station's Detail via a `stationId` intent extra (`MainActivity` → `FuelApp(startStationId)` in `Navigation.kt`). Requests `POST_NOTIFICATIONS` at runtime on Android 13+. Expects a snake_case data payload (`station_id`, `fuel_type`, `price_pence`, `station_name`). Phone-only, not present in `:automotive`.
- **Android Auto / Automotive OS** (`core/.../car/`): `FuelCarAppService` (`@AndroidEntryPoint CarAppService`, registered under the `POI` category) → `FuelCarSession` → `NearbyStationsScreen` (`PlaceListMapTemplate`) → `StationDetailScreen` (`PaneTemplate` + navigate action; per-row national-average delta; inline "Data source" attribution row) or `CarPreferencesScreen` (`PaneTemplate` with a long-names `Toggle` + a "Change" action → `FuelTypePickerScreen`, a single-select `ListTemplate`). The car needs its own preferences screen because the standalone Automotive OS app has no pairing to the phone's DataStore. The `<service>` declaration, car permissions, and `minCarApiLevel` all live in `core/src/main/AndroidManifest.xml` and merge into both `:app` and `:automotive`. `:app` adds `androidx.car.app:app-projected` for phone-projected Android Auto; `:automotive` adds `androidx.car.app:app-automotive` plus the library's `CarAppActivity` as its launcher entry (required on Automotive OS — there's no external host app the way there is for phone projection).
  - **Car map is non-interactive by design**: `PlaceListMapTemplate`'s map is entirely host-rendered from `Place` metadata — no pan/zoom and no tappable pins are exposed by the template (verified against the Car App Library source). The driver opens a station by tapping its **list row**, not a pin. An interactive map would require switching to the navigation-category templates + a self-rendered map surface (`SurfaceCallback`), a deliberate non-goal for a POI app.
  - **No web links in the car**: Android Automotive OS denies templated car apps permission to launch a browser (`SecurityException` on `startActivity(ACTION_VIEW)`). There is therefore no discrepancy-report link on the car screens (it was removed); reporting is phone-only. `StationDetailScreen` still carries the required data-attribution notice inline.

## Key Configuration

- **API base URL**: Set once in `core/build.gradle.kts`'s `defaultConfig` (`API_BASE_URL` BuildConfig field, consumed by `:core`'s `AppModule`) to `https://api.fueltracker.uk` — the deployed prod backend, see the `fuel-api` repo. There are no per-build-type overrides: **debug builds hit prod too**, so they work on physical hardware. To run against a local `fuel-api`, edit that field by hand to `http://10.0.2.2:8000` (emulator → host localhost) or your LAN IP.
- **Maps**: Phone UI uses the Google Maps SDK (`FuelMapView`) — requires `MAPS_API_KEY=...` in `local.properties` (gitignored, never commit a real key) with billing enabled on the Google Cloud project. Release builds use `MAPS_API_KEY_RELEASE` instead, falling back to `MAPS_API_KEY` when unset — see "Release / prod builds". `local.properties` also carries `GOOGLE_WEB_CLIENT_ID` (Credential Manager sign-in) and `UNLEASH_CLIENT_KEY` (feature flags). The car experience is unaffected — it uses the host-rendered map in `PlaceListMapTemplate`, no key needed there.
- **Firebase**: Requires `google-services.json` in `app/` for push notifications.
- **SDK targets**: compileSdk 36, targetSdk 36, JVM target 17. `:app` and `:automotive` use minSdk 29 — `androidx.car.app:app-automotive`'s floor. `:core` stays at minSdk 26, since the shared data/DI code carries no such constraint.
- **Testing the car experience**: `:app` (with `:core`) is tested via the **Desktop Head Unit (DHU)** against a physical Android-Auto phone that has the app installed — **not** on a real car head unit. This is a hard platform rule: Car App Library *template* apps **cannot be sideloaded onto a real head unit**; Android Auto's "Unknown sources" developer setting explicitly does not apply to template apps (only to media/messaging/parked apps). An unpublished template app appears on a physical car only after Play Store distribution + Android Auto app-quality review. DHU setup: phone Android Auto → Developer settings → "Start head unit server"; then `adb forward tcp:5277 tcp:5277` and run `<SDK>/extras/google/auto/desktop-head-unit(.exe)`. `:automotive` (Automotive OS) is different — it deploys straight to the Android Automotive OS emulator (Tools → AVD Manager → Automotive hardware profile), no phone or DHU needed; set the Run config's Launch Option to "Nothing" since the OS launches the app via `CarAppActivity`.

## Release / prod builds

`./gradlew :app:bundleRelease` produces the Play Store artifact — a signed AAB at `app/build/outputs/bundle/release/app-release.aab`. `./gradlew :app:assembleRelease` produces a signed APK at `app/build/outputs/apk/release/app-release.apk` for sideload testing; Play does not accept it. Both point at the prod backend and are R8-minified.

- **Signing**: the release build type is signed with the upload keystore described by `keystore.properties` (gitignored — see `keystore.properties.example`; the key lives in `upload-keystore.jks`, alias `upload`, `CN=Fuel Tracker UK`). When that file is absent — a fresh clone, or CI without secrets — the build silently falls back to the debug keystore so it still compiles. **That fallback is not valid for Play**; check the signer before uploading:
  ```bash
  keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab
  # Expect Owner: CN=Fuel Tracker UK, not CN=Android Debug
  ```
- **Version codes**: Play rejects a versionCode it has already seen. Bump `versionCode` in `app/build.gradle.kts` for every upload, and keep `versionName` aligned with the iOS marketing version.
- **Maps key**: release builds use `MAPS_API_KEY_RELEASE` from `local.properties` (falling back to `MAPS_API_KEY` when unset). Restrict that key in Cloud Console against the **Play app-signing** SHA-1 and the `uk.fueltracker.app` applicationId — Play re-signs the AAB, so the upload cert's SHA-1 is not what the installed app presents, and a key restricted to it leaves the map blank in production. Note the consequence for sideload testing: a locally built release APK carries the **upload** cert, not the Play one, so add the upload SHA-1 (`keytool -list -v -keystore upload-keystore.jks`) to the same key's restrictions if you want the map to render in a sideloaded release build.
- **Build memory**: the release build needs the `MaxMetaspaceSize=1g` set in `gradle.properties`. At the 512m this project used previously it fails at `:app:hiltJavaCompileRelease` with a bare `Metaspace` error. The heap (`-Xmx2048m`) is unrelated and sufficient as-is.
- **R8 / ProGuard**: minification is on for release. `app/proguard-rules.pro` holds the kotlinx.serialization keep rules (without them the generated `$serializer` classes are stripped and every API response fails to deserialize at runtime — the APK installs but crashes), plus defensive rules for the API DTOs, the Retrofit interface, and the `car/` entry points. Always smoke-test a release build against a live backend after touching serialization or DI.
- **Sideloading**: `adb install app-release.apk` installs the **phone** app fine for testing the phone UI directly. It does **not** make the app appear on a real Android Auto car head unit — Car App Library template apps can't be sideloaded there (see "Testing the car experience" above); use the DHU, or publish to Play. Enabling Android Auto Developer settings + "Unknown sources" is still required for the DHU to load the unpublished build, just not sufficient for a physical head unit.

## Fair Use Policy Compliance

The app must comply with the Aggregator Fair Use Policy:
- Display all prices unmodified with original timestamps
- Never filter or manipulate data to favour any supplier
- Include a data attribution notice on all price views (phone: footer on price screens; car: inline "Data source" row on `StationDetailScreen`)
- Provide the Gov discrepancy-report link on price screens **on the phone** (Detail/Prices screens). The car app cannot open web links (Automotive OS blocks it), so the link is intentionally absent there — reporting is phone-only.
