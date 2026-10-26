# Locsim

English | [简体中文](README.zh-CN.md)

Locsim is an Android mock-location app built around a single fixed point. Its interaction model is inspired by [GoGoGo](https://github.com/ZCShou/GoGoGo), but the product is intentionally different:

- **The launch screen is the saved-places list**, so the app opens with the locations you care about.
- **The map opens only after tapping "Add place"**; there are no routes, joysticks, favorites folders, or history screens.
- The UI is built with **native Jetpack Compose + Material 3**.
- The map uses **osmdroid + OpenStreetMap** (OSM France HOT mirror), so no map API key is required. The map view is embedded in Compose with `AndroidView`.
- Mock positions are injected in **WGS-84** through Android `LocationManager` test providers. Root is not required.

> For learning, development, debugging, and location testing only. Target apps may detect mock locations through their own risk controls; follow their terms of service and applicable laws.

## Features

### Saved places (home)

- Empty state on first launch, with a shortcut to pick a place on the map.
- Shows name, address, and WGS-84 coordinates; tap a card or its play button to start mocking.
- Tap the active place again to stop.
- Context menu: edit on map, rename, delete.
- Deleting the active place stops the mock-location service automatically.

### Map picker

- Opens at the last viewed location, or at the edited place when editing.
- Tap the map to place the pin; long-press and drag the pin to fine-tune.
- The search bar supports place search and direct "latitude longitude" input.
- The "Coordinates" button accepts decimal WGS-84 coordinates.
- Zoom and "current location" controls on the right.
- Editable place name before saving, with automatic reverse geocoding.
- OpenStreetMap attribution is kept at the bottom of the map.

### Settings and offline maps

- Open Settings from the gear icon on the home screen.
- **Offline map packages**: choose the latest map position or a saved place as the center, pick a 2/5/10 km radius and a maximum zoom of z14–z17, and download OSM tiles into an internal osmdroid SQLite package.
- **Cache management**: inspect the online tile cache size, clear it with one tap, and list/delete downloaded offline packages.
- Downloaded packages are loaded automatically the next time the map opens, and keep working without a network connection.
- The settings screen also exposes location permission, system location, mock-location app status, and version information.

### Mock location

- Foreground service with an ongoing notification, so simulation can continue in the background.
- Writes both GPS and NETWORK test providers every second to reduce apps falling back to network positioning.
- Includes basic accuracy, altitude, and satellite fields.
- The home screen shows a setup checklist when permissions or system switches are missing, then resumes the pending simulation after setup.
- Stop from the UI, the notification action, or by deleting the active place; all test providers are cleaned up immediately.

## Getting started

1. Install and open Locsim, then grant location permission.
2. Open **Settings → About phone** and tap **Build number** repeatedly to enable Developer options.
3. Open **Settings → Developer options → Select mock location app** and choose **Locsim**.
4. Return to the app, tap **Add place**, pick a point on the map, and save it.
5. Back on the list, tap the place to start mocking.

Menu names vary slightly between OEM ROMs (for example "Select mock location app" or "Mock location app").

## Tech stack

| Layer | Implementation |
| --- | --- |
| UI | Jetpack Compose, Material 3, dynamic color (Android 12+) |
| State | ViewModel + StateFlow + `collectAsStateWithLifecycle` |
| Persistence | SharedPreferences + JSON (saved places, last map center) |
| Map | osmdroid 6.1.20, OpenStreetMap France HOT mirror |
| Geocoding | Android `Geocoder` (system service; coordinate input still works if unavailable) |
| Mock location | `LocationManager.addTestProvider` / `setTestProviderLocation` |
| Background | Foreground service, `foregroundServiceType="location"` |

## Project structure

```text
app/src/main/java/org/iicrom/locsim/
├── LocsimApp.kt                    # osmdroid global configuration
├── MainActivity.kt                 # Compose entry point
├── data/
│   ├── SavedPlace.kt               # place model
│   ├── PlaceRepository.kt          # JSON persistence + StateFlow
│   ├── MapPreferences.kt           # last map center
│   └── MapCacheRepository.kt       # online cache stats, offline packages
├── location/
│   ├── MockLocationService.kt      # foreground mock service (GPS + NETWORK)
│   ├── MockLocationController.kt   # start/stop entry points
│   ├── MockLocationState.kt        # running state / errors
│   └── MockLocationSettings.kt     # permissions, developer options, AppOps
├── ui/
│   ├── AppRoot.kt                  # lightweight list ⇄ map ⇄ settings navigation
│   ├── home/HomeScreen.kt          # saved-places list
│   ├── home/HomeViewModel.kt
│   ├── map/MapPickerScreen.kt      # osmdroid picker
│   ├── map/MapPickerViewModel.kt   # search, reverse geocoding, saving
│   ├── map/OsmTileSource.kt        # OSM France HOT tile source
│   ├── settings/SettingsScreen.kt  # settings, offline packages, cache management
│   ├── settings/SettingsViewModel.kt
│   └── theme/                      # Material 3 theme
└── util/Geo.kt                     # coordinate validation/formatting/parsing
```

## Build

Requirements: **JDK 21**, Android SDK 37, Gradle 9.6. The Gradle daemon is pinned to JDK 21 in `gradle/gradle-daemon-jvm.properties`; JDK 25/26 can fail while generating the Android JDK image with `jlink`.

Runtime requirement: Android 12 (API 31) or newer.

```bash
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Run unit tests and lint:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

The project does not need a map API key. It defaults to OpenStreetMap France's HOT mirror (`a/b/c.tile.openstreetmap.fr/hot/`) because `tile.openstreetmap.org` is unreachable on some networks. OSM tile services have usage policies; offline downloads are limited to 3,000 tiles per package. For public distribution, self-host tiles or switch to a commercial map provider, and replace `OsmTileSource` as needed.

## Differences from GoGoGo

| | GoGoGo | Locsim |
| --- | --- | --- |
| Home screen | Map + feature entry points | Saved-places list |
| Map entry | Map at launch | Only after tapping "Add place" |
| Movement | Fixed point, routes, joystick | Fixed point only |
| Routes/GPX | Supported | Not supported |
| Map | Baidu Maps (API key) | osmdroid + OSM (no key) |
| UI | Java + XML | Kotlin + Compose |
| Storage | SQLite | SharedPreferences + JSON |

## Known limitations

- The coordinate system is fixed to **WGS-84**. Some Chinese map services use GCJ-02 or BD-09, so display or matching may be offset; Locsim does not convert coordinates for target apps.
- Whether a target app honors system mock locations depends on that app. Some apps also use Wi-Fi, cell towers, or sensors to detect mock positions.
- System geocoding depends on the device vendor's `Geocoder` implementation and may be unavailable offline or without Google services; coordinate input remains available.
- OpenStreetMap and mirror tile services are not guaranteed to be available. Follow the [tile usage policy](https://operations.osmfoundation.org/policies/tiles/).
- Offline packages are stored in app-internal storage (`filesDir/osmdroid`) and are removed when the app is uninstalled. Clearing the online cache does not remove offline packages.
- Offline downloads require the app to stay in the foreground; each package is capped at 3,000 tiles. The default 5 km / z16 package is about 700 tiles.
- Offline packages are bound to the current tile source name (`OSM-HOT`); changing the tile source requires downloading them again.
- Not implemented: boot autostart, route movement, floating joystick, import/export, app cloning.

## Map attribution

Map data © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright), available under the ODbL.
