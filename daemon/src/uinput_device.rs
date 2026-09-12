use anyhow::Result;
use evdev::uinput::{VirtualDevice, VirtualDeviceBuilder};
use evdev::{
    AbsInfo, AbsoluteAxisCode, AbsoluteAxisEvent, AttributeSet, InputEvent, InputId, KeyCode,
    KeyEvent, SynchronizationCode, SynchronizationEvent,
};

use crate::protocol::{EventKind, PenEvent};

pub const AXIS_MAX: i32 = 32767;

pub struct PenDevice {
    dev: VirtualDevice,
    /// Separate pure-keyboard device for Undo/Redo shortcuts. libinput classifies a
    /// device primarily by its capabilities, and a device that also exposes
    /// BTN_TOOL_PEN gets treated strictly as a tablet tool — arbitrary KEY_* codes
    /// riding along on that same device don't reliably reach X11/Wayland clients even
    /// though they're visible at the raw kernel evdev level. A dedicated keyboard-only
    /// device sidesteps that entirely.
    keyboard: VirtualDevice,
    down: bool,
    /// Tracks whether BTN_TOOL_PEN/RUBBER has been raised. In real tablets this goes high
    /// as soon as the pen enters proximity (hover), independent of BTN_TOUCH — apps rely
    /// on it to show a cursor/highlight before the pen actually touches the surface.
    in_proximity: bool,
    tablet_mode: bool,
}

