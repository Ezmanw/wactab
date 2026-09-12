package com.wactab.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 *
 * Draws a plain dot-grid texture as a static backdrop — this view never renders actual
 * ink (drawing happens on the connected PC), the grid is purely so the surface reads as
 * a tablet rather than a blank void.
 */
class PenSurfaceView(context: Context) : View(context) {

    var activeRect: FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
    var onPenEvent: ((PenEvent) -> Unit)? = null

    /** When true, any non-stylus touch (finger, palm) is swallowed instead of passed through. */
    var penOnlyMode: Boolean = false

    var canvasColor: Int = Color.rgb(0x1c, 0x1c, 0x1e)
        set(value) {
            field = value
            backgroundPaint.color = value
            invalidate()
        }

    private val backgroundPaint = Paint().apply { color = canvasColor }
    private val dotPaint = Paint().apply {
        color = Color.rgb(0x3a, 0x3a, 0x3d)
        isAntiAlias = true
    }
    private val dotSpacingDp = 32f
    private val dotRadiusDp = 1.6f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        val density = resources.displayMetrics.density
        val spacing = dotSpacingDp * density
        val radius = dotRadiusDp * density
        var y = spacing / 2
        while (y < height) {
            var x = spacing / 2
            while (x < width) {
                canvas.drawCircle(x, y, radius, dotPaint)
                x += spacing
            }
            y += spacing
        }
    }

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
            // Pen-only mode swallows finger/palm touches instead of letting them through,
            // so a resting palm can't trigger anything (drawing already ignores them either way).
            return penOnlyMode
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
