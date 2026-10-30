# Yunus HAL bridge status

This folder documents the live-data integration boundary. It does not claim a working vehicle
telemetry connection yet.

## Implemented in this branch

- The Android app creates an ADB RSA key in Android Keystore and can negotiate the ADB TCP
  transport with the vehicle head unit at `127.0.0.1:5555`.
- It pushes the Kinex HAL service JAR from the APK asset to `/data/local/tmp/` and starts its
  `HalApiServiceMain` on port `19322`.
- It can open a loopback TCP connection and decode the service's 4-byte big-endian length-prefixed
  UTF-8 JSON frames.
- Raw service events and connection diagnostics are kept separate from `window.yunusCar()` vehicle
  state. The demo only switches to live when a recognized telemetry field is supplied.

The service JAR in `android/app/src/main/assets/kinex_hal_api_service.jar` was extracted from the
Kinex APK supplied for this private Yunus project. Keep the repository and resulting APK private;
the JAR is Kinex software, not Yunus source.

## Still needs verification on the Dolphin

Kinex's client handshake (`seed`/`h0` proof), subscription topic names, event payload schema, and
BYD getter units/signatures have not been validated against the vehicle. The app therefore does
not send an invented handshake, does not subscribe, and does not map raw events to speed, SOC,
range, doors, climate, or other vehicle state. At present this code is a transport/bootstrap
prototype, not a live telemetry bridge. Read-only behavior is intentional; no vehicle commands
are forwarded.

To finish the bridge, capture the first service exchange while the car is accessible, decode the
handshake and event/subscription formats, then validate each field and unit against the Dolphin's
instrument cluster before enabling it in `window.yunusCar()`.
