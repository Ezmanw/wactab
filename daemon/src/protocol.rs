use anyhow::{bail, Result};

pub const PACKET_LEN: usize = 24;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EventKind {
    Down,
    Move,
    Up,
    Hover,
    /// Not spatial — sent by the app's optional Undo/Redo buttons. Position/pressure/tilt
    /// fields are unused and should be zeroed by the sender.
    Undo,
    Redo,
}

#[derive(Debug, Clone, Copy)]
pub struct PenEvent {
    pub kind: EventKind,
    pub barrel_button: bool,
    pub eraser: bool,
    /// Normalized 0.0..1.0 within the mapped drawing area.
    pub x: f32,
    pub y: f32,
    /// Normalized 0.0..1.0
    pub pressure: f32,
    /// Degrees, -90.0..90.0
    pub tilt_x: f32,
    pub tilt_y: f32,
}

/// Wire format, little-endian, fixed 24 bytes per event:
/// [0]      kind    (0=down,1=move,2=up,3=hover)
/// [1]      buttons (bit0 = barrel button, bit1 = eraser)
/// [2..4]   reserved
/// [4..8]   x        f32
/// [8..12]  y        f32
/// [12..16] pressure f32
/// [16..20] tilt_x   f32
/// [20..24] tilt_y   f32
impl PenEvent {
    pub fn decode(buf: &[u8]) -> Result<Self> {
        if buf.len() != PACKET_LEN {
            bail!("bad packet length {} (expected {})", buf.len(), PACKET_LEN);
        }
        let kind = match buf[0] {
            0 => EventKind::Down,
            1 => EventKind::Move,
            2 => EventKind::Up,
            3 => EventKind::Hover,
            4 => EventKind::Undo,
            5 => EventKind::Redo,
            other => bail!("unknown event kind {other}"),
        };
        let buttons = buf[1];
        let f = |off: usize| f32::from_le_bytes(buf[off..off + 4].try_into().unwrap());
        Ok(PenEvent {
            kind,
            barrel_button: buttons & 0x01 != 0,
            eraser: buttons & 0x02 != 0,
            x: f(4).clamp(0.0, 1.0),
            y: f(8).clamp(0.0, 1.0),
            pressure: f(12).clamp(0.0, 1.0),
            tilt_x: f(16).clamp(-90.0, 90.0),
            tilt_y: f(20).clamp(-90.0, 90.0),
        })
    }
}
