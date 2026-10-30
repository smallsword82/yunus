package app.yunus.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** An app's launcher icon as a small PNG data URL for the page, or null. */
fun appIconData(ctx: Context, pkg: String): String? = try {
    val d = ctx.packageManager.getApplicationIcon(pkg)
    val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(bmp))
    val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
} catch (_: Exception) { null }

fun appLabel(ctx: Context, pkg: String): String = try {
    ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (_: Exception) { pkg }

/**
 * Every launchable app on the device for the apps card, by name: [{pkg,app,icon}] (Yunus itself
 * left out). Called from the page's JavaScript thread, so the icons don't hold up the screen.
 */
fun launcherApps(ctx: Context): String {
    val pm = ctx.packageManager
    val pkgs = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }
        .distinct().filter { it != ctx.packageName }
    val collator = java.text.Collator.getInstance(java.util.Locale("tr"))
    return JSONArray().apply {
        pkgs.map { it to appLabel(ctx, it) }.sortedWith { a, b -> collator.compare(a.second, b.second) }
            .forEach { (pkg, label) -> put(JSONObject().put("pkg", pkg).put("app", label).put("icon", appIconData(ctx, pkg) ?: JSONObject.NULL)) }
    }.toString()
}

/**
 * Installed navigation apps for the map area: those that open a place or a route (geo: and
 * google.navigation: links) or call themselves a maps app, and can be launched. JSON for the page.
 */
fun navigationApps(ctx: Context): String {
    val pm = ctx.packageManager
    val probes = listOf(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=İzmir")),
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=İzmir")),
        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_APP_MAPS))
    val pkgs = probes.flatMap { pm.queryIntentActivities(it, 0) }.map { it.activityInfo.packageName }
        .distinct().filter { it != ctx.packageName && pm.getLaunchIntentForPackage(it) != null }
    return JSONArray().apply {
        pkgs.forEach { put(JSONObject().put("pkg", it).put("app", appLabel(ctx, it)).put("icon", appIconData(ctx, it) ?: JSONObject.NULL)) }
    }.toString()
}

private val MEDIA_NAME = Regex("music|radio|mediacenter|media[.]player|video|audio|podcast|tunes|tube|player|spotify|youtube|deezer|fizy|tidal|soundcloud", RegexOption.IGNORE_CASE)
private val NOT_MEDIA = Regex("call|phone|dial|camera|gallery|photo", RegexOption.IGNORE_CASE)

