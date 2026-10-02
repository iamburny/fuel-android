# FEATURES.md

A user-facing feature reference for **Fuel Tracker UK** (`uk.co.fuelprices`). This complements
[`CLAUDE.md`](CLAUDE.md), which covers architecture and build/config. This file answers
"**what can the app do?**" — the screens, the flows, and the behaviour a user experiences.

Source of truth: this document is derived from the code under `app/.../ui/`, `core/.../car/`,
and `core/.../data/`. When behaviour changes, update this file alongside the code.

---

## At a glance

Fuel Tracker UK shows live UK fuel prices from the Government Fuel Finder scheme across three
surfaces:

- **Phone app** (Compose) — the full experience: map, national trends, favourites, detail, settings.
- **Android Auto** (phone-projected) and **Android Automotive OS** (in-car) — a driver-safe,
  template-based nearby-stations experience via the Car App Library.

Six fuel types are supported throughout, each with a consistent brand colour used for pins,
chips, prices, and charts:

| Code | Short label | Long label | Colour |
|------|-------------|------------|--------|
| `E10` | E10 | Unleaded (E10) | green |
| `E5` | E5 | Super Unleaded (E5) | blue |
| `B7_STANDARD` | Diesel | Diesel (B7) | near-black |
| `B7_PREMIUM` | Super Diesel | Premium Diesel (B7) | dark grey |
| `B10` | B10 | Biodiesel (B10) | purple |
| `HVO` | HVO | HVO Diesel | teal |

`E10` is the default fuel type everywhere. Whether the short or long label is shown is controlled
by a single app-wide "long fuel names" preference.

---

## Phone app

Bottom navigation with four tabs — **Nearby**, **Prices**, **Favourites**, **Settings** — plus a
**Detail** screen reached by tapping a station (no bottom bar while viewing it). The app launches
on Nearby. Each tab preserves its own state when switching.

### Nearby (map)

Find fuel stations near you on a live map, with a searchable/filterable list.

- **Live Google map** under the "Fuel Tracker UK" title bar, centred on your GPS at zoom 12. Until a location
  resolves, a centred spinner shows (no fallback-location flash). If GPS is unavailable — no fix,
  or location permission denied — falls back to a fixed default location (Oxford), and the map's
  "my location" blue dot is only enabled when location permission is actually granted (enabling it
  without the permission crashes the app).
- **Station pins** coloured per the selected fuel type, each labelled with the cheapest price of
  that fuel type at the station (e.g. "129.9p") or "No price."
- **Fuel-type pill** (top-right) always shows the current fuel type; tap to cycle through all six.
- **Drag to explore:** panning the map loads stations for the newly visible viewport (pins update
  to the dragged area). The bottom list stays anchored to your GPS location regardless of drag.
  The dragged-to position and zoom are remembered — opening a station's Detail screen and coming
  back restores the map exactly where you left it, rather than snapping back to GPS.
- **Recenter button** (bottom-left) appears once you've dragged away; tap to jump the camera back
  and restore the GPS pin set.
- **Search / filter panel** (toggled top-right): a fixed "Search by name, postcode, or brand"
  field (debounced, needs 2+ chars); a **Nearby / Cheapest** mode toggle (hidden while searching);
  a scrollable row of fuel-type filter chips; and the station list.
- **Station rows** show name, brand · distance · postcode, and a bold coloured cheapest price. Tap
  to open Detail.
- Header attribution: "Prices sourced from the UK Government Fuel Finder scheme…"
- **Behaviour notes:** changing fuel type in Nearby mode re-filters instantly (client-side); in
  Cheapest mode it re-fetches from the server. Default search radius is 10 miles.

### Prices (Prices & Trends)

National fuel-price statistics and historical trends.

- For the selected fuel type: **Average**, **Cheapest**, **Highest** (pence, one decimal), and
  **Stations** (count) stat cards.
- **"All Fuel Types"** grid: one card per fuel type showing its colour, average, and min–max range.
  Tapping a card selects that fuel type and reloads the trend chart.
- **"Price Trend"**: a **7d / 30d / 90d** range selector (default 30) and a line chart of average
  price over time, coloured by fuel type, with start/end dates and a "Range: X.Xp – Y.Yp" label.
