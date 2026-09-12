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
2. On the PC: `./wactab.sh` (defaults to port 7912, real tablet mode with pressure/tilt;
   pass `--port` to change the port, or `--pointer-mode` if you're on a compositor without
   working tablet-v2 support — see Status below).
3. In the app, tap **Start**. The daemon runs `adb forward` automatically and connects.
4. Optionally tap **Calibrate area** and drag the corner handles to restrict which part
   of the tablet screen is active (e.g. to leave room for on-screen UI, or match your
   monitor's aspect ratio).
5. Draw.

## Wire protocol

Fixed 24-byte little-endian packets over the TCP stream (app is the server, daemon is the
client, connected through `adb forward`). See [`daemon/src/protocol.rs`](daemon/src/protocol.rs)
and [`app/app/src/main/kotlin/com/wactab/app/net/Protocol.kt`](app/app/src/main/kotlin/com/wactab/app/net/Protocol.kt).

## Status

Verified end-to-end on real hardware (Samsung Galaxy Tab S6 Lite + S Pen, Linux/COSMIC):

- **Pressure, tilt, and hover work by default**, verified end-to-end in GIMP under a KDE
  Plasma (X11) session: real pressure-sensitive line width, tilt, and a hover cursor that
  appears before the pen touches down. The daemon presents the device as a real tablet
  (`BTN_TOOL_PEN`/`BTN_STYLUS`), which needs a compositor with working `zwp_tablet_v2`
  support — X11 sessions (via plain `libinput` + XInput2, no Wayland protocol involved)
  and mature Wayland compositors (GNOME, KDE) work; COSMIC's `cosmic-comp` does not yet
  (tracked upstream: [pop-os/cosmic-comp#2721](https://github.com/pop-os/cosmic-comp/issues/2721)).
- **`--pointer-mode` is the fallback** for compositors without working tablet-v2 support.
  It presents the virtual device as a plain absolute pointer (`BTN_LEFT`/`BTN_RIGHT` +
  `ABS_X/Y`) instead, going through the much more mature, universally-supported core
  `wl_pointer` protocol — confirmed working on COSMIC, where tablet-classified devices
  don't (confirmed via `wayland-info` and a native GTK4 app misinterpreting tablet axes
  as a zoom gesture). Trade-off: no pressure/tilt/hover, just position and click/drag.
- Confining the tablet to one monitor under X11 (so the cursor doesn't wander onto other
  displays) uses the same trick as `xsetwacom --map-to-output`:
  ```bash
  # id from `xinput list` (the "... Pen (0)" sub-device); geometry from `xrandr --query`
  xinput set-prop <id> "Coordinate Transformation Matrix" \
    <mon_w/screen_w> 0 <mon_x/screen_w>  0 <mon_h/screen_h> <mon_y/screen_h>  0 0 1
  ```
- USB transport only (via `adb forward`), single-client. Wireless (Wi-Fi / wireless-adb
  pairing) is a planned follow-up.