/** A music or video app by its declared category or its package/name (SmartTube is org.smarttube.beta). */
fun isMediaApp(ctx: Context, pkg: String): Boolean {
    val info = try { ctx.packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { return false }
    if (info.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO || info.category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO) return true
    val name = "$pkg ${appLabel(ctx, pkg)}"
    return MEDIA_NAME.containsMatchIn(name) && !NOT_MEDIA.containsMatchIn(name)
}

/** A video app (YouTube, SmartTube, …): it plays a video the driver picks on its screen, so it
 *  cannot be started in the background from nothing. */
fun isVideoApp(ctx: Context, pkg: String): Boolean {
    val info = try { ctx.packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { return false }
    if (info.category == android.content.pm.ApplicationInfo.CATEGORY_VIDEO) return true
    return Regex("tube|video", RegexOption.IGNORE_CASE).containsMatchIn("$pkg ${appLabel(ctx, pkg)}") && !Regex("music", RegexOption.IGNORE_CASE).containsMatchIn(pkg)
}

fun isBluetoothApp(pkg: String) = pkg == "com.android.bluetooth" || pkg == "com.google.android.bluetooth"

/**
 * Music and video apps for the music card when nothing is open: apps that call themselves audio or
 * video apps (Spotify, YouTube Music, YouTube, …), the car's own media (com.byd.mediacenter: radio,
 * USB, Bluetooth music) and the phone over Bluetooth (the Bluetooth app's media service). Other apps
 * with a media service, such as the Google app for podcasts, are left out. JSON for the page.
 */
fun mediaApps(ctx: Context, seen: Set<String> = emptySet()): JSONArray {
    val pm = ctx.packageManager
    val launchable = pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet()
    val browsers = pm.queryIntentServices(android.content.Intent("android.media.browse.MediaBrowserService"), 0)
        .map { it.serviceInfo.packageName }.toSet()
    // Also any app that has played media here (seen), whatever its name.
    fun isMedia(pkg: String) = pkg in seen || isMediaApp(ctx, pkg)
    // Bluetooth isn't launchable but plays the phone through its media service.
    val bluetooth = browsers.filter(::isBluetoothApp)
    val pkgs = (launchable.filter(::isMedia) + bluetooth).distinct().filter { it != ctx.packageName }
    return JSONArray().apply {
        pkgs.forEach { put(JSONObject().put("pkg", it).put("app", if (it in bluetooth) "Bluetooth" else appLabel(ctx, it)).put("icon", appIconData(ctx, it) ?: JSONObject.NULL)
            .put("video", isVideoApp(ctx, it))) }
    }
}

/**
 * The dock's volume control on the system media volume: the page sets it, and any change made
 * elsewhere (steering-wheel keys, the car's own bar) comes back as {volume:{cur,max}}.
 */
class VolumeBridge(private val ctx: Context, private val emit: (String) -> Unit) {
    private val audio = ctx.getSystemService(android.media.AudioManager::class.java)
    private val stream = android.media.AudioManager.STREAM_MUSIC
    private val changed = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == stream) send()
        }
    }
    // Not exported: the system's own broadcast still arrives (Android 14 requires the flag).
    init {
        androidx.core.content.ContextCompat.registerReceiver(ctx, changed,
            android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun send() = emit(JSONObject().put("volume", JSONObject()
        .put("cur", audio.getStreamVolume(stream)).put("max", audio.getStreamMaxVolume(stream))).toString())

    fun set(level: Int) = audio.setStreamVolume(stream, level.coerceIn(0, audio.getStreamMaxVolume(stream)), 0)

    fun close() { try { ctx.unregisterReceiver(changed) } catch (_: Exception) { } }
}

/**
 * Lets Yunus read the media sessions (Android hands them to an enabled notification listener), and
 * count the notifications for the status bar's bell: only how many there are (dismissable ones, not
 * Yunus's own), never their content. The owner enables it once in notification access.
 */
class MediaListener : NotificationListenerService() {
    companion object {
        /** Set by the activity; called with the count whenever it may have changed. */
        @Volatile var onCount: ((Int) -> Unit)? = null
        @Volatile var instance: MediaListener? = null
    }

    fun count(): Int = try {
        activeNotifications.orEmpty().count { it.isClearable && it.packageName != packageName }
    } catch (_: Exception) { 0 }

    private fun report() { onCount?.invoke(count()) }
    override fun onListenerConnected() { instance = this; report() }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) = report()
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) = report()
}

/**
 * Now playing for the music card, from the apps' media sessions (Spotify, YouTube Music, the car's
 * radio in com.byd.mediacenter, …): title, artist, cover, state and position go to the page as
 * {media:{…}}, and the card's buttons control the chosen session. Without notification access the
 * page gets {media:{access:false}} and offers to open that setting.
 */
