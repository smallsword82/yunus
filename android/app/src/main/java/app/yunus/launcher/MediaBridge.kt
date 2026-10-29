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
 * Exists only so Yunus may read the media sessions (Android hands them to an enabled notification
 * listener). It reads no notifications. The owner enables it once in notification access.
 */
class MediaListener : NotificationListenerService()

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
    private var artData: String? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> use(list.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = send()
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

    private fun use(list: List<MediaController>) {
        sessions.forEach { it.unregisterCallback(callback) }
        sessions = list
        sessions.forEach { it.registerCallback(callback, main) }
        pickCurrent()
        send()
    }

    // The session the user chose, else the one playing, else the most recent one.
    private fun pickCurrent() {
        current = sessions.firstOrNull { it.packageName == chosen }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull()
    }

    fun choose(pkg: String) { chosen = pkg; pickCurrent(); send() }

    fun command(cmd: String) {
        val controls = current?.transportControls ?: return
        when (cmd) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "toggle" -> if (current?.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            "next" -> controls.skipToNext()
            "prev" -> controls.skipToPrevious()
        }
    }

    fun seek(ms: Long) { current?.transportControls?.seekTo(ms) }

    /** The app behind the current session, to open it full screen. */
    fun currentPackage(): String? = current?.packageName

    private fun send() {
        val c = current
        val out = JSONObject().put("access", true)
        out.put("sessions", JSONArray().apply {
            sessions.forEach { put(JSONObject().put("pkg", it.packageName).put("app", label(it.packageName)).put("icon", icon(it.packageName) ?: JSONObject.NULL)
                .put("title", it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
                .put("playing", it.playbackState?.state == PlaybackState.STATE_PLAYING)) }
        })
        if (c != null) {
            val md = c.metadata
            val st = c.playbackState
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            // Position now, from the last report and the playback speed.
            var pos = st?.position ?: 0L
            if (st != null && st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0)
                pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
            out.put("pkg", c.packageName).put("app", label(c.packageName)).put("icon", icon(c.packageName) ?: JSONObject.NULL)
                .put("title", title).put("artist", artist)
                .put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
                .put("playing", st?.state == PlaybackState.STATE_PLAYING)
                .put("pos", pos).put("dur", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                .put("art", art(c.packageName, title, md) ?: JSONObject.NULL)
        }
        emit(JSONObject().put("media", out).toString())
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

    // Each app's own launcher icon as a small PNG data URL, so any music app looks right on the
    // card without being known in advance; made once per app.
    private val icons = HashMap<String, String?>()
    private fun icon(pkg: String): String? = icons.getOrPut(pkg) {
        try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(bmp))
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    private fun label(pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    fun close() {
        try { manager.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Exception) { }
        sessions.forEach { it.unregisterCallback(callback) }
    }
}
