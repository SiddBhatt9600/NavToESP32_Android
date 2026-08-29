# NAV2ESP — Android App

The phone-side half of NAV2ESP: a navigation app that fetches routes from OpenStreetMap-based routing, tracks your progress locally, and streams turn-by-turn updates to a companion ESP32 + TFT display over Bluetooth.

The ESP32 firmware that receives this data lives in a separate repo: **[NAV2ESP-ESP32](#)** *(link to your ESP32 repo here)*.

## How it works

```
Phone GPS ──▶ OpenRouteService (routing + geocoding)
                     │
                     ▼
        On-device step tracking (distance-to-turn,
        ETA, off-route detection, auto re-routing)
                     │
                     ▼
        JSON payload over Bluetooth SPP ──▶ ESP32 display
```

Routing happens once per trip (plus once more per re-route if you drift off course) — everything else is computed locally from GPS updates, so a normal trip stays well within OpenRouteService's free tier (2,000 requests/day).

## Features

- Destination entered as free text, geocoded automatically
- Current location used as the route origin — no manual coordinate entry
- Live turn-by-turn updates: next maneuver, road name, distance to turn, ETA
- Automatic off-route detection and re-routing
- Runs as a foreground service — keeps working with the screen off
- Streams live nav data to a paired ESP32 over classic Bluetooth (SPP)

## Requirements

- Android Studio
- Android 8.0 (API 26) or newer, physical device (GPS/Bluetooth need real hardware — an emulator won't meaningfully test this)
- A free [OpenRouteService API key](https://openrouteservice.org/dev/#/signup)
- A paired ESP32 running the companion firmware (optional — the app works standalone without it, you just won't get display output)

## Setup

1. Clone this repo and open it in Android Studio.
2. Get a free API key from [OpenRouteService](https://openrouteservice.org/dev/#/signup).
3. Add it to `local.properties` in the project root (this file is git-ignored and never committed):
   ```
   ORS_API_KEY=your_key_here
   ```
4. Build and run on a physical device.
5. Grant location and Bluetooth permissions when prompted.

## Using it with the ESP32 display

1. Flash and power on the ESP32 (see the firmware repo for setup).
2. On your phone, pair with **ESP32_Nav** in Bluetooth settings — this is a one-time step done outside the app.
3. Open NAV2ESP, type a destination, tap **Start Navigation**.
4. The app fetches your route, connects to the ESP32, and streams updates — both the phone and the TFT update in sync.
5. Tap **Stop Navigation** (or the notification's Stop action) to end the session.

If the ESP32 isn't paired or isn't reachable, the app still works — it'll show a status message (e.g. "ESP32 not paired") but continues navigating and updating its own screen regardless.

## JSON payload sent to the ESP32

One newline-terminated JSON object per update, over Bluetooth SPP:

```json
{"turn":"L","road":"MG Road","dist":120,"eta":5,"off":false}
```

| Key | Meaning |
|---|---|
| `turn` | Maneuver code (`L`, `R`, `STRAIGHT`, `DEPART`, `ARRIVE`, etc.) |
| `road` | Road name for the current step |
| `dist` | Distance to the next turn, in meters |
| `eta`  | Estimated minutes remaining |
| `off`  | Whether the tracker currently thinks you're off-route |

## Roadmap

- BLE transport as an alternative to classic BT SPP (needed for ESP32-S3/C3/H2)
- Wi-Fi/WebSocket transport option
- On-display mini-map rendering (vector route overlay)
- Offline map tiles via SD card, for areas with no phone connectivity

## License

MIT — see [LICENSE](LICENSE).
