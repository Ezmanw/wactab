# wactab

Turn an Android tablet into a pressure-sensitive drawing tablet for Linux, connected over USB.

Two pieces:

- **`app/`** — Android app (Kotlin, Jetpack Compose, Material 3). Captures stylus input
  (position, pressure, tilt, barrel button) via the touchscreen digitizer and streams it
  over a TCP socket. Full immersive fullscreen UI with an on-screen calibration overlay
  to restrict the active drawing area (drag the four corner handles), an auto-hiding
  control bar, and a Settings panel (see below).
- **`daemon/`** — Linux daemon (Rust). Runs `adb forward` for you, connects to the app,
  and creates virtual input devices via `uinput` that Linux picks up as a real pointer
  (or tablet) and, for Undo/Redo, a real keyboard.

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

## Settings

Tap the ⚙ button in the control bar:

- **Pen-only mode** — ignore finger/palm touches entirely (drawing already ignores
  non-stylus input; this additionally swallows those touches instead of passing them
  through, so a resting palm can't trigger anything).
- **Undo/redo buttons** — adds Undo/Redo to the control bar, sending Ctrl+Z / Ctrl+Y to
  whichever app has focus on the PC (most drawing apps bind these). Delivered via a
  dedicated virtual keyboard device — see [Status](#status) for why that's not just the
  pen device with a couple of extra keys bolted on.
- **Dark / Light** appearance, plus an **OLED mode** (true black) for dark theme.
- **Accent colour** — eight presets, or **Use device theme** to pull from Android's
  Material You wallpaper-based palette (Android 12+).
- **Touch surface colour** — the same presets, or type any hex code directly.
- **Reset settings to default**.

All settings persist across app restarts.

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
- **Undo/Redo ride on a dedicated virtual keyboard device, not the pen device.** Ctrl+Z/
  Ctrl+Y were initially added as extra keys on the same `uinput` device as the pen —
  which the kernel and raw `evdev` both reported correctly, but `libinput` classifies a
  device primarily by its capabilities, and one that also exposes `BTN_TOOL_PEN` gets
  treated strictly as a tablet tool. Arbitrary `KEY_*` codes riding on that device never
  reliably reached X11/Wayland clients even though they were visible at the raw kernel
  level. A second, keyboard-only device sidesteps this. It also needs a broad,
  qwerty-shaped key range (the same trick tools like `ydotool` use) — udev's own
  heuristic for `ID_INPUT_KEYBOARD` (which gates whether `libinput` grants keyboard
  capability at all) doesn't fire for a device with only a couple of keys registered.
- USB transport only (via `adb forward`), single-client. Wireless (Wi-Fi / wireless-adb
  pairing) is a planned follow-up.
