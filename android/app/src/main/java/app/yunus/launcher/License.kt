package app.yunus.launcher

import android.content.Context
import org.json.JSONObject

/**
 * The free trial: Yunus runs for 30 days from its first start, then asks the owner to write to the
 * author for an unlock code (free; each code opens one more year). Offline: the page shows this
 * device's code, the author answers with an unlock code made by tools/yunus_kod.py from the same
 * secret (android/license.properties, not in the repo). Without that file the secret is empty and
 * the build never expires (anyone building from source gets an unlimited copy).
 *
 * The page decides when to show the notice (only in P); nothing here blocks the car's controls.
 * The 30-day clock survives an uninstall: the first-run day is also stamped by the shell helper into
 * Settings.Global and /data/local/tmp (ShellHelper.stampTrialAnchor), which an uninstall does not
 * clear, and the trial counts from the earliest day found. Reinstalling therefore does not reset it
 * (a factory reset or ADB would; it is a deterrent, not an unbreakable lock).
 */
object License {
    const val TRIAL_DAYS = 30
    private const val DAY_MS = 86_400_000L
    private const val EPOCH_DAY_2024 = 19_723L // 2024-01-01 in days since 1970: unlock codes count days from here
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I, O, 0, 1: read off a screen and typed

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("yunus", Context.MODE_PRIVATE)

    /** The first-run day persisted outside the app's own (uninstall-wiped) storage: Settings.Global and
     *  /data/local/tmp, both written by the shell helper and both surviving an uninstall. The earliest
     *  of them is the real trial start, so reinstalling cannot reset the 30 days. null if neither is set. */
    private fun persistedFirst(ctx: Context): Long? {
        var v: Long? = null
        try { android.provider.Settings.Global.getString(ctx.contentResolver, "yunus_lf")?.trim()?.toLongOrNull()?.let { v = it } } catch (_: Throwable) {}
        try { val f = java.io.File("/data/local/tmp/yunus_lf"); if (f.canRead()) f.readText().trim().toLongOrNull()?.let { d -> v = if (v == null) d else minOf(v!!, d) } } catch (_: Throwable) {}
        return v
    }

    /** Today's day number. The clock set back does not bring days back: the latest time seen is kept. */
    private fun today(ctx: Context): Long {
        val p = prefs(ctx)
        val now = maxOf(System.currentTimeMillis(), p.getLong("licSeen", 0L))
        p.edit().putLong("licSeen", now).apply()
        return now / DAY_MS
    }

    /** Eight characters that name this head unit (from ANDROID_ID, the same after an update or reinstall). */
    fun deviceCode(ctx: Context): String {
        val id = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "yunus"
        val h = java.security.MessageDigest.getInstance("SHA-256").digest("yunus-device:$id".toByteArray())
        return base32(h, 8)
    }

    private fun base32(bytes: ByteArray, chars: Int): String {
        val sb = StringBuilder()
        var acc = 0L; var bits = 0; var i = 0
        while (sb.length < chars) {
            if (bits < 5) { acc = (acc shl 8) or (bytes[i++].toLong() and 0xff); bits += 8 }
            sb.append(ALPHABET[((acc shr (bits - 5)) and 31).toInt()]); bits -= 5
        }
        return sb.toString()
    }

    /** The unlock code for [device] valid through [day] (days since 2024-01-01): 16 bits of day, 44 of HMAC. */
    private fun codeFor(device: String, day: Int): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(BuildConfig.LICENSE_SECRET.toByteArray(), "HmacSHA256"))
        val sig = mac.doFinal("$device:$day".toByteArray())
        return base32(byteArrayOf((day shr 8).toByte(), day.toByte()) + sig, 12)
    }

    /** {enforced, device, trialLeft, until, expired}: [until] is the unlock's last day (yyyy-mm-dd) or null. */
    fun state(ctx: Context): String {
        val p = prefs(ctx)
        val today = today(ctx)
        // The trial start is the earliest of: what this install recorded, and the uninstall-proof
        // anchor (Settings.Global / tmp). So a reinstall — which wipes this install's record but not
        // the anchor — keeps counting from the original first run instead of starting over.
        val prefsFirst = if (p.contains("licFirst")) p.getLong("licFirst", today) else today
        val persisted = persistedFirst(ctx)
        val first = if (persisted != null) minOf(prefsFirst, persisted) else prefsFirst
        p.edit().putLong("licFirst", first).apply()
        val trialLeft = (TRIAL_DAYS - (today - first)).coerceAtLeast(0)
        val until = p.getLong("licUntil", 0L)
        val enforced = BuildConfig.LICENSE_SECRET.isNotEmpty()
        return JSONObject()
            .put("enforced", enforced)
            .put("device", deviceCode(ctx))
            .put("trialLeft", trialLeft)
            .put("until", if (until > 0) java.time.LocalDate.ofEpochDay(until).toString() else JSONObject.NULL)
            .put("expired", enforced && trialLeft <= 0 && until < today)
            .toString()
    }

    /** Takes an unlock code as typed (dashes, spaces and case do not matter); true when it opens this device. */
    fun enter(ctx: Context, typed: String): Boolean {
        if (BuildConfig.LICENSE_SECRET.isEmpty()) return false
        val code = typed.uppercase().filter { it in ALPHABET }
        if (code.length != 12) return false
        // The first 16 bits (3.2 characters) are the day: decode the first four characters and drop 4 bits.
        var v = 0
        for (c in code.substring(0, 4)) v = (v shl 5) or ALPHABET.indexOf(c)
        val day = v shr 4
        if (!java.security.MessageDigest.isEqual(codeFor(deviceCode(ctx), day).toByteArray(), code.toByteArray())) return false
        val until = EPOCH_DAY_2024 + day
        if (until < today(ctx)) return false // a code whose year is already over
        prefs(ctx).edit().putLong("licUntil", until).apply()
        return true
    }
}
