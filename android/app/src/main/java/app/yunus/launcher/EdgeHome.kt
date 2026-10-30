package app.yunus.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView

/**
 * Yunus's bottom bar over any app. BYD's own bar is pushed off the screen (wm overscan), so a thin,
 * invisible strip along the bottom edge, over the other apps, takes a swipe up: the first brings
 * Yunus's dock up over the app (the page itself in its dock-only mode, launcher.html#dock, in an
 * overlay window); a swipe up on that dock opens the recent apps (the page asks for it). The dock
 * goes when it is left alone a few seconds, when something outside it is touched, or when one of
 * its keys takes Yunus to the front. Only while another app is in front (Yunus has its own dock);
 * needs the "display over other apps" permission (granted once over ADB).
 */
class EdgeHome(private val ctx: Context, private val recents: () -> Unit, private val setupDock: (WebView) -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var strip: View? = null
    private val stripH = (22 * ctx.resources.displayMetrics.density).toInt()
    private var dock: WebView? = null
    private var dockShown = false
    private val dockH = 112 // the page's dock row; the page is laid out 1:1 with the screen
    private val autoHide = Runnable { hideDock() }

    fun show() {
        if (strip != null || !android.provider.Settings.canDrawOverlays(ctx)) return
        val v = View(ctx)
        var y0 = -1f
        var fired = false
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = ev.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && y0 - ev.rawY > stripH * 1.5f) {
                    fired = true
                    if (dockShown) { hideDock(); recents() } else showDock()
                }
            }
            true
        }
        val lp = overlayParams(stripH, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        try { wm.addView(v, lp); strip = v } catch (t: Throwable) { android.util.Log.w("Yunus", "edge: $t") }
    }

    fun hide() {
        hideDock()
        strip?.let { try { wm.removeView(it) } catch (_: Throwable) { } }
        strip = null
    }

    /** The dock overlay's page, while it is up (it gets the car's data like the main page). */
    fun dockWeb(): WebView? = if (dockShown) dock else null

    private fun showDock() {
        val w = dock ?: WebView(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            setupDock(this)
            loadUrl("https://appassets.androidplatform.net/assets/launcher.html#dock")
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) hideDock()
                else { removeCallbacks(autoHide); postDelayed(autoHide, 7000) } // kept while in use
                false
            }
        }.also { dock = it }
        if (!dockShown) {
            val lp = overlayParams(dockH, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            try { wm.addView(w, lp); dockShown = true } catch (t: Throwable) { android.util.Log.w("Yunus", "dock: $t") }
        }
        w.removeCallbacks(autoHide); w.postDelayed(autoHide, 7000)
    }

    fun hideDock() {
        val w = dock ?: return
        w.removeCallbacks(autoHide)
        if (dockShown) { try { wm.removeView(w) } catch (_: Throwable) { } }
        dockShown = false
    }

    private fun overlayParams(height: Int, flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM }
}
