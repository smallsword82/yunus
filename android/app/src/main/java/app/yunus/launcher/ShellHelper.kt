package app.yunus.launcher

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs as the shell user, started over Yunus's own ADB connection with app_process from this APK
 * (the way Kinex runs its touch bridge). The shell may start an app on Yunus's private virtual
 * display and inject touches into it, which an app cannot do itself.
 *
 * Deliberately narrow: it runs no arbitrary commands, and what it does is limited to virtual
 * displays owned by Yunus (never the car's main screen or another app's display), except one fixed
 * gesture on the main screen that takes no arguments (qsbar). It
 * listens on 127.0.0.1 only and serves a connection only after it sends the token Yunus passed
 * on the command line. One request per line:
 *   launch <display> <package>/<activity>              start that activity on the display
 *   close <display>                                     close the apps running on the display
 *   t <display> <action> <id> <x> <y> [<id> <x> <y> …]   inject a touch event into the display
 *   qsbar                                               open the car's quick panel (BYD SystemUI's
 *                                                       BydQSBar): the swipe a finger would make
 *   watch                                               send the car's window, sunshade and speed
 *                                                       values on this connection (read only)
 * Car values come back as lines "ev <Device> 0x<id> <i|f|d> <value>".
 */
object ShellHelper {
    private const val OWNER = "app.yunus.launcher"
    private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")

