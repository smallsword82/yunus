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
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The car's side cameras in Yunus's signal window, the way Kinex reads them. Android hides the
 * car's camera from other apps, but BYD's own camera library (/system/framework/bmmcamera.jar,
 * android.hardware.AVMCamera) opens it: camera "pano_h" (id 0) gives the four fisheye cameras side
 * by side in one 1920×1080 picture - front, left, right, rear, 480 px each.
 *
 * The picture goes through a small GL program on its way to the window. "dewarp" mode takes the
 * fisheye out: each window pixel is a ray of a virtual straight-lens camera looking back along the
 * car (as a door mirror does), found in the fisheye picture (equidistant lens) and mirrored. "flat"
 * mode is the plain camera picture, turned and stretched over the window. The page sends the mode
 * and its numbers (set on the car by eye).
 */
class SideCamera(private val activity: Activity, private val root: FrameLayout, private val onDismiss: () -> Unit) {
    companion object {
        const val BUF_W = 1920
        const val BUF_H = 1080
        private const val IDLE_CLOSE_MS = 30_000L
    }

    private val loader by lazy { dalvik.system.PathClassLoader("/system/framework/bmmcamera.jar", "/system/lib64:/system/lib", activity.classLoader) }
    private var cam: Any? = null
    // The window: the picture and, as on Kinex, "tap to close" along its foot. A tap closes it until
    // the stalk is released (the page keeps it closed, onDismiss).
    private var box: FrameLayout? = null
    private var view: TextureView? = null
    private var gl: Renderer? = null
    private val idle = Runnable { close() }

