package app.yunus.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen Yunus WebView and the read-only Kinex HAL telemetry client.
 *
 * The assets are served from https://appassets.androidplatform.net/assets/ through
 * WebViewAssetLoader, so the page's ES modules, fetches and WebAssembly load as from a server.
 */
private val CAR_COMMON_PERMS = arrayOf(
    "android.permission.BYDAUTO_AC_COMMON", "android.permission.BYDAUTO_BODYWORK_COMMON",
    "android.permission.BYDAUTO_CHARGING_COMMON", "android.permission.BYDAUTO_DOOR_LOCK_COMMON",
    "android.permission.BYDAUTO_ENERGY_COMMON", "android.permission.BYDAUTO_ENGINE_COMMON",
    "android.permission.BYDAUTO_GEARBOX_COMMON", "android.permission.BYDAUTO_INSTRUMENT_COMMON",
    "android.permission.BYDAUTO_LIGHT_COMMON", "android.permission.BYDAUTO_PM2P5_COMMON",
    "android.permission.BYDAUTO_SAFETY_BELT_COMMON", "android.permission.BYDAUTO_SETTING_COMMON",
    "android.permission.BYDAUTO_SPEED_COMMON", "android.permission.BYDAUTO_STATISTIC_COMMON",
    "android.permission.BYDAUTO_TIME_COMMON", "android.permission.BYDAUTO_TYRE_COMMON")

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var halBridge: HalBridge
    private var carHal: CarHal? = null
    private lateinit var shell: ShellLink
    private lateinit var embed: AppEmbed
    private lateinit var volPop: VolumeOverlay
    private lateinit var media: MediaBridge
    private lateinit var volume: VolumeBridge
    private lateinit var status: StatusBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Lets a PC inspect the page over ADB (chrome://inspect) while testing.
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // settings, plate, paint are kept in localStorage
            settings.mediaPlaybackRequiresUserGesture = false
            // The head unit's WebView forces a density-scaled CSS viewport (1280x720 at dpr 1.5),
            // which made the 1920x1080 page render oversized and then scale down. Honour the page's
            // own width=1920 viewport and fit it to the panel, so the page lays out at the screen's
            // native 1920x1080 with devicePixelRatio 1 — no stage scaling, nothing to composite down.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setGeolocationEnabled(true) // the map follows the car's GPS
            addJavascriptInterface(object {
                @JavascriptInterface
                fun status(): String = halBridge.statusJson()
            }, "YunusBridge")
            addJavascriptInterface(object {
                // Opens one of the car's own screens, given as "package/.Activity".
                @JavascriptInterface
                fun open(component: String): Boolean = try {
                    val name = android.content.ComponentName.unflattenFromString(component) ?: return false
                    startActivity(android.content.Intent().setComponent(name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Throwable) { false }

                // Opens an installed app by package name; false if it is not installed.
                @JavascriptInterface
                fun openApp(pkg: String): Boolean = try {
                    val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
                    startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Throwable) { false }

                // A system settings screen for the status bar's shortcuts (wifi, date); false if the
                // head unit has no screen for it (the page then opens the car's own settings).
                @JavascriptInterface
                fun openSetting(which: String): Boolean = try {
                    val action = when (which) {
                        "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
                        "date" -> android.provider.Settings.ACTION_DATE_SETTINGS
                        else -> null
                    }
                    val intent = action?.let { android.content.Intent(it).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    if (intent == null || intent.resolveActivity(packageManager) == null) false else { startActivity(intent); true }
                } catch (_: Throwable) { false }

                // Commands to the car need BYD's signature permissions, which this app cannot hold.
                @JavascriptInterface
                fun command(json: String) {}

                // Shows the first installed of [packages] (comma separated) inside slot [slot] at
                // x,y size w×h (page pixels = screen pixels); the page then gets
                // {embed:{slot,display}}.
                @JavascriptInterface
                fun embedApp(slot: String, packages: String, x: Int, y: Int, w: Int, h: Int) = runOnUiThread {
                    embed.show(slot, packages.split(','), x, y, w, h) { id ->
                        postToPage(JSONObject().put("embed", JSONObject().put("slot", slot).put("display", id)).toString())
                    }
                }

                // Hides a slot while a Yunus card covers its area, and shows it again after.
                @JavascriptInterface
                fun embedVisible(slot: String, visible: Boolean) = runOnUiThread { embed.setVisible(slot, visible) }

                // Closes the app running in a slot (the third window's close button).
                @JavascriptInterface
                fun embedClose(slot: String) = runOnUiThread { embed.closeApp(slot) }

                // Music card: the page asks for now playing once it is ready ({media:{…}} follows
                // on every change); play/pause/toggle/next/prev; pick a session; open its app;
                // open the notification-access setting the media sessions need.
                @JavascriptInterface
                fun mediaRefresh() = runOnUiThread { media.refresh() }

                @JavascriptInterface
                fun media(cmd: String) = runOnUiThread { media.command(cmd) }

                @JavascriptInterface
                fun mediaChoose(pkg: String) = runOnUiThread { media.choose(pkg) }

                // Starts a music app's playback when none of it is open (the card's app list).
                @JavascriptInterface
                fun mediaStart(pkg: String) = runOnUiThread { media.start(pkg) }

                // The car-start resume (setting "Araç çalışınca son müziği çal"): plays in the
                // background only, never opens the app over Yunus.
                @JavascriptInterface
                fun mediaResume(pkg: String) = runOnUiThread { media.start(pkg) }

                @JavascriptInterface
                fun mediaSeek(ms: Double) = runOnUiThread { media.seek(ms.toLong()) }

                // Whether the device (the car's day/night mode) is in night mode, for the Oto theme.
                @JavascriptInterface
                fun isNight(): Boolean = isNightMode()

                // Spoken navigation prompts, in Turkish, through the system text-to-speech, at the
                // guidance voice's own level (0–1, set on Yunus's map).
                @JavascriptInterface
                fun say(text: String, volume: Double) = runOnUiThread { speak(text, volume.toFloat()) }

                // Opens a link (a destination for a navigation app) in the first of [packages] that
                // handles it, else in any app that does.
                @JavascriptInterface
                fun openUri(uri: String, packages: String): Boolean = try {
                    val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    val target = packages.split(',').filter { it.isNotBlank() }
                        .map { android.content.Intent(view).setPackage(it) }
                        .firstOrNull { it.resolveActivity(packageManager) != null } ?: view
                    if (target.resolveActivity(packageManager) == null) false else { startActivity(target); true }
                } catch (_: Throwable) { false }

                // One app's {app,icon} (a dock icon), or null if it is not installed.
                @JavascriptInterface
                fun appInfo(pkg: String): String? = if (packageManager.getLaunchIntentForPackage(pkg) == null) null
                    else org.json.JSONObject().put("app", appLabel(this@MainActivity, pkg)).put("icon", appIconData(this@MainActivity, pkg) ?: org.json.JSONObject.NULL).toString()

                // Every launchable app [{pkg,app,icon}] for the apps card.
                @JavascriptInterface
                fun apps(): String = launcherApps(this@MainActivity)

                // Installed navigation apps [{pkg,app,icon}] for the map-area choice.
                @JavascriptInterface
                fun navApps(): String = navigationApps(this@MainActivity)

                // Dock volume: the page asks for the level once ({volume:{cur,max}} then follows
                // every change) and sets it from its keys and slider.
                @JavascriptInterface
                fun volumeRefresh() = runOnUiThread { volume.send() }

                // Wi-Fi and mobile signal for the top bar: the page asks once it is ready, then
                // {net:{…}} follows every change.
                @JavascriptInterface
                fun statusRefresh() = runOnUiThread { status.send(true) }

                @JavascriptInterface
                fun volumeSet(level: Int) = runOnUiThread { volume.set(level) }

                // The volume bar, drawn natively above the embedded apps (the page can't cover them),
                // at the page bar's place and with its colours; k is screen px per design px.
                @JavascriptInterface
                fun volumePopup(x: Int, y: Int, w: Int, h: Int, k: Double, level: Int, max: Int, bg: String, fg: String, track: String) = runOnUiThread {
                    val color = { s: String -> try { android.graphics.Color.parseColor(s.trim()) } catch (_: Exception) { android.graphics.Color.GRAY } }
                    volPop.show(x, y, w, h, k.toFloat(), level, max, color(bg), color(fg), color(track))
                }

                @JavascriptInterface
                fun mediaOpen() = runOnUiThread {
                    media.currentPackage()?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } }
                }

                @JavascriptInterface
                fun mediaAccess() = runOnUiThread {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }, "YunusNative")
            webChromeClient = object : WebChromeClient() { // alert/confirm/prompt dialogs
                // The page is our own asset, so its location requests are allowed without a prompt
                // (the app-level location permission is asked once at start).
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) =
                    callback.invoke(origin, true, false)
            }
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)
            }
        }
        halBridge = HalBridge(assets, filesDir, ::postToPage)
        val root = android.widget.FrameLayout(this).apply { addView(web) }
        shell = ShellLink(this, { msg -> postToPage(JSONObject().put("embedStatus", msg).toString()) }) { line -> carFromHelper(line) }
        // Windows, sunshade and speed through the shell helper (read only), as Kinex reads them.
        shell.send("watch")
        embed = AppEmbed(this, root, shell)
        volPop = VolumeOverlay(this) { volume.set(it) }.also { root.addView(it) }
        media = MediaBridge(this, ::postToPage)
        volume = VolumeBridge(this, ::postToPage)
        status = StatusBridge(this, ::postToPage).also { it.start() }
        setContentView(root)
        if (savedInstanceState == null) web.loadUrl("https://appassets.androidplatform.net/assets/launcher.html")
        else web.restoreState(savedInstanceState)
        // halBridge (the Kinex HAL service over ADB) is no longer started: CarHal reads the car
        // in-process, and the ADB connection is now used only for the shell helper (ShellLink).
        // Ask for the BYDAuto _COMMON permissions (dangerous/grantable) once. With them we can
        // construct the HAL devices — instrument, ac, doorlock, gearbox, charging, statistic... —
        // and register listeners; live values then arrive through the push path. The enforcing
        // getters stay locked behind BYD's signature _GET perms, which no third-party app can hold.
        requestCarPermissions()
        // Live vehicle data the way 3rd-party apps on this head unit do it (BYD Road Computer /
        // electro.app.br): read the BYDAuto HAL devices directly via listeners, in-process.
        carHal = CarHal(applicationContext, ::postToPage).also { it.start() }
    }

    private fun isNightMode() =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    // uiMode is in configChanges, so a day/night switch arrives here instead of restarting; the
    // page's Oto theme follows it.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        postToPage(JSONObject().put("deviceDark", isNightMode()).toString())
    }

    // Text-to-speech starts on the first prompt; a prompt asked for meanwhile is spoken once ready.
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var ttsPending: Pair<String, Float>? = null
    private fun speak(text: String, volume: Float) {
        if (ttsReady) {
            val params = android.os.Bundle().apply { putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
            tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, "yunus-nav")
            return
        }
        ttsPending = text to volume
        if (tts == null) tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale("tr", "TR")
                // Navigation guidance, not media: the system lowers the music while it speaks.
                tts?.setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                ttsReady = true
                ttsPending?.let { (t, v) -> speak(t, v) }
                ttsPending = null
            }
        }
    }

    private fun requestCarPermissions() {
        try {
            val want = (CAR_COMMON_PERMS + android.Manifest.permission.ACCESS_FINE_LOCATION).filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
        } catch (_: Throwable) { }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Re-start the HAL reader so any devices unlocked by a fresh grant get listeners attached.
        if (requestCode == 1) { carHal?.close(); carHal = CarHal(applicationContext, ::postToPage).also { it.start() } }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Back in front, also after another window covered Yunus (an app in the third window starting
    // its player on the main screen for a moment): the page draws only on change, so it is asked to
    // draw again, or the screen would stay black.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        hideSystemBars()
        if (::web.isInitialized) { web.invalidate(); postToPage(JSONObject().put("redraw", true).toString()) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // Back from the notification-access setting, the music card may now read the media sessions.
    override fun onResume() { super.onResume(); web.onResume(); media.refresh() }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onDestroy() { carHal?.close(); halBridge.close(); embed.close(); shell.close(); media.close(); volume.close(); status.close(); tts?.shutdown(); web.destroy(); super.onDestroy() }

    // "ev <Device> 0x<id> <i|f|d> <value>" from the shell helper → the page's car events.
    private fun carFromHelper(line: String) {
        val f = line.split(' ')
        if (f.size != 5 || f[0] != "ev") return
        val v = f[4].toDoubleOrNull() ?: return
        val key = when (f[3]) { "i" -> "int"; "f" -> "float"; else -> "double" }
        val o = JSONObject().put("dev", f[1]).put("type", f[2]).put(key, if (key == "int") v.toInt() else v)
        postToPage(JSONObject().put("carHal", o).toString())
    }

    private fun postToPage(message: String) {
        runOnUiThread {
            if (::web.isInitialized && !isFinishing) {
                val quoted = JSONObject.quote(message)
                web.evaluateJavascript("window.yunusCar && window.yunusCar(JSON.parse($quoted))", null)
            }
        }
    }
}

/**
 * Other apps' screens inside Yunus (Google Maps in the map area, …), the way Kinex does it: each
 * slot is a private virtual display drawn into a TextureView over its area. The app is started on
 * the display by the shell helper (`am start --display <id>`; the shell may launch onto a display
 * it does not own) and touches on the view are injected into that display by the same helper.
 */
private class AppEmbed(private val activity: Activity, private val root: android.widget.FrameLayout, private val shell: ShellLink) {
    private class Slot(val view: android.view.TextureView) {
        var display: android.hardware.display.VirtualDisplay? = null
        var component: String? = null // the app shown, so a different one can replace it
        val displayId get() = display?.display?.displayId ?: -1
    }
    private val slots = HashMap<String, Slot>()

    /** Shows [packages]' first installed app in slot [name] at x,y size w×h (screen pixels). */
    fun show(name: String, packages: List<String>, x: Int, y: Int, w: Int, h: Int, onReady: (Int) -> Unit) {
        // -1: none of the apps is installed, so the page keeps its own content there.
        val launch = packages.firstNotNullOfOrNull { activity.packageManager.getLaunchIntentForPackage(it)?.component } ?: return onReady(-1)
        val slot = slots.getOrPut(name) {
            Slot(android.view.TextureView(activity)).also { s ->
                root.addView(s.view)
                s.view.setOnTouchListener { _, ev -> shell.send(touchLine(s.displayId, ev)); true }
            }
        }
        slot.view.layoutParams = android.widget.FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y }
        slot.view.visibility = android.view.View.VISIBLE
        val dpi = activity.resources.displayMetrics.densityDpi
        val component = launch.flattenToShortString()
        // The helper stops the app's task elsewhere first; otherwise that task would just come to
        // the front on the main screen. A different app chosen for the slot is started on top.
        fun launchIfNew() {
            if (slot.component == component) return
            slot.component = component
            // Not started after all: forget it, so choosing the app again retries the launch.
            shell.send("launch ${slot.displayId} $component") { activity.runOnUiThread { if (slot.component == component) slot.component = null } }
        }
        fun attach(texture: android.graphics.SurfaceTexture) {
            texture.setDefaultBufferSize(w, h)
            val existing = slot.display
            if (existing != null) { existing.resize(w, h, dpi); launchIfNew(); onReady(slot.displayId); return }
            val dm = activity.getSystemService(android.hardware.display.DisplayManager::class.java)
            // OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL (hidden flag 1 << 8), as Kinex uses.
            val flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8)
            slot.display = dm.createVirtualDisplay("yunus-$name", w, h, dpi, android.view.Surface(texture), flags)
            launchIfNew()
            onReady(slot.displayId)
        }
        val texture = slot.view.surfaceTexture
        if (texture != null) attach(texture)
        else slot.view.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: android.graphics.SurfaceTexture, width: Int, height: Int) = attach(t)
            override fun onSurfaceTextureSizeChanged(t: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(t: android.graphics.SurfaceTexture) {}
            // Keep the texture (and so the display's surface) across a pause.
            override fun onSurfaceTextureDestroyed(t: android.graphics.SurfaceTexture) = false
        }
    }

    /** Closes the app in slot [name]: its tasks on the display go, the view hides, and the next
     *  show() launches it afresh. */
    fun closeApp(name: String) {
        val slot = slots[name] ?: return
        if (slot.displayId > 0) shell.send("close ${slot.displayId}")
        slot.component = null
        slot.view.visibility = android.view.View.GONE
    }

    fun setVisible(name: String, visible: Boolean) {
        slots[name]?.view?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    fun close() {
        for (s in slots.values) { s.display?.release(); root.removeView(s.view) }
        slots.clear()
    }

    // "t <display> <action> <id> <x> <y> …" in the view's own pixels, which are the display's.
    private fun touchLine(display: Int, ev: android.view.MotionEvent) = buildString {
        append("t ").append(display).append(' ').append(ev.action)
        for (i in 0 until ev.pointerCount) append(' ').append(ev.getPointerId(i)).append(' ').append(ev.getX(i)).append(' ').append(ev.getY(i))
    }
}

