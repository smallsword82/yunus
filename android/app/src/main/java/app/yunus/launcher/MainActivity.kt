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
    private var carHal: CarHal? = null
    @Volatile private var halWanted: Set<String>? = null // see carHalWant; null: all (before the page says)
    private fun halWant(key: String) = halWanted?.contains(key) ?: true
    private lateinit var shell: ShellLink
    private lateinit var embed: AppEmbed
    private lateinit var carCam: CarCamera
    private lateinit var volPop: VolumeOverlay
    private lateinit var media: MediaBridge
    private lateinit var bt: BtBridge
    private lateinit var edge: EdgeHome
    private lateinit var nativeApi: Any // YunusNative, shared with the dock overlay's page
    private lateinit var volume: VolumeBridge
    private lateinit var status: StatusBridge

    companion object {
        /** Yunus's screen exists in this process (MediaListener brings it up after a crash if not). */
        @Volatile var alive = false
    }

    @SuppressLint("SetJavaScriptEnabled")
    // A small display nobody sees, for an app's screen that must exist but not show: a closed music
    // app started from the card (MediaBridge.wake), the car's media center switching its source.
    private var bgDisplay: android.hardware.display.VirtualDisplay? = null
    private fun bgDisplayId(): Int = (bgDisplay ?: run {
        val reader = android.media.ImageReader.newInstance(480, 320, android.graphics.PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, null)
        getSystemService(android.hardware.display.DisplayManager::class.java).createVirtualDisplay("yunus-bg", 480, 320, 160, reader.surface,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8))
    }.also { bgDisplay = it }).display.displayId

    /** The car's media center to [mode] (0 radio, 9 DAB, 1 music, 2 video; [source] 0 local, 1 USB,
     *  3 Bluetooth for music and video), as the steering wheel's MODE key does but without its screen:
     *  its activity takes the request (its service needs BYD's signature) on the hidden display. */
    fun mediaMode(mode: Int, source: Int) {
        val i = android.content.Intent("byd.intent.action.START_MEDIA").setClassName("com.byd.mediacenter", "com.byd.mediacenter.main.MediaActivity")
            .putExtra("MediaMode", mode).putExtra("MediaAction", 1)
            .putExtra("MediaParams", android.os.Bundle().apply { if (source >= 0) putInt("source", source); putString("package", packageName); putString("withui", "false") })
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(bgDisplayId()).toBundle())
        } catch (t: Throwable) {
            System.err.println("mediaMode: $t"); startActivity(i)
        }
        // Yunus stays in front in any case (the media center may come up on the main screen).
        web.postDelayed({ startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }, 400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Lets a PC inspect the page over ADB (chrome://inspect) while testing.
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT) // see-through where the page is, over the map under it
            outlineProvider = null // raised by Z under pop-ups: no shadow
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
            }, "YunusBridge")
            nativeApi = object {
                // Opens one of the car's own screens, given as "package/.Activity".
                @JavascriptInterface
                fun open(component: String): Boolean = try {
                    val name = android.content.ComponentName.unflattenFromString(component) ?: return false
                    startActivity(android.content.Intent().setComponent(name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: Throwable) { false }

                // Opens an installed app by package name; false if it is not installed.
                // The car values the page uses ("Dev 0xID" / "Get key"): only these are passed on. The car
                // pushes many more (one cluster value flips 20 times a second), each one evaluated in
                // both pages; that alone kept Yunus busy while nothing on the screen changed.
                @JavascriptInterface
                fun carHalWant(keys: String) {
                    halWanted = try { org.json.JSONArray(keys).let { a -> (0 until a.length()).map { a.getString(it) }.toHashSet() } } catch (_: Throwable) { null }
                }

                // The app tray's long press, as the car's own launcher: the app's info screen, and
                // removing it (Android asks to confirm). System apps can't be removed: the page hides that.
                @JavascriptInterface
                fun appDetails(pkg: String) = runOnUiThread {
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$pkg")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
                }

                @JavascriptInterface
                fun appUninstall(pkg: String) = runOnUiThread {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:$pkg")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
                }

                @JavascriptInterface
                fun appRemovable(pkg: String): Boolean = try {
                    val f = packageManager.getApplicationInfo(pkg, 0).flags
                    f and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 && f and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                } catch (_: Throwable) { false }

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
                        "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                        "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        else -> null
                    }
                    val intent = action?.let { android.content.Intent(it).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    if (intent == null || intent.resolveActivity(packageManager) == null) false else { startActivity(intent); true }
                } catch (_: Throwable) { false }

                // Commands to the car (window, sunshade) go to the shell helper, which runs them on
                // the bodywork device with the permission-passing context (the way it reads them).
                @JavascriptInterface
                fun command(json: String) { shell.send("cmd $json") }

                // The bell's panel: the notifications (see MediaListener.list), open, dismiss, clear all.
                @JavascriptInterface
                fun notifList(): String = MediaListener.instance?.list() ?: "null"
                @JavascriptInterface
                fun notifOpen(key: String) { MediaListener.instance?.open(key) }
                @JavascriptInterface
                fun notifDismiss(key: String) { MediaListener.instance?.dismiss(key) }
                @JavascriptInterface
                fun notifClear() { MediaListener.instance?.clearAll() }

                // The status bar's Bluetooth panel (see BtBridge): the list comes back as {btList}.
                @JavascriptInterface
                fun btReport() = bt.report()
                @JavascriptInterface
                fun btSet(on: Boolean) = bt.set(on)
                @JavascriptInterface
                fun btConnect(addr: String, on: Boolean) = bt.connect(addr, on)

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
                @JavascriptInterface
                fun embedShift(slot: String, dx: Int, cut: Int, ms: Int) = runOnUiThread { embed.shift(slot, dx, cut, ms.toLong()) }

                // While a Yunus pop-up over the map is open the page is raised above the embedded apps
                // (it is see-through there), so the pop-up shows over live Maps; after, Maps is on
                // top again, fastest and taking its touches. Raised by Z, not moved: a TextureView
                // taken out and put back loses its picture until the app draws again.
                // The car's home screen: Yunus or DiLink's launcher (see the helper's "home").
                @JavascriptInterface
                fun isHome(): Boolean = isDefaultHome()
                @JavascriptInterface
                fun setHome(yunus: Boolean) { wantHome(this@MainActivity, yunus); shell.send(if (yunus) "home yunus" else "home byd") }

                // The recent apps (BYD's bar with its key is off the screen); the shell sends the key.
                @JavascriptInterface
                fun recents() { shell.send("recents") }

                // The car's cameras (CarCamera): the signal window and the rear-view mirror, each at
                // a screen rect with its settings (JSON).
                @JavascriptInterface
                fun sideCam(x: Int, y: Int, w: Int, h: Int, json: String) = runOnUiThread { carCam.show("side", x, y, w, h, json) }
                @JavascriptInterface
                fun sideCamHide() = runOnUiThread { carCam.hide("side") }
                @JavascriptInterface
                fun rearCam(x: Int, y: Int, w: Int, h: Int, json: String) = runOnUiThread { carCam.show("rear", x, y, w, h, json) }
                @JavascriptInterface
                fun rearCamHide() = runOnUiThread { carCam.hide("rear") }
                @JavascriptInterface
                fun camWarm(on: Boolean) = runOnUiThread { carCam.warm(on) }

                @JavascriptInterface
                fun mapUnder(under: Boolean) = runOnUiThread {
                    // Raised over the map, without the shadow a raised view casts (a full-screen
                    // shadow redrawn every frame made the app tray stutter as it slid in).
                    if (web.outlineProvider != null) web.outlineProvider = null
                    web.translationZ = if (under) 10f else 0f
                }

                // Closes the app running in a slot (the third window's close button).
                @JavascriptInterface
                fun embedClose(slot: String) = runOnUiThread { embed.closeApp(slot) }
                // The page's colour behind it (seen where a sliding embedded app hasn't arrived yet).
                @JavascriptInterface
                fun rootBg(css: String) = runOnUiThread {
                    val c = try { if (css.startsWith("#")) android.graphics.Color.parseColor(if (css.length == 4) "#" + css.drop(1).map { "$it$it" }.joinToString("") else css)
                        else Regex("[0-9.]+").findAll(css).map { it.value.toFloat().toInt() }.toList().let { android.graphics.Color.rgb(it[0], it[1], it[2]) } } catch (_: Throwable) { null }
                    if (c != null) { (web.parent as? android.view.View)?.setBackgroundColor(c); embed.coverColor = c }
                }
                @JavascriptInterface
                fun embedFull(slot: String) = runOnUiThread { embed.fullApp(slot) }
                @JavascriptInterface
                fun embedBack(slot: String) = runOnUiThread { embed.back(slot) }
                @JavascriptInterface
                fun embedMaxKey(on: Boolean) = runOnUiThread { embed.maxKey(on) }

                // Music card: the page asks for now playing once it is ready ({media:{…}} follows
                // on every change); play/pause/toggle/next/prev; pick a session; open its app;
                // open the notification-access setting the media sessions need.
                @JavascriptInterface
                fun mediaRefresh() = runOnUiThread { media.refresh() }

                @JavascriptInterface
                fun media(cmd: String) = runOnUiThread { media.command(cmd) }

                @JavascriptInterface
                fun mediaChoose(pkg: String) = runOnUiThread { media.choose(pkg) }
                @JavascriptInterface
                fun mediaMode(mode: Int, source: Int) = runOnUiThread { this@MainActivity.mediaMode(mode, source) }

                // Permission status for Yunus › Sistem, and the "İzinleri tazele" button.
                @JavascriptInterface
                fun permissions() = runOnUiThread { sendPermissions() }

                @JavascriptInterface
                fun fixPermissions() = runOnUiThread { this@MainActivity.fixPermissions() }

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

                // The car's signed-in user (name and photo) for the status bar, {user:{…}}.
                @JavascriptInterface
                fun userRefresh() = CarUser.read(applicationContext, ::postToPage)

                // Wi-Fi and mobile signal for the top bar: the page asks once it is ready, then
                // {net:{…}} follows every change.
                @JavascriptInterface
                fun statusRefresh() = runOnUiThread {
                    status.send(true)
                    MediaListener.instance?.let { postToPage(JSONObject().put("notifCount", it.count()).toString()) }
                }

                // A pull down on Yunus's status bar: the car's own quick panel (BYD's BydQSBar),
                // opened by the shell helper with the swipe a finger would make on the car's bar.
                @JavascriptInterface
                fun openCarPanel() = shell.send("qsbar")

                // The bell: pulls down the system's notification shade over Yunus.
                @JavascriptInterface
                fun openNotifications() = runOnUiThread {
                    try {
                        val sb = getSystemService("statusbar")
                        sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
                    } catch (_: Throwable) { }
                }

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

                // From the dock over another app: Yunus to the front, then [card] opened there ('' just home).
                @JavascriptInterface
                fun dockHome(card: String) = runOnUiThread {
                    edge.hideDock()
                    startActivity(android.content.Intent(this@MainActivity, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    postToPage(JSONObject().put("openCard", card).toString())
                }
            }
            addJavascriptInterface(nativeApi, "YunusNative")
            webChromeClient = object : WebChromeClient() { // alert/confirm/prompt dialogs
                // The page is our own asset, so its location requests are allowed without a prompt
                // (the app-level location permission is asked once at start).
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) =
                    callback.invoke(origin, true, false)
            }
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)
                // Loaded: everything the car said so far, so its keys (seat heating on, …) show as they are.
                override fun onPageFinished(view: WebView, url: String) {
                    for (m in dockCache.values.toList()) view.evaluateJavascript("window.yunusCar && window.yunusCar(JSON.parse(${JSONObject.quote(m)}))", null)
                }
            }
        }
        val root = android.widget.FrameLayout(this).apply { addView(web) }
        shell = ShellLink(this, { msg ->
            shellReady = msg.contains("hazır")
            postToPage(JSONObject().put("embedStatus", msg).toString())
        }) { line -> carFromHelper(line) }
        // Windows, sunshade and speed through the shell helper (read only), as Kinex reads them.
        shell.watch()
        // The car came up on DiLink's home although Yunus is the chosen one (seen after a restart:
        // the choice was gone, and with BYD's bars still off the screen nothing could be reached).
        // Make Yunus the home screen again; the helper also puts the bars back off the screen.
        if (!isDefaultHome(this) && wantHome(this)) shell.send("home yunus")
        contentResolver.registerContentObserver(android.provider.Settings.System.getUriFor(VOICE_KEY), false, voiceObserver)
        voiceLift()
        embed = AppEmbed(this, root, shell) { ev -> postToPage(JSONObject().put(ev, true).toString()) } // sideBack, sideClose
        carCam = CarCamera(this, root) { ev -> postToPage(JSONObject().put(ev, true).toString()) } // sideCamDismissed, rearClosed
        volPop = VolumeOverlay(this) { volume.set(it) }.also { root.addView(it) }
        media = MediaBridge(this, ::postToPage)
        // A closed music app started from the card: its screen opens on a small display nobody sees
        // (see MediaBridge.wake), then closes once it plays.
        media.wake = { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()?.let { shell.send("launch ${bgDisplayId()} $it") }
        }
        media.sleep = { bgDisplay?.let { android.util.Log.d("YunusMedia", "close ${it.display.displayId}"); shell.send("close ${it.display.displayId}") } }
        bt = BtBridge(this, ::postToPage)
        edge = EdgeHome(this, { shell.send("recents") }, { val d = window.decorView; val l = IntArray(2); d.getLocationOnScreen(l); l[1] + d.height }) { dock ->
            // The dock overlay's page: the same app bridge and assets as the main page.
            dock.addJavascriptInterface(nativeApi, "YunusNative")
            dock.webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)
                // Loaded: everything the car said so far, so its keys (seat heating on, …) show as they are.
                override fun onPageFinished(view: WebView, url: String) {
                    for (m in dockCache.values.toList()) view.evaluateJavascript("window.yunusCar && window.yunusCar(JSON.parse(${JSONObject.quote(m)}))", null)
                }
            }
        }
        volume = VolumeBridge(this, ::postToPage)
        status = StatusBridge(this, ::postToPage).also { it.start() }
        // The bell's count from the notification listener (only the number, never the content).
        MediaListener.onCount = { n -> postToPage(JSONObject().put("notifCount", n).toString()) }
        setContentView(root)
        if (savedInstanceState == null) web.loadUrl("https://appassets.androidplatform.net/assets/launcher.html")
        else web.restoreState(savedInstanceState)
        // CarHal reads the car in-process; the ADB connection serves the shell helper (ShellLink).
        // Ask for the BYDAuto _COMMON permissions (dangerous/grantable) once. With them we can
        // construct the HAL devices — instrument, ac, doorlock, gearbox, charging, statistic... —
        // and register listeners; live values then arrive through the push path. The enforcing
        // getters stay locked behind BYD's signature _GET perms, which no third-party app can hold.
        // Everything else Yunus needs is asked for here too (the first start after installing
        // above all): location, and the music card's notification access (see fixPermissions).
        fixPermissions()
        // Live vehicle data the way 3rd-party apps on this head unit do it (BYD Road Computer /
        // electro.app.br): read the BYDAuto HAL devices directly via listeners, in-process.
        carHal = CarHal(applicationContext, ::halWant, ::postToPage).also { it.start() }
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
        if (requestCode == 1) { carHal?.close(); carHal = CarHal(applicationContext, ::halWant, ::postToPage).also { it.start() } }
        sendPermissions()
    }

    // ---------- permissions: what Yunus needs, and putting it right in one go ----------
    private val listenerComponent get() = android.content.ComponentName(this, MediaListener::class.java).flattenToString()

    private fun permissionState(): JSONObject {
        val granted = { p: String -> checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        return JSONObject()
            .put("car", CAR_COMMON_PERMS.count(granted)).put("carTotal", CAR_COMMON_PERMS.size)
            .put("location", granted(android.Manifest.permission.ACCESS_FINE_LOCATION))
            .put("media", media.hasAccess())
            .put("adb", shellReady)
    }

    private fun sendPermissions() = postToPage(JSONObject().put("perms", permissionState()).toString())

    /**
     * Asks for everything missing: the car and location permissions through Android's own dialog,
     * and the notification access the music card needs. BYD replaced that setting's screen with an
     * "unsupported" page, so Yunus gives it to its own listener over its ADB connection (the one the
     * owner approved for the shell helper): one fixed command, only for Yunus's own component.
     */
    private fun fixPermissions() {
        requestCarPermissions()
        if (!media.hasAccess()) Thread({
            val ok = try {
                DirectAdb("127.0.0.1", 5555, filesDir).use {
                    it.connect()
                    it.shell("cmd notification allow_listener $listenerComponent", 3)
                }
                true
            } catch (t: Throwable) { false }
            runOnUiThread { if (ok) media.refresh(); sendPermissions() }
        }, "yunus-perms").apply { isDaemon = true }.start()
        else sendPermissions()
    }
    @Volatile private var shellReady = false

    // BYD's voice assistant draws its bar inside the status bar, which is above the screen in Yunus's
    // mode: while it talks (it writes CONVERSATION_BEGIN / _END to this system setting) the helper
    // brings the status bar back on the screen, and takes it up again after.
    private val VOICE_KEY = "KEY_VOICE_DIALOGEVENT"
    private var voiceLifted = false
    private val voiceObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = voiceLift()
    }
    private fun voiceLift() {
        val talking = android.provider.Settings.System.getString(contentResolver, VOICE_KEY) == "CONVERSATION_BEGIN"
        if (talking && !voiceLifted && barsOffScreen()) { voiceLifted = true; shell.send("voice 1") }
        else if (!talking && (voiceLifted || (isDefaultHome() && !barsOffScreen()))) { // (or left down by a Yunus restart mid-talk)
            voiceLifted = false
            shell.send("voice 0")
            window.decorView.postDelayed({ hideSystemBars() }, 800)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Up to the top edge, where the hidden status bar was (Android 14 otherwise starts the window below it).
        if (android.os.Build.VERSION.SDK_INT >= 30 && window.attributes.layoutInDisplayCutoutMode != android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS)
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        // On the car BYD's bars are pushed off the screen (wm overscan 0,-84,0,-90): then they are
        // left "shown" up there, out of sight, so a swipe shows nothing and the helper can pull the
        // status bar there to open the quick panel. Elsewhere they are hidden as usual.
        if (barsOffScreen() || voiceLifted) { // (down for the voice assistant for a moment: left as it is)
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) // the theme's
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            return
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // The display's overscan is negative at the top (the status bar above the screen), read from
    // the hidden DisplayInfo the system keeps for it.
    // (Hidden API: reached by reflecting on reflection, as BtBridge does.)
    private fun barsOffScreen(): Boolean = try {
        val infoCls = Class.forName("android.view.DisplayInfo")
        val info = infoCls.getConstructor().newInstance()
        val getMethod = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
        val getField = Class::class.java.getDeclaredMethod("getDeclaredField", String::class.java)
        val m = getMethod.invoke(android.view.Display::class.java, "getDisplayInfo", arrayOf<Class<*>>(infoCls)) as java.lang.reflect.Method
        m.isAccessible = true
        m.invoke(windowManager.defaultDisplay, info)
        val f = getField.invoke(infoCls, "overscanTop") as java.lang.reflect.Field
        f.isAccessible = true
        f.getInt(info) < 0
    } catch (t: Throwable) { android.util.Log.w("Yunus", "barsOffScreen: $t"); false }

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
    // The swipe-up-for-home strip is over the other apps only (Yunus has its own dock).
    // Another app in front: Yunus's dock stays on the screen over it (hidden while it is full screen).
    // Yunus is home: the back key goes to the page (the third window back from full screen) instead
    // of leaving Yunus for the app behind it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { postToPage(JSONObject().put("back", true).toString()) }

    override fun onResume() { super.onResume(); web.onResume(); media.refresh(); edge.hide(); embed.backFromFull() }
    override fun onPause() { web.onPause(); if (isDefaultHome()) edge.show(); super.onPause() }

    private fun isDefaultHome(): Boolean = isDefaultHome(this)


    override fun onDestroy() { alive = false; carCam.close(); contentResolver.unregisterContentObserver(voiceObserver); carHal?.close(); bt.close(); embed.close(); shell.close(); media.close(); volume.close(); status.close(); tts?.shutdown(); web.destroy(); super.onDestroy() }

    // "ev <Device> 0x<id> <i|f|d> <value>" from the shell helper → the page's car events.
    private fun carFromHelper(line: String) {
        if (line.startsWith("msg ")) return postToPage(line.substring(4)) // a reply for the page as it is (Wi-Fi, …)
        if (line.startsWith("shown ")) { line.substring(6).trim().toIntOrNull()?.let { d -> runOnUiThread { embed.shown(d) } }; return }
        val f = line.split(' ')
        if (f.size != 5 || f[0] != "ev" || !halWant(f[1] + " " + f[2])) return
        val v = f[4].toDoubleOrNull() ?: return
        val key = when (f[3]) { "i" -> "int"; "f" -> "float"; else -> "double" }
        val o = JSONObject().put("dev", f[1]).put("type", f[2]).put(key, if (key == "int") v.toInt() else v)
        postToPage(JSONObject().put("carHal", o).toString())
    }

    // The car's last word on each thing (a HAL value by device and type, else by the message's
    // keys), for the dock page when it loads.
    private val dockCache = LinkedHashMap<String, String>()
    private fun cacheKey(message: String): String = try {
        val o = JSONObject(message)
        o.optJSONObject("carHal")?.let { "hal " + it.optString("dev") + " " + it.optString("type") }
            ?: o.keys().asSequence().joinToString(",")
    } catch (_: Throwable) { message }

    private fun postToPage(message: String) {
        runOnUiThread {
            if (::web.isInitialized && !isFinishing) {
                val quoted = JSONObject.quote(message)
                web.evaluateJavascript("window.yunusCar && window.yunusCar(JSON.parse($quoted))", null)
                // The dock over other apps gets the same, hidden or not, so it is up to date when it comes up.
                dockCache.remove(cacheKey(message)); dockCache[cacheKey(message)] = message
                if (dockCache.size > 600) dockCache.remove(dockCache.keys.first())
                if (::edge.isInitialized) edge.dockWeb()?.evaluateJavascript("window.yunusCar && window.yunusCar(JSON.parse($quoted))", null)
            }
        }
    }
}

/**
 * Other apps' screens inside Yunus (Google Maps in the map area, …), the way Kinex does it: each
 * slot is a private virtual display shown by a SurfaceView over its area. A SurfaceView's picture
 * is put on the screen by the system on its own, under Yunus's window (seen through a hole the view
 * leaves in it): the app's frames don't make Yunus draw again. A TextureView drew them inside the
 * window, so every frame Maps drew (it moves all the time) cost a whole frame of Yunus's page as
 * well, ~45 ms with a pop-up over the map: everything over it stuttered. The app is started on
 * the display by the shell helper (`am start --display <id>`; the shell may launch onto a display
 * it does not own) and touches on the view are injected into that display by the same helper.
 */
private class AppEmbed(private val activity: Activity, private val root: android.widget.FrameLayout, private val shell: ShellLink,
                       private val onEvent: (String) -> Unit) {
    // box: the slot's area (its clip is the hole, see shift); view: the app's picture inside it.
    private class Slot(val box: android.widget.FrameLayout, val view: android.view.SurfaceView) {
        var display: android.hardware.display.VirtualDisplay? = null
        var pending: ((android.view.Surface) -> Unit)? = null // makes the display once the surface is there
        var component: String? = null // the app shown, so a different one can replace it
        val displayId get() = display?.display?.displayId ?: -1
        var keys: List<android.view.View> = emptyList() // the third window's floating back and close keys
        // The display's own size when it differs from the window (Android Auto: 1920×1080, fitted into
        // the window keeping its shape); 0: the window's size. cw×ch: the picture's size in the window.
        var vw = 0; var vh = 0; var w = 0; var h = 0; var cw = 0f; var ch = 0f
        var cover: android.view.View? = null // the page's colour over the window while its app starts
    }
    private val slots = HashMap<String, Slot>()
    // The window shows black until its app's first frame (a cold start: seconds): the page's colour
    // covers it until the helper says the app is shown ("shown <display>"), at most 10 s.
    var coverColor = 0xff0b0c0e.toInt()
    private fun cover(slot: Slot) {
        val v = slot.cover ?: android.view.View(activity).also { slot.cover = it; slot.box.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        v.animate().cancel(); v.setBackgroundColor(coverColor); v.alpha = 1f; v.visibility = android.view.View.VISIBLE; v.bringToFront()
        val token = Any(); v.tag = token
        root.postDelayed({ if (v.tag === token) uncover(slot) }, 10000)
    }
    private fun uncover(slot: Slot) {
        val v = slot.cover ?: return
        v.tag = null
        if (v.visibility != android.view.View.VISIBLE) return
        v.animate().alpha(0f).setDuration(150).withEndAction { v.visibility = android.view.View.GONE }.start()
    }
    fun shown(display: Int) { slots.values.firstOrNull { it.displayId == display }?.let { s -> root.postDelayed({ uncover(s) }, 120) } } // its frame reaches the screen just after
    private val KEY = 60 // px, the floating keys' size

    // The third window's back (to Yunus's app list, the app keeps running) and close keys float over
    // the app's top corners: the page can't draw over an embedded app. Kept as separate views on the
    // root, above the app's view (moving that view into a box would blank it).
    private fun sideKeys(): List<android.view.View> = listOf("←" to "sideBack", "✕" to "sideClose").map { (glyph, ev) ->
        android.widget.TextView(activity).apply {
            text = glyph; textSize = 15f; gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x8C000000.toInt()) }
            setOnClickListener { onEvent(ev) } // added after the app's view, so above it; a card (web raised) still covers both
            root.addView(this, android.widget.FrameLayout.LayoutParams(KEY, KEY))
        }
    }
    private fun placeKeys(slot: Slot, x: Int, y: Int, w: Int) {
        val (back, close) = slot.keys
        back.layoutParams = android.widget.FrameLayout.LayoutParams(KEY, KEY).apply { leftMargin = x + 12; topMargin = y + 12 }
        close.layoutParams = android.widget.FrameLayout.LayoutParams(KEY, KEY).apply { leftMargin = x + w - 12 - KEY; topMargin = y + 12 }
    }
    private fun showKeys(slot: Slot, on: Boolean) { for (k in slot.keys) { k.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE; k.translationX = 0f } }

    /** Shows [packages]' first installed app in slot [name] at x,y size w×h (screen pixels). */
    fun show(name: String, packages: List<String>, x: Int, y: Int, w: Int, h: Int, onReady: (Int) -> Unit) {
        // -1: none of the apps is installed, so the page keeps its own content there.
        val launch = packages.firstNotNullOfOrNull { activity.packageManager.getLaunchIntentForPackage(it)?.component } ?: return onReady(-1)
        val slot = slots.getOrPut(name) { newSlot(name) }
        slot.box.layoutParams = android.widget.FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y }
        // Android Auto lays its 16:9 picture out for a car screen: in a window-sized display it came out
        // wider than the window (its app bar cut off) with bars above and below. It gets a display of
        // the car screen's size instead, shown in the window whole, keeping its shape.
        // A short third window (under the map, ~430 px) broke apps laid out for the car's screen (BYD's
        // phone showed only its "Ara" key): it gets a display at least 720 px tall, same shape, scaled
        // into the window.
        val minH = 720
        val (dw, dh) = if (launch.packageName == "com.byd.androidauto") 1920 to 1080
            else if (name == "side" && h in 1 until minH) (w * minH / h) to minH else w to h
        val resized = slot.display != null && (slot.w != w || slot.h != h)
        slot.w = w; slot.h = h; slot.vw = if (dw != w || dh != h) dw else 0; slot.vh = if (slot.vw > 0) dh else 0
        val k = if (slot.vw > 0) minOf(w.toFloat() / dw, h.toFloat() / dh) else 1f
        slot.cw = dw * k; slot.ch = dh * k
        // The picture: the window's size, or the display's shape fitted in it and centred (the system
        // scales the buffer to the view).
        slot.view.layoutParams = android.widget.FrameLayout.LayoutParams(slot.cw.toInt(), slot.ch.toInt()).apply {
            leftMargin = ((w - slot.cw) / 2).toInt(); topMargin = ((h - slot.ch) / 2).toInt() }
        slot.view.translationX = 0f; slot.box.translationX = 0f; slot.box.clipBounds = null // placed for real: no shift (see shift)
        cuts.remove(name)
        // Covered from the moment it shows: a new app (until its first frame) - the window was black
        // until its display existed - or a resized one while it redraws at the new size (Maps showed
        // its old picture stretched, black under it, as the layout changed).
        val newApp = slot.component != launch.flattenToShortString()
        if (newApp || resized) cover(slot)
        if (resized && !newApp) slot.cover?.let { c -> val t = c.tag; root.postDelayed({ if (c.tag === t) uncover(slot) }, 350) }
        slot.box.visibility = android.view.View.VISIBLE
        if (slot.keys.isNotEmpty()) { placeKeys(slot, x, y, w); showKeys(slot, true) }
        val dpi = activity.resources.displayMetrics.densityDpi
        val component = launch.flattenToShortString()
        // The helper stops the app's task elsewhere first; otherwise that task would just come to
        // the front on the main screen. A different app chosen for the slot is started on top.
        fun launchIfNew() {
            if (slot.component == component) return
            slot.component = component
            // Not started after all: forget it, so choosing the app again retries the launch.
            shell.send("launch ${slot.displayId} $component") { activity.runOnUiThread { if (slot.component == component) slot.component = null } }
            // The map app opens on its last view, not following the car. A tap on Google Maps'
            // my-location button (bottom-right, ~45dp from the right, ~122dp up) a few seconds after
            // launch centers it on the car and turns on follow mode, so the car stays centered.
            if (name == "map" && launch.packageName.contains("apps.maps")) { // Google Maps only (not Android Auto, Yandex…)
                val d = slot.displayId
                val bx = w - 45f * dpi / 160f; val by = h - 122f * dpi / 160f
                root.postDelayed({
                    if (slot.component != component || slot.displayId != d) return@postDelayed
                    shell.send("t $d 0 0 $bx $by") // ACTION_DOWN
                    root.postDelayed({ shell.send("t $d 1 0 $bx $by") }, 60) // ACTION_UP → a tap
                }, 4500)
            }
        }
        slot.view.holder.setFixedSize(dw, dh)
        val existing = slot.display
        if (existing != null) { existing.resize(dw, dh, dpi); launchIfNew(); onReady(slot.displayId); return }
        // The display is made once its surface is there (now, or when the view's surface comes).
        slot.pending = { surface ->
            val dm = activity.getSystemService(android.hardware.display.DisplayManager::class.java)
            // OWN_CONTENT_ONLY | DESTROY_CONTENT_ON_REMOVAL (hidden flag 1 << 8), as Kinex uses.
            val flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8)
            slot.display = dm.createVirtualDisplay("yunus-$name", dw, dh, dpi, surface, flags)
            launchIfNew()
            onReady(slot.displayId)
        }
        val sf = slot.view.holder.surface
        if (sf != null && sf.isValid) slot.pending?.let { slot.pending = null; it(sf) }
    }

    private fun newSlot(name: String): Slot {
        val box = android.widget.FrameLayout(activity).apply { clipChildren = true }
        val view = android.view.SurfaceView(activity)
        // The third window's surface over the map's where they meet (both under Yunus's window).
        if (name != "map") view.setZOrderMediaOverlay(true)
        box.addView(view)
        root.addView(box)
        val slot = Slot(box, view)
        view.setOnTouchListener { _, ev -> shell.send(touchLine(slot, ev)); true }
        view.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            // The surface comes and goes with the view on the screen (hidden, Yunus in the
            // background): the app keeps running on its display, drawn again into the new surface.
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                val p = slot.pending
                if (p != null) { slot.pending = null; p(holder.surface); return }
                val d = slot.display ?: return
                d.surface = holder.surface
                // The app draws only when something changes: the new surface stayed black for a few
                // seconds (back to Yunus from another app). A pixel's resize and back makes it draw now.
                val dpi = activity.resources.displayMetrics.densityDpi
                val m = android.util.DisplayMetrics().also { d.display.getRealMetrics(it) }
                d.resize(m.widthPixels, m.heightPixels - 1, dpi)
                root.postDelayed({ if (slot.display === d) d.resize(m.widthPixels, m.heightPixels, dpi) }, 60)
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) { slot.display?.surface = null }
        })
        return slot
    }

    /** Closes the app in slot [name]: its tasks on the display go, the view hides, and the next
     *  show() launches it afresh. */
    // The app taken full screen from its slot: back in Yunus, it goes into the slot again.
    private var full: String? = null
    fun fullApp(name: String) {
        val slot = slots[name] ?: return
        if (slot.displayId <= 0 || slot.component == null) return
        full = name
        shell.send("full ${slot.displayId} ${slot.component}")
    }
    fun backFromFull() {
        val name = full ?: return
        full = null
        val slot = slots[name] ?: return
        val c = slot.component ?: return
        if (slot.displayId > 0) shell.send("launch ${slot.displayId} $c") // moved back as it is, or started again
    }

    // Full screen: the page can't draw over the app, so its way back is a small key of its own over
    // the app's top-left corner.
    private var exitKey: android.view.View? = null
    fun maxKey(on: Boolean) {
        val k = exitKey ?: android.widget.TextView(activity).apply {
            text = "⤡"; textSize = 20f; gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0x73000000) }
            setOnClickListener { onEvent("sideMaxOff") }
            exitKey = this
        }
        if (on) {
            (k.parent as? android.view.ViewGroup)?.removeView(k)
            root.addView(k, android.widget.FrameLayout.LayoutParams(KEY, KEY).apply { leftMargin = 16; topMargin = 16 }) // last: above the app
            k.translationZ = 20f
        } else (k.parent as? android.view.ViewGroup)?.removeView(k)
    }

    fun back(name: String) { val s = slots[name] ?: return; if (s.displayId > 0 && s.component != null) shell.send("back ${s.displayId}") }

    fun closeApp(name: String) {
        if (full == name) full = null
        val slot = slots[name] ?: return
        if (slot.displayId > 0) shell.send("close ${slot.displayId}")
        slot.component = null
        slot.box.visibility = android.view.View.GONE
        showKeys(slot, false)
    }

    fun setVisible(name: String, visible: Boolean) {
        val slot = slots[name] ?: return
        slot.box.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        showKeys(slot, visible)
    }

    /**
     * Moves slot [name] sideways by [dx] px and cuts [cut] px off its right side, without resizing
     * it (resizing makes the app redraw): the map area for a moment narrower, the third window or a
     * column at its right not covered. Over [ms] it slides there, in step with the page's column.
     */
    private val cuts = HashMap<String, Float>()
    private val shifting = HashMap<String, android.animation.ValueAnimator>()
    fun shift(name: String, dx: Int, cut: Int, ms: Long = 0) {
        val slot = slots[name] ?: return
        val v = slot.view
        shifting.remove(name)?.cancel()
        // The box slides with its picture, and its right side is cut off (its clip, and so the hole the
        // picture is seen through, is that much narrower). The picture alone slid out of its box and
        // was clipped by it: shifted left for a right signal column, only a 120 px strip of the third
        // window was left.
        fun place(x: Float, c: Float) {
            v.translationX = 0f
            slot.box.translationX = x
            cuts[name] = c
            slot.box.clipBounds = if (c > 0.5f) android.graphics.Rect(0, 0, (slot.w - c).toInt().coerceIn(0, slot.w), slot.h) else null
        }
        val x0 = slot.box.translationX; val c0 = cuts[name] ?: 0f
        if (ms <= 0) return place(dx.toFloat(), cut.toFloat())
        shifting[name] = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f) // the page's column curve
            addUpdateListener { val f = it.animatedValue as Float; place(x0 + (dx - x0) * f, c0 + (cut - c0) * f) }
            start()
        }
    }

    fun close() {
        for (s in slots.values) { s.display?.release(); root.removeView(s.box); s.keys.forEach(root::removeView) }
        slots.clear()
    }

    // "t <display> <action> <id> <x> <y> …" in the view's own pixels, which are the display's.
    // In a display of its own size (see Slot.vw) a point in the window is scaled to the display.
    private fun touchLine(s: Slot, ev: android.view.MotionEvent) = buildString {
        append("t ").append(s.displayId).append(' ').append(ev.action)
        for (i in 0 until ev.pointerCount) {
            var x = ev.getX(i); var y = ev.getY(i)
            if (s.vw > 0) { x = x * s.vw / s.cw; y = y * s.vh / s.ch } // the view is the picture itself
            append(' ').append(ev.getPointerId(i)).append(' ').append(x).append(' ').append(y)
        }
    }
}

