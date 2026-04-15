# Family Tracks - Android App

Android companion app for the [Family Tracks](https://github.com/mwales-ai/family_tracks)
self-hosted location tracker. Sends encrypted location updates to your private server
via UDP — no third-party services, no data brokers.

## Features

- **Encrypted location reporting** — AES-256-GCM encrypted UDP packets sent directly
  to your server
- **QR code setup** — scan a code from the server admin panel to configure the app
  (no manual entry of server details)
- **Background tracking** — continues reporting when the app is backgrounded or the
  screen is off
- **Smart power management** — switches to coarse/infrequent reporting when stationary
  inside a geofence for 30+ minutes
- **Geofence events** — view entry/exit events for all family members
- **Map view** — WebView-based map showing all family member locations
- **Configurable reporting** — choose GPS accuracy and reporting interval
- **Avatar upload** — set a profile photo displayed on the map
- **Data sharing controls** — toggle sharing of speed and battery level

## Requirements

- Android 8.0 (API 26) or higher
- Google Play Services (for fused location provider)

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config — see below)
./gradlew assembleRelease
```

The debug APK is output to `app/build/outputs/apk/debug/app-debug.apk`.

### Signing a Release Build

1. Generate a keystore (one time):
   ```bash
   keytool -genkey -v -keystore familytracks.jks -alias familytracks \
       -keyalg RSA -keysize 2048 -validity 10000
   ```

2. Add signing config to `app/build.gradle`:
   ```groovy
   android {
       signingConfigs {
           release {
               storeFile file('../familytracks.jks')
               storePassword 'your-store-password'
               keyAlias 'familytracks'
               keyPassword 'your-key-password'
           }
       }
       buildTypes {
           release {
               signingConfig signingConfigs.release
           }
       }
   }
   ```

3. Build: `./gradlew assembleRelease`

## Installing on a Device

```bash
# Via ADB (USB debugging must be enabled)
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Setup

1. Deploy the Family Tracks server and create a user account in the admin panel
2. Click **QR Code** next to the user in the admin panel
3. Open the app and tap **Scan QR Code** on the Status tab
4. Tap **Start Tracking**

The app will request location permissions and battery optimization exemption
on first start.

## Project Structure

```
app/src/main/java/com/familytracks/app/
    MainActivity.java       - Bottom tab navigation (Map, Events, Status, Settings, Debug)
    MapViewFragment.java    - WebView showing the server's map dashboard
    EventsFragment.java     - Geofence entry/exit event list
    StatusFragment.java     - Connection status, start/stop tracking, avatar upload
    SettingsFragment.java   - Preferences (GPS accuracy, reporting interval, data sharing)
    DebugFragment.java      - Diagnostic info (packet counts, tracking mode, errors)
    LocationService.java    - Foreground service for background location reporting
    ServerConfig.java       - QR code parsing and server connection persistence
    QrScanActivity.java     - Camera-based QR code scanner (ML Kit)
```

## Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Send UDP packets and communicate with server API |
| `ACCESS_FINE_LOCATION` | GPS location for tracking |
| `ACCESS_COARSE_LOCATION` | Fallback location when in power-saving mode |
| `ACCESS_BACKGROUND_LOCATION` | Continue tracking when app is backgrounded |
| `FOREGROUND_SERVICE` | Keep location service running |
| `POST_NOTIFICATIONS` | Show tracking notification (required on Android 13+) |
| `CAMERA` | Scan QR codes and take avatar photos |

## Protocol

The app sends location data as UDP packets to the server:

```
Wire format: [36-byte user UUID][12-byte nonce][16-byte GCM tag][ciphertext]
```

The encrypted payload is JSON:
```json
{
    "uid": "user-uuid",
    "lat": 39.123,
    "lon": -98.456,
    "ts": "2026-04-01T12:00:00",
    "alt": 300.0,
    "spd": 1.5,
    "brg": 180.0,
    "acc": 10.0,
    "bat": 85.0
}
```

## Related

- **Server:** [github.com/mwales-ai/family_tracks](https://github.com/mwales-ai/family_tracks)
