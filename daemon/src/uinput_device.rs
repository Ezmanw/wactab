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
    down: bool,
}

impl PenDevice {
    pub fn new() -> Result<Self> {
        let abs_xy = AbsInfo::new(0, 0, AXIS_MAX, 0, 0, 100);
        let abs_pressure = AbsInfo::new(0, 0, AXIS_MAX, 0, 0, 0);
        let abs_tilt = AbsInfo::new(0, -90, 90, 0, 0, 0);

        // Deliberately presented as a plain absolute pointer (BTN_LEFT/RIGHT + ABS_X/Y),
        // not a tablet (BTN_TOOL_PEN/BTN_STYLUS). Tablet-tagged devices go through the
        // Wayland tablet-v2 protocol, which immature compositors (e.g. cosmic-comp as of
        // 2026) don't fully forward to apps. A generic absolute pointer goes through the
        // universally-supported core wl_pointer protocol instead. Trade-off: no dedicated
        // pressure/tilt reporting to the OS today; revisit once tablet-v2 support matures.
        let mut keys = AttributeSet::<KeyCode>::new();
        keys.insert(KeyCode::BTN_LEFT);
        keys.insert(KeyCode::BTN_RIGHT);

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

        Ok(Self { dev, down: false })
    }

    pub fn apply(&mut self, ev: &PenEvent) -> Result<()> {
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
        }

        events.push(KeyEvent::new(KeyCode::BTN_RIGHT, ev.barrel_button as i32).into());
        events.push(SynchronizationEvent::new(SynchronizationCode::SYN_REPORT, 0).into());

        self.dev.emit(&events)?;
        Ok(())
    }
}
