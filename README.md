# Flight Map

A native Android window-seat companion. Enter a flight and ticket times, prepare on Wi-Fi, then explore an **estimated** journey offline. Kotlin, Jetpack Compose, Material 3 and MapLibre Native. Android 8.0+ (API 26).

![Design reference](docs/design/flight-map-design.png)

## Use

1. Install the debug APK from [Releases](https://github.com/alexeygrigorev/flight-map/releases). Android may ask permission to install from the browser/files app.
2. Enter a flight number and departure date. AY87 has a bundled HEL–PVG schedule fallback; other flights use manual entry. Search airport codes or names. Confirm **both local dates and times against your ticket**, including the arrival date. Airport time zones are shown below the fields.
3. Tap **Prepare for flight** while connected. Keep the app open until the route, cities, weather and map all have checks. Forecast dates must be within Open-Meteo's forecast window (approximately 16 days). Partial failures are shown explicitly and can be retried.
4. Open the saved flight, then enable airplane mode. In-flight mode reads only saved data and disables MapLibre networking. The pack survives app restarts.
5. Explore the map, nearby city, local time and forecast, or open **Along the route**. **Preview journey** moves through the scheduled flight; the PREVIEW badge and simulated time distinguish it from current-time mode.

The position moves at constant elapsed fraction of **scheduled block time** along a spherical great circle. It is not live tracking. Actual routes, restricted airspace, taxi time, delays, speed and winds can put the aircraft far from the displayed estimate. Heading and distances follow that same model; ETA is the ticket arrival time.

## Offline contents

- Route and schedule; 4,568 bundled medium/large IATA airports.
- All GeoNames cities15000 within 180 km of the route segment, ordered along the route; IANA time zones and city population.
- Hourly Open-Meteo temperature and weather code per corridor city, covering the scheduled dates. Saved forecasts are timestamped, reused for up to three hours during preparation, and never described as live weather.
- MapLibre offline **vector** corridor at zoom 0–5, using OpenFreeMap. Downloads stop below 3,000 tiles / 50 MB, with at most four parallel requests. The map cannot zoom beyond its saved detail. A permanently bundled Natural Earth outline remains available if a corridor download fails.

Readiness requires a verified complete native offline region and saved forecasts for the cities. Incomplete packs can still be opened with a **PARTIAL OFFLINE** indicator. Weather outside the saved hours displays unavailable. Failed/cancelled region resources are removed, and a newly saved flight replaces the previous region. The MVP saves one flight at a time.

## Build and test

JDK 21, Android SDK platform 35/build tools 35, and network for initial Gradle dependency resolution:

```sh
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :core:test :app:assembleDebug :app:lintDebug
# app/build/outputs/apk/debug/app-debug.apk
```

`core` is plain Kotlin/JVM: spherical distance/interpolation/bearing, bounded corridor selection, scheduled timing and strict weather parsing. JUnit tests cover poles, antipodes, date-line crossings, pre-/post-flight timing, invalid schedules and malformed forecasts.

`app` contains Compose UI, a ViewModel, atomic JSON pack persistence, batched weather downloads, native offline region creation/verification and a lifecycle-aware MapView. No GPS/location permissions, accounts, analytics or API keys. Preparation sends city coordinates to Open-Meteo and tile requests to OpenFreeMap; no calls to those services are initiated by the in-flight UI.

Dataset validation and regeneration:

```sh
python3 tools/update_data.py --verify-only
python3 tools/update_data.py  # refresh public source datasets
```

A smoke script exercises a **dedicated** emulator, prepares the default HEL–PVG example, disables connectivity, cold-restarts, checks the restored pack and captures screenshots:

```sh
python3 tools/emulator_smoke.py --serial emulator-5580 --shots /tmp/flight-map-shots
```

It changes connectivity on that emulator; use an isolated AVD. It requires ADB and Python 3, and the app already installed. Unit tests require no emulator/network once dependencies are cached.

## Sources and next steps

[Attribution, licenses and service policies](docs/DATA-SOURCES.md). [Generated design assets](docs/design/README.md).

The fallback timetable is advisory, not a date-verified booking. Next: optional keyed flight-provider adapters, background preparation with foreground notifications, more saved flights, exact time-zone boundaries for airports near borders, accessibility/font-scale testing on physical devices, and a release signing process. The debug APK is intended for personal evaluation, not a Play Store release. Builds run locally on Hetzner; no GitHub Actions required.
