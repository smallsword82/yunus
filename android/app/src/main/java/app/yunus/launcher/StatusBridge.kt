package app.yunus.launcher

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import org.json.JSONObject

/**
 * The status icons the car's own status bar shows and Yunus hides by running full screen: Wi-Fi
 * and mobile signal, each a level 0–4 or -1 when there is none (Wi-Fi off or not joined; no SIM).
 * Read every few seconds and sent to the page as {net:{wifi,mobile,online}} only when they change.
 * Signal levels need no runtime permission.
 */
class StatusBridge(ctx: Context, private val emit: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val phone = ctx.getSystemService(TelephonyManager::class.java)
    private val net = ctx.getSystemService(ConnectivityManager::class.java)
    private var sent: String? = null
    private val tick = object : Runnable {
        override fun run() { send(false); main.postDelayed(this, 5000) }
    }

    fun start() { main.removeCallbacks(tick); main.post(tick) }

    /** Sends the current state; [always] even if unchanged (the page asks once it is ready). */
    fun send(always: Boolean) {
        val out = JSONObject().put("wifi", wifiLevel()).put("mobile", mobileLevel()).put("online", online()).toString()
        if (!always && out == sent) return
        sent = out
        emit(JSONObject().put("net", JSONObject(out)).toString())
    }

    @Suppress("DEPRECATION") // connectionInfo is the one call that works from Android 10 to 14
    private fun wifiLevel(): Int = try {
        val info = wifi?.connectionInfo
        if (wifi?.isWifiEnabled != true || info == null || info.networkId == -1) -1
        else WifiManager.calculateSignalLevel(info.rssi, 5)
    } catch (_: Exception) { -1 }

    private fun mobileLevel(): Int = try {
        if (phone == null || phone.simState != TelephonyManager.SIM_STATE_READY) -1
        else phone.signalStrength?.level ?: 0
    } catch (_: Exception) { -1 }

    private fun online(): Boolean = try {
        net.getNetworkCapabilities(net.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    } catch (_: Exception) { false }

    fun close() = main.removeCallbacks(tick)
}
