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

                // Commands to the car need BYD's signature permissions, which this app cannot hold.
                @JavascriptInterface
                fun command(json: String) {}
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
        setContentView(web)
        if (savedInstanceState == null) web.loadUrl("https://appassets.androidplatform.net/assets/launcher.html")
        else web.restoreState(savedInstanceState)
        halBridge.start()
        // Ask for the BYDAuto _COMMON permissions (dangerous/grantable) once. With them we can
        // construct the HAL devices — instrument, ac, doorlock, gearbox, charging, statistic... —
        // and register listeners; live values then arrive through the push path. The enforcing
        // getters stay locked behind BYD's signature _GET perms, which no third-party app can hold.
        requestCarPermissions()
        // Live vehicle data the way 3rd-party apps on this head unit do it (BYD Road Computer /
        // electro.app.br): read the BYDAuto HAL devices directly via listeners, in-process.
        carHal = CarHal(applicationContext, ::postToPage).also { it.start() }
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() { super.onResume(); web.onResume() }
    override fun onPause() { web.onPause(); super.onPause() }
    override fun onDestroy() { carHal?.close(); halBridge.close(); web.destroy(); super.onDestroy() }

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
                    val adb = DirectAdb(ADB_HOST, ADB_PORT)
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

/** Minimal ADB-over-TCP transport. Authentication uses Android's per-install RSA keypair. */
private class DirectAdb(host: String, port: Int) : java.io.Closeable {
    private val socket = Socket().apply { connect(InetSocketAddress(host, port), 2500); soTimeout = 5000 }
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val auth = AdbAuth()

    fun connect() {
        sendAdb("CNXN", 0x01000000, 4096, "host::features=shell_v2\u0000".toByteArray(Charsets.US_ASCII))
        repeat(4) {
            val packet = readAdb()
            when (packet.command) {
                "CNXN" -> return
                "AUTH" -> {
                    when (packet.arg0) {
                        1 -> sendAdb("AUTH", 2, 0, auth.sign(packet.payload))
                        2 -> sendAdb("AUTH", 3, 0, auth.publicKey())
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
/** Android KeyStore-backed ADB RSA credentials; never exports the private key from the keystore. */
private class AdbAuth : java.io.Closeable {
    private val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "yunus-adb-rsa"
    private val pair: java.security.KeyPair
    init {
        val existing = keyStore.getEntry(alias, null) as? java.security.KeyStore.PrivateKeyEntry
        pair = if (existing != null) java.security.KeyPair(existing.certificate.publicKey, existing.privateKey) else {
            val gen = java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
            gen.initialize(android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_SIGN or android.security.keystore.KeyProperties.PURPOSE_VERIFY)
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA1, android.security.keystore.KeyProperties.DIGEST_SHA256).setKeySize(2048).build())
            gen.generateKeyPair()
        }
    }
    fun sign(token: ByteArray): ByteArray = java.security.Signature.getInstance("SHA1withRSA").run { initSign(pair.private); update(token); sign() }
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
        "safetybelt.BYDAutoSafetyBeltDevice", "setting.BYDAutoSettingDevice")
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
