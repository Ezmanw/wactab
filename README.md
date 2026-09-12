# wactab

Turn an Android tablet into a pressure-sensitive drawing tablet for Linux, connected over USB.

Two pieces:

- **`app/`** — Android app (Kotlin, Jetpack Compose, Material 3). Captures stylus input
  (position, pressure, tilt, barrel button) via the touchscreen digitizer and streams it
  over a TCP socket. Includes an on-screen calibration overlay to restrict the active
  drawing area (drag the four corner handles).
- **`daemon/`** — Linux daemon (Rust). Runs `adb forward` for you, connects to the app,
  and creates a virtual input device via `uinput` that Linux picks up as a real pointer
  device.

## Requirements

- `adb` installed and the tablet has USB debugging enabled and is authorized.
- Your Linux user is in the `input` group (needed for `/dev/uinput`).
- Rust toolchain (`cargo`) to build the daemon.
- Android Studio, or just `./gradlew`, to build/install the app (minSdk 26).

## Building

```bash
# Android app
cd app && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Linux daemon
cd ../daemon && cargo build --release
```

## Running

1. Plug the tablet in over USB, open the Wactab app.
2. On the PC: `./daemon/target/release/wactab-daemon` (defaults to port 7912; pass `--port` to change it — must match the app).
3. In the app, tap **Start**. The daemon runs `adb forward` automatically and connects.
4. Optionally tap **Calibrate area** and drag the corner handles to restrict which part
   of the tablet screen is active (e.g. to leave room for on-screen UI, or match your
   monitor's aspect ratio).
5. Draw — cursor position and click/drag work in any app.

## Wire protocol

Fixed 24-byte little-endian packets over the TCP stream (app is the server, daemon is the
client, connected through `adb forward`). See [`daemon/src/protocol.rs`](daemon/src/protocol.rs)
and [`app/app/src/main/kotlin/com/wactab/app/net/Protocol.kt`](app/app/src/main/kotlin/com/wactab/app/net/Protocol.kt).

## Status

Verified end-to-end on real hardware (Samsung Galaxy Tab S6 Lite + S Pen, Linux/COSMIC):

- **Position, click, and drag work today.** The virtual device is intentionally presented
  as a plain absolute pointer (`BTN_LEFT`/`BTN_RIGHT` + `ABS_X/Y`), not a tablet
  (`BTN_TOOL_PEN`/`BTN_STYLUS`). Tablet-classified devices are routed through the Wayland
  `zwp_tablet_v2` protocol, which immature compositors (COSMIC's `cosmic-comp`, as of
  2026) advertise but don't yet forward correctly to apps — confirmed via `wayland-info`
  and a native GTK4 app misinterpreting tablet axes as a zoom gesture. Plain-pointer mode
  goes through the much more mature, universally-supported core `wl_pointer` protocol
  instead, at the cost of pressure/tilt not being applied at the OS level yet.
- **Pressure and tilt are already captured and transmitted** by the Android app (see
  [`Protocol.kt`](app/app/src/main/kotlin/com/wactab/app/net/Protocol.kt)) — the daemon
  just isn't applying them to the OS-level device in the current default mode. Switching
  `daemon/src/uinput_device.rs` back to tablet-classified axes
  (`BTN_TOOL_PEN`/`BTN_STYLUS`) restores full pressure/tilt reporting and was verified
  working at the kernel `evdev` level; it's gated on the compositor's tablet-protocol
  support rather than anything in this app. This likely already works out of the box on
  GNOME or KDE (both have mature `zwp_tablet_v2` support).
- USB transport only (via `adb forward`), single-client. Wireless (Wi-Fi / wireless-adb
  pairing) is a planned follow-up.
