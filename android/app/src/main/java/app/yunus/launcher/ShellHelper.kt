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
                }
            } catch (t: Throwable) { System.err.println("$line: $t") } // goes to the helper's log file
        }
    }

    // Fixed argument lists (no shell): stop the app's task elsewhere, then start it on the display.
    private fun launch(display: Int, component: String) {
        if (!COMPONENT.matches(component)) return
        ProcessBuilder("am", "force-stop", component.substringBefore('/')).inheritIO().start().waitFor()
        ProcessBuilder("am", "start", "--display", display.toString(), "-n", component).inheritIO().start().waitFor()
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
            val ok = Regex("""DisplayInfo\{"[^"]*(?:", )?displayId $display[",][^
]*?uniqueId "virtual:${Regex.escape(OWNER)},""").containsMatchIn(dump)
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
