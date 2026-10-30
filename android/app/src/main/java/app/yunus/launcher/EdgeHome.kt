package app.yunus.launcher

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Back home from any app: BYD's bottom bar is pushed off the screen (wm overscan), so a thin,
 * invisible strip along the bottom edge, over the other apps, takes a swipe up and brings Yunus
 * (the home screen) back; swiped up and held there it opens the recent apps instead (as Android's
 * gesture navigation does). It is there only while another app is in front (Yunus has its own
 * dock), and needs the "display over other apps" permission (granted once over ADB).
 */
class EdgeHome(private val ctx: Context, private val recents: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val height = (22 * ctx.resources.displayMetrics.density).toInt()

    fun show() {
        if (view != null || !android.provider.Settings.canDrawOverlays(ctx)) return
        val v = View(ctx)
        var y0 = -1f
        var up = false // past the threshold, waiting: let go → home, hold → recents
        val hold = Runnable { if (up) { up = false; recents() } }
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = ev.rawY; up = false }
                MotionEvent.ACTION_MOVE -> if (!up && y0 - ev.rawY > height * 1.5f) { up = true; v.postDelayed(hold, 350) }
                MotionEvent.ACTION_UP -> { v.removeCallbacks(hold); if (up) { up = false; home() } }
                MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(hold); up = false }
            }
            true
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        try { wm.addView(v, lp); view = v } catch (t: Throwable) { android.util.Log.w("Yunus", "edge: $t") }
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        view = null
    }

    private fun home() {
        ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
}