    /** Shows the camera at x,y w×h (screen px) with the page's settings [json] (see Renderer.set). */
    fun show(x: Int, y: Int, w: Int, h: Int, json: String) {
        root.removeCallbacks(idle)
        val b = box ?: FrameLayout(activity).also { fb ->
            fb.translationZ = 50f // over the embedded apps (Maps)
            fb.setOnClickListener { hide(); onDismiss() }
            root.addView(fb, FrameLayout.LayoutParams(w, h))
            box = fb
        }
        if (view == null) TextureView(activity).also { tv ->
            b.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            b.addView(android.widget.TextView(activity).apply {
                text = "Kapatmak için dokunun"
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(24, 10, 24, 10)
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 40f; setColor(0x8C000000.toInt()) }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = 28 })
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, vw: Int, vh: Int) {
                    gl = Renderer(st, vw, vh) { camTex -> open(camTex) }.also { it.set(JSONObject(json)) }
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, vw: Int, vh: Int) { gl?.resize(vw, vh) }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { stop(); gl?.release(); gl = null; return true }
            }
            view = tv
        }
        b.layoutParams = FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y }
        b.visibility = View.VISIBLE
        gl?.set(JSONObject(json))
        gl?.visible = true
    }

    /** Hides the window; the camera stays open for a while, so the next signal shows it at once. */
    fun hide() {
        box?.visibility = View.GONE
        gl?.visible = false // frames are still taken from the camera, but not drawn
        root.removeCallbacks(idle); root.postDelayed(idle, IDLE_CLOSE_MS)
    }

    fun close() {
        root.removeCallbacks(idle)
        stop()
        gl?.release(); gl = null
        box?.let { root.removeView(it) }
        box = null
        view = null
    }

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
        } catch (t: Throwable) { android.util.Log.w("Yunus", "side camera: ${t.cause ?: t}") }
    }

    private fun stop() {
        val c = cam ?: return
        cam = null
        try { c.javaClass.getMethod("stopPreview").invoke(c); c.javaClass.getMethod("close").invoke(c) } catch (_: Throwable) { }
    }

    /**
     * Draws the camera into the window's surface on a thread of its own: the camera's frames arrive
     * in an external texture, and each one is drawn through the shader below.
     */
    private class Renderer(out: SurfaceTexture, private var w: Int, private var h: Int, onCamera: (SurfaceTexture) -> Unit) {
        private val thread = HandlerThread("yunus-sidecam").apply { start() }
        private val handler = Handler(thread.looper)
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var camTex: SurfaceTexture? = null
        private val texMatrix = FloatArray(16)
        @Volatile var visible = true
        @Volatile private var p = FloatArray(16) // the settings, packed for the shader (see set)

        init { handler.post { setup(out); onCamera(camTex!!) } }

        fun resize(nw: Int, nh: Int) { w = nw; h = nh }

        /**
         * mode "dewarp" | "flat"; sector 0-3 (front, left, right, rear). Lens (dewarp): cx, cy centre
         * and rx, ry radius of the fisheye circle in the sector (0-1 of its width / height), fov the
         * lens's full field (deg). View (dewarp): yaw from straight back towards outwards, pitch
         * down (deg), back -1 if the car's rear is at the picture's left edge (left camera) else 1,
         * hfov the window's field across (deg), mirror. Flat: rot (deg clockwise, 90 steps).
         */
        fun set(o: JSONObject) {
            p = floatArrayOf(
                if (o.optString("mode") == "flat") 0f else 1f, o.optDouble("sector", 1.0).toFloat(),
                o.optDouble("cx", .5).toFloat(), o.optDouble("cy", .5).toFloat(),
                o.optDouble("rx", .5).toFloat(), o.optDouble("ry", .5).toFloat(),
                Math.toRadians(o.optDouble("fov", 190.0) / 2).toFloat(),
                Math.toRadians(o.optDouble("yaw", 15.0)).toFloat(), Math.toRadians(o.optDouble("pitch", 15.0)).toFloat(),
                o.optDouble("back", -1.0).toFloat(), Math.toRadians(o.optDouble("hfov", 70.0) / 2).toFloat(),
                if (o.optBoolean("mirror", true)) 1f else 0f, o.optDouble("rot", 0.0).toFloat(), 0f, 0f, 0f)
        }

        fun release() { handler.post { teardown(); thread.quitSafely() } }

        private fun setup(out: SurfaceTexture) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
            val cfg = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
            context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreateWindowSurface(display, cfg[0], out, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, context)
            program = link(VERTEX, FRAGMENT)
            val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            camTex = SurfaceTexture(tex[0]).apply { setOnFrameAvailableListener({ draw() }, handler) }
        }

        private fun draw() {
            val st = camTex ?: return
            st.updateTexImage() // always take the frame, so the camera never waits on us
            if (!visible) return
            st.getTransformMatrix(texMatrix)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, texMatrix, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uP"), 1, false, p, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAspect"), w.toFloat() / h)
            val pos = GLES20.glGetAttribLocation(program, "aPos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGL14.eglSwapBuffers(display, surface)
        }

        private fun teardown() {
            camTex?.release(); camTex = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
        }

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { s ->
                GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
                val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                if (ok[0] == 0) android.util.Log.w("Yunus", "side camera shader: " + GLES20.glGetShaderInfoLog(s))
            }
            return GLES20.glCreateProgram().also { pr ->
                GLES20.glAttachShader(pr, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(pr, shader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(pr)
            }
        }

        private companion object {
            val QUAD = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
            const val VERTEX = """
                attribute vec2 aPos;
                varying vec2 vPos; // -1..1, y up
                void main() { vPos = aPos; gl_Position = vec4(aPos, 0.0, 1.0); }"""
            // uP (column-major mat4): [0] mode, sector, cx, cy | [1] rx, ry, half fov, yaw | [2] pitch, back, half hfov, mirror | [3] rot
            const val FRAGMENT = """
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
                    // Fisheye camera frame: x picture right, y picture down, z the lens axis.
                    // Its picture's top looks outwards and the car is at its foot; straight back is
                    // along x (to the left edge for the left camera: back = -1).
                    float back = uP[2][1], yaw = uP[1][3], pitch = uP[2][0];
                    vec3 f = normalize(vec3(back * cos(pitch) * cos(yaw), -cos(pitch) * sin(yaw), sin(pitch)));
                    vec3 up = vec3(0.0, 0.0, -1.0);
                    vec3 right = normalize(cross(f, up));
                    vec3 u = cross(right, f);
                    float t = tan(uP[2][2]);
                    float sx = vPos.x * (uP[2][3] > 0.5 ? -1.0 : 1.0);
                    vec3 d = normalize(f + right * sx * t + u * vPos.y * t / uAspect);
                    float theta = acos(clamp(d.z, -1.0, 1.0));
                    float k = theta / uP[1][2]; // 0 at the centre, 1 at the edge of the lens's field
                    vec2 dir = length(d.xy) > 1e-5 ? normalize(d.xy) : vec2(0.0);
                    gl_FragColor = pick(vec2(uP[0][2], uP[0][3]) + dir * k * vec2(uP[1][0], uP[1][1]));
                }"""
        }
    }
}
