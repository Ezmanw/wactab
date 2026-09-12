package com.wactab.app.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mirrors the wire format read by the wactab-daemon Rust binary (see daemon/src/protocol.rs). */
enum class EventKind(val code: Byte) {
    DOWN(0),
    MOVE(1),
    UP(2),
    HOVER(3),
    /** Not spatial — position/pressure/tilt fields are ignored by the daemon. */
    UNDO(4),
    REDO(5),
}

const val PACKET_LEN = 24

/**
 * @param x,y normalized 0f..1f within the mapped drawing area
 * @param pressure normalized 0f..1f
 * @param tiltX,tiltY degrees, -90f..90f
 */
class PenEvent(
    val kind: EventKind,
    val barrelButton: Boolean,
    val eraser: Boolean,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tiltX: Float,
    val tiltY: Float,
) {
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_LEN).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(kind.code)
        var buttons = 0
        if (barrelButton) buttons = buttons or 0x01
        if (eraser) buttons = buttons or 0x02
        buf.put(buttons.toByte())
        buf.put(0) // reserved
        buf.put(0) // reserved
        buf.putFloat(x.coerceIn(0f, 1f))
        buf.putFloat(y.coerceIn(0f, 1f))
        buf.putFloat(pressure.coerceIn(0f, 1f))
        buf.putFloat(tiltX.coerceIn(-90f, 90f))
        buf.putFloat(tiltY.coerceIn(-90f, 90f))
        return buf.array()
    }

    companion object {
        fun undo() = PenEvent(EventKind.UNDO, false, false, 0f, 0f, 0f, 0f, 0f)
        fun redo() = PenEvent(EventKind.REDO, false, false, 0f, 0f, 0f, 0f, 0f)
    }
}
