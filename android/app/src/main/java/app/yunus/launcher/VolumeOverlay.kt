package app.yunus.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * The volume bar as a native view, so it shows above the apps embedded in the map area and the
 * third window (those are drawn above the page, which can't cover them). It looks like the page's
 * bar (the page passes its place, size and colours) and sets the level as the finger moves.
 * It hides 2 s after the last change, like the page's.
 */
class VolumeOverlay(ctx: Context, private val onSet: (Int) -> Unit, private val onIconHold: () -> Unit = {}) : View(ctx) {
    // The speaker icon held down: the car's sound settings (a touch there doesn't set the level).
    private var onIcon = false
    private val iconHeld = Runnable { onIcon = false; visibility = GONE; onIconHold() }
    private val main = Handler(Looper.getMainLooper())
    private val hide = Runnable { if (!dragging) visibility = GONE }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pad = 0f // room around the bar for its shadow (as the page's box-shadow)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); setShadowLayer(6f, 0f, 2f, 0x80000000.toInt()) }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var level = 0
    private var max = 15
    private var scale = 1f // page px per design px, for the bar's inner sizes
    private var dragging = false
    /** Places the bar itself (x, y, w, h with its shadow's room) when it is in a window of its own. */
    var place: ((Int, Int, Int, Int) -> Unit)? = null
    /** Told when the bar shows or hides (its own window then takes touches or lets them through). */
    var shownHook: ((Boolean) -> Unit)? = null
    override fun setVisibility(v: Int) { super.setVisibility(v); shownHook?.invoke(v == VISIBLE) }

    init {
        visibility = GONE
        setLayerType(LAYER_TYPE_SOFTWARE, null) // for the knob's shadow
    }

    /** Shows the bar at x,y w×h (screen px) with the page's colours; [k] is screen px per design px. */
    fun show(x: Int, y: Int, w: Int, h: Int, k: Float, lvl: Int, mx: Int, bg: Int, fg: Int, track: Int) {
        level = lvl; max = maxOf(1, mx); scale = k
        bgPaint.color = bg; fillPaint.color = fg; trackPaint.color = track; iconPaint.color = fg
        pad = 28 * k
        bgPaint.setShadowLayer(20 * k, 0f, 8 * k, 0x73000000)
        val p = pad.toInt()
        place?.invoke(x - p, y - p, w + 2 * p, h + 2 * p)
            ?: run { layoutParams = FrameLayout.LayoutParams(w + 2 * p, h + 2 * p).apply { leftMargin = x - p; topMargin = y - p } }
        visibility = VISIBLE
        if (place == null) bringToFront()
        invalidate()
        main.removeCallbacks(hide); main.postDelayed(hide, 2000)
    }

    fun update(lvl: Int, mx: Int) { level = lvl; max = maxOf(1, mx); invalidate() }

    // The track: after the speaker icon (26 px padding, 34 px icon, 20 px gap), up to 26 px from the end.
    private fun trackLeft() = pad + (26 + 34 + 20) * scale
    private fun trackRight() = width - pad - 26 * scale

    override fun onDraw(c: Canvas) {
        val r = 22 * scale
        c.drawRoundRect(RectF(pad, pad, width - pad, height - pad), r, r, bgPaint)
        // Speaker: a small body and cone, waves by level.
        val cx = pad + 26 * scale; val cy = height / 2f; val u = 34 * scale / 24f
        iconPaint.strokeWidth = 2f * u
        val speaker = Path().apply {
            moveTo(cx + 3 * u, cy - 3 * u); lineTo(cx + 7 * u, cy - 3 * u); lineTo(cx + 12 * u, cy - 8 * u)
            lineTo(cx + 12 * u, cy + 8 * u); lineTo(cx + 7 * u, cy + 3 * u); lineTo(cx + 3 * u, cy + 3 * u); close()
        }
        c.drawPath(speaker, iconPaint)
        if (level > 0) c.drawArc(RectF(cx + 11 * u, cy - 4 * u, cx + 17 * u, cy + 4 * u), -60f, 120f, false, iconPaint)
        if (level > max / 2) c.drawArc(RectF(cx + 11 * u, cy - 8 * u, cx + 22 * u, cy + 8 * u), -60f, 120f, false, iconPaint)
        if (level == 0) { c.drawLine(cx + 16 * u, cy - 3 * u, cx + 22 * u, cy + 3 * u, iconPaint); c.drawLine(cx + 22 * u, cy - 3 * u, cx + 16 * u, cy + 3 * u, iconPaint) }
        // Track, fill and knob.
        val l = trackLeft(); val rr = trackRight(); val th = 10 * scale
        c.drawRoundRect(RectF(l, cy - th / 2, rr, cy + th / 2), th / 2, th / 2, trackPaint)
        val at = l + (rr - l) * level / max
        c.drawRoundRect(RectF(l, cy - th / 2, at, cy + th / 2), th / 2, th / 2, fillPaint)
        c.drawCircle(at, cy, 14 * scale, knobPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.x < trackLeft() - 6 * scale) {
            onIcon = true; main.removeCallbacks(hide); main.postDelayed(iconHeld, 600); return true
        }
        if (onIcon) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                onIcon = false; main.removeCallbacks(iconHeld); main.postDelayed(hide, 2000)
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                dragging = true
                val f = ((e.x - trackLeft()) / (trackRight() - trackLeft())).coerceIn(0f, 1f)
                val lvl = Math.round(f * max)
                if (lvl != level) { level = lvl; invalidate(); onSet(lvl) }
                main.removeCallbacks(hide)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false; main.postDelayed(hide, 2000) }
        }
        return true
    }
}