    @JvmStatic
    @Suppress("DEPRECATION") // prepareMainLooper: this is a process of its own (app_process), its main looper is ours
    fun main(args: Array<String>) {
        val port = args[0].toInt()
        val token = args[1]
        val injector = TouchInjector()
        // The main thread runs a looper (the car's listeners call back through it, as in Kinex's
        // daemon); connections are served on their own threads.
        android.os.Looper.prepareMainLooper()
        Thread {
            ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                while (true) {
                    val client = server.accept()
                    Thread { serve(client, token, injector) }.start()
                }
            }
        }.start()
        android.os.Looper.loop()
    }

    private fun serve(client: Socket, token: String, injector: TouchInjector) = client.use {
        client.tcpNoDelay = true
        val reader = client.getInputStream().bufferedReader()
        if (reader.readLine() != token) return
        client.getOutputStream().apply { write("ok\n".toByteArray()); flush() }
        val out = client.getOutputStream()
        try { while (true) {
            val line = reader.readLine() ?: break
            if (line == "watch") { CarWatch.subscribe(out); continue }
            if (line == "qsbar") { try { injector.openCarPanel() } catch (t: Throwable) { System.err.println("qsbar: $t") }; continue }
            if (line.startsWith("cmd ")) { CarWatch.command(line.substring(4)); continue }
            try {
                val fields = line.split(' ')
                val display = fields.getOrNull(1)?.toIntOrNull() ?: continue
                if (!ownedByYunus(display)) { System.err.println("refused, not Yunus's display: $line"); continue }
                when (fields[0]) {
                    "t" -> injector.touch(display, fields.drop(2))
                    "launch" -> launch(display, fields.getOrNull(2) ?: continue)
                    "close" -> close(display)
                }
            } catch (t: Throwable) { System.err.println("$line: $t") } // goes to the helper's log file
        } } finally { CarWatch.unsubscribe(out) }
    }

    // Fixed argument lists (no shell): keep the display upright, stop the app's task elsewhere,
    // then start it on the display.
    private fun launch(display: Int, component: String) {
        if (!COMPONENT.matches(component)) return
        lockRotation(display)
        ProcessBuilder("am", "force-stop", component.substringBefore('/')).inheritIO().start().waitFor()
        ProcessBuilder("am", "start", "--display", display.toString(), "-n", component).inheritIO().start().waitFor()
        keepOnDisplay(display, component.substringBefore('/'))
    }

    private fun nudge(display: Int) {
        val line = ProcessBuilder("dumpsys", "display").start().inputStream.bufferedReader().readText()
            .lineSequence().firstOrNull { it.contains("DisplayInfo{") && Regex("displayId $display[,\"]").containsMatchIn(it) } ?: return
        val (w, h) = Regex("real (\\d+) x (\\d+)").find(line)?.destructured ?: return
        ProcessBuilder("wm", "size", "${w.toInt() - 1}x$h", "-d", display.toString()).inheritIO().start().waitFor()
        SystemClock.sleep(200)
        ProcessBuilder("wm", "size", "reset", "-d", display.toString()).inheritIO().start().waitFor()
    }

    // The window's close button: every task on that display (only Yunus's own) is removed, as
    // swiping an app away in recents does; the next launch starts the app afresh.
    private fun close(display: Int) {
        synchronized(watching) { watching.remove(display) }
        for ((task, onDisplay, _) in Tasks.roots()) if (onDisplay == display) Tasks.remove(task)
    }

    // Some apps start over in a new task right after launch (Chrome's restart, splash screens,
    // permission flows); a task started without a display lands on the main screen. For a short
    // while after the launch, a task of that same app found on the main screen is moved back onto
    // the display. Only that app, only from the main screen, only onto Yunus's own display.
    private val watching = HashMap<Int, Long>()
    private fun keepOnDisplay(display: Int, pkg: String) {
        val id = System.nanoTime()
        synchronized(watching) { watching[display] = id }
        Thread {
            val end = SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < end && synchronized(watching) { watching[display] == id }) {
                SystemClock.sleep(700)
                try {
                    for ((task, onDisplay, pkgs) in Tasks.roots())
                        if (onDisplay == 0 && pkg in pkgs) {
                            Tasks.move(task, display)
                            System.err.println("moved $pkg task $task back to display $display")
                            // A video surface (SurfaceView) can stay behind on the main screen after the
                            // move; a relayout on the display (1 px narrower, then back) brings it along.
                            SystemClock.sleep(300)
                            nudge(display)
                            // Its start had put it in front of Yunus on the main screen; with it gone the
                            // home screen would show, so Yunus (single task) comes back to the front.
                            ProcessBuilder("am", "start", "-n", "$OWNER/.MainActivity").inheritIO().start().waitFor()
                        }
                } catch (t: Throwable) { System.err.println("keep $pkg: $t"); return@Thread }
            }
        }.start()
    }

    // An app that asks for portrait (YouTube's player) would turn the whole display, leaving a narrow
    // strip in Yunus's wide window: the display stays at rotation 0 and ignores such requests.
    // Android 10 knows "set-fix-to-user-rotation", 12+ "fixed-to-user-rotation" and
    // "set-ignore-orientation-request"; the ones a version lacks just fail.
    private fun lockRotation(display: Int) {
        val d = display.toString()
        for (cmd in listOf(listOf("user-rotation", "-d", d, "lock", "0"), listOf("set-fix-to-user-rotation", "-d", d, "enabled"),
                listOf("fixed-to-user-rotation", "-d", d, "enabled"), listOf("set-ignore-orientation-request", "-d", d, "true")))
            ProcessBuilder(listOf("cmd", "window") + cmd).inheritIO().start().waitFor()
    }

    // Private displays are hidden from everyone but their owner (DisplayManager returns null to
    // the shell), so the owner is read from dumpsys: virtual displays' unique ids carry it.
    private val owned = HashSet<Int>()
    private fun ownedByYunus(display: Int): Boolean {
        if (display <= 0) return false
        synchronized(owned) {
            if (display in owned) return true
            val dump = ProcessBuilder("dumpsys", "display").start().inputStream.bufferedReader().readText()
            // Android 10: DisplayInfo{"name, displayId 7", uniqueId "virtual:<owner>,…
            // Android 14: DisplayInfo{"name", displayId 7, …, uniqueId "virtual:<owner>,…
            val ok = Regex("""DisplayInfo\{"[^"]*(?:", )?displayId $display[",][^\n]*?uniqueId "virtual:${Regex.escape(OWNER)},""").containsMatchIn(dump)
            if (ok) owned.add(display)
            return ok
        }
    }
}

/** Builds touchscreen MotionEvents for a given display and injects them (shell-only APIs, via reflection). */
private class TouchInjector {
    // Android 14 moved injection to InputManagerGlobal (InputManager.getInstance() there needs an
    // app context, which app_process lacks); older versions, the car's Android 10 included, keep it
    // on InputManager.
    private val inputManager = try {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    } catch (_: ClassNotFoundException) {
        Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
    }
    private val inject = inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    private val setDisplayId = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    private var downTime = 0L