- **"Report a price discrepancy"** button → opens the Gov Fuel Finder site in a browser.
- Data-source / Open Government Licence compliance notice.
- **Requires connectivity** — national stats and trends are never cached.

### Favourites

Your saved stations. **Requires sign-in.**

- **Not logged in:** prompt to "Sign in to use favourites" (save stations, get price-drop alerts).
- **Empty:** "No favourites yet — tap the heart icon on a station to add it here."
- **Populated:** rows with a fuel-type-coloured heart, "Station #<id>", the fuel label, and an
  "Alerts on" label when price-drop alerts are enabled for that favourite.
- **Interactions:** tap a row → Detail; **swipe left to remove** (reveals a red delete icon).

### Detail

Everything about one station, plus favouriting, directions, and history.

- Top bar: station name, back arrow, and a **favourite toggle** (filled/outline heart).
- **Mini map** (zoom 15) with a single marker at the station.
- **Info block:** brand; status chips — "Temporarily Closed", "Motorway Services", "Supermarket";
  full address; distance ("X.X miles away", computed client-side); and, if you've set MPG + tank
  capacity, an **"Est. £X.XX in fuel to get here"** one-way drive-cost estimate. A tappable phone
  number and a **"Get directions"** button (launches Google Maps navigation).
- **Current Prices:** every fuel type, cheapest first, each with the label, an unmodified
  "Reported: <timestamp>", a **coloured delta vs national average** ("+1.2p vs national avg" —
  green if at/below average, red if above), and the bold coloured price.
