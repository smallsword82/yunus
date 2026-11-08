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
class EdgeHome(private val ctx: Context, private val recents: () -> Unit, private val visibleBottom: () -> Int, private val setupDock: (WebView) -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var dock: WebView? = null
    private var dockShown = false
    // The page's dock row (the page is laid out 1:1 with the screen): two rows upright.
    private var dockH = 112
    private fun dockHNow() = if (ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) 196 else 112
    private var active = false // another app in front
    private var fullScreen = false // that app hides the system bars (a video full screen)
    private var peek = false // brought up by a swipe while full screen
    private val endPeek = Runnable { peek = false; update() }

    // Full screen, the system lays overlay windows out down to the screen's hidden bottom (BYD's bar
    // is moved off the screen there), so a window at the bottom ends up below the visible screen.
    // How far is measured from the strip, and that tells also that the app went full screen: some
    // players (HakoPlayer) hide the bars without the change ever reaching the strip's listener.
    private var lift = 0
    private val poll = object : Runnable { override fun run() { measure(); if (active) strip?.postDelayed(this, 700) } }

    private var seen = -1 // the last reading: one is acted on only when read the same twice (the
    // window's place on the screen is known a moment after it was moved)

    @Suppress("DEPRECATION")
    private fun measure() {
        val s = strip ?: return
        if (!s.isAttachedToWindow) return
        val h = dockHNow()
        if (h != dockH) { // the screen turned
            dockH = h
            if (dockAdded) dock?.let { try { wm.updateViewLayout(it, overlayParams(dockH, if (dockShown) shownFlags else hiddenFlags)) } catch (_: Throwable) { } }
        }
        if (s.height == 0) return
        val loc = IntArray(2); s.getLocationOnScreen(loc)
        val anchor = loc[1] + s.height + ((s.layoutParams as? WindowManager.LayoutParams)?.y ?: 0) // where the bottom of a window is put
        val bottom = visibleBottom()
        if (bottom <= 0) return
        val over = (anchor - bottom).coerceAtLeast(0)
        val full = over > 0 || (s.windowSystemUiVisibility and fullFlags != 0)
        val reading = over * 2 + (if (full) 1 else 0)
        val same = reading == seen; seen = reading
        if (!same) return
        if (over != lift || full != fullScreen) {
            android.util.Log.i("Yunus", "edge: anchor=$anchor bottom=$bottom lift=$over full=$full")
            lift = over
            if (full != fullScreen) fullScreen = full
            if (dockAdded) dock?.let { try { wm.updateViewLayout(it, overlayParams(dockH, if (dockShown) shownFlags else hiddenFlags)) } catch (_: Throwable) { } }
            update()
        }
    }

    // A thin strip along the bottom edge: it hears the system bars' state (an app going full screen
    // hides them) and, while full screen, takes the swipe up that brings the dock.
    private var strip: View? = null
    private val stripH = (22 * ctx.resources.displayMetrics.density).toInt()
    private val fullFlags = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

    fun show() {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        active = true
        addStrip()
        // Over another app the dock stays only a few seconds after that app came up, then gets
        // out of the app's way (apps are laid out to the screen's bottom); a swipe up brings it.
        peek = true
        strip?.let { it.removeCallbacks(endPeek); it.postDelayed(endPeek, PEEK_MS) }
        update()
        strip?.let { it.removeCallbacks(poll); it.post(poll) }
    }

    fun hide() {
        active = false; peek = false
        update()
    }

    /** The dock overlay's page, shown or hidden (it gets the car's data like the main page). */
    fun dockWeb(): WebView? = if (dockAdded) dock else null

    private fun update() {
        if (active && peek) showDock() else hideDock()
        strip?.let { s ->
            val touch = active && !peek
            // Full screen, some apps leave the screen's last pixels unpainted and Yunus's own dock
            // showed through there: the strip covers that edge in black.
            s.setBackgroundColor(if (touch && fullScreen) Color.BLACK else Color.TRANSPARENT)
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
                    v.removeCallbacks(endPeek); v.postDelayed(endPeek, PEEK_MS)
                }
            }
            true
        }
        v.setOnSystemUiVisibilityChangeListener { vis ->
            val full = vis and fullFlags != 0
            if (full != fullScreen) { fullScreen = full; update() }
            v.post { measure() }
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
            setOnTouchListener { _, _ -> if (peek) { strip?.removeCallbacks(endPeek); strip?.postDelayed(endPeek, PEEK_MS) }; false } // kept while in use
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
        // ALT_FOCUSABLE_IM: under the keyboard, not over it (the strip lay over its bottom row).
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.BOTTOM; y = lift }

    private companion object { const val PEEK_MS = 3000L }
}