impl PenDevice {
    pub fn new(tablet_mode: bool) -> Result<Self> {
        let abs_xy = AbsInfo::new(0, 0, AXIS_MAX, 0, 0, 100);
        let abs_pressure = AbsInfo::new(0, 0, AXIS_MAX, 0, 0, 0);
        let abs_tilt = AbsInfo::new(0, -90, 90, 0, 0, 0);

        // Presented as a real tablet (BTN_TOOL_PEN/BTN_STYLUS) by default, giving real
        // pressure/tilt/hover — this needs a compositor with working Wayland tablet-v2
        // support (X11 sessions, GNOME, KDE all work; COSMIC's cosmic-comp as of 2026
        // does not, see README). --pointer-mode (see main.rs) falls back to a plain
        // absolute pointer (BTN_LEFT/RIGHT + ABS_X/Y) for those compositors instead,
        // trading away pressure/tilt for reliable position/click via the much more
        // mature core wl_pointer protocol.
        let mut keys = AttributeSet::<KeyCode>::new();
        if tablet_mode {
            keys.insert(KeyCode::BTN_TOOL_PEN);
            keys.insert(KeyCode::BTN_TOOL_RUBBER);
            keys.insert(KeyCode::BTN_TOUCH);
            keys.insert(KeyCode::BTN_STYLUS);
        } else {
            keys.insert(KeyCode::BTN_LEFT);
            keys.insert(KeyCode::BTN_RIGHT);
        }
        let dev = VirtualDeviceBuilder::new()?
            .name("Wactab Virtual Pen")
            .input_id(InputId::new(evdev::BusType::BUS_VIRTUAL, 0x1209, 0x0001, 1))
            .with_keys(&keys)?
            .with_absolute_axis(&evdev::UinputAbsSetup::new(AbsoluteAxisCode::ABS_X, abs_xy))?
            .with_absolute_axis(&evdev::UinputAbsSetup::new(AbsoluteAxisCode::ABS_Y, abs_xy))?
            .with_absolute_axis(&evdev::UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_PRESSURE,
                abs_pressure,
            ))?
            .with_absolute_axis(&evdev::UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_TILT_X,
                abs_tilt,
            ))?
            .with_absolute_axis(&evdev::UinputAbsSetup::new(
                AbsoluteAxisCode::ABS_TILT_Y,
                abs_tilt,
            ))?
            .build()?;

        // udev's "is this a real keyboard" heuristic (which gates whether libinput grants
        // LIBINPUT_DEVICE_CAP_KEYBOARD at all) requires a broad, qwerty-shaped key range —
        // a handful of keys only earns the weaker ID_INPUT_KEY tag, which several
        // compositors won't treat as a keyboard focus target. Register the same low
        // key-code range (1..255, below BTN_MISC/0x100) a real AT keyboard reports, same
        // trick tools like ydotool use, so this reliably reads as a real keyboard.
        let mut keyboard_keys = AttributeSet::<KeyCode>::new();
        for code in 1u16..255 {
            keyboard_keys.insert(KeyCode(code));
        }
        let keyboard = VirtualDeviceBuilder::new()?
            .name("Wactab Virtual Keyboard")
            .input_id(InputId::new(evdev::BusType::BUS_VIRTUAL, 0x1209, 0x0002, 1))
            .with_keys(&keyboard_keys)?
            .build()?;

        Ok(Self {
            dev,
            keyboard,
            down: false,
            in_proximity: false,
            tablet_mode,
        })
    }

    fn send_shortcut(&mut self, key: KeyCode) -> Result<()> {
        self.keyboard.emit(&[
            KeyEvent::new(KeyCode::KEY_LEFTCTRL, 1).into(),
            KeyEvent::new(key, 1).into(),
            SynchronizationEvent::new(SynchronizationCode::SYN_REPORT, 0).into(),
        ])?;
        self.keyboard.emit(&[
            KeyEvent::new(key, 0).into(),
            KeyEvent::new(KeyCode::KEY_LEFTCTRL, 0).into(),
            SynchronizationEvent::new(SynchronizationCode::SYN_REPORT, 0).into(),
        ])?;
        Ok(())
    }

    pub fn apply(&mut self, ev: &PenEvent) -> Result<()> {
        match ev.kind {
            EventKind::Undo => return self.send_shortcut(KeyCode::KEY_Z),
            EventKind::Redo => return self.send_shortcut(KeyCode::KEY_Y),
            EventKind::Down | EventKind::Move | EventKind::Up | EventKind::Hover => {}
        }

        let x = (ev.x * AXIS_MAX as f32) as i32;
        let y = (ev.y * AXIS_MAX as f32) as i32;
        let pressure = (ev.pressure * AXIS_MAX as f32) as i32;
        let tilt_x = ev.tilt_x as i32;
        let tilt_y = ev.tilt_y as i32;

        let mut events: Vec<InputEvent> = vec![
            AbsoluteAxisEvent::new(AbsoluteAxisCode::ABS_X, x).into(),
            AbsoluteAxisEvent::new(AbsoluteAxisCode::ABS_Y, y).into(),
            AbsoluteAxisEvent::new(AbsoluteAxisCode::ABS_PRESSURE, pressure).into(),
            AbsoluteAxisEvent::new(AbsoluteAxisCode::ABS_TILT_X, tilt_x).into(),
            AbsoluteAxisEvent::new(AbsoluteAxisCode::ABS_TILT_Y, tilt_y).into(),
        ];

        if self.tablet_mode {
            let tool_key = if ev.eraser {
                KeyCode::BTN_TOOL_RUBBER
            } else {
                KeyCode::BTN_TOOL_PEN
            };

            // Raise "tool in proximity" as soon as any event arrives (including Hover),
            // not just on Down — otherwise the OS doesn't know the pen exists until it
            // touches the surface, so there's no cursor feedback while hovering.
            if !self.in_proximity {
                events.push(KeyEvent::new(tool_key, 1).into());
                self.in_proximity = true;
            }

            match ev.kind {
                EventKind::Down => {
                    if !self.down {
                        events.push(KeyEvent::new(KeyCode::BTN_TOUCH, 1).into());
                        self.down = true;
                    }
                }
                EventKind::Move | EventKind::Hover => {}
                EventKind::Up => {
                    if self.down {
                        events.push(KeyEvent::new(KeyCode::BTN_TOUCH, 0).into());
                        self.down = false;
                    }
                }
                EventKind::Undo | EventKind::Redo => unreachable!("handled above"),
            }
            events.push(KeyEvent::new(KeyCode::BTN_STYLUS, ev.barrel_button as i32).into());
        } else {
            match ev.kind {
                EventKind::Down => {
                    if !self.down {
                        events.push(KeyEvent::new(KeyCode::BTN_LEFT, 1).into());
                        self.down = true;
                    }
                }
                EventKind::Move | EventKind::Hover => {}
                EventKind::Up => {
                    if self.down {
                        events.push(KeyEvent::new(KeyCode::BTN_LEFT, 0).into());
                        self.down = false;
                    }
                }
                EventKind::Undo | EventKind::Redo => unreachable!("handled above"),
            }
            events.push(KeyEvent::new(KeyCode::BTN_RIGHT, ev.barrel_button as i32).into());
        }

        events.push(SynchronizationEvent::new(SynchronizationCode::SYN_REPORT, 0).into());

        self.dev.emit(&events)?;
        Ok(())
    }
}
