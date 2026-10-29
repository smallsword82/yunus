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
 * Deliberately narrow: it runs no arbitrary commands, and both things it does are limited to
 * virtual displays owned by Yunus (never the car's main screen or another app's display). It
 * listens on 127.0.0.1 only and serves a connection only after it sends the token Yunus passed
 * on the command line. One request per line:
 *   launch <display> <package>/<activity>              start that activity on the display
 *   close <display>                                     close the apps running on the display
 *   t <display> <action> <id> <x> <y> [<id> <x> <y> …]   inject a touch event into the display
 */
object ShellHelper {
    private const val OWNER = "app.yunus.launcher"
    private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args[0].toInt()
        val token = args[1]
        val injector = TouchInjector()
        ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            while (true) {
                val client = server.accept()
                Thread { serve(client, token, injector) }.start()
            }
        }
    }

    private fun serve(client: Socket, token: String, injector: TouchInjector) = client.use {
        client.tcpNoDelay = true
        val reader = client.getInputStream().bufferedReader()
        if (reader.readLine() != token) return
        client.getOutputStream().apply { write("ok\n".toByteArray()); flush() }
        while (true) {
            val line = reader.readLine() ?: break
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
        }
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
