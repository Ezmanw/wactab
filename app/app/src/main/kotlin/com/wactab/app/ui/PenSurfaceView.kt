package com.wactab.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.wactab.app.net.EventKind
import com.wactab.app.net.PenEvent
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen view that captures raw stylus [MotionEvent]s (position, pressure, tilt,
 * barrel button) and reports them as normalized [PenEvent]s within [activeRect].
 *
 * [activeRect] is (left, top, right, bottom) as fractions of the view, 0f..1f each,
 * letting the user restrict the usable drawing area (e.g. to match monitor aspect ratio
 * or avoid a bezel) without changing anything else.
 */
class PenSurfaceView(context: Context) : View(context) {

    var activeRect: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
    var onPenEvent: ((PenEvent) -> Unit)? = null

    private fun normalize(rawX: Float, rawY: Float): Pair<Float, Float> {
        val (l, t, r, b) = activeRect
        val w = (width.coerceAtLeast(1)).toFloat()
        val h = (height.coerceAtLeast(1)).toFloat()
        val rectW = ((r - l) * w).coerceAtLeast(1f)
        val rectH = ((b - t) * h).coerceAtLeast(1f)
        val nx = (rawX - l * w) / rectW
        val ny = (rawY - t * h) / rectH
        return nx.coerceIn(0f, 1f) to ny.coerceIn(0f, 1f)
    }

    private fun buildEvent(event: MotionEvent, kind: EventKind): PenEvent {
        val (nx, ny) = normalize(event.x, event.y)
        val toolType = event.getToolType(0)
        val eraser = toolType == MotionEvent.TOOL_TYPE_ERASER
        val pressure = if (kind == EventKind.HOVER) 0f else event.pressure.coerceIn(0f, 1f)

        // Android reports tilt as the angle from the screen's perpendicular (0 = straight up)
        // and orientation as the azimuth around that axis. Convert to the X/Y tilt components
        // (degrees, -90..90) used by USB HID / Linux ABS_TILT_X/Y — this is an approximation
        // but matches close enough for pressure-and-shading style brush behavior.
        val tiltRad = event.getAxisValue(MotionEvent.AXIS_TILT)
        val orientationRad = event.orientation
        val tiltDeg = Math.toDegrees(tiltRad.toDouble()).toFloat()
        val tiltX = tiltDeg * sin(orientationRad)
        val tiltY = -tiltDeg * cos(orientationRad)

        val barrelButton = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        return PenEvent(
            kind = kind,
            barrelButton = barrelButton,
            eraser = eraser,
            x = nx,
            y = ny,
            pressure = pressure,
            tiltX = tiltX,
            tiltY = tiltY,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS &&
            event.getToolType(0) != MotionEvent.TOOL_TYPE_ERASER
        ) {
            return false
        }
        val kind = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> EventKind.DOWN
            MotionEvent.ACTION_MOVE -> EventKind.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> EventKind.UP
            else -> return false
        }
        onPenEvent?.invoke(buildEvent(event, kind))
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            onPenEvent?.invoke(buildEvent(event, EventKind.HOVER))
            return true
        }
        return super.onGenericMotionEvent(event)
    }
}

private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]
