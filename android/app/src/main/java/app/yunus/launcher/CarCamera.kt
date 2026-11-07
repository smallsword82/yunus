package app.yunus.launcher

import android.app.Activity
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The car's cameras in Yunus's windows, the way Kinex reads them. Android hides the car's camera
 * from other apps, but BYD's own camera library (/system/framework/bmmcamera.jar,
 * android.hardware.AVMCamera) opens it: camera "pano_h" (id 0) gives the four fisheye cameras side
 * by side in one 1920×1080 picture - rear, left, right, front, 480 px each.
 *
 * One camera, one GL thread, any number of windows: each frame is drawn into every window that is
 * up, through a small GL program with that window's settings. "dewarp" takes the fisheye out: each
 * window pixel is a ray of a virtual straight-lens camera (pointed by the page's numbers), found in
 * the fisheye picture (equidistant lens) and mirrored if asked. "flat" is the plain picture turned
 * and stretched. Windows: "side" (the signal window; a tap closes it, as on Kinex) and "rear" (the
 * rear-view mirror, floating: dragged anywhere with a finger, its place kept; ✕ closes it; its
 * label switches between the rear and the front camera).
 */
class CarCamera(private val activity: Activity, private val root: FrameLayout, private val onEvent: (String) -> Unit) {
    companion object {
        const val BUF_W = 1920
        const val BUF_H = 1080
        private const val IDLE_CLOSE_MS = 30_000L
        const val SLIDE_MS = 150L // the signal window's slide, as long as the page's column (setSigCol)
    }

    private val loader by lazy { dalvik.system.PathClassLoader("/system/framework/bmmcamera.jar", "/system/lib64:/system/lib", activity.classLoader) }
    private val prefs = activity.getSharedPreferences("yunus", android.content.Context.MODE_PRIVATE)
    private var cam: Any? = null
    private var gl: Renderer? = null
    private val windows = HashMap<String, Win>()
    private val idle = Runnable { close() }
    private var warm = false

    private inner class Win(val name: String) {
        val box = FrameLayout(activity)
        val view = TextureView(activity)
        @Volatile var params = FloatArray(16)
        @Volatile var visible = false
        // Frames still to draw before it shows: it is kept see-through till then (the first frames
        // reach the screen a little after they are drawn; shown at the first, it was black a moment).
        @Volatile var waiting = 0
        var out: SurfaceTexture? = null
        var w = 0; var h = 0
        var label: android.widget.TextView? = null // the mirror's front/rear switch
        // The signal window is a window of its own over everything (another app in front too);
        // its place on the screen (the mirror sits in Yunus's own layout).
        var overlay = false
        var x = 0; var y = 0
    }

    private val wm = activity.getSystemService(android.view.WindowManager::class.java)
    private fun overlayParams(win: Win, w: Int, h: Int, touchable: Boolean) = android.view.WindowManager.LayoutParams(
        w, h, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            or (if (touchable) 0 else android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = win.x - originX; y = win.y - originY }
    // Where an overlay's 0,0 is, in Yunus's window's coordinates (the page's): with BYD's bars off
    // the screen, the overlays count from the screen's hidden top. Measured from the window itself.
    private var originX = 0; private var originY = 0
    private fun measureOrigin(win: Win) {
        win.box.post {
            val lp = win.box.layoutParams as? android.view.WindowManager.LayoutParams ?: return@post
            val a = IntArray(2); val r = IntArray(2)
            win.box.getLocationOnScreen(a); root.getLocationOnScreen(r)
            val ox = a[0] - lp.x - r[0]; val oy = a[1] - lp.y - r[1]
            if (ox != originX || oy != originY) {
                originX = ox; originY = oy
                try { wm.updateViewLayout(win.box, overlayParams(win, lp.width, lp.height, win.visible)) } catch (_: Throwable) { }
            }
        }
    }
    private fun canOverlay() = android.provider.Settings.canDrawOverlays(activity)

    /** Shows window [name] at x,y w×h (screen px) with the page's settings [json] (see pack). */
    fun show(name: String, x: Int, y: Int, w: Int, h: Int, json: String) {
        root.removeCallbacks(idle)
        val win = windows.getOrPut(name) { makeWindow(name, w, h) }
        val o = JSONObject(json)
        win.params = pack(o)
        o.optString("label").takeIf { it.isNotEmpty() }?.let { win.label?.text = it }
        // The mirror stays where it was dragged to.
        val px = if (name == "rear") prefs.getInt("rearCamX", x) else x
        val py = if (name == "rear") prefs.getInt("rearCamY", y) else y
        // Moved while up (the signal switched sides): shown afresh, as if it had just opened.
        val moved = win.visible && (if (win.overlay) win.x != px || win.y != py
            else (win.box.layoutParams as? FrameLayout.LayoutParams)?.let { it.leftMargin != px || it.topMargin != py } == true)
        if (moved) win.visible = false
        if (win.overlay) {
            win.x = px; win.y = py
            try { wm.updateViewLayout(win.box, overlayParams(win, w, h, true)) } catch (t: Throwable) { android.util.Log.w("Yunus", "sidecam: $t") }
            measureOrigin(win)
        } else win.box.layoutParams = FrameLayout.LayoutParams(w, h).apply { leftMargin = px; topMargin = py }
        // Until the camera's first frame is drawn in it the window is see-through, not black (opening
        // the camera takes a moment); it shows with that frame.
        if (!win.visible) { win.box.animate().cancel(); win.box.alpha = 0f; win.box.translationX = 0f; win.waiting = 2 }
        win.box.visibility = View.VISIBLE
        win.visible = true
        renderer() // starts the camera when the first window comes up
    }

    /** Hides window [name]; the camera is let go 30 s after the last window went (unless kept warm). */
    fun hide(name: String) {
        val win = windows[name] ?: return
        win.visible = false
        win.waiting = 0
        win.box.animate().cancel()
        // The signal window slides back out to its edge with the page's column; the mirror just goes.
        if (name == "side" && win.box.alpha > 0f) win.box.animate().translationX(edgeOffset(win)).setDuration(SLIDE_MS)
            .setInterpolator(android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f)).withEndAction { if (!win.visible) win.box.visibility = View.GONE }.start()
        else win.box.visibility = View.GONE
        if (win.overlay) { // hidden: touches go through it to what is below
            val lp = win.box.layoutParams as? android.view.WindowManager.LayoutParams
            if (lp != null) try { wm.updateViewLayout(win.box, overlayParams(win, lp.width, lp.height, false)) } catch (_: Throwable) { }
        }
        idleLater()
    }
    // Off the screen at the edge the window sits by (left half: to the left).
    private fun edgeOffset(win: Win): Float {
        val left = if (win.overlay) win.x else (win.box.layoutParams as FrameLayout.LayoutParams).leftMargin
        val width = win.box.width.takeIf { it > 0 } ?: win.box.layoutParams.width
        return if (left + width / 2 < root.width / 2) -width.toFloat() else width.toFloat()
    }

    /**
     * Keeps the camera running while driving (the page asks in D and R), so a signal's window shows
     * a picture at once: opening the camera and its first frame took a moment on the car. Frames
     * are only taken from it, not drawn, while no window is up.
     */
    fun warm(on: Boolean) {
        warm = on
        if (on) { root.removeCallbacks(idle); renderer() } else idleLater()
    }

    private fun idleLater() {
        root.removeCallbacks(idle)
        if (!warm && windows.values.none { it.visible }) root.postDelayed(idle, IDLE_CLOSE_MS)
    }

    fun close() {
        root.removeCallbacks(idle)
        stop()
        gl?.release(); gl = null
        for (win in windows.values) if (win.overlay) try { wm.removeView(win.box) } catch (_: Throwable) { } else root.removeView(win.box)
        windows.clear()
    }

    private fun makeWindow(name: String, w: Int, h: Int): Win {
        val win = Win(name)
        win.box.translationZ = 50f // over the embedded apps (Maps)
        win.box.addView(win.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (name == "rear") {
            win.box.clipToOutline = true
            win.box.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 36f)
            }
            win.box.addView(android.widget.TextView(activity).apply {
                text = "✕"; textSize = 15f; gravity = android.view.Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x8C000000.toInt()) }
                setOnClickListener { hide(name); onEvent("rearClosed") }
            }, FrameLayout.LayoutParams(64, 64, android.view.Gravity.TOP or android.view.Gravity.END).apply { topMargin = 10; rightMargin = 14 })
            // Front ↔ rear: the page picks the camera (rearSwitch) and sends its settings again.
            win.label = android.widget.TextView(activity).apply {
                textSize = 14f; setTextColor(android.graphics.Color.WHITE); setPadding(26, 8, 26, 8)
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 32f; setColor(0x8C000000.toInt()) }
                setOnClickListener { onEvent("rearSwitch") }
            }
            win.box.addView(win.label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.START).apply { topMargin = 12; leftMargin = 16 })
            dragging(win.box)
        } else {
            win.box.setOnClickListener { hide(name); onEvent("sideCamDismissed") }
            win.box.addView(android.widget.TextView(activity).apply {
                text = "Kapatmak için dokunun"; textSize = 13f
                setTextColor(android.graphics.Color.WHITE); setPadding(24, 10, 24, 10)
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 40f; setColor(0x8C000000.toInt()) }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 28 })
        }
        win.view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, vw: Int, vh: Int) { win.out = st; win.w = vw; win.h = vh; gl?.attach(win) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, vw: Int, vh: Int) { win.w = vw; win.h = vh }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { gl?.detach(win); win.out = null; return true }
        }
        if (name == "side" && canOverlay()) {
            win.box.visibility = View.GONE
            try { wm.addView(win.box, overlayParams(win, w, h, false)); win.overlay = true; return win } catch (t: Throwable) { android.util.Log.w("Yunus", "sidecam overlay: $t") }
        }
        root.addView(win.box, FrameLayout.LayoutParams(w, h))
        return win
    }

    /** A finger moves the window anywhere on the screen; where it is let go is kept. */
    private fun dragging(box: FrameLayout) {
        var dx = 0f; var dy = 0f
        box.setOnTouchListener { v, ev ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX - lp.leftMargin; dy = ev.rawY - lp.topMargin }
                MotionEvent.ACTION_MOVE -> {
                    lp.leftMargin = (ev.rawX - dx).toInt().coerceIn(0, root.width - v.width)
                    lp.topMargin = (ev.rawY - dy).toInt().coerceIn(0, root.height - v.height)
                    v.layoutParams = lp
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("rearCamX", lp.leftMargin).putInt("rearCamY", lp.topMargin).apply()
            }
            true
        }
    }

    /** A window's picture is on screen: the signal window slides in from its edge as the page opens its column. */
    private fun ready(win: Win) {
        win.box.alpha = 1f
        if (win.name == "side") {
            win.box.translationX = edgeOffset(win)
            win.box.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f)).start()
        }
        onEvent(win.name + "CamReady")
    }

    private fun renderer(): Renderer = gl ?: Renderer { st -> open(st) }.also { r ->
        gl = r
        for (win in windows.values) if (win.out != null) r.attach(win)
    }

    /**
     * mode "dewarp" | "flat"; sector 0-3 (rear, left, right, front). Lens (dewarp): cx, cy centre
     * and rx, ry radius of the fisheye circle in the sector (0-1 of its width / height), fov the
     * lens's full field (deg). View (dewarp): the virtual camera looks along x (back = -1 or 1: the
     * picture's left or right edge), turned by yaw towards the picture's top and by pitch towards
     * the lens's axis (deg); hfov its field across (deg); mirror. Flat: rot (deg clockwise, 90 steps).
     */
    private fun pack(o: JSONObject) = floatArrayOf(
        if (o.optString("mode") == "flat") 0f else 1f, o.optDouble("sector", 1.0).toFloat(),
        o.optDouble("cx", .5).toFloat(), o.optDouble("cy", .5).toFloat(),
        o.optDouble("rx", .5).toFloat(), o.optDouble("ry", .5).toFloat(),
        Math.toRadians(o.optDouble("fov", 190.0) / 2).toFloat(),
        Math.toRadians(o.optDouble("yaw", 15.0)).toFloat(), Math.toRadians(o.optDouble("pitch", 15.0)).toFloat(),
        o.optDouble("back", -1.0).toFloat(), Math.toRadians(o.optDouble("hfov", 70.0) / 2).toFloat(),
        if (o.optBoolean("mirror", true)) 1f else 0f, o.optDouble("rot", 0.0).toFloat(), Math.toRadians(o.optDouble("roll", 0.0)).toFloat(), 0f, 0f)

    /** Opens the camera into [st] (the renderer's camera texture). Called on the renderer's thread. */
    private fun open(st: SurfaceTexture) {
        if (cam != null) return
        try {
            val c = loader.loadClass("android.hardware.AVMCamera")
            val id = loader.loadClass("android.hardware.BmmCameraInfo").let { it.getMethod("getCameraId", String::class.java).invoke(it.getConstructor().newInstance(), "pano_h") as Int }
            val camera = c.getMethod("open", Integer.TYPE).invoke(null, if (id >= 0) id else 0) ?: return
            c.getMethod("setPreviewSize", Integer.TYPE, Integer.TYPE).invoke(camera, BUF_W, BUF_H)
            st.setDefaultBufferSize(BUF_W, BUF_H)
            // Both: with setPreviewSurface alone no frame ever came (tried on the car).
            val surface = Surface(st)
            c.getMethod("setPreviewSurface", Surface::class.java, Integer.TYPE).invoke(camera, surface, 0)
            c.getMethod("addPreviewSurface", Surface::class.java, Integer.TYPE).invoke(camera, surface, 0)
            c.getMethod("startPreview").invoke(camera)
            cam = camera
        } catch (t: Throwable) { android.util.Log.w("Yunus", "car camera: ${t.cause ?: t}") }
    }

    private fun stop() {
        val c = cam ?: return
        cam = null
        try { c.javaClass.getMethod("stopPreview").invoke(c); c.javaClass.getMethod("close").invoke(c) } catch (_: Throwable) { }
    }

    /**
     * The GL thread: the camera's frames arrive in an external texture, and each is drawn through
     * the shader below into every window that is up (one EGL surface per window).
     */
    private inner class Renderer(onCamera: (SurfaceTexture) -> Unit) {
        private val thread = HandlerThread("yunus-carcam").apply { start() }
        private val handler = Handler(thread.looper)
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var config: EGLConfig? = null
        private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        private val surfaces = HashMap<Win, EGLSurface>()
        private var program = 0
        private var camTex: SurfaceTexture? = null
        private val texMatrix = FloatArray(16)

        init { handler.post { setup(); onCamera(camTex!!) } }

        fun attach(win: Win) = handler.post {
            val out = win.out ?: return@post
            if (win in surfaces) return@post
            surfaces[win] = EGL14.eglCreateWindowSurface(display, config, out, intArrayOf(EGL14.EGL_NONE), 0)
        }

        fun detach(win: Win) {
            // Must finish before the TextureView lets its surface go.
            val done = java.util.concurrent.CountDownLatch(1)
            handler.post { surfaces.remove(win)?.let { EGL14.eglDestroySurface(display, it) }; done.countDown() }
            done.await(1, java.util.concurrent.TimeUnit.SECONDS)
        }

        fun release() { handler.post { teardown(); thread.quitSafely() } }

        private fun setup() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
            val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
            config = cfg[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            // A tiny off-screen surface keeps the context current while no window is up.
            pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            program = link(VERTEX, FRAGMENT)
            val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            for ((k, value) in listOf(GLES20.GL_TEXTURE_MIN_FILTER to GLES20.GL_LINEAR, GLES20.GL_TEXTURE_MAG_FILTER to GLES20.GL_LINEAR,
                    GLES20.GL_TEXTURE_WRAP_S to GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_TEXTURE_WRAP_T to GLES20.GL_CLAMP_TO_EDGE))
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, k, value)
            camTex = SurfaceTexture(tex[0]).apply { setOnFrameAvailableListener({ draw() }, handler) }
        }

        private fun draw() {
            val st = camTex ?: return
            EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
            st.updateTexImage() // always take the frame, so the camera never waits on us
            st.getTransformMatrix(texMatrix)
            for ((win, surface) in surfaces) {
                if (!win.visible || win.w == 0) continue
                EGL14.eglMakeCurrent(display, surface, surface, context)
                GLES20.glViewport(0, 0, win.w, win.h)
                GLES20.glUseProgram(program)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uP"), 1, false, win.params, 0)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAspect"), win.w.toFloat() / win.h)
                val pos = GLES20.glGetAttribLocation(program, "aPos")
                GLES20.glEnableVertexAttribArray(pos)
                GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGL14.eglSwapBuffers(display, surface)
                // Its third frame: shown at once, and the page told (sideCamReady), which opens the
                // signal column just then, so the column is never black while the camera starts.
                if (win.waiting > 0 && --win.waiting == 0) root.post { if (win.visible) ready(win) }
            }
        }

        private fun teardown() {
            camTex?.release(); camTex = null
            if (display == EGL14.EGL_NO_DISPLAY) return
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            for (s in surfaces.values) EGL14.eglDestroySurface(display, s)
            surfaces.clear()
            EGL14.eglDestroySurface(display, pbuffer); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { s ->
                GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
                val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) android.util.Log.w("Yunus", "car camera shader: " + GLES20.glGetShaderInfoLog(s))
            }
            return GLES20.glCreateProgram().also { pr ->
                GLES20.glAttachShader(pr, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(pr, shader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(pr)
            }
        }
    }

    private val QUAD = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val VERTEX = """
        attribute vec2 aPos;
        varying vec2 vPos; // -1..1, y up
        void main() { vPos = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }"""
    // uP (column-major mat4): [0] mode, sector, cx, cy | [1] rx, ry, half fov, yaw | [2] pitch, back, half hfov, mirror | [3] rot, roll
    private val FRAGMENT = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES uCam;
        uniform mat4 uTex;
        uniform mat4 uP;
        uniform float uAspect;
        varying vec2 vPos;
        // A point in the four cameras' picture: sector-relative (0-1, y down) to texture.
        vec4 pick(vec2 s) {
            if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
            vec2 buf = vec2((uP[0][1] + s.x) / 4.0, 1.0 - s.y);
            return texture2D(uCam, (uTex * vec4(buf, 0.0, 1.0)).xy);
        }
        void main() {
            if (uP[0][0] < 0.5) { // flat: the camera picture turned (90° steps) and stretched
                vec2 q = vPos * 0.5 + 0.5; q.y = 1.0 - q.y; // 0-1, y down
                float r = mod(uP[3][0], 360.0);
                vec2 s = r < 45.0 ? q : r < 135.0 ? vec2(q.y, 1.0 - q.x) : r < 225.0 ? vec2(1.0 - q.x, 1.0 - q.y) : vec2(1.0 - q.y, q.x);
                gl_FragColor = pick(s); return;
            }
            // Fisheye camera frame: x picture right, y picture down, z the lens axis. The virtual
            // camera looks along x (back = -1: to the picture's left edge), turned towards the
            // picture's top by yaw and towards the lens axis by pitch; up is away from the lens axis.
            float back = uP[2][1], yaw = uP[1][3], pitch = uP[2][0];
            vec3 f = normalize(vec3(back * cos(pitch) * cos(yaw), -cos(pitch) * sin(yaw), sin(pitch)));
            vec3 up = vec3(0.0, 0.0, -1.0);
            vec3 right = normalize(cross(f, up));
            vec3 u = cross(right, f);
            // Turned about the view's axis, to level the road: the side cameras sit tilted.
            float cr = cos(uP[3][1]), sr = sin(uP[3][1]);
            vec3 r0 = right;
            right = r0 * cr + u * sr;
            u = u * cr - r0 * sr;
            float t = tan(uP[2][2]);
            float sx = vPos.x * (uP[2][3] > 0.5 ? -1.0 : 1.0);
            vec3 d = normalize(f + right * sx * t + u * vPos.y * t / uAspect);
            float theta = acos(clamp(d.z, -1.0, 1.0));
            float k = theta / uP[1][2]; // 0 at the centre, 1 at the edge of the lens's field
            vec2 dir = length(d.xy) > 1e-5 ? normalize(d.xy) : vec2(0.0);
            gl_FragColor = pick(vec2(uP[0][2], uP[0][3]) + dir * k * vec2(uP[1][0], uP[1][1]));
        }"""
}
