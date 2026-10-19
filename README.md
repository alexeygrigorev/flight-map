# Flight Map

A native Android window-seat companion. Enter a flight and ticket times, prepare on Wi-Fi, then explore an **estimated** journey offline. Kotlin, Jetpack Compose, Material 3 and MapLibre Native. Android 8.0+ (API 26).

![Design reference](docs/design/flight-map-design.png)

## Use

1. Install the **release** APK from [Releases](https://github.com/alexeygrigorev/flight-map/releases) (signed; preferred). A debug APK may also be listed for development. Android may ask permission to install from the browser/files app.
2. Enter a flight number and departure date. AY87 has a bundled HEL–PVG schedule fallback; other flights use manual entry. Search airport codes or names. Confirm **both local dates and times against your ticket**, including the arrival date. Airport time zones are shown below the fields.
3. Tap **Prepare for flight** while connected. Preparation runs in a foreground service with a progress notification, so you can leave the app. You can pause and resume; interrupted work is saved for recovery after a restart. Wait for the readiness checks before enabling airplane mode. Forecast dates must be within Open-Meteo's forecast window (approximately 16 days). Partial failures are shown explicitly and can be retried.
4. Open the saved flight, then enable airplane mode. In-flight mode reads only saved data and disables MapLibre networking. Select a saved flight from the list; several packs can be retained and deleted independently. Packs survive app restarts.
5. Explore the map, nearby city, local time and forecast, or open **Along the route**. **Preview journey** moves through the scheduled flight; the PREVIEW badge and simulated time distinguish it from current-time mode.

The position moves at constant elapsed fraction of **scheduled block time** along a spherical great circle. It is not live tracking. Actual routes, restricted airspace, taxi time, delays, speed and winds can put the aircraft far from the displayed estimate. Heading and distances follow that same model; ETA is the ticket arrival time.

## Offline contents

- Route and schedule; 4,568 bundled medium/large IATA airports and 69,822 places with time zones.
- All GeoNames cities5000 within 180 km of the route segment, ordered along the route; IANA time zones and city population.
- Hourly Open-Meteo temperature and weather code for sampled corridor locations, covering the scheduled dates. Smaller places retain their names and local times even when no forecast was downloaded. Saved forecasts are timestamped, reused for up to three hours during preparation, and never described as live weather.
- MapLibre offline **vector** detail concentrated along a narrow route band and around airports, using OpenFreeMap. One aggregate budget per flight pack stays below 3,000 tiles / 50 MB, with at most four parallel requests. Detail varies with distance from the route; outside the saved band only the bundled overview is available. A permanently bundled Natural Earth outline remains available if a corridor download fails.

Readiness requires verified complete native offline regions and saved forecasts for the selected weather locations. Incomplete packs can still be opened with a **PARTIAL OFFLINE** indicator. Weather outside the saved hours displays unavailable. Paused and interrupted native regions are reused on resume. Saving a different flight preserves the other packs. Deleting a pack removes its regions while preserving any referenced by another pack or a pending preparation.

## Build and test

JDK 21, Android SDK platform 35/build tools 35, and network for initial Gradle dependency resolution:

```sh
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :core:test :app:assembleDebug :app:lintDebug
# app/build/outputs/apk/debug/app-debug.apk
```

### Release signing

Release builds are signed with a keystore that lives **outside** the repo. Never commit the keystore or passwords.

1. Create a PKCS12 keystore (once), e.g. `~/.flight-map/keys/flight-map-release.keystore`.
2. Put a gitignored `keystore.properties` at the repo root (also keep a copy under `~/.flight-map/`):

```properties
storeFile=/home/YOU/.flight-map/keys/flight-map-release.keystore
storePassword=...
keyAlias=flight-map-release
keyPassword=...
```

`storeFile` may be absolute or relative to the repo root. For PKCS12, `storePassword` and `keyPassword` must match.

3. Build the signed release APK:

```sh
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` (or all four `ANDROID_RELEASE_*` env vars for CI), `assembleRelease` / `packageRelease` fails closed instead of producing an unsigned APK. On the Hetzner build host the keystore is at `~/.flight-map/keys/flight-map-release.keystore` with properties at `~/.flight-map/keystore.properties` (symlink or copy into the worktree as `keystore.properties`).

`core` is plain Kotlin/JVM: spherical distance/interpolation/bearing, bounded corridor selection, scheduled timing and strict weather parsing. JUnit tests cover poles, antipodes, date-line crossings, pre-/post-flight timing, invalid schedules and malformed forecasts.

`app` contains Compose UI, a ViewModel, atomic JSON multi-pack and preparation-checkpoint persistence, foreground-service preparation, batched weather downloads, native offline region creation/verification and a lifecycle-aware MapView. No GPS/location permissions, accounts, analytics or API keys. Preparation sends city coordinates to Open-Meteo and tile requests to OpenFreeMap; no calls to those services are initiated by the in-flight UI.

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

The fallback timetable is advisory, not a date-verified booking. Next: exact time-zone boundaries for airports near borders, accessibility/font-scale testing on physical devices. Release APKs are signed for sideload evaluation; this is not yet a Play Store release. Builds run locally on Hetzner; no GitHub Actions required.

Additional v0.2 offline UI smoke (dedicated emulator only):

```sh
python3 tools/emulator_v02_smoke.py --serial emulator-5582 --shots /tmp/flight-map-v02-shots --offline-open
```

Android may suppress notifications if permission is denied; preparation still runs in the foreground-service task manager. Android force-stop intentionally prevents automatic service restart: reopen the app and tap **Resume preparation**. Reboot recovery follows Android background-service restrictions; a saved checkpoint remains available for resume.

Recovery and multi-pack verification (destructive to the dedicated emulator's Flight Map test data):

```sh
python3 tools/emulator_v02_recovery.py --serial emulator-5582 --shots /tmp/flight-map-v02-shots --evidence /tmp/flight-map-v02-validation
python3 tools/emulator_v02_packs.py --serial emulator-5582 --shots /tmp/flight-map-v02-shots --evidence /tmp/flight-map-v02-validation
```

The recovery check sends preparation to Home, kills the process, checks sticky foreground-service restart, pauses, reboots the dedicated emulator, and resumes the same native region IDs. The pack check prepares a second route, restores both in airplane mode, deletes the newest pack, and verifies only its native regions were removed.
