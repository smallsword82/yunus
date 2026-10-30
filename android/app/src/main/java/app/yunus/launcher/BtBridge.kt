package app.yunus.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.json.JSONArray
import org.json.JSONObject

/**
 * The status bar's Bluetooth panel: on/off, the paired phones and which is connected, and
 * connecting one, as the car's own panel does. The head unit is the hands-free and music sink
 * for the phone, so a phone is connected through the HFP client (16) and A2DP sink (11)
 * profiles. Switching Bluetooth needs only BLUETOOTH_ADMIN (an app may hold it up to Android 11);
 * the profiles' connect/disconnect are hidden, reached by reflecting on reflection, which
 * Android 10 still lets through. Sends {btList:{on, devs:[{name, addr, on}]}} to the page.
 */
class BtBridge(private val ctx: Context, private val emit: (String) -> Unit) {
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val proxies = HashMap<Int, BluetoothProfile>()
    private val profiles = intArrayOf(16, 11) // HEADSET_CLIENT, A2DP_SINK

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy; report() }
        override fun onServiceDisconnected(profile: Int) { proxies.remove(profile) }
    }
    private val changed = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = report()
    }

    init {
        try { profiles.forEach { adapter?.getProfileProxy(ctx, listener, it) } } catch (_: Throwable) { }
        val f = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
        }
        ctx.registerReceiver(changed, f)
    }

    @Suppress("DEPRECATION")
    fun report() {
        try {
            val a = adapter ?: return
            val on = a.isEnabled
            val connected = proxies.values.flatMap { try { it.connectedDevices } catch (_: Throwable) { emptyList() } }.map { it.address }.toSet()
            val devs = JSONArray()
            if (on) a.bondedDevices.sortedByDescending { it.address in connected }.forEach { d ->
                devs.put(JSONObject().put("name", d.name ?: d.address).put("addr", d.address).put("on", d.address in connected))
            }
            emit(JSONObject().put("btList", JSONObject().put("on", on).put("devs", devs)).toString())
        } catch (t: Throwable) { android.util.Log.w("YunusBt", "report: $t") }
    }

    @Suppress("DEPRECATION")
    fun set(on: Boolean) {
        try { if (on) adapter?.enable() else adapter?.disable() } catch (t: Throwable) { android.util.Log.w("YunusBt", "set: $t") }
    }

    /** Connects (or disconnects) the paired phone [addr] on the hands-free and music profiles. */
    fun connect(addr: String, on: Boolean) {
        val dev = try { adapter?.getRemoteDevice(addr) } catch (_: Throwable) { null } ?: return
        // A phone at a time, as the car does: connecting one lets the other go.
        if (on) proxies.values.forEach { p -> try { p.connectedDevices.filter { it.address != addr }.forEach { hidden(p, "disconnect", it) } } catch (_: Throwable) { } }
        proxies.values.forEach { hidden(it, if (on) "connect" else "disconnect", dev) }
    }

    private fun hidden(p: BluetoothProfile, name: String, dev: BluetoothDevice) {
        try {
            val getDeclared = Class::class.java.getDeclaredMethod("getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java)
            val m = getDeclared.invoke(p.javaClass, name, arrayOf<Class<*>>(BluetoothDevice::class.java)) as java.lang.reflect.Method
            m.isAccessible = true
            val r = m.invoke(p, dev)
            android.util.Log.i("YunusBt", "${p.javaClass.simpleName}.$name(${dev.address}) = $r")
        } catch (t: Throwable) { android.util.Log.w("YunusBt", "${p.javaClass.simpleName}.$name: ${t.cause ?: t}") }
    }

    fun close() {
        try { ctx.unregisterReceiver(changed) } catch (_: Throwable) { }
        proxies.forEach { (k, p) -> try { adapter?.closeProfileProxy(k, p) } catch (_: Throwable) { } }
    }
}