class MediaBridge(private val ctx: Context, private val emit: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val manager = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, MediaListener::class.java)
    private var sessions: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var chosen: String? = null // package the user picked; null: follow what is playing
    private var artKey: String? = null
    // The last app that had a session, kept across restarts: when every app is closed the card still
    // shows it, and play starts it again (as Kinex does).
    private val prefs = ctx.getSharedPreferences("media", Context.MODE_PRIVATE)
    // (A non-media app saved before the session filter, such as the Google app, is dropped.)
    private var last: String? = prefs.getString("last", null)?.takeIf { isMediaApp(ctx, it) || isBluetoothApp(it) }
    // Every app that has played media here, so it stays in the card's app list. ("seen2": the first
    // list also took apps whose session never played, such as the Google app.)
    private val seen = HashSet(prefs.getStringSet("seen2", emptySet()).orEmpty())
    @Volatile private var apps: JSONArray? = null
    private var artData: String? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> use(list.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { pickCurrent(); send() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { pickCurrent(); send() }
        override fun onSessionDestroyed() = refresh()
    }

    fun hasAccess(): Boolean = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == listener } == true

    /** (Re)starts reading; call again after the owner may have granted access. */
    fun refresh() {
        if (!hasAccess()) { emit(JSONObject().put("media", JSONObject().put("access", false)).toString()); return }
        try {
            manager.removeOnActiveSessionsChangedListener(sessionsChanged)
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            use(manager.getActiveSessions(listener))
        } catch (_: SecurityException) {
            emit(JSONObject().put("media", JSONObject().put("access", false)).toString())
        }
    }

    private var all: List<MediaController> = emptyList()
    private fun use(list: List<MediaController>) {
        all.forEach { it.unregisterCallback(callback) }
        all = list
        all.forEach { it.registerCallback(callback, main) }
        pickCurrent()
        send()
    }

    // The card's sessions: music, video and Bluetooth apps, and any other that has really played
    // something (a title, or playing). An app's idle session (the Google app opens one at start)
    // is left out.
    private fun shown() {
        sessions = all.filter { c ->
            isBluetoothApp(c.packageName) || c.packageName in seen || isMedia(c.packageName) ||
                !c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank() || c.playbackState?.state == PlaybackState.STATE_PLAYING
        }
    }

    // The session the user chose, else the one playing, else the most recent one.
    // An app that starts playing takes the card over (a YouTube video after YouTube Music was
    // picked), as the system's own media controls do; picking one in the list still follows it.
    private var playingBefore = emptySet<String>()
    private fun pickCurrent() {
        shown()
        val playingNow = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.map { it.packageName }.toSet()
        (playingNow - playingBefore).firstOrNull()?.let { chosen = it }
        playingBefore = playingNow
        current = sessions.firstOrNull { it.packageName == chosen }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    fun choose(pkg: String) { chosen = pkg; pickCurrent(); send() }

    fun command(cmd: String) {
        val controls = current?.transportControls
        if (controls == null) { if (cmd == "play" || cmd == "toggle") last?.let { start(it) }; return }
        when (cmd) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (current?.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "next" -> controls.skipToNext()
            "prev" -> controls.skipToPrevious()
        }
    }

    fun seek(ms: Long) { current?.transportControls?.seekTo(ms) }

    /** The app behind the current session (else the last one), to open it full screen. */
    fun currentPackage(): String? = current?.packageName ?: last

    /**
     * Starts [pkg]'s music with nothing of it open, in the background (the app never comes to the
     * front): connect to the app's media browser service and press play on the session it hands
     * over, the way Android Auto does (Spotify …). An app that refuses outside clients (YouTube Music)
     * or has no such service gets a play key instead: through the system for the last app (as the
     * steering-wheel key does, which may restart it), else to its own receiver. Still silent after
     * a few seconds, the card says so ({startFailed}); tapping its title opens the app.
     */
    private var browser: android.media.browse.MediaBrowser? = null
    fun start(pkg: String) {
        chosen = pkg
        val pm = ctx.packageManager
        val service = pm.queryIntentServices(android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0).firstOrNull()?.serviceInfo
        if (service == null) return playKey(pkg)
        browser?.disconnect()
        var b: android.media.browse.MediaBrowser? = null
        b = android.media.browse.MediaBrowser(ctx, ComponentName(service.packageName, service.name), object : android.media.browse.MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                try { MediaController(ctx, b!!.sessionToken).transportControls.play() } catch (_: Exception) { }
                // Stay bound a little while the app starts playing and publishes its session.
                main.postDelayed({ if (browser === b) { b?.disconnect(); browser = null } }, 15000)
                checkStarted(pkg)
            }
            override fun onConnectionFailed() { if (browser === b) browser = null; playKey(pkg) }
        }, null)
        browser = b
        b.connect()
    }

    private fun playKey(pkg: String) {
        val keys = intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)
            .map { android.view.KeyEvent(it, android.view.KeyEvent.KEYCODE_MEDIA_PLAY) }
        if (pkg == last) keys.forEach { ctx.getSystemService(android.media.AudioManager::class.java).dispatchMediaKeyEvent(it) }
        else {
            val receiver = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
            ctx.packageManager.queryBroadcastReceivers(receiver, 0).firstOrNull()?.activityInfo?.let { target ->
                receiver.component = ComponentName(target.packageName, target.name)
                receiver.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                keys.forEach { ctx.sendBroadcast(android.content.Intent(receiver).putExtra(android.content.Intent.EXTRA_KEY_EVENT, it)) }
            }
        }
        checkStarted(pkg)
    }

    private var startFailed: String? = null
    private fun checkStarted(pkg: String) {
        startFailed = null
        main.postDelayed({
            if (all.none { it.packageName == pkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }) { startFailed = pkg; send() }
        }, 6000)
    }

    // The card's message is built off the main thread: covers (scaled, JPEG), app icons and the
    // list of music apps take time, and at a car start the system is busy enough that holding the
    // main thread made Android report Yunus as not responding. The main thread only takes a
    // snapshot of the sessions (their metadata and states are immutable copies).
    private class Snap(val pkg: String, val md: MediaMetadata?, val st: PlaybackState?)
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "yunus-media").apply { isDaemon = true } }

    private fun send() {
        val c = current
        if (c != null && c.packageName != last) { last = c.packageName; prefs.edit().putString("last", last).apply() }
        if (sessions.filter { !isBluetoothApp(it.packageName) }.any { seen.add(it.packageName) }) { prefs.edit().putStringSet("seen2", HashSet(seen)).apply(); apps = null }
        if (startFailed != null && sessions.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }) startFailed = null
        val now = c?.let { Snap(it.packageName, it.metadata, it.playbackState) }
        val all = sessions.map { Snap(it.packageName, it.metadata, it.playbackState) }
        val lastPkg = last
        val failed = startFailed
        val seenNow = HashSet(seen)
        worker.execute { emit(JSONObject().put("media", build(now, all, lastPkg, failed, seenNow)).toString()) }
    }

    private fun build(c: Snap?, all: List<Snap>, lastPkg: String?, failed: String?, seenNow: Set<String>): JSONObject {
        val out = JSONObject().put("access", true)
        failed?.let { out.put("startFailed", it) }
        // Nothing open: the last app. Always: the installed music apps, to start one from.
        if (c == null) lastPkg?.let { out.put("last", JSONObject().put("pkg", it).put("app", label(it)).put("icon", icon(it) ?: JSONObject.NULL)) }
        out.put("apps", apps ?: mediaApps(ctx, seenNow).also { apps = it })
        out.put("sessions", JSONArray().apply {
            all.forEach { put(JSONObject().put("pkg", it.pkg).put("app", label(it.pkg)).put("icon", icon(it.pkg) ?: JSONObject.NULL)
                .put("title", it.md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                .put("playing", it.st?.state == PlaybackState.STATE_PLAYING)) }
        })
        if (c != null) {
            val md = c.md
            val st = c.st
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            // Position now, from the last report and the playback speed.
            var pos = st?.position ?: 0L
            if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0)
                pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            out.put("pkg", c.pkg).put("app", label(c.pkg)).put("icon", icon(c.pkg) ?: JSONObject.NULL)
                .put("title", title).put("artist", artist)
                .put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
                .put("playing", st?.state == PlaybackState.STATE_PLAYING)
                // The app's own reason it can't play ("Please login…"), shown on the card.
                .put("error", if (st?.state == PlaybackState.STATE_ERROR) st.errorMessage?.toString() ?: "" else "")
                .put("pos", pos).put("dur", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                .put("art", art(c.pkg, title, md) ?: JSONObject.NULL)
        }
        return out
    }

    // The cover as a small JPEG data URL, encoded once per track.
    private fun art(pkg: String, title: String, md: MediaMetadata?): String? {
        val key = "$pkg|$title"
        if (key == artKey) return artData
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        artKey = key
        artData = bmp?.let {
            val side = 240
            val scaled = Bitmap.createScaledBitmap(it, side, side * it.height / maxOf(1, it.width), true)
            val bytes = ByteArrayOutputStream().also { o -> scaled.compress(Bitmap.CompressFormat.JPEG, 82, o) }.toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        return artData
    }

    // Each app's own icon, so any music app looks right on the card without being known in advance.
    private val icons = HashMap<String, String?>()
    private fun icon(pkg: String): String? = icons.getOrPut(pkg) { appIconData(ctx, pkg) }

    private fun label(pkg: String): String = appLabel(ctx, pkg)
    // Whether a session's app counts as media, asked on every playback change: kept per app.
    private val mediaKind = HashMap<String, Boolean>()
    private fun isMedia(pkg: String) = mediaKind.getOrPut(pkg) { isMediaApp(ctx, pkg) }

    fun close() {
        browser?.disconnect()
        worker.shutdownNow()
        try { manager.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Exception) { }
        all.forEach { it.unregisterCallback(callback) }
    }
}