- **Amenities** chips (when present).
- **Opening Hours** table for the seven weekdays (today's row highlighted/bold), plus a bank
  holidays sub-list when available.
- **Price History (30 days):** a bar chart with the date range and a "X.Xp – Y.Yp" label.
- Discrepancy-report button + compliance notice.
- Favourite toggling, history, averages, and drive-cost are all best-effort (fail silently).

### Settings (Preferences)

Personal defaults used across the app. Everything **saves automatically** (a transient "Saved"
confirmation appears).

- **Your usual fuel** — fuel-type chips; the selection becomes the app-wide default fuel.
- **Long fuel names** toggle — show "Unleaded (E10)" instead of "E10" everywhere.
- **Your car** — **Average MPG** and **Tank capacity (litres)** fields. These unlock the drive-cost
  estimate (phone Detail) and the net-savings sorting (car app).

---

## Car app (Android Auto & Automotive OS)

A POI-style, driver-safe experience built on Car App Library templates. The session opens directly
onto the nearby-stations list — there is no home/menu screen. Flow:

**Nearby Stations** → (row) **Station Detail** · (action bar) **Preferences** → **Fuel Type Picker**

### Nearby Stations (root)

- A `PlaceListMapTemplate` — scrolling station list beside a host-rendered map anchored on your
  location. Title "Fuel Tracker UK."
- **Location gate:** if permission isn't granted, shows "Location Access Needed" with a
  **Grant Access** button (parked-only).
- Stations within a **15-mile radius**. Each row shows the brand/name, a native distance badge, a
  fuel-type-coloured price pin (whole pence, since pin labels can't fit a decimal), and a trailing
  price/savings label.
- **Adaptive sorting:**
  - Without MPG + tank capacity set: sorted **by distance**; trailing text is the usual fuel's
    price (falls back to cheapest, then "No price reported").
  - With MPG + tank capacity set (on the phone): sorted **by estimated net savings**; trailing text
    is "Save £X.XX (half tank, net)" or "Costs £X.XX more (net)".
- **Action bar:** **Refresh** and **Preferences**. The list auto-refreshes on return (cache-first),
  so changed prefs apply without a manual refresh.
- Loading/empty/error surface as the list's no-items message.

### Station Detail

- A `PaneTemplate`: address; per-fuel price rows (cheapest first) with a signed "vs national avg"
  delta line (shown regardless of MPG/tank prefs); a required **Data source** attribution row; and
  a **Navigate** action that launches the car's navigation app to the station.

### Preferences & Fuel Type Picker

- The car has **no pairing to the phone**, so it keeps its own local preferences. Editable in the
  car: **usual fuel** (via the single-choice Fuel Type Picker) and **long fuel names** (toggle).
- **MPG and tank capacity are read but not editable in the car** — they can only be set in the
  phone app. Savings-based sorting therefore only activates if you configured them on the phone.

### Deliberate car limitations

- **No web links** — Automotive OS blocks car apps from opening a browser, so there is no
  discrepancy-report link; reporting is phone-only. Inline data attribution remains.
- **Map is non-interactive** — host-rendered pins only; no pan/zoom/tappable pins (open a station
  via its list row). This is a Car App Library template constraint, not a bug.
- Pin labels are limited to whole pence; the row count is capped to the host's list limit.

---

## Data, accounts & offline

### Where data comes from

A single `FuelRepository` mediates a Retrofit REST API and a Room cache. Endpoints cover nearby
stations, map-bounds queries, station lookup, search, cheapest prices, national averages, price
history, national trends, auth (login/register), FCM token registration, favourites (list/add/
remove), and discrepancy reporting.

### Offline behaviour

- **Stations are cached** (Room, 24-hour TTL). Nearby and map-bounds queries are **cache-first**;
  on a network failure they fall back to cached stations. A single nearby fetch deliberately omits
  the fuel-type filter so the cache is populated for all fuel types.
- The **manual Refresh** button on Nearby bypasses the cache and forces a live network fetch.
- **Prices are never cached** — cheapest, averages, history, and trends require connectivity.
- Cached stations retain **full detail** — phone, county, second address line, amenities, opening
  hours, and the motorway/supermarket/closure flags are all persisted (amenities and opening hours
  as JSON columns), so an offline-served station's Detail screen looks the same as a fresh one.
- Distance is never stored — it's recomputed client-side (haversine) relative to your location.
- If station fetches fail to connect **repeatedly** (2+ back-to-back), the Nearby screen shows a
  graceful **"Can't reach the fuel price service"** banner with a **Retry** action, instead of a
  silently empty map. It clears automatically on the next successful fetch.

### Live location on Nearby

- The Nearby map tracks the device's **live GPS** via a continuous location stream: the Google
  "my location" blue dot is enabled, and the camera **follows** the user while they haven't dragged
  the map. Once the user pans/zooms away, following stops and the recenter (my-location) FAB
  appears; tapping it re-centres and resumes following.

### Accounts

- Email/password **login and registration**; the JWT is stored via DataStore. Registration does
  not auto-login. Sign-in is required only for **Favourites**.

### Price-drop alerts

- Favourites support a per-favourite **`notify_on_drop`** flag and an optional price threshold, and
  the app registers an **FCM token** with the backend for server-side alerting.
- Incoming price-drop pushes are **displayed as notifications** (`FcmService`) on the
  `price_alerts` channel (created in `FuelApp`). Tapping a notification **deep-links straight to
  that station's Detail screen** (via a `stationId` intent extra consumed in `MainActivity`/
  `Navigation`). `POST_NOTIFICATIONS` is requested at runtime on Android 13+.
- The notification is built defensively from a snake_case data payload — expected keys:
  `station_id`, `fuel_type`, `price_pence`, `station_name` (it falls back to the message's
  `notification` block if present). **Backend contract:** the `fuel-api` service must send this
  `data` payload for alerts to render with full detail and deep-linking.

### The value calculation (net savings / drive cost)

`FuelCostCalculator` powers the "is the detour worth it?" logic:

- **Drive cost (one-way):** cost-per-mile = `(pricePence/100 × 4.546 L/gal) / mpg`, × distance.
  Used for the phone Detail "Est. £X.XX in fuel to get here."
- **Net savings:** gross savings on filling **half a tank** at the station's price vs the national
  average, **minus** the round-trip fuel cost of driving there. Positive = the detour saves money.
  Used to sort the car app's nearby list. Requires MPG, tank capacity, station distance, the
  station's price for your fuel, and a national average — otherwise it's unavailable.

---

## Fair Use / compliance features

The app is built to comply with the Aggregator Fair Use Policy:

- Prices are shown **unmodified with original timestamps**; no filtering/manipulation to favour a
  supplier.
- **Data attribution** is present on every price view (phone: footer on price screens; car: inline
  "Data source" row on Station Detail).
- The **Gov discrepancy-report link** is provided on the phone (Prices and Detail screens). It is
  intentionally **absent in the car** because Automotive OS blocks browser launches — reporting is
  phone-only.