    /**
     * The car's quick panel opens from a swipe down on the right part of its status bar (the left
     * part pulls down the notifications). Yunus runs full screen, so the bar is hidden: a first
     * swipe from the top edge brings it in, a second one on it opens the panel. Fixed, on the main
     * screen (display 0), in its current orientation.
     */
    fun openCarPanel() {
        val info = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
            .let { it.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(it, 0) }
        val w = info.javaClass.getField("logicalWidth").getInt(info).toFloat()
        val h = info.javaClass.getField("logicalHeight").getInt(info).toFloat()
        swipe(w * 0.78f, 1f, h * 0.2f)
        SystemClock.sleep(700)
        swipe(w * 0.78f, h * 0.028f, h * 0.46f)
    }

    private fun swipe(x: Float, y0: Float, y1: Float) {
        val steps = 12
        for (i in 0..steps + 1) {
            val y = y0 + (y1 - y0) * minOf(i, steps) / steps
            val action = if (i == 0) MotionEvent.ACTION_DOWN else if (i > steps) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            touch(0, listOf(action.toString(), "0", x.toString(), y.toString()))
            SystemClock.sleep(20)
        }
    }

    // fields: <action> <id> <x> <y> [<id> <x> <y> …]
    fun touch(display: Int, fields: List<String>) {
        val action = fields[0].toInt()
        val count = (fields.size - 1) / 3
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply { id = fields[1 + i * 3].toInt(); toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().apply { x = fields[2 + i * 3].toFloat(); y = fields[3 + i * 3].toFloat(); pressure = 1f; size = 1f }
        }
        val now = SystemClock.uptimeMillis()
        if (action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_DOWN) downTime = now
        val event = MotionEvent.obtain(downTime, now, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        setDisplayId.invoke(event, display)
        inject.invoke(inputManager, event, 0) // 0: INJECT_INPUT_EVENT_MODE_ASYNC
        event.recycle()
    }
}

/**
 * The system's task list and moving a task to another display, as the shell may (hidden APIs, via
 * reflection). Android 12+ has root tasks (getAllRootTaskInfos / moveRootTaskToDisplay), Android 10
 * stacks (getAllStackInfos / moveStackToDisplay); each entry is (id, display, packages in it).
 */
private object Tasks {
    private val atm by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null) }

    private fun field(o: Any, name: String): Any? = try { o.javaClass.getField(name).get(o) } catch (_: Exception) { null }
    private fun pkgOf(c: Any?) = (c as? android.content.ComponentName)?.packageName

    fun roots(): List<Triple<Int, Int, Set<String>>> {
        val rootInfos = atm.javaClass.methods.firstOrNull { it.name == "getAllRootTaskInfos" && it.parameterCount == 0 }
        if (rootInfos != null) return (rootInfos.invoke(atm) as List<*>).filterNotNull().map { info ->
            val names = (field(info, "childTaskNames") as? Array<*>)?.mapNotNull { (it as? String)?.substringBefore('/') }.orEmpty()
            Triple(field(info, "taskId") as Int, field(info, "displayId") as Int,
                (names + listOfNotNull(pkgOf(field(info, "topActivity")), pkgOf(field(info, "baseActivity")))).toSet())
        }
        val stacks = atm.javaClass.getMethod("getAllStackInfos").invoke(atm) as List<*>
        return stacks.filterNotNull().map { info ->
            val names = (field(info, "taskNames") as? Array<*>)?.mapNotNull { (it as? String)?.substringBefore('/') }.orEmpty()
            Triple(field(info, "stackId") as Int, field(info, "displayId") as Int, (names + listOfNotNull(pkgOf(field(info, "topActivity")))).toSet())
        }
    }

    fun remove(id: Int) { atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, id) }

    fun move(id: Int, display: Int) {
        val m = atm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" }
            ?: atm.javaClass.getMethod("moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        m.invoke(atm, id, display)
    }
}

/**
 * Read-only: the car's window and sunshade positions (Bodywork) and its speed (Speed), which an app
 * can't get (their devices need BYD's signature permissions), heard here as the shell user with a
 * system context, the way Kinex's HAL daemon does. Nothing is set on the car. Each value change is
 * sent to the subscribed connections as "ev <Device> 0x<id> <i|f|d> <value>".
 */
private object CarWatch {
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<java.io.OutputStream>()
    private val held = ArrayList<Any>()
    private val last = HashMap<String, String>()
    private var started = false
    @Volatile private var bodyworkDev: Any? = null // for commands (windows, sunshade), set in listen()
    private val invalid = setOf(-999999999.0, -1.0E9, -9.99999999E8)