/**
 * Yunus's line to its shell-side helper (ShellHelper, run with app_process from this APK). If the
 * helper is not running it is started over Yunus's own ADB connection to the car (127.0.0.1:5555;
 * the owner approves Yunus's key once on the screen). Lines are sent in order on one thread.
 */
private class ShellLink(private val ctx: android.content.Context, private val status: (String) -> Unit, private val onLine: (String) -> Unit = {}) {
    companion object { private const val PORT = 19340 }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "yunus-shell").apply { isDaemon = true } }
    // The app's install time is part of it, so after an update the old helper (old code) is
    // not reused but replaced.
    private val token = File(ctx.filesDir, "shell.token").let { f ->
        if (f.exists()) f.readText() else java.util.UUID.randomUUID().toString().also { f.writeText(it) }
    } + "-" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
    private var socket: Socket? = null

    /** Sends [line]; [failed] runs (on this link's thread) if the helper could not be reached. */
    fun send(line: String, failed: () -> Unit = {}) = worker.execute {
        try { write(line) } catch (_: Throwable) {
            try { socket?.close() } catch (_: Throwable) { }
            socket = null
            try { write(line) } catch (t: Throwable) { failed(); status("Kabuk yardımcısı yok: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    private fun write(line: String) {
        val s = socket ?: connect()
        s.getOutputStream().apply { write("$line\n".toByteArray()); flush() }
    }

    private fun connect(): Socket {
        tryConnect()?.let { socket = it; return it }
        status("Kabuk yardımcısı başlatılıyor (araç ekranında hata ayıklama iznini onayla)")
        val apk = ctx.applicationInfo.sourceDir
        // The helper runs in the foreground of an ADB shell that stays open (as Kinex does): a
        // backgrounded one died with the session, which adbd closes as soon as the shell exits.
        // Its life is tied to Yunus's: when Yunus goes, the connection and the helper go too.
        Thread({
            try {
                DirectAdb("127.0.0.1", 5555, ctx.filesDir).use {
                    it.connect()
                    // "[S]" so the pattern does not match this pkill's own command line.
                    it.shell("pkill -f 'app.yunus.launcher.[S]hellHelper'", 1)
                    it.hold("CLASSPATH=$apk exec app_process /system/bin app.yunus.launcher.ShellHelper $PORT $token 2>/data/local/tmp/yunus_helper.log")
                }
            } catch (t: Throwable) { status("Kabuk yardımcısı durdu: ${t.message ?: t.javaClass.simpleName}") }
        }, "yunus-shell-adb").apply { isDaemon = true }.start()
        repeat(30) {
            Thread.sleep(500)
            tryConnect()?.let { socket = it; status("Kabuk yardımcısı hazır"); return it }
        }
        error("yardımcı başlamadı")
    }

    private fun tryConnect(): Socket? = try {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", PORT), 1000)
        s.tcpNoDelay = true
        s.getOutputStream().apply { write("$token\n".toByteArray()); flush() }
        s.soTimeout = 2000
        val input = s.getInputStream().bufferedReader()
        val reply = input.readLine()
        s.soTimeout = 0
        if (reply == "ok") {
            // Anything the helper sends back (car values from "watch") arrives on this connection.
            Thread({ try { while (true) onLine(input.readLine() ?: break) } catch (_: Throwable) { } }, "yunus-shell-in").apply { isDaemon = true }.start()
            s
        } else { s.close(); null }
    } catch (_: Throwable) { null }

    fun close() { worker.shutdownNow(); try { socket?.close() } catch (_: Throwable) { } }
}

/**
 * Connects only to the loopback Kinex HAL service. It is deliberately read-only: no remote
 * command/call surface is exposed to JavaScript. The car's wireless ADB endpoint is expected at
 * 127.0.0.1:5555 and must have been enabled/paired by the owner.
 */
private class HalBridge(private val assets: AssetManager, private val appFiles: File, private val emit: (String) -> Unit) {
    companion object {
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_PORT = 5555
        private const val HAL_HOST = "127.0.0.1"
        private const val HAL_PORT = 19322
        private const val MAX_FRAME = 2 * 1024 * 1024
        private const val HAL_JAR_ASSET = "kinex_hal_api_service.jar"
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "yunus-hal").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    @Volatile private var connection = "Bekleniyor"
    @Volatile private var detail = ""
    @Volatile private var activeHalSocket: Socket? = null
    private var task: Future<*>? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        task = worker.submit {
            while (running.get()) {
                try {
                    connection = "ADB bağlantısı deneniyor"
                    publishStatus()
                    val adb = DirectAdb(ADB_HOST, ADB_PORT, appFiles)
                    adb.use {
                        it.connect()
                        val localJar = File(appFiles, HAL_JAR_ASSET)
                        if (!localJar.exists()) assets.open(HAL_JAR_ASSET).use { input -> localJar.outputStream().use { output -> input.copyTo(output) } }
                        it.pushIfNeeded(localJar, "/data/local/tmp/yunus_hal_api_service.jar")
                        it.shell("CLASSPATH=/data/local/tmp/yunus_hal_api_service.jar app_process / com.lexwah.kinex.hal.HalApiServiceMain $HAL_PORT >/dev/null 2>&1 &", 2)
                        connection = "HAL servisi bekleniyor"
                        publishStatus()
                        readHal()
                    }
                } catch (t: Throwable) {
                    detail = t.message ?: t.javaClass.simpleName
                    connection = "Bağlantı yok"
                    publishStatus()
                    try { Thread.sleep(5000) } catch (_: InterruptedException) { }
                }
            }
        }
    }

    fun statusJson(): String = JSONObject().put("adb", connection).put("detail", detail).toString()

    private fun publishStatus() {
        emit(JSONObject().put("bridge", JSONObject().put("adb", connection).put("detail", detail)).toString())
    }

    private fun readHal() {
        Socket().use { socket ->
            activeHalSocket = socket
            socket.connect(InetSocketAddress(HAL_HOST, HAL_PORT), 3000)
            socket.soTimeout = 0
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            connection = "HAL bağlı; el sıkışma gerekli"
            publishStatus()
            // The framing is known (big-endian int byte count + UTF-8 JSON), but Kinex's
            // handshake seed/proof is not yet verified. Do not invent a handshake or report live.
            // Handshake fields must be taken from the verified Kinex client protocol before
            // sending anything. Keep this connection diagnostic-only until that is decoded.
            while (running.get()) {
                val message = readFrame(input)
                detail = message.toString()
                when (message.optString("type")) {
                    "event" -> {
                        // Raw service events are retained for diagnosis until the event schema and
                        // field units have been validated on the Dolphin. No guessed field mapping.
                        emit(JSONObject().put("halEvent", message).toString())
                    }
                    "error" -> {
                        connection = "HAL protokol hatası"
                        detail = message.optString("error", "HAL error")
                        publishStatus()
                    }
                    else -> {
                        connection = "HAL yanıtı alındı; veri eşlemesi bekliyor"
                        publishStatus()
                    }
                }
            }
            activeHalSocket = null
        }
    }

    private fun readFrame(input: DataInputStream): JSONObject {
        val length = input.readInt()
        require(length in 1..MAX_FRAME) { "Geçersiz HAL çerçeve boyutu: $length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return JSONObject(String(bytes, StandardCharsets.UTF_8))
    }

    fun close() {
        running.set(false)
        try { activeHalSocket?.close() } catch (_: Exception) { }
        task?.cancel(true)
        worker.shutdownNow()
        try { worker.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
    }
}

/** Minimal ADB-over-TCP transport, authenticated with the app's own RSA key (see AdbAuth). */
private class DirectAdb(host: String, port: Int, keyDir: File) : java.io.Closeable {
    private val socket = Socket().apply { connect(InetSocketAddress(host, port), 2500); soTimeout = 5000 }
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val auth = AdbAuth(keyDir)

    fun connect() {
        sendAdb("CNXN", 0x01000000, 4096, "host::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII))
        var signed = false
        repeat(4) {
            val packet = readAdb()
            when (packet.command) {
                "CNXN" -> { socket.soTimeout = 5000; return }
                "AUTH" -> {
                    when (packet.arg0) {
                        // A second token means adbd did not know our key: send the public key, and
                        // adbd asks the owner on screen, so give them a minute to approve it.
                        1 -> if (!signed) { signed = true; sendAdb("AUTH", 2, 0, auth.sign(packet.payload)) }
                             else { sendAdb("AUTH", 3, 0, auth.publicKey()); socket.soTimeout = 60_000 }
                        else -> error("ADB auth türü desteklenmiyor: ${packet.arg0}")
                    }
                }
                else -> error("ADB bağlantı yanıtı: ${packet.command}")
            }
        }
        error("ADB kimlik doğrulaması tamamlanmadı")
    }

    fun pushIfNeeded(local: File, remote: String) {
        if (!local.exists()) error("HAL JAR eksik: ${local.name}")
        val sync = open("sync:")
        try {
            val stat = syncStat(sync, remote)
            if (stat != null && local.length() == stat) return
            val pathMode = "$remote,33261".toByteArray(StandardCharsets.UTF_8)
            sync.write("SEND".toByteArray(Charsets.US_ASCII)); sync.writeIntLE(pathMode.size); sync.write(pathMode)
            local.inputStream().use { file ->
                val chunk = ByteArray(4096) // ADB max-data negotiated in CNXN.
                while (true) {
                    val count = file.read(chunk)
                    if (count < 0) break
                    sync.write("DATA".toByteArray(Charsets.US_ASCII)); sync.writeIntLE(count); sync.write(chunk, 0, count)
                }
            }
            sync.write("DONE".toByteArray(Charsets.US_ASCII)); sync.writeIntLE((System.currentTimeMillis() / 1000).toInt())
            val response = ByteArray(8); sync.readFully(response)
            if (String(response, 0, 4, Charsets.US_ASCII) != "OKAY") error("ADB JAR aktarımı başarısız: ${String(response, Charsets.US_ASCII)}")
            val msgLen = littleInt(response, 4)
            if (msgLen > 0) { val msg = ByteArray(msgLen); sync.readFully(msg); error("ADB SYNC: ${String(msg, Charsets.UTF_8)}") }
        } finally { closeStream(sync) }
    }

    fun shell(command: String, waitSeconds: Int) {
        val stream = open("shell:$command")
        // Shell v2 emits an exit packet. The HAL daemon intentionally stays alive; close the
        // stream after a short start window while leaving the remote shell process running.
        try { socket.soTimeout = waitSeconds * 1000; try { stream.readAvailable(4096) } catch (_: java.net.SocketTimeoutException) { } }
        finally { closeStream(stream); socket.soTimeout = 5000 }
    }

    /** Runs [command] and keeps its stream, and so the process, open until it ends or the connection closes. */
    fun hold(command: String) {
        val stream = open("shell:$command")
        socket.soTimeout = 0
        while (stream.readAvailable(4096).isNotEmpty()) { }
    }

    private fun open(destination: String): AdbStream {
        val id = nextLocalId++
        sendAdb("OPEN", id, 0, (destination + "\u0000").toByteArray(StandardCharsets.UTF_8))
        while (true) {
            val p = readAdb()
            when (p.command) {
                "OKAY" -> if (p.arg1 == id) return AdbStream(id, p.arg0)
                "CLSE" -> error("ADB stream açılamadı: $destination")
                else -> error("ADB stream yanıtı beklenmiyordu: ${p.command}")
            }
        }
    }

    private fun syncStat(stream: AdbStream, path: String): Long? {
        val payload = path.toByteArray(StandardCharsets.UTF_8)
        stream.write("STAT".toByteArray(Charsets.US_ASCII)); stream.writeIntLE(payload.size); stream.write(payload)
        val header = ByteArray(4); stream.readFully(header)
        val id = String(header, Charsets.US_ASCII)
        return when (id) {
            "STAT" -> {
                val stat = ByteArray(12); stream.readFully(stat)
                if (littleInt(stat, 0) == 0) null else littleInt(stat, 4).toLong()
            }
            "FAIL" -> {
                val lenBytes = ByteArray(4); stream.readFully(lenBytes)
                val size = littleInt(lenBytes, 0).coerceIn(0, 4096)
                val msg = ByteArray(size); stream.readFully(msg); null
            }
            else -> error("ADB STAT yanıtı: $id")
        }
    }

    private fun sendAdb(command: String, arg0: Int, arg1: Int, payload: ByteArray) {
        val cmd = command.toByteArray(Charsets.US_ASCII).foldIndexed(0) { i, acc, b -> acc or ((b.toInt() and 0xff) shl (8 * i)) }
        val check = payload.fold(0L) { s, b -> s + (b.toInt() and 0xff) }.toInt()
        output.writeIntLE(cmd); output.writeIntLE(arg0); output.writeIntLE(arg1); output.writeIntLE(payload.size)
        output.writeIntLE(check); output.writeIntLE(cmd.inv()); output.write(payload); output.flush()
    }

    private fun readAdb(): AdbPacket {
        val cmd = input.readIntLE(); val a0 = input.readIntLE(); val a1 = input.readIntLE(); val len = input.readIntLE()
        val checksum = input.readIntLE(); val magic = input.readIntLE()
        require(magic == cmd.inv() && len in 0..1024 * 1024) { "Geçersiz ADB paketi" }
        val payload = ByteArray(len); input.readFully(payload)
        require(payload.fold(0L) { s, b -> s + (b.toInt() and 0xff) }.toInt() == checksum) { "ADB checksum uyuşmuyor" }
        val name = CharArray(4) { ((cmd ushr (8 * it)) and 0xff).toChar() }.concatToString()
        return AdbPacket(name, a0, a1, payload)
    }

    private fun closeStream(stream: AdbStream) {
        try {
            sendAdb("CLSE", stream.localId, stream.remoteId, ByteArray(0))
            while (true) {
                val p = readAdb()
                if (p.command == "CLSE" && p.arg0 == stream.remoteId) break
            }
        } catch (_: Exception) { }
    }

    override fun close() { try { socket.close() } finally { auth.close() } }
    private data class AdbPacket(val command: String, val arg0: Int, val arg1: Int, val payload: ByteArray)
    private inner class AdbStream(val localId: Int, val remoteId: Int) {
        private var pending = ByteArray(0)
        private var pendingOffset = 0
        fun write(bytes: ByteArray) {
            sendAdb("WRTE", localId, remoteId, bytes)
            while (true) {
                val p = readAdb()
                if (p.command == "OKAY" && p.arg0 == remoteId && p.arg1 == localId) return
                if (p.command == "CLSE") error("ADB stream kapandı")
            }
        }
        fun write(bytes: ByteArray, offset: Int, count: Int) { write(bytes.copyOfRange(offset, offset + count)) }
        fun writeIntLE(v: Int) { write(le(v)) }
        fun flush() = Unit
        fun readAvailable(maxBytes: Int): ByteArray {
            while (true) {
                val p = readAdb()
                when (p.command) {
                    "WRTE" -> { sendAdb("OKAY", localId, remoteId, ByteArray(0)); return p.payload.copyOf(minOf(maxBytes, p.payload.size)) }
                    "CLSE" -> return ByteArray(0)
                }
            }
        }
        fun readFully(target: ByteArray) {
            var offset = 0
            while (offset < target.size) {
                if (pendingOffset >= pending.size) {
                    val p = readAdb()
                    when (p.command) {
                        "WRTE" -> { pending = p.payload; pendingOffset = 0; sendAdb("OKAY", localId, remoteId, ByteArray(0)) }
                        "CLSE" -> error("ADB stream kapandı")
                        else -> error("ADB stream paketi: ${p.command}")
                    }
                }
                val n = minOf(target.size - offset, pending.size - pendingOffset)
                pending.copyInto(target, offset, pendingOffset, pendingOffset + n)
                offset += n
                pendingOffset += n
            }
        }
    }

    companion object {
        private var nextLocalId = 1
        private fun le(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
        private fun littleInt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8) or ((bytes[offset+2].toInt() and 255) shl 16) or ((bytes[offset+3].toInt() and 255) shl 24)
    }
}

private fun DataInputStream.readIntLE(): Int = readUnsignedByte() or (readUnsignedByte() shl 8) or (readUnsignedByte() shl 16) or (readUnsignedByte() shl 24)
private fun DataOutputStream.writeIntLE(v: Int) { writeByte(v); writeByte(v ushr 8); writeByte(v ushr 16); writeByte(v ushr 24) }
/**
 * ADB RSA credentials, kept in the app's private files: the head unit's Android keystore fails to
 * sign with them ("Keystore operation failed"). The owner approves this key once on the screen.
 */
private class AdbAuth(dir: File) : java.io.Closeable {
    private val pair: java.security.KeyPair
    init {
        val privFile = File(dir, "adbkey.pk8")
        val pubFile = File(dir, "adbkey.x509")
        val factory = java.security.KeyFactory.getInstance("RSA")
        // A key file that can't be read (cut short, or from an older build) is replaced rather than
        // failing every connection; adbd then asks once more to allow the new key.
        val saved = try {
            if (privFile.exists() && pubFile.exists()) java.security.KeyPair(
                factory.generatePublic(java.security.spec.X509EncodedKeySpec(pubFile.readBytes())),
                factory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privFile.readBytes())))
            else null
        } catch (_: Exception) { null }
        pair = saved ?: java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().also {
            privFile.writeBytes(it.private.encoded); pubFile.writeBytes(it.public.encoded)
        }
    }
    // adbd verifies RSA over an SHA-1 digest and the 20-byte token *is* that digest, so sign the
    // DigestInfo itself (SHA1withRSA would hash the token a second time).
    fun sign(token: ByteArray): ByteArray = java.security.Signature.getInstance("NONEwithRSA").run { initSign(pair.private); update(SHA1_DIGEST_INFO + token); sign() }
    fun publicKey(): ByteArray {
        val rsa = pair.public as java.security.interfaces.RSAPublicKey
        val words = 64
        val modulus = fixedLittleEndian(rsa.modulus, words)
        val rr = java.math.BigInteger.ONE.shiftLeft(words * 64).mod(rsa.modulus)
        // ADB RSAPublicKey: n0inv = -(n mod 2^32)^-1 mod 2^32, as a 32-bit word.
        val radix = java.math.BigInteger.ONE.shiftLeft(32)
        val n0inv = radix.subtract(rsa.modulus.mod(radix).modInverse(radix)).toInt()
        val blob = java.io.ByteArrayOutputStream().also { b ->
            val out = DataOutputStream(b); out.writeIntLE(words); out.writeIntLE(n0inv); out.write(modulus); out.write(fixedLittleEndian(rr, words)); out.writeIntLE(rsa.publicExponent.toInt())
        }.toByteArray()
        val encoded = android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP) + " yunus@android"
        return (encoded + "\u0000").toByteArray(Charsets.US_ASCII)
    }
    private fun fixedLittleEndian(value: java.math.BigInteger, words: Int): ByteArray {
        val be = value.toByteArray().let { if (it.size > words * 4) it.copyOfRange(it.size - words * 4, it.size) else it }
        val out = ByteArray(words * 4)
        for (i in be.indices) out[i] = be[be.lastIndex - i]
        return out
    }
    override fun close() = Unit

    companion object {
        private val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
    }
}

/**
 * Live vehicle data via the direct BYDAuto HAL, the way 3rd-party apps on this head unit read it
 * (e.g. the electro.app.br reader). It constructs the HAL devices that don't enforce a permission
 * at getInstance (charging, statistic, gearbox, tyre, light, pm2p5, power, sensor, ota) and
 * registers an IBYDAutoListener. The getters enforce signature BYDAUTO_*_GET perms a self-signed
 * app can't hold, but the event PUSH path does not — so the values the car pushes
 * (onDataEventChanged) arrive with no permissions. Fully reflective: no BYD SDK, no declared
 * BYDAUTO permissions, in-process (no ADB). Each distinct event is forwarded to the page as
 * {carHal:{dev,type,int|float|double}} only when its value changes.
 */
private class CarHal(private val ctx: android.content.Context, private val emit: (String) -> Unit) {
    // Every device we can construct once the _COMMON perms are granted. Each one that constructs
    // gets a listener; the car pushes live values through onDataEventChanged (not permission-gated).
    // speed/bodywork are listed too but enforce _GET even on getInstance, so they simply fail here.
    private val deviceClasses = listOf(
        "instrument.BYDAutoInstrumentDevice", "ac.BYDAutoAcDevice",
        "doorlock.BYDAutoDoorLockDevice", "energy.BYDAutoEnergyDevice",
        "engine.BYDAutoEngineDevice", "gearbox.BYDAutoGearboxDevice",
        "charging.BYDAutoChargingDevice", "statistic.BYDAutoStatisticDevice",
        "tyre.BYDAutoTyreDevice", "light.BYDAutoLightDevice",
        "pm2p5.BYDAutoPM2p5Device", "power.BYDAutoPowerDevice",
        "sensor.BYDAutoSensorDevice", "ota.BYDAutoOtaDevice",
        "safetybelt.BYDAutoSafetyBeltDevice", "setting.BYDAutoSettingDevice",
        // Windows and the sunshade live here; it is expected to refuse (signature _GET), tried
        // in case this head unit's build lets the listener through.
        "bodywork.BYDAutoBodyworkDevice")
    private val held = java.util.concurrent.CopyOnWriteArrayList<Any>()
    private val last = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val invalid = setOf(-999999999.0, -1.0E9, -9.99999999E8)

    fun start() {
        for (d in deviceClasses) try { listen("android.hardware.bydauto.$d") } catch (_: Throwable) { }
    }

    private fun listen(clsName: String) {
        val c = Class.forName(clsName)
        val dev = c.getMethod("getInstance", android.content.Context::class.java).invoke(null, ctx) ?: return
        var iface: Class<*>? = null
        var reg: java.lang.reflect.Method? = null
        for (m in c.methods) if (m.name == "registerListener") {
            val p = m.parameterTypes
            if (p.size == 1 && p[0].isInterface) { reg = m; iface = p[0]; break }
        }
        val regM = reg ?: return
        val ifc = iface ?: return
        val tag = clsName.substringAfterLast('.').removePrefix("BYDAuto").removeSuffix("Device")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(ifc.classLoader, arrayOf(ifc)) { _, m, args ->
            try {
                if (m.name == "onDataEventChanged" && args != null && args.size == 2) {
                    onEvent(tag, (args[0] as? Int) ?: 0, args[1])
                }
            } catch (_: Throwable) { }
            when (m.returnType) { java.lang.Boolean.TYPE -> false; Integer.TYPE -> 0; else -> null }
        }
        regM.invoke(dev, proxy)
        held.add(dev)
        try { c.getMethod("getAllStatus").invoke(dev) } catch (_: Throwable) { }
    }

    private fun onEvent(dev: String, type: Int, value: Any?) {
        if (value == null) return
        val o = JSONObject().put("dev", dev).put("type", String.format("0x%08X", type))
        var meaningful = false
        for (f in arrayOf("intValue", "floatValue", "doubleValue")) {
            try {
                val v = value.javaClass.getField(f).get(value) as? Number ?: continue
                if (v.toDouble() in invalid) continue
                o.put(f.removeSuffix("Value"), v); meaningful = true
            } catch (_: Throwable) { }
        }
        if (!meaningful) return
        val key = dev + "|" + String.format("0x%08X", type)
        val s = o.toString()
        if (last.put(key, s) == s) return // only forward on change
        emit(JSONObject().put("carHal", o).toString())
    }

    fun close() { held.clear(); last.clear() }
}