/**
 * Yunus's line to its shell-side helper (ShellHelper, run with app_process from this APK). If the
 * helper is not running it is started over Yunus's own ADB connection to the car (127.0.0.1:5555;
 * the owner approves Yunus's key once on the screen). Lines are sent in order on one thread.
 */
/** Yunus is the car's home screen (the HOME intent resolves to it). */
fun isDefaultHome(ctx: android.content.Context): Boolean = try {
    ctx.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
        ?.activityInfo?.packageName == ctx.packageName
} catch (_: Throwable) { false }

/** Whether the owner wants Yunus as the home screen: yes unless DiLink's was picked in Yunus's settings. */
fun wantHome(ctx: android.content.Context): Boolean =
    ctx.getSharedPreferences("yunus", android.content.Context.MODE_PRIVATE).getBoolean("wantHome", true)
fun wantHome(ctx: android.content.Context, yunus: Boolean) =
    ctx.getSharedPreferences("yunus", android.content.Context.MODE_PRIVATE).edit().putBoolean("wantHome", yunus).apply()

private class ShellLink(private val ctx: android.content.Context, private val status: (String) -> Unit, private val onLine: (String) -> Unit = {}) {
    companion object { private const val PORT = 19340 }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "yunus-shell").apply { isDaemon = true } }
    // The app's install time is part of it, so after an update the old helper (old code) is
    // not reused but replaced.
    private val token = File(ctx.filesDir, "shell.token").let { f ->
        if (f.exists()) f.readText() else java.util.UUID.randomUUID().toString().also { f.writeText(it) }
    } + "-" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
    private var socket: Socket? = null

    // Car values ("watch") are subscribed per connection: every new connection asks again, and a
    // connection that drops (the helper restarted, after an update say) is made again, so the
    // values never stop coming until Yunus restarts.
    @Volatile private var watching = false
    // After close() the worker is stopped: late calls are dropped instead of crashing Yunus.
    private fun queue(task: () -> Unit) { try { worker.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) { } }
    fun watch() {
        watching = true
        queue { try { if (socket == null) connect() else write("watch") } catch (t: Throwable) { status("Kabuk yardımcısı yok: ${t.message ?: t.javaClass.simpleName}") } }
    }

    /** Sends [line]; [failed] runs (on this link's thread) if the helper could not be reached. */
    fun send(line: String, failed: () -> Unit = {}) = queue {
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
            if (watching) s.getOutputStream().apply { write("watch\n".toByteArray()); flush() }
            // Anything the helper sends back (car values from "watch") arrives on this connection.
            Thread({
                try { while (true) onLine(input.readLine() ?: break) } catch (_: Throwable) { }
                // Dropped: connect again (once the helper is back) to keep the values coming.
                if (watching) try {
                    queue {
                        if (socket === s) socket = null
                        Thread.sleep(1500)
                        try { if (socket == null) connect() } catch (_: Throwable) { }
                    }
                } catch (_: Throwable) { } // closed with Yunus
            }, "yunus-shell-in").apply { isDaemon = true }.start()
            s
        } else { s.close(); null }
    } catch (_: Throwable) { null }

    fun close() { worker.shutdownNow(); try { socket?.close() } catch (_: Throwable) { } }
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
private class CarHal(private val ctx: android.content.Context, private val want: (String) -> Boolean, private val emit: (String) -> Unit) {
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
        if (value == null || !want(dev + " " + String.format("0x%08X", type))) return
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
