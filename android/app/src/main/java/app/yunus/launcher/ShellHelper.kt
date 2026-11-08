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

    // A dropped connection (Yunus restarting) must end only this thread: an exception left to escape
    // here took the whole helper down with it.
    private fun serve(client: Socket, token: String, injector: TouchInjector) = try { serveLines(client, token, injector) } catch (_: Throwable) { }

    private fun serveLines(client: Socket, token: String, injector: TouchInjector) = client.use {
        client.tcpNoDelay = true
        val reader = client.getInputStream().bufferedReader()
        if (reader.readLine() != token) return
        client.getOutputStream().apply { write("ok\n".toByteArray()); flush() }
        val out = client.getOutputStream()
        try { while (true) {
            val line = reader.readLine() ?: break
            if (line == "watch") { CarWatch.subscribe(out); continue }
            // "home yunus|byd": the car's home screen. Yunus's mode has BYD's bars off the screen
            // (Yunus has its own); DiLink's launcher needs them back.
            if (line.startsWith("home ")) {
                val yunus = line.endsWith("yunus")
                val sh = { c: String -> try { Runtime.getRuntime().exec(arrayOf("sh", "-c", c)).waitFor() } catch (t: Throwable) { System.err.println("$c: $t") } }
                sh("cmd package set-home-activity " + if (yunus) "app.yunus.launcher/.MainActivity" else "com.android.launcher3/.home.MainActivity")
                sh(if (yunus) CarWatch.barsCommand() else "wm overscan reset")
                if (!yunus) sh("input keyevent 3") // KEYCODE_HOME: over to DiLink's home
                continue
            }
            // "voice 1|0": BYD's voice assistant draws its bar inside the status bar (at 24,8 dp, fixed in
            // its code), which Yunus's mode keeps above the screen: while it talks the status bar comes
            // back on the screen, then goes back up. Yunus's own window stays where it is.
            if (line.startsWith("voice ")) {
                CarWatch.voiceUp = line.endsWith("1")
                val c = CarWatch.barsCommand()
                try { Runtime.getRuntime().exec(arrayOf("sh", "-c", c)).waitFor() } catch (t: Throwable) { System.err.println("$c: $t") }
                continue
            }
            if (line == "recents") { try { Runtime.getRuntime().exec(arrayOf("input", "keyevent", "187")) } catch (t: Throwable) { System.err.println("recents: $t") }; continue } // KEYCODE_APP_SWITCH
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
                    "full" -> full(display, fields.getOrNull(2) ?: continue)
                }
            } catch (t: Throwable) { System.err.println("$line: $t") } // goes to the helper's log file
        } } finally { CarWatch.unsubscribe(out) }
    }

    // Fixed argument lists (no shell): keep the display upright, stop the app's task elsewhere,
    // then start it on the display.
    // Opening took seconds: four window commands to keep the display upright on every launch, and the
    // app force-stopped so it always started cold. Now the display is set upright once, and an app
    // already running is moved over as it is (warm, at once); only an app not running is started.
    private val upright = HashSet<Int>()
    private fun launch(display: Int, component: String) {
        if (!COMPONENT.matches(component)) return
        if (upright.add(display)) lockRotation(display)
        val pkg = component.substringBefore('/')
        val running = try { Tasks.roots().firstOrNull { (_, d, names) -> d != display && pkg in names } } catch (_: Throwable) { null }
        val moved = running != null && try { Tasks.move(running.first, display); true } catch (t: Throwable) { System.err.println("move $pkg: ${t.cause ?: t}"); false }
        if (!moved) {
            if (running != null) ProcessBuilder("am", "force-stop", pkg).inheritIO().start().waitFor()
            ProcessBuilder("am", "start", "--display", display.toString(), "-n", component).inheritIO().start().waitFor()
        }
        keepOnDisplay(display, pkg)
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
    // The app is then stopped as well (it kept running in the background otherwise), unless it is
    // also open somewhere else (the map window, the main screen).
    private fun close(display: Int) {
        synchronized(watching) { watching.remove(display) }
        val roots = Tasks.roots()
        val pkgs = HashSet<String>()
        for ((task, onDisplay, names) in roots) if (onDisplay == display) {
            pkgs.addAll(names)
            try { Tasks.remove(task) } catch (t: Throwable) { System.err.println("close $display task $task: $t") }
        }
        val elsewhere = roots.filter { it.second != display }.flatMap { it.third }.toSet()
        for (pkg in pkgs) if (pkg !in elsewhere && pkg != OWNER && Regex("[A-Za-z0-9_.]+").matches(pkg))
            ProcessBuilder("am", "force-stop", pkg).inheritIO().start().waitFor()
    }

    // The window's full-screen key: the app's task goes onto the main screen, in front, as it is.
    // Not watched any more (the watch would move it back); Yunus's launch puts it back later.
    private fun full(display: Int, component: String) {
        if (!COMPONENT.matches(component)) return
        synchronized(watching) { watching.remove(display) }
        for ((task, onDisplay, _) in Tasks.roots()) if (onDisplay == display) try { Tasks.move(task, 0) } catch (t: Throwable) { System.err.println("full $display task $task: $t") }
        // Moved, it sits behind Yunus: started again it comes to the front as it is (same task),
        // once the move has settled (started at once, it stayed behind).
        SystemClock.sleep(400)
        ProcessBuilder("am", "start", "-n", component).inheritIO().start().waitFor()
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
            // Android Auto starts its screen again on the main display whenever the phone connects,
            // which can be any time: it is kept on the display for as long as it is the slot's app
            // (until another launch on this display); everything else for 20 s after its start.
            val end = if (pkg == "com.byd.androidauto") Long.MAX_VALUE else SystemClock.uptimeMillis() + 20000
            while (SystemClock.uptimeMillis() < end && synchronized(watching) { watching[display] == id }) {
                SystemClock.sleep(700)
                try {
                    for ((task, onDisplay, pkgs) in Tasks.roots())
                        if (onDisplay == 0 && pkg in pkgs) {
                            // Its start put it in front of Yunus on the main screen. Yunus (single task)
                            // is brought back in front of it FIRST: started while the app is still on
                            // top, that is a real transition that shows Yunus again. Moving the app away
                            // first left Yunus "on top" but hidden after Android 14's to-back animation
                            // (a black screen), and a start then changed nothing.
                            ProcessBuilder("am", "start", "-W", "-n", "$OWNER/.MainActivity").inheritIO().start().waitFor()
                            SystemClock.sleep(150)
                            Tasks.move(task, display)
                            System.err.println("moved $pkg task $task back to display $display")
                            // A video surface (SurfaceView) can stay behind on the main screen after the
                            // move; a relayout on the display (1 px narrower, then back) brings it along.
                            SystemClock.sleep(300)
                            nudge(display)
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
        // BYD's status bar may be pushed above the screen (wm overscan 0,-84,…: it never shows over
        // Yunus); then one pull started on it, up there, opens the quick panel straight away.
        val bar = statusBarTop()
        if (bar != null && bar < 0) { swipe(w * 0.78f, bar / 2f, h * 0.46f); return }
        swipe(w * 0.78f, 1f, h * 0.2f)
        SystemClock.sleep(700)
        swipe(w * 0.78f, h * 0.028f, h * 0.46f)
    }

    /** The top of BYD's status bar window (negative when it sits above the screen), from dumpsys. */
    private fun statusBarTop(): Int? = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys window windows | grep -A30 ' StatusBar}' | grep -m1 'mFrame='"))
        val line = p.inputStream.bufferedReader().readText(); p.waitFor()
        Regex("""mFrame=\[(-?\d+),(-?\d+)\]""").find(line)?.groupValues?.get(2)?.toInt()
    } catch (_: Throwable) { null }

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

    // roots() gives root task ids (Android 12+) or, on Android 10, stack ids: each is removed by its
    // own call (removeTask with a stack id removed nothing, or another task with that number).
    fun remove(id: Int) {
        val rootTasks = atm.javaClass.methods.any { it.name == "getAllRootTaskInfos" && it.parameterCount == 0 }
        if (rootTasks) atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType).invoke(atm, id)
        else atm.javaClass.getMethod("removeStack", Int::class.javaPrimitiveType).invoke(atm, id)
    }

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
    @Volatile private var acDev: Any? = null // for the climate commands, set in start()
    @Volatile private var settingDev: Any? = null // heated steering wheel, set in start()
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
            else {
                // Yunus restarted while the helper kept running: send the current values straight to
                // this new connection (not broadcast, which a stale old connection could disrupt),
                // and re-prime the listener snapshots so a full fresh set flows.
                synchronized(last) {
                    for (line in last.values) try { synchronized(out) { out.write("$line\n".toByteArray()); out.flush() } } catch (_: Throwable) { subscribers.remove(out); return@post }
                }
                for (dev in held) try { dev.javaClass.getMethod("getAllStatus").invoke(dev) } catch (_: Throwable) { }
            }
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
        for (name in listOf("bodywork.BYDAutoBodyworkDevice", "speed.BYDAutoSpeedDevice",
                "doorlock.BYDAutoDoorLockDevice", "ota.BYDAutoOtaDevice",
                // Driver assist: the lane camera's lines come as events at once (the poll is once a second).
                "adas.BYDAutoADASDevice"))
            try { listen(ctx, "android.hardware.bydauto.$name") } catch (t: Throwable) { System.err.println("watch $name: ${t.cause ?: t}") }
        // TEMPORARY, to find the blind spot's side on a drive: every other device, while this file exists.
        if (java.io.File("/data/local/tmp/yunus_rec").exists()) for (name in listOf("radar.BYDAutoRadarDevice", "signal.BYDAutoSignalDevice",
                "funcnotice.BYDAutoFuncNoticeDevice", "reminder.BYDAutoReminderDevice", "vehicledata.BYDAutoVehicleDataDevice", "collision.BYDAutoCollisionDevice",
                "doormirror.BYDAutoRearViewMirrorDevice", "special.BYDAutoSpecialDevice", "panorama.BYDAutoPanoramaDevice", "auxiliary.BYDAutoAuxDevice", "motor.BYDAutoMotorDevice", "instrument.BYDAutoInstrumentDevice"))
            try { listen(ctx, "android.hardware.bydauto.$name") } catch (t: Throwable) { System.err.println("watch $name: ${t.cause ?: t}") }
        // Also the getters Kinex reads, once a second (SOC, range and speed don't come as events
        // while parked). A getter that is refused is not asked again.
        for ((name, gets) in GETTERS) try {
            val c = Class.forName("android.hardware.bydauto.$name")
            val dev = c.getMethod("getInstance", android.content.Context::class.java).invoke(null, ctx) ?: continue
            for (g in gets) polls.add(Poll(dev, c, g))
        } catch (t: Throwable) { System.err.println("watch getters $name: ${t.cause ?: t}") }
        try {
            acDev = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice")
                .getMethod("getInstance", android.content.Context::class.java).invoke(null, ctx)
            settingDev = Class.forName("android.hardware.bydauto.setting.BYDAutoSettingDevice")
                .getMethod("getInstance", android.content.Context::class.java).invoke(null, ctx)
        } catch (t: Throwable) { System.err.println("watch ac: ${t.cause ?: t}") }
        try {
            val c = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
            instrumentDev = c.getMethod("getInstance", android.content.Context::class.java).invoke(null, ctx)
            var cls: Class<*>? = c
            while (cls != null && featGet == null) {
                featGet = try { cls.getDeclaredMethod("get", Integer.TYPE, Integer.TYPE).apply { isAccessible = true } } catch (_: Throwable) { null }
                cls = cls.superclass
            }
        } catch (t: Throwable) { System.err.println("watch instrument: ${t.cause ?: t}") }
        poll()
        steerPoll()
        speedPoll()
    }

    // Device → (key sent, getter, its int argument or null).
    private class Get(val key: String, val method: String, val arg: Int? = null)
    private val GETTERS = listOf(
        "speed.BYDAutoSpeedDevice" to listOf(Get("speed", "getCurrentSpeed")),
        // The park-brake and seat-belt lights arrive as events only on change, so read them too
        // (Instrument's getELECParkingState/Color answer 65535, invalid, on the Dolphin).
        // getCurrentGear is refused (its EPB check queries CarSettings as package "android", not our
        // uid 2000); it is built on GEARBOX_AUTO_MODE (P 1, R 2, N 3, D 4, M 5, S 6), read directly.
        "gearbox.BYDAutoGearboxDevice" to listOf(Get("gear", "getGearboxAutoModeType"), Get("parkSw", "getParkBrakeSwitch")), // 1 applied
        // Seat heat and ventilation (1 off, 2 low, 3 high): their events come only on change.
        "setting.BYDAutoSettingDevice" to listOf(Get("seatH1", "getSeatHeatingState", 1), Get("seatH2", "getSeatHeatingState", 2),
            Get("seatV1", "getSeatVentilatingState", 1), Get("seatV2", "getSeatVentilatingState", 2)),
        // Lamps (getLightStatus: 1 side, 2 low, 3 high; 1 on) and the knob on AUTO: events only on change.
        "light.BYDAutoLightDevice" to listOf(Get("lampSide", "getLightStatus", 1), Get("lampLow", "getLightStatus", 2),
            Get("lampHigh", "getLightStatus", 3), Get("drl", "getDayRunningLightState")),
        "safetybelt.BYDAutoSafetyBeltDevice" to listOf(Get("belt1", "getSafetyBeltStatus", 1)), // SAFETY_BELT_AREA_MAIN; 1 buckled, 0 not
        "doorlock.BYDAutoDoorLockDevice" to listOf(Get("lock", "getDoorLockStatus", 1)), // driver's door: 1 unlocked, 2 locked
        "statistic.BYDAutoStatisticDevice" to listOf(Get("soc", "getElecPercentageValue"), Get("range", "getElecDrivingRangeValue"),
            Get("odo", "getTotalMileageValue")),
        "bodywork.BYDAutoBodyworkDevice" to listOf(Get("steer", "getSteeringWheelValue", 1), // BODYWORK_CMD_STEERING_WHEEL_ANGEL
            Get("winFL", "getWindowOpenPercent", 1), Get("winFR", "getWindowOpenPercent", 2),
            Get("winRL", "getWindowOpenPercent", 3), Get("winRR", "getWindowOpenPercent", 4),
            Get("shade", "getSunroofWindowblindPosition")),
        // The charge events only arrive on change, so a car already plugged in when Yunus started
        // never sends them; read the gun (2 = connected → show the cable) and the state.
        "charging.BYDAutoChargingDevice" to listOf(Get("chgGun", "getChargingGunState"), Get("chgState", "getChargingState"),
            Get("chgPower", "getChargingPower"), Get("restMin", "getChargingRestTime"))) // getChargingRestTime: [hour, minute]
    // The cluster's warning lights (INSTRUMENT_* feature ids) come as events only when they change,
    // so a light already on when Yunus starts would never show. Read them with the device's own
    // get(deviceType, featureId) and hand them on as Instrument events: 1 on, 2 off, 3+ flashing.
    private val LIGHTS = intArrayOf(0x2960000E, 0x29600026, 0x27500014, 0x2750002C, 0x28700014, 0x27500020, 0x2870000E,
        0x2870002C, 0x2A60002C, 0x2A600032, 0x2B000008, 0x2A60001A, 0x2B00001A, 0x27500008, 0x2C000014,
        0x28F0000E, 0x2B00000E)
    private var instrumentDev: Any? = null
    private var featGet: java.lang.reflect.Method? = null
    private var lightsDead = false
    private class Poll(val dev: Any, val cls: Class<*>, val get: Get) {
        var dead = false
        val method: java.lang.reflect.Method by lazy { if (get.arg == null) cls.getMethod(get.method) else cls.getMethod(get.method, Integer.TYPE) }
        fun read(): Any? = if (get.arg == null) method.invoke(dev) else method.invoke(dev, get.arg)
    }
    // Read in their own loops.
    private val FAST = setOf("steer", "speed")
    // These also come as events when they change, and change slowly: read every 5 s, not every second.
    private val SLOW = setOf("soc", "range", "odo", "chgGun", "chgState", "chgPower", "restMin", "shade")
    private var pollTurn = 0
    private val polls = ArrayList<Poll>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // DAB stations broadcast slides (the station logo, the programme's picture); the DAB service
    // saves them in its own app folder, which Yunus can't read but shell (sdcard_rw) can. The newest
    // is copied where the app can read it, for the music card (MediaBridge.DAB_SLIDE).
    private val slideDir = java.io.File("/storage/emulated/0/Android/data/com.fd.service.dab/files/dab/slide")
    private var slideSeen = 0L
    private fun copyDabSlide() { try {
        val f = slideDir.listFiles()?.maxByOrNull { it.lastModified() }
        if (f != null && f.lastModified() != slideSeen) {
            slideSeen = f.lastModified()
            val tmp = java.io.File("/data/local/tmp/yunus_dab.tmp")
            f.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.setReadable(true, false)
            tmp.setLastModified(f.lastModified())
            tmp.renameTo(java.io.File("/data/local/tmp/yunus_dab.jpg"))
        }
    } catch (_: Throwable) { } }

    // BYD's bars off the screen: wm overscan is given in the landscape frame and turns with the screen,
    // so upright it would push the sides off and leave the bars on. Top and bottom of the screen as it
    // is turned now, in that frame (the status bar 84 px, the navigation bar 90 px).
    @Volatile var voiceUp = false // the status bar back on the screen for the voice assistant
    private fun displayInfo() = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
        .let { it.javaClass.getMethod("getDisplayInfo", Integer.TYPE).invoke(it, 0) }
    fun barsCommand(): String {
        val top = if (voiceUp) 0 else -84
        val bottom = -90
        return when (displayInfo().let { it.javaClass.getField("rotation").getInt(it) }) {
            1 -> "wm overscan $bottom,0,$top,0"
            2 -> "wm overscan 0,$bottom,0,$top"
            3 -> "wm overscan $top,0,$bottom,0"
            else -> "wm overscan 0,$top,0,$bottom"
        }
    }
    // The screen was turned while the bars are off (some overscan negative): set them again for the new
    // rotation. The overscan values themselves don't change on a turn, so the rotation is what's watched.
    private var barsRotation = -1
    private fun keepBars() { try {
        val i = displayInfo(); val c = i.javaClass
        val rot = c.getField("rotation").getInt(i)
        if (rot == barsRotation) return
        if (listOf("overscanLeft", "overscanTop", "overscanRight", "overscanBottom").any { c.getField(it).getInt(i) < 0 })
            setBars()
        barsRotation = rot
    } catch (t: Throwable) { System.err.println("bars: $t") } }

    // Straight to the window manager (this process may: it is the shell): a "wm overscan" process
    // took a few hundred ms to start, and the screen showed the wrong size meanwhile.
    private fun setBars() {
        val v = Regex("-?[0-9]+").findAll(barsCommand()).map { it.value.toInt() }.toList()
        try {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
            wm.javaClass.getMethod("setOverscan", Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE)
                .invoke(wm, 0, v[0], v[1], v[2], v[3])
        } catch (t: Throwable) {
            System.err.println("bars direct: ${t.cause ?: t}")
            Runtime.getRuntime().exec(arrayOf("sh", "-c", barsCommand())).waitFor()
        }
    }

    // The screen's turn watched closely (on its own thread, not the 1 s poll): the bars are set
    // again for the new rotation within a frame or two, so the screen doesn't show the in-between.
    private val turnWatch = Thread {
        while (true) { keepBars(); try { Thread.sleep(50) } catch (_: InterruptedException) { break } }
    }.apply { isDaemon = true; name = "yunus-turn" }

    private fun poll() {
        copyDabSlide()
        if (!turnWatch.isAlive) try { turnWatch.start() } catch (_: Throwable) { }
        val slowTurn = pollTurn++ % 5 == 0
        for (p in polls) if (!p.dead && p.get.key !in FAST && (slowTurn || p.get.key !in SLOW)) try {
            val raw = p.read()
            // getChargingRestTime returns [hour, minute]; everything else a single number.
            val dv = when (raw) {
                is Number -> raw.toDouble()
                is IntArray -> if (raw.size >= 2) (raw[0] * 60 + raw[1]).toDouble() else if (raw.isNotEmpty()) raw[0].toDouble() else continue
                else -> continue
            }
            if (dv in invalid) continue
            val line = "ev Get ${p.get.key} d $dv"
            val same = synchronized(last) { last.put("Get ${p.get.key}", line) == line }
            if (!same) send(line)
        } catch (t: Throwable) { p.dead = true; System.err.println("watch ${p.get.method}: ${t.cause ?: t}") }
        val inst = instrumentDev; val fg = featGet
        if (inst != null && fg != null && !lightsDead) try {
            // Their events carry any change at once; this is for a light already on at the start.
            if (slowTurn) for (id in LIGHTS) {
                val v = fg.invoke(inst, 1007, id) as Int // BYDAUTO_DEVICE_INSTRUMENT
                if (v in 0..15) event("Instrument", id, v) // 65535 and the like: no such light here
            }
            // The light knob (LIGHT_KNOB_CURRENT_GEAR, 1 off 2 AUTO 3 park 4 low) and the daytime
            // lights, read the same way on the light device's type (BYDAUTO_DEVICE_LIGHT 1004).
            for (id in intArrayOf(0x13300023, 0x4FE0000E)) {
                val v = fg.invoke(inst, 1004, id) as Int
                if (v in 0..15) event("Light", id, v)
            }
            // The cluster's driver-assist row: blind spot (2: a car in it; the side is not given, not in
            // the ADAS, instrument, mirror or collision events either), lane keeping, and the door-open
            // warning with its side (DOW_WARN_LEFT/RIGHT), and whether the lane camera sees the left and
            // right line (LEFT/RIGHT_LINE_TRACKING_STATUS; also as events, see the ADAS listener),
            // and the cruise control's state and set speed (instrument; 65535 while not in use).
            for (id in intArrayOf(0x41800008, 0x32F0002A, 0x41800020, 0x41800022, 0x3160000C, 0x3160002A)) { val v = fg.invoke(inst, 1038, id) as Int; if (v in 0..15) event("Adas", id, v) }
            for (id in intArrayOf(0x29600032, 0x2A600008)) { val v = fg.invoke(inst, 1007, id) as Int; event("Instrument", id, if (v in 0..300) v else -1) }
        } catch (t: Throwable) { lightsDead = true; System.err.println("watch lights: ${t.cause ?: t}") }
        if (polls.any { !it.dead } || !lightsDead) main.postDelayed({ poll() }, 1000)
    }

    // The steering angle comes as no event, and once a second made the model's front wheels and the
    // lane lines lag the wheel in steps: it alone is read ten times a second. Sent only when it has
    // moved 2° or more, so sensor jitter never keeps the page drawing (each change wakes the 3D view).
    private var steerSent = Double.NaN
    private fun steerPoll() {
        val p = polls.firstOrNull { it.get.key == "steer" } ?: return
        if (p.dead) return
        try {
            val v = Math.round((p.read() as Number).toDouble()).toDouble()
            if (v !in invalid && !(Math.abs(v - steerSent) < 2.0)) { steerSent = v; send("ev Get steer d $v") }
        } catch (t: Throwable) { p.dead = true; System.err.println("watch steer: ${t.cause ?: t}"); return }
        main.postDelayed({ steerPoll() }, 100)
    }

    // The speedometer: four times a second, sent only when the whole km/h changes.
    private var speedSent = Double.NaN
    private fun speedPoll() {
        val p = polls.firstOrNull { it.get.key == "speed" } ?: return
        if (p.dead) return
        try {
            val v = Math.round((p.read() as Number).toDouble()).toDouble()
            if (v !in invalid && v != speedSent) { speedSent = v; send("ev Get speed d $v") }
        } catch (t: Throwable) { p.dead = true; System.err.println("watch speed: ${t.cause ?: t}"); return }
        main.postDelayed({ speedPoll() }, 250)
    }

    private fun listen(ctx: android.content.Context, clsName: String) {
        val c = Class.forName(clsName)
        val dev = c.getMethod("getInstance", android.content.Context::class.java).invoke(null, ctx) ?: return
        val tag = clsName.substringAfterLast('.').removePrefix("BYDAuto").removeSuffix("Device")
        if (clsName.contains("bodywork")) bodyworkDev = dev // for commands, even if no interface listener below
        val reg = c.methods.firstOrNull { it.name == "registerListener" && it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface }
        if (reg == null) { System.err.println("watch: $tag has no interface registerListener"); return }
        val iface = reg.parameterTypes[0]
        val proxy = java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, m, args ->
            try {
                if (m.name == "onDataEventChanged" && args != null && args.size == 2) event(tag, (args[0] as? Int) ?: 0, args[1])
                else if (m.name == "onDataChanged" && args != null && args.size == 1) {
                    val ev = args[0]
                    val id = (try { ev?.javaClass?.getMethod("getEventType")?.invoke(ev) } catch (_: Throwable) { null }) as? Int ?: 0
                    event(tag, id, try { ev?.javaClass?.getMethod("getValue")?.invoke(ev) } catch (_: Throwable) { null })
                } else if (m.name.startsWith("on")) System.err.println("watch $tag ${m.name}(${args?.joinToString { it?.toString() ?: "null" } ?: ""})")
            } catch (_: Throwable) { }
            when (m.returnType) { java.lang.Boolean.TYPE -> false; Integer.TYPE -> 0; else -> null }
        }
        reg.invoke(dev, proxy)
        held.add(dev)
        System.err.println("watch: listening to $tag (${iface.simpleName})")
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
        if (json.contains("\"cmd\":\"wifi")) return@post wifiCommand(json)
        if (json.contains("\"cmd\":\"probe")) return@post probe(json)
        if (json.contains("\"cmd\":\"ac") || json.contains("\"cmd\":\"wheelHeat") || json.contains("\"cmd\":\"seat\"")) return@post acCommand(json)
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
                "lock" -> {
                    // Whole-car lock/unlock through the Bodywork device's inherited set(deviceType, featureId, value):
                    // BODYWORK_REMOTE_CONTROL_LOCK 0x18000009 / UNLOCK 0x18000008, Bodywork deviceType 1001.
                    val on = o.optBoolean("on", true)
                    val feature = if (o.has("feature")) o.getInt("feature") else if (on) 0x18000009 else 0x18000008
                    val devType = o.optInt("type", 1001)
                    val value = o.optInt("value", 1)
                    var setM: java.lang.reflect.Method? = null
                    var cls: Class<*>? = dev.javaClass
                    while (cls != null && setM == null) {
                        setM = try { cls.getDeclaredMethod("set", Integer.TYPE, Integer.TYPE, Integer.TYPE).apply { isAccessible = true } } catch (_: Throwable) { null }
                        cls = cls.superclass
                    }
                    val r = setM?.invoke(dev, devType, feature, value)
                    System.err.println("cmd lock on=$on type=$devType feature=0x${Integer.toHexString(feature)} value=$value -> $r (set=${setM != null})")
                }
                "post" -> {
                    // Raw postEvent(device_type, event_type, value, obj) — how the third-party lock app
                    // (br.com.rory.electro) drives the car: BODYWORK 1001 / 0x48E00037 / value 1 = lock, 0 = unlock.
                    // set() is refused for this feature; postEvent is the channel that actually acts.
                    val devType = o.optInt("type", 1001)
                    val feature = o.getInt("feature")
                    val value = o.optInt("value", 1)
                    // The 4th arg carries the real payload; null posts nothing (returns true but no-op).
                    // The lock app passes a BYDAutoEventValue(int), so build the same.
                    val evObj = try { Class.forName("android.hardware.bydauto.BYDAutoEventValue").getConstructor(Integer.TYPE).newInstance(value) } catch (t: Throwable) { System.err.println("post: no BYDAutoEventValue: ${t.cause ?: t}"); null }
                    var pm: java.lang.reflect.Method? = null
                    var cls: Class<*>? = dev.javaClass
                    while (cls != null && pm == null) {
                        pm = try { cls.getDeclaredMethod("postEvent", Integer.TYPE, Integer.TYPE, Integer.TYPE, Any::class.java).apply { isAccessible = true } } catch (_: Throwable) { null }
                        cls = cls.superclass
                    }
                    val r = pm?.invoke(dev, devType, feature, value, evObj)
                    System.err.println("cmd post type=$devType feature=0x${Integer.toHexString(feature)} value=$value obj=${evObj != null} -> $r (m=${pm != null})")
                }
                "lockViaSetting" -> {
                    // A third path, not in Kinex's own bundled HAL bridge either (it never sets lock
                    // state anywhere, DL3 or DL5 — only reads it): the car's own Setting device
                    // (introspect-dilink3.txt) declares a plain getDoorLock()/setDoorLock(int) pair,
                    // separate from Bodywork's set()/postEvent() (both already refused/no-op, see
                    // commit 1c80e5e) and from the read-only doorlock device. Tried on the car (this
                    // commit): setDoorLock refused (0x800003E8, same signature-gate family as
                    // Bodywork's set()) and getDoorLock itself is also gated (-10011, not a real
                    // state) — same dead end as the other two. The lock/post/lockViaSetting hooks are
                    // all kept for reference; the real command needs Bluetooth phone-key emulation.
                    val sd = settingDev ?: run { System.err.println("cmd lockViaSetting: no setting device"); return@post }
                    val value = o.optInt("value", if (o.optBoolean("on", true)) 1 else 0)
                    val r = try { sd.javaClass.getMethod("setDoorLock", Integer.TYPE).invoke(sd, value) } catch (t: Throwable) { "refused: ${t.cause ?: t}" }
                    val now = try { sd.javaClass.getMethod("getDoorLock").invoke(sd) } catch (t: Throwable) { "?: ${t.cause ?: t}" }
                    System.err.println("cmd lockViaSetting value=$value -> $r (getDoorLock now=$now)")
                }
            }
        } catch (t: Throwable) { System.err.println("cmd $json: ${t.cause ?: t}") }
    }

    /**
     * Climate, on the AC device with the permissive context. Every setter takes the control source
     * first (0, AC_CTRL_SOURCE_UI_KEY, as the car's own screen). acPower {on} → start/stop;
     * acTemp {c} → setAcTemperature(area 0 both, whole °C 17..33, source, unit 1 °C; the
     * framework also lets 34..66 through, but the Dolphin takes that as 33);
     * acFan {level 1..7} → setAcWindLevel; acFlow {mode} → setAcWindMode (1 face, 2 face+feet,
     * 3 feet, 4 feet+screen, 5 screen); acDefrost {area 1 front / 2 rear, on} → setAcDefrostState;
     * acCycle {inside} → setAcCycleMode (1 inside air); acAuto {on} → setAcControlMode (0 auto).
     * wheelHeat {on} → the Setting device's setSteeringWheelHeatingState (1 off, 2 on).
     */
    private fun acCommand(json: String) {
        val o0 = try { org.json.JSONObject(json) } catch (_: Throwable) { return }
        if (o0.optString("cmd") == "seat") {
            // {seat: driver|pass, heat 0..2, vent 0..2} → setSeatHeatingState / setSeatVentilatingState
            // (area 1 driver, 2 passenger; level 1 off, 2 low, 3 high). Heat and vent exclude each
            // other, so the one switched off goes first.
            val sd = settingDev ?: run { System.err.println("cmd: no setting device"); return }
            try {
                val area = if (o0.optString("seat") == "pass") 2 else 1
                val heat = o0.optInt("heat", 0).coerceIn(0, 2) + 1
                val vent = o0.optInt("vent", 0).coerceIn(0, 2) + 1
                val c = sd.javaClass
                val h = c.getMethod("setSeatHeatingState", Integer.TYPE, Integer.TYPE)
                val v = c.getMethod("setSeatVentilatingState", Integer.TYPE, Integer.TYPE)
                // Only what changes: a second set right after the first (say "cooling off" when it
                // already is) cancels the first one on the car (seen: heat off then vent off left
                // the heat on).
                val curH = c.getMethod("getSeatHeatingState", Integer.TYPE).invoke(sd, area) as Int
                val curV = c.getMethod("getSeatVentilatingState", Integer.TYPE).invoke(sd, area) as Int
                val r = ArrayList<Any?>()
                if (heat > 1) { if (curV != vent && vent > 1) r += v.invoke(sd, area, vent); if (curH != heat) r += h.invoke(sd, area, heat) }
                else if (vent > 1) { if (curV != vent) r += v.invoke(sd, area, vent) } // on: the car lets the heat go
                else { if (curH != 1) r += h.invoke(sd, area, 1); if (curV != 1) { if (r.isNotEmpty()) Thread.sleep(400); r += v.invoke(sd, area, 1) } }
                System.err.println("cmd seat $area heat=$heat vent=$vent (was $curH/$curV) = $r")
            } catch (t: Throwable) { System.err.println("cmd $json: ${t.cause ?: t}") }
            return
        }
        if (o0.optString("cmd") == "wheelHeat") {
            val sd = settingDev ?: run { System.err.println("cmd: no setting device"); return }
            try {
                val r = sd.javaClass.getMethod("setSteeringWheelHeatingState", Integer.TYPE).invoke(sd, if (o0.optBoolean("on")) 2 else 1)
                System.err.println("cmd wheelHeat ${o0.optBoolean("on")} = $r")
            } catch (t: Throwable) { System.err.println("cmd $json: ${t.cause ?: t}") }
            return
        }
        val dev = acDev ?: run { System.err.println("cmd: no ac device"); return }
        try {
            val o = o0
            val c = dev.javaClass
            val i = Integer.TYPE
            val call = { name: String, args: Array<Int> ->
                val m = c.getMethod(name, *Array(args.size) { i })
                val r = m.invoke(dev, *args)
                System.err.println("cmd ac $name(${args.joinToString()}) = $r")
            }
            when (o.optString("cmd")) {
                "acPower" -> call(if (o.optBoolean("on")) "start" else "stop", arrayOf(0))
                "acTemp" -> call("setAcTemperature", arrayOf(0, Math.round(o.optDouble("c", 22.0)).toInt().coerceIn(17, 33), 0, 1))
                "acFan" -> call("setAcWindLevel", arrayOf(0, o.optInt("level", 1).coerceIn(1, 7)))
                "acFlow" -> call("setAcWindMode", arrayOf(0, o.optInt("mode", 1).coerceIn(1, 7)))
                "acDefrost" -> call("setAcDefrostState", arrayOf(0, o.optInt("area", 1), if (o.optBoolean("on")) 1 else 0))
                "acCycle" -> call("setAcCycleMode", arrayOf(0, if (o.optBoolean("inside")) 1 else 0))
                "acAuto" -> call("setAcControlMode", arrayOf(0, if (o.optBoolean("on")) 0 else 1))
            }
        } catch (t: Throwable) { System.err.println("cmd $json: ${t.cause ?: t}") }
    }

    /**
     * Wi-Fi, through the wifi service itself as the shell (which holds NETWORK_SETTINGS and location,
     * so it may switch Wi-Fi, scan, read the saved networks and join one; an app may not on
     * Android 10). Replies go to the page as "msg {wifi:{on, ssid, nets:[…]}}".
     * {cmd:wifi} → state and list; wifiOn {on}; wifiScan; wifiJoin {ssid, pass?}.
     */
    private val wifiSvc by lazy {
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "wifi") as android.os.IBinder
        Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)!!
    }
    private fun wifi(name: String, vararg args: Any?): Any? =
        wifiSvc.javaClass.methods.first { it.name == name && it.parameterTypes.size == args.size }.invoke(wifiSvc, *args)
    private fun field(o: Any, name: String): Any? = try { o.javaClass.getField(name).get(o) } catch (_: Throwable) { null }
    private fun saved(): List<Any> {
        val slice = wifi("getConfiguredNetworks", SHELL) ?: return emptyList() // (the "privileged" list needs READ_WIFI_CREDENTIAL)
        @Suppress("UNCHECKED_CAST")
        return (slice.javaClass.getMethod("getList").invoke(slice) as? List<Any>).orEmpty()
    }
    private const val SHELL = "com.android.shell"
    private fun unquote(s: String?) = s?.removeSurrounding("\"").orEmpty()

    private fun wifiCommand(json: String) {
        try {
            val o = org.json.JSONObject(json)
            when (o.optString("cmd")) {
                "wifiOn" -> wifi("setWifiEnabled", SHELL, o.optBoolean("on"))
                "wifiScan" -> { wifi("startScan", SHELL); main.postDelayed({ wifiReport() }, 3500) }
                "wifiJoin" -> {
                    val ssid = o.optString("ssid"); val pass = o.optString("pass", "")
                    val known = saved().firstOrNull { unquote(field(it, "SSID") as? String) == ssid }
                    val id = if (known != null && pass.isEmpty()) field(known, "networkId") as Int else {
                        val c = Class.forName("android.net.wifi.WifiConfiguration").getConstructor().newInstance()
                        c.javaClass.getField("SSID").set(c, "\"$ssid\"")
                        val km = c.javaClass.getField("allowedKeyManagement").get(c) as java.util.BitSet
                        if (pass.isEmpty()) km.set(0) /* NONE */ else c.javaClass.getField("preSharedKey").set(c, "\"$pass\"")
                        wifi("addOrUpdateNetwork", c, SHELL) as Int
                    }
                    val r = id >= 0 && wifi("enableNetwork", id, true, SHELL) == true
                    wifi("reconnect", SHELL)
                    System.err.println("wifi join $ssid id=$id -> $r")
                    main.postDelayed({ wifiReport() }, 4000)
                }
            }
            main.postDelayed({ wifiReport() }, 600)
        } catch (t: Throwable) { System.err.println("wifi $json: ${t.cause ?: t}") }
    }

    /** {cmd:probe, type, ids:[…]} → msg {probe:{type, values:{id: value}}}: a feature's raw value read
     *  with get(deviceType, featureId), to find what a signal says on this car. */
    private fun probe(json: String) {
        val inst = instrumentDev; val fg = featGet ?: return
        try {
            val o = org.json.JSONObject(json); val type = o.getInt("type"); val ids = o.getJSONArray("ids")
            val vals = org.json.JSONObject()
            for (i in 0 until ids.length()) { val id = ids.getInt(i); vals.put(String.format("0x%08X", id), try { fg.invoke(inst, type, id) } catch (t: Throwable) { "${t.cause ?: t}" }) }
            send("msg " + org.json.JSONObject().put("probe", org.json.JSONObject().put("type", type).put("values", vals)))
        } catch (t: Throwable) { System.err.println("probe $json: ${t.cause ?: t}") }
    }

    private fun wifiReport() {
        try {
            val on = wifi("getWifiEnabledState") == 3 // WIFI_STATE_ENABLED
            val info = wifi("getConnectionInfo", SHELL)
            val cur = info?.let { unquote(it.javaClass.getMethod("getSSID").invoke(it) as? String) }.orEmpty().takeIf { it != "<unknown ssid>" }.orEmpty()
            val known = saved().map { unquote(field(it, "SSID") as? String) }.toSet()
            val nets = org.json.JSONArray()
            @Suppress("UNCHECKED_CAST")
            val scan = (wifi("getScanResults", SHELL) as? List<Any>).orEmpty()
            // One row per name, its strongest access point.
            scan.groupBy { field(it, "SSID") as? String ?: "" }.filterKeys { it.isNotBlank() }
                .map { (name, aps) -> name to aps.maxByOrNull { field(it, "level") as Int }!! }
                .sortedByDescending { field(it.second, "level") as Int }
                .forEach { (name, ap) ->
                    val rssi = field(ap, "level") as Int
                    val caps = field(ap, "capabilities") as? String ?: ""
                    nets.put(org.json.JSONObject().put("ssid", name)
                        .put("bars", android.net.wifi.WifiManager.calculateSignalLevel(rssi, 4))
                        .put("lock", Regex("WPA|WEP|SAE|PSK|EAP").containsMatchIn(caps))
                        .put("saved", name in known).put("on", name == cur))
                }
            send("msg " + org.json.JSONObject().put("wifi", org.json.JSONObject().put("on", on).put("ssid", cur).put("nets", nets)))
        } catch (t: Throwable) { System.err.println("wifi report: ${t.cause ?: t}") }
    }

    private fun event(dev: String, type: Int, value: Any?) {
        if (value == null) return
        // Normally a BYDAutoEventValue (intValue/floatValue/doubleValue); onDataChanged may hand a raw Number.
        val cand: List<Pair<String, Number>> = if (value is Number) listOf("i" to value)
            else listOf("intValue" to "i", "floatValue" to "f", "doubleValue" to "d").mapNotNull { (f, k) ->
                (try { value.javaClass.getField(f).get(value) as? Number } catch (_: Throwable) { null })?.let { k to it } }
        // The value object has all three fields and fills one: take the first that holds something,
        // or a value sent as a double would go out as int 0.
        for ((k, v) in cand.sortedBy { it.second.toDouble() == 0.0 }) {
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