    /** A context whose permission checks all pass, so the HAL devices' own check does not refuse this
     *  shell process (as Kinex's Lb/W; does). Overrides only the check/enforce methods. */
    private class Permissive(base: android.content.Context) : android.content.ContextWrapper(base) {
        private val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
        // Some getters (gearbox, door-lock) also check the caller's package matches its uid; the
        // system context reports "android" (uid 1000) while we run as uid 2000, so they throw
        // "calling package android does not match caller's uid 2000". Report shell's package, which
        // matches uid 2000 (Kinex forces the same op-package).
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getApplicationContext(): android.content.Context = this
        override fun checkSelfPermission(permission: String) = granted
        override fun checkCallingOrSelfPermission(permission: String) = granted
        override fun checkCallingPermission(permission: String) = granted
        override fun checkPermission(permission: String, pid: Int, uid: Int) = granted
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        override fun enforceCallingPermission(permission: String, message: String?) {}
    }

    fun subscribe(out: java.io.OutputStream) {
        subscribers.add(out)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!started) { started = true; start() }
            else synchronized(last) { last.values.forEach { send(it) } } // the newcomer gets the current values
        }
    }

    fun unsubscribe(out: java.io.OutputStream) { subscribers.remove(out) }

    private fun start() {
        val base = try {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            at.getMethod("getSystemContext").invoke(thread) as android.content.Context
        } catch (t: Throwable) { System.err.println("watch: no system context: $t"); return }
        // The BYD HAL devices check their signature BYDAUTO_*_GET/SET perms on the Context handed to
        // getInstance; running as shell we don't hold them, so a direct construction is refused.
        // Kinex's kinex_hal_api_service.jar (class Lb/W;) hands the device a Context whose permission
        // checks all pass, so the device's own check succeeds and bodywork (windows, sunshade,
        // steering) and door-lock become readable and commandable from this shell process. Same
        // approach here; proven on the car, where Kinex reads and locks the Dolphin this way.
        val ctx: android.content.Context = Permissive(base)
        for (name in listOf("bodywork.BYDAutoBodyworkDevice", "speed.BYDAutoSpeedDevice"))
            try { listen(ctx, "android.hardware.bydauto.$name") } catch (t: Throwable) { System.err.println("watch $name: ${t.cause ?: t}") }
        // Also the getters Kinex reads, once a second (SOC, range and speed don't come as events
        // while parked). A getter that is refused is not asked again.
        for ((name, gets) in GETTERS) try {
            val c = Class.forName("android.hardware.bydauto.$name")
            val dev = c.getMethod("getInstance", android.content.Context::class.java).invoke(null, ctx) ?: continue
            for (g in gets) polls.add(Poll(dev, c, g))
        } catch (t: Throwable) { System.err.println("watch getters $name: ${t.cause ?: t}") }
        poll()
    }

    // Device → (key sent, getter, its int argument or null).
    private class Get(val key: String, val method: String, val arg: Int? = null)
    private val GETTERS = listOf(
        "speed.BYDAutoSpeedDevice" to listOf(Get("speed", "getCurrentSpeed")),
        "gearbox.BYDAutoGearboxDevice" to listOf(Get("gear", "getCurrentGear")),
        "doorlock.BYDAutoDoorLockDevice" to listOf(Get("lock", "getDoorLockStatus", 1)), // driver's door: 1 unlocked, 2 locked
        "statistic.BYDAutoStatisticDevice" to listOf(Get("soc", "getElecPercentageValue"), Get("range", "getElecDrivingRangeValue"),
            Get("odo", "getTotalMileageValue")),
        "bodywork.BYDAutoBodyworkDevice" to listOf(Get("steer", "getSteeringWheelValue", 1), // BODYWORK_CMD_STEERING_WHEEL_ANGEL
            Get("winFL", "getWindowOpenPercent", 1), Get("winFR", "getWindowOpenPercent", 2),
            Get("winRL", "getWindowOpenPercent", 3), Get("winRR", "getWindowOpenPercent", 4),
            Get("shade", "getSunroofWindowblindPosition")))
    private class Poll(val dev: Any, val cls: Class<*>, val get: Get) { var dead = false }
    private val polls = ArrayList<Poll>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun poll() {
        for (p in polls) if (!p.dead) try {
            val m = if (p.get.arg == null) p.cls.getMethod(p.get.method) else p.cls.getMethod(p.get.method, Integer.TYPE)
            val v = (if (p.get.arg == null) m.invoke(p.dev) else m.invoke(p.dev, p.get.arg)) as? Number ?: continue
            if (v.toDouble() in invalid) continue
            val line = "ev Get ${p.get.key} d ${v.toDouble()}"
            val same = synchronized(last) { last.put("Get ${p.get.key}", line) == line }
            if (!same) send(line)
        } catch (t: Throwable) { p.dead = true; System.err.println("watch ${p.get.method}: ${t.cause ?: t}") }
        if (polls.any { !it.dead }) main.postDelayed({ poll() }, 1000)
    }

    private fun listen(ctx: android.content.Context, clsName: String) {
        val c = Class.forName(clsName)
        val dev = c.getMethod("getInstance", android.content.Context::class.java).invoke(null, ctx) ?: return
        val reg = c.methods.firstOrNull { it.name == "registerListener" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface } ?: return
        val iface = reg.parameterTypes[0]
        val tag = clsName.substringAfterLast('.').removePrefix("BYDAuto").removeSuffix("Device")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
            try { if (m.name == "onDataEventChanged" && args != null && args.size == 2) event(tag, (args[0] as? Int) ?: 0, args[1]) } catch (_: Throwable) { }
            when (m.returnType) { java.lang.Boolean.TYPE -> false; Integer.TYPE -> 0; else -> null }
        }
        reg.invoke(dev, proxy)
        held.add(dev)
        if (clsName.contains("bodywork")) bodyworkDev = dev
        System.err.println("watch: listening to $tag")
        try { c.getMethod("getAllStatus").invoke(dev) } catch (_: Throwable) { }
    }

    /**
     * A command from the page (JSON over the "cmd" line), run on the bodywork device with the
     * permissive context, so the car acts. The state constants are read from the device class by
     * name, so they are whatever this head unit's framework defines. Windows:
     * {cmd:window, pos:FL|FR|RL|RR, open:0..100} → setBodyWindowCtrlState(area, CLOSE|HALF|OPEN_FULL).
     * Sunshade: {cmd:sunshade, open:0|100} → setSunshadeState(SUNROOF_CLOSE|SUNROOF_OPEN).
     */
    fun command(json: String) = main.post {
        val dev = bodyworkDev ?: run { System.err.println("cmd: no bodywork device"); return@post }
        try {
            val o = org.json.JSONObject(json)
            val c = dev.javaClass
            val k = { name: String -> c.getField(name).getInt(null) }
            when (o.optString("cmd")) {
                "window" -> {
                    val area = mapOf("FL" to 1, "FR" to 2, "RL" to 3, "RR" to 4)[o.optString("pos")] ?: return@post
                    val pct = o.optInt("open", 0)
                    val state = if (pct <= 5) k("WINDOW_CLOSE") else if (pct >= 70) k("WINDOW_OPEN_FULL") else k("WINDOW_OPEN_HALF")
                    val r = c.getMethod("setBodyWindowCtrlState", Integer.TYPE, Integer.TYPE).invoke(dev, area, state)
                    System.err.println("cmd window $area -> $state = $r")
                }
                "sunshade" -> {
                    // setSunshadeState takes a percent 0..100 (0 closed, 100 open), like the moonroof.
                    val pct = o.optInt("open", 0).coerceIn(0, 100)
                    val r = c.getMethod("setSunshadeState", Integer.TYPE).invoke(dev, pct)
                    System.err.println("cmd sunshade -> $pct = $r")
                }
            }
        } catch (t: Throwable) { System.err.println("cmd $json: ${t.cause ?: t}") }
    }

    private fun event(dev: String, type: Int, value: Any?) {
        if (value == null) return
        for ((f, k) in listOf("intValue" to "i", "floatValue" to "f", "doubleValue" to "d")) {
            val v = try { value.javaClass.getField(f).get(value) as? Number } catch (_: Throwable) { null } ?: continue
            if (v.toDouble() in invalid) continue
            val id = String.format("0x%08X", type)
            val line = "ev $dev $id $k $v"
            synchronized(last) { if (last.put("$dev $id", line) == line) return }
            send(line)
            return
        }
    }

    private fun send(line: String) {
        for (o in subscribers) try { synchronized(o) { o.write("$line\n".toByteArray()); o.flush() } } catch (_: Throwable) { subscribers.remove(o) }
    }
}
