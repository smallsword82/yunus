package app.yunus.launcher

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/**
 * The car's signed-in user, for the status bar: name and photo from BYD's account app
 * (com.byd.diLinkAccount, where BYD's own status bar takes its photo from), as {user:{name, photo}}.
 * Its provider has the account at /account_msg and the photo file at /avatar. For an app outside
 * BYD's list /account_msg has no nickName (only isLogin, isCarOwner, photoUrl, hasUnreadMsg: seen
 * on the car), so the name stays the one set in Yunus › Ekran unless a later version adds it. The
 * sign-in token is on another path (/account_msg_with_tk, BYD's own apps), never asked for.
 */
object CarUser {
    private const val BASE = "content://com.byd.accountProvider"

    fun read(ctx: Context, emit: (String) -> Unit) = Thread({
        try {
            var name: String? = null
            var photoUrl: String? = null
            ctx.contentResolver.query(Uri.parse("$BASE/account_msg"), arrayOf("nickName", "photoUrl"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex("nickName").takeIf { it >= 0 }?.let { name = c.getString(it) }
                    c.getColumnIndex("photoUrl").takeIf { it >= 0 }?.let { photoUrl = c.getString(it) }
                }
            }
            val photo = avatar(ctx) ?: photoUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            android.util.Log.d("YunusUser", "account: name ${if (name.isNullOrBlank()) "none" else "found"}, photo ${if (photo == null) "none" else "found"}")
            if (!name.isNullOrBlank() || photo != null)
                emit(JSONObject().put("user", JSONObject().put("name", name.orEmpty()).put("photo", photo ?: JSONObject.NULL)).toString())
        } catch (t: Throwable) {
            android.util.Log.d("YunusUser", "account: $t")
        }
    }, "yunus-user").apply { isDaemon = true }.start()

    // The photo file the account app keeps, as a data: URL for the page.
    private fun avatar(ctx: Context): String? = runCatching {
        ctx.contentResolver.openInputStream(Uri.parse("$BASE/avatar"))?.use { input ->
            val bytes = input.readBytes()
            if (bytes.isEmpty()) null
            else "data:image/${if (bytes[0] == 0x89.toByte()) "png" else "jpeg"};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }.getOrNull()
}
