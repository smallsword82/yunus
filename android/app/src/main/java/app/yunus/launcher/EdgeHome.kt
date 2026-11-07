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
 * Yunus's bottom bar over any app. BYD's own bar is off the screen; while another app is in front,
 * Yunus's dock (the page in its dock-only mode, launcher.html#dock, in an overlay window) stays on
 * the screen over the bottom of the app, which is laid out full screen. While that app is itself
 * full screen (a video), the dock hides; a swipe up from the bottom edge brings it for a few seconds.
 * A swipe up on the dock opens the recent apps (the page asks for it). Only while another app is in
 * front (Yunus has its own dock); needs the "display over other apps" permission.
 */
class EdgeHome(private val ctx: Context, private val recents: () -> Unit, private val setupDock: (WebView) -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var dock: WebView? = null
    private var dockShown = false
    private val dockH = 112 // the page's dock row; the page is laid out 1:1 with the screen
    private var active = false // another app in front
    private var fullScreen = false // that app hides the system bars (a video full screen)
    private var peek = false // brought up by a swipe while full screen
    private val endPeek = Runnable { peek = false; update() }

    // A thin strip along the bottom edge: it hears the system bars' state (an app going full screen
    // hides them) and, while full screen, takes the swipe up that brings the dock.
    private var strip: View? = null
    private val stripH = (22 * ctx.resources.displayMetrics.density).toInt()
    private val fullFlags = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

    fun show() {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        active = true
        addStrip()
        update()
    }

    fun hide() {
        active = false; peek = false
        update()
    }

    /** The dock overlay's page, while it is up (it gets the car's data like the main page). */
    fun dockWeb(): WebView? = if (dockShown) dock else null

    private fun update() {
        if (active && (!fullScreen || peek)) showDock() else hideDock()
        strip?.let { s ->
            val touch = active && fullScreen && !peek
            try { wm.updateViewLayout(s, overlayParams(stripH, if (touch) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else hiddenFlags)) } catch (_: Throwable) { }
        }
    }

    @Suppress("DEPRECATION")
    private fun addStrip() {
        if (strip != null) return
        val v = View(ctx)
        var y0 = -1f
        var fired = false
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = ev.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && y0 - ev.rawY > stripH * 1.5f) {
                    fired = true
                    peek = true; update()
                    v.removeCallbacks(endPeek); v.postDelayed(endPeek, 5000)
                }
            }
            true
        }
        v.setOnSystemUiVisibilityChangeListener { vis ->
            val full = vis and fullFlags != 0
            if (full != fullScreen) { fullScreen = full; if (!full) peek = false; update() }
        }
        try { wm.addView(v, overlayParams(stripH, hiddenFlags)); strip = v } catch (t: Throwable) { android.util.Log.w("Yunus", "edge: $t") }
    }

    // The dock's window is made once, page loaded, and then stays on the screen hidden and
    // untouchable: showing only turns it visible. Adding the window (and loading the page) each
    // time made the dock come up late.
    private val hiddenFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    private val shownFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    private var dockAdded = false

    private fun prepareDock(): WebView {
        val w = dock ?: WebView(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            setupDock(this)
            loadUrl("https://appassets.androidplatform.net/assets/launcher.html#dock")
            setOnTouchListener { _, _ -> if (peek) { removeCallbacks(endPeek); postDelayed(endPeek, 5000) }; false } // kept while in use
        }.also { dock = it }
        if (!dockAdded) {
            w.visibility = View.INVISIBLE
            try { wm.addView(w, overlayParams(dockH, hiddenFlags)); dockAdded = true } catch (t: Throwable) { android.util.Log.w("Yunus", "dock: $t") }
        }
        return w
    }

    private fun showDock() {
        val w = prepareDock()
        if (!dockShown && dockAdded) {
            try { wm.updateViewLayout(w, overlayParams(dockH, shownFlags)) } catch (_: Throwable) { }
            w.visibility = View.VISIBLE
            dockShown = true
        }
    }

    fun hideDock() {
        val w = dock ?: return
        if (dockShown && dockAdded) {
            w.visibility = View.INVISIBLE
            try { wm.updateViewLayout(w, overlayParams(dockH, hiddenFlags)) } catch (_: Throwable) { }
        }
        dockShown = false
    }

    private fun overlayParams(height: Int, flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM }
}
