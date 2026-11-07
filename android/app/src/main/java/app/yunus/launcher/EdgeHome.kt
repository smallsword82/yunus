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
 * Yunus's bottom bar over any app. BYD's own bar is off the screen; while another app is in front
 * the helper keeps the screen's bottom 112 px free of it (overscan) and Yunus's dock (the page in its
 * dock-only mode, launcher.html#dock, in an overlay window) stays there, always on the screen. A
 * swipe up on it opens the recent apps (the page asks for it). Only while another app is in front
 * (Yunus has its own dock); needs the "display over other apps" permission.
 */
class EdgeHome(private val ctx: Context, private val recents: () -> Unit, private val setupDock: (WebView) -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var dock: WebView? = null
    private var dockShown = false
    private val dockH = 112 // the page's dock row; the page is laid out 1:1 with the screen

    // The dock stays up over the other app, in the room kept free for it at the bottom of the screen
    // (the helper's overscan, which the app's window doesn't reach).
    fun show() {
        if (!android.provider.Settings.canDrawOverlays(ctx)) return
        showDock()
    }

    fun hide() {
        hideDock()
    }

    /** The dock overlay's page, while it is up (it gets the car's data like the main page). */
    fun dockWeb(): WebView? = if (dockShown) dock else null

    // The dock's window is made once, page loaded, and then stays on the screen hidden and
    // untouchable: a swipe only turns it visible. Adding the window (and on the first swipe loading
    // the page) at every swipe made the dock come up late.
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
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN
            or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Below the app's frame, in the room the helper keeps free (the frame ends dockH above the
        // screen's bottom): placed from the top at the screen's full height.
        gravity = Gravity.BOTTOM
        y = -height
    }
}
