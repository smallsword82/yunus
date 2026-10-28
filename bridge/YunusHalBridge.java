/*
 * Yunus HAL bridge — on-device vehicle-data bridge for the BYD Dolphin (DiLink 3).
 *
 * Runs with shell UID over ADB via app_process (NOT installed as an app), so it can touch the
 * BYD framework classes a sideloaded app is refused (see ../KINEX_NOTLARI.md). It reads vehicle
 * data through `android.hardware.bydauto.*` + the CarStatusProvider and serves it as line-based
 * JSON on 127.0.0.1:<port>; it accepts command JSON on the same socket. Protocol: ../bridge/PROTOCOL.md.
 *
 * Pure reflection, no android.jar needed to compile. Build & run: see README.md.
 *
 *   introspect        dump the real method signatures of every BYD device + listener class,
 *                     so the TODO[verify-on-car] guesses below can be replaced with facts.
 *   serve [port]      run the bridge (default port 19322).
 *
 * IMPORTANT: every BYD getter/setter/field name below is a best guess from the class names in
 * KINEX_NOTLARI.md and the byd-dolphin-hacking research. None is confirmed on the car yet. Each is
 * marked TODO[verify-on-car]; run `introspect` on the real unit, then fix the CANDIDATES tables.
 * Nothing here is copied from Kinex — only which BYD classes exist is reused.
 */

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class YunusHalBridge {

    static final int DEFAULT_PORT = 19322;
    static final String CAR_STATUS_URI = "content://com.byd.carStatusProvider/car_status";
    static final String BYDAUTO_PKG = "android.hardware.bydauto";

    static Object context; // android.content.Context (system context)

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "serve";
        try {
            bootstrapContext();
            if ("introspect".equals(mode)) {
                introspect();
            } else {
                int port = DEFAULT_PORT;
                for (String a : args) {
                    try { port = Integer.parseInt(a); } catch (NumberFormatException ignore) {}
                }
                serve(port);
            }
        } catch (Throwable t) {
            System.err.println("[yunus-hal] fatal: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    // ---- Android context bootstrap (same pattern as byd-dolphin-hacking scripts) ----
    static void bootstrapContext() throws Exception {
        Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        context = at.getMethod("getSystemContext").invoke(thread);
        if (context == null) throw new IllegalStateException("getSystemContext() returned null");
        log("context ready: " + context);
    }

    // =====================================================================================
    // Device registry. getInstance(Context) is the documented accessor (KINEX_NOTLARI.md).
    // className is TODO[verify-on-car] where the exact class name is a guess.
    // =====================================================================================
    static final String[][] DEVICES = {
        // key          sub-package.ClassName                              needs context?
        {"speed",      "speed.BYDAutoSpeedDevice"},        // TODO[verify-on-car]
        {"bodywork",   "bodywork.BYDAutoBodyworkDevice"},  // TODO[verify-on-car] doors/windows/trunk/battery
        {"doorlock",   "doorlock.BYDAutoDoorLockDevice"},  // TODO[verify-on-car]
        {"charging",   "charging.BYDAutoChargingDevice"},  // TODO[verify-on-car]
        {"gearbox",    "gearbox.BYDAutoGearboxDevice"},    // TODO[verify-on-car]
        {"tyre",       "tyre.BYDAutoTyreDevice"},          // TODO[verify-on-car]
        {"light",      "light.BYDAutoLightDevice"},        // TODO[verify-on-car]
        {"statistic",  "statistic.BYDAutoStatisticDevice"},// TODO[verify-on-car] range/consumption/odo
        {"ac",         "ac.BYDAutoAcDevice"},              // TODO[verify-on-car]
        {"instrument", "instrument.BYDAutoInstrumentDevice"}, // TODO[verify-on-car]
        {"pm2p5",      "pm2p5.BYDAutoPM2p5Device"},        // TODO[verify-on-car]
        {"setting",    "setting.BYDAutoSettingDevice"},    // TODO[verify-on-car]
    };

    static final Map<String, Object> deviceInstances = new LinkedHashMap<>();

    static void loadDevices() {
        for (String[] d : DEVICES) {
            String key = d[0];
            String cls = BYDAUTO_PKG + "." + d[1];
            Object inst = instantiateDevice(cls);
            if (inst != null) {
                deviceInstances.put(key, inst);
                log("device ok: " + key + " -> " + cls);
            } else {
                log("device MISSING: " + key + " (" + cls + ") — class name or getInstance differs; run introspect");
            }
        }
    }

    /** Tries getInstance(Context), getInstance(), then a no-arg constructor. */
    static Object instantiateDevice(String className) {
        Class<?> c;
        try { c = Class.forName(className); } catch (Throwable t) { return null; }
        // getInstance(Context)
        try {
            Method m = c.getMethod("getInstance", Class.forName("android.content.Context"));
            Object o = m.invoke(null, context);
            if (o != null) return o;
        } catch (Throwable ignore) {}
        // getInstance()
        try {
            Method m = c.getMethod("getInstance");
            Object o = m.invoke(null);
            if (o != null) return o;
        } catch (Throwable ignore) {}
        // no-arg constructor
        try { return c.getDeclaredConstructor().newInstance(); } catch (Throwable ignore) {}
        return null;
    }

    // =====================================================================================
    // INTROSPECT — print the real API so the guesses below can be corrected.
    // =====================================================================================
    static void introspect() {
        System.out.println("=== Yunus HAL introspect: BYD device classes ===\n");
        for (String[] d : DEVICES) {
            String key = d[0];
            String cls = BYDAUTO_PKG + "." + d[1];
            System.out.println("## " + key + "  (" + cls + ")");
            Class<?> c;
            try { c = Class.forName(cls); }
            catch (Throwable t) { System.out.println("   NOT FOUND: " + t + "\n"); continue; }
            Object inst = instantiateDevice(cls);
            System.out.println("   getInstance -> " + (inst != null ? "OK (" + inst + ")" : "FAILED"));
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                System.out.println("   " + sig(m));
            }
            // listener abstract class, e.g. ...speed.AbsBYDAutoSpeedListener
            String listener = cls.replaceFirst("\\.BYDAuto", ".AbsBYDAuto").replaceFirst("Device$", "Listener");
            try {
                Class<?> lc = Class.forName(listener);
                System.out.println("   listener " + listener + ":");
                for (Method m : lc.getMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    System.out.println("      " + sig(m));
                }
            } catch (Throwable ignore) {
                System.out.println("   (no listener class at " + listener + ")");
            }
            System.out.println();
        }
        System.out.println("=== CarStatusProvider sample ===");
        Map<String, String> cs = readCarStatus();
        for (Map.Entry<String, String> e : cs.entrySet()) {
            System.out.println("   " + e.getKey() + " = " + e.getValue());
        }
    }

    static String sig(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("(");
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    // =====================================================================================
    // SERVE — poll the car, emit full state then deltas; accept command lines.
    // =====================================================================================
    static final List<Socket> clients = new CopyOnWriteArrayList<>();
    static Map<String, Object> lastState = new LinkedHashMap<>();

    static void serve(int port) throws Exception {
        loadDevices();
        ServerSocket server = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        log("serving on 127.0.0.1:" + port);

        // poll loop
        Thread poll = new Thread(() -> {
            while (true) {
                try {
                    Map<String, Object> state = readState();
                    Map<String, Object> delta = diff(lastState, state);
                    if (!delta.isEmpty()) {
                        lastState = state;
                        broadcast(toJson(delta));
                    }
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable t) {
                    log("poll error: " + t);
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "yunus-poll");
        poll.setDaemon(true);
        poll.start();

        // accept loop
        while (true) {
            Socket s = server.accept();
            clients.add(s);
            log("client connected: " + s.getRemoteSocketAddress() + " (" + clients.size() + " total)");
            // send full state immediately
            try { sendLine(s, toJson(lastState.isEmpty() ? readState() : lastState)); }
            catch (Throwable t) { log("initial send failed: " + t); }
            // reader thread for commands
            Thread reader = new Thread(() -> handleCommands(s), "yunus-cmd");
            reader.setDaemon(true);
            reader.start();
        }
    }

    static void broadcast(String line) {
        for (Socket s : clients) {
            try { sendLine(s, line); }
            catch (Throwable t) { clients.remove(s); try { s.close(); } catch (Throwable ignore) {} }
        }
    }

    static void sendLine(Socket s, String line) throws Exception {
        OutputStream os = s.getOutputStream();
        os.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    static void handleCommands(Socket s) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> cmd = Json.parseObject(line);
                    applyCommand(cmd);
                } catch (Throwable t) {
                    log("bad command '" + line + "': " + t);
                }
            }
        } catch (Throwable t) {
            log("command stream closed: " + t);
        } finally {
            clients.remove(s);
            try { s.close(); } catch (Throwable ignore) {}
        }
    }

    // =====================================================================================
    // Read vehicle state. Each field tries candidate getter names; unknown ones are skipped.
    // Replace the candidate lists with the real names from `introspect`.  TODO[verify-on-car]
    // =====================================================================================
    static Map<String, Object> readState() {
        Map<String, Object> st = new LinkedHashMap<>();

        Object speed = deviceInstances.get("speed");
        putNum(st, "speed", callNumber(speed, "getSpeed", "getVehicleSpeed", "getCurrentSpeed")); // TODO[verify-on-car]

        Object gearbox = deviceInstances.get("gearbox");
        Object gear = callAny(gearbox, "getGear", "getGearPosition", "getCurrentGear"); // TODO[verify-on-car]
        if (gear != null) st.put("gear", mapGear(gear));

        Object stat = deviceInstances.get("statistic");
        putNum(st, "range", callNumber(stat, "getRemainingRange", "getRange", "getEnduranceMileage")); // TODO[verify-on-car]
        putNum(st, "powerKw", callNumber(stat, "getPower", "getInstantPower")); // TODO[verify-on-car]

        Object charging = deviceInstances.get("charging");
        putNum(st, "soc", callNumber(charging, "getSoc", "getBatteryPercent", "getBatteryLevel")); // TODO[verify-on-car]
        putBool(st, "charging", callAny(charging, "isCharging", "getChargingState")); // TODO[verify-on-car]
        putBool(st, "chargePort", callAny(charging, "isChargePortOpen", "getChargePortState")); // TODO[verify-on-car]

        Object lock = deviceInstances.get("doorlock");
        putBool(st, "locked", callAny(lock, "isLocked", "getLockState")); // TODO[verify-on-car]

        Object ac = deviceInstances.get("ac");
        putBool(st, "acOn", callAny(ac, "isAcOn", "getAcState", "isOn")); // TODO[verify-on-car]
        putNum(st, "acTemp", callNumber(ac, "getTemprature", "getTemperature", "getSetTemp")); // TODO[verify-on-car] (note BYD spelling getTemprature)
        putNum(st, "fan", callNumber(ac, "getWindLevel", "getFanLevel", "getBlowerLevel")); // TODO[verify-on-car]
        putNum(st, "outside", callNumber(ac, "getOutsideTemp", "getAmbientTemp")); // TODO[verify-on-car]

        // CarStatusProvider: confirmed working (byd-dolphin-hacking docs/content-providers.md)
        Map<String, String> cs = readCarStatus();
        if (!cs.isEmpty()) {
            Map<String, Object> maint = new LinkedHashMap<>();
            putInt(maint, "days", cs.get("car_status_maintenance_time"));
            putInt(maint, "km", cs.get("car_status_maintenance_mile"));
            if (!maint.isEmpty()) st.put("maintenance", maint);
        }

        return st;
    }

    // =====================================================================================
    // Apply a command. Setter names are TODO[verify-on-car]; unknown ones log and no-op.
    // After a successful call the next poll reflects the new state back to the page.
    // =====================================================================================
    static void applyCommand(Map<String, Object> cmd) {
        String c = String.valueOf(cmd.get("cmd"));
        switch (c) {
            case "lock":   invokeVoid(deviceInstances.get("doorlock"), new Object[]{true},  "setLock", "lock", "setLockState"); break; // TODO[verify-on-car]
            case "unlock": invokeVoid(deviceInstances.get("doorlock"), new Object[]{false}, "setLock", "unlock", "setLockState"); break; // TODO[verify-on-car]
            case "trunkOpen":  invokeVoid(deviceInstances.get("bodywork"), new Object[]{true},  "setTrunk", "openTrunk", "setTrunkState"); break; // TODO[verify-on-car]
            case "trunkClose": invokeVoid(deviceInstances.get("bodywork"), new Object[]{false}, "setTrunk", "closeTrunk", "setTrunkState"); break; // TODO[verify-on-car]
            case "chargePortOpen":  invokeVoid(deviceInstances.get("charging"), new Object[]{true},  "setChargePort", "openChargePort"); break; // TODO[verify-on-car]
            case "chargePortClose": invokeVoid(deviceInstances.get("charging"), new Object[]{false}, "setChargePort", "closeChargePort"); break; // TODO[verify-on-car]
            case "acPower": invokeVoid(deviceInstances.get("ac"), new Object[]{truthy(cmd.get("on"))}, "setAcPower", "setAcState", "setPower"); break; // TODO[verify-on-car]
            case "acTemp":  invokeVoid(deviceInstances.get("ac"), new Object[]{numArg(cmd.get("value"))}, "setTemprature", "setTemperature", "setTemp"); break; // TODO[verify-on-car]
            case "window":  log("window cmd (pos=" + cmd.get("pos") + " open=" + cmd.get("open") + ") — map to bodywork setter"); break; // TODO[verify-on-car]
            case "sunshade": log("sunshade cmd — map to bodywork/sunroof setter"); break; // TODO[verify-on-car]
            case "seat":    log("seat cmd (" + cmd + ") — map to seat/ac setter"); break; // TODO[verify-on-car]
            default: log("unknown command: " + c);
        }
    }

    // =====================================================================================
    // CarStatusProvider via ContentResolver (confirmed accessible from shell).
    // =====================================================================================
    static Map<String, String> readCarStatus() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            Object resolver = context.getClass().getMethod("getContentResolver").invoke(context);
            Class<?> uriClass = Class.forName("android.net.Uri");
            Object uri = uriClass.getMethod("parse", String.class).invoke(null, CAR_STATUS_URI);
            Method query = resolver.getClass().getMethod(
                "query", uriClass, String[].class, String.class, String[].class, String.class);
            Object cursor = query.invoke(resolver, uri, null, null, null, null);
            if (cursor == null) return out;
            Class<?> cc = cursor.getClass();
            Method moveNext = cc.getMethod("moveToNext");
            Method colIdx = cc.getMethod("getColumnIndex", String.class);
            Method getString = cc.getMethod("getString", int.class);
            Method close = cc.getMethod("close");
            int keyCol = (int) colIdx.invoke(cursor, "key");
            int valCol = (int) colIdx.invoke(cursor, "value");
            while ((boolean) moveNext.invoke(cursor)) {
                if (keyCol >= 0 && valCol >= 0) {
                    Object k = getString.invoke(cursor, keyCol);
                    Object v = getString.invoke(cursor, valCol);
                    if (k != null) out.put(String.valueOf(k), v == null ? "" : String.valueOf(v));
                }
            }
            close.invoke(cursor);
        } catch (Throwable t) {
            log("CarStatusProvider read failed: " + t);
        }
        return out;
    }

    // =====================================================================================
    // Reflection helpers
    // =====================================================================================
    static Object callAny(Object target, String... names) {
        if (target == null) return null;
        for (String n : names) {
            try {
                Method m = target.getClass().getMethod(n);
                return m.invoke(target);
            } catch (Throwable ignore) {}
        }
        return null;
    }

    static Double callNumber(Object target, String... names) {
        Object o = callAny(target, names);
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) { try { return Double.parseDouble((String) o); } catch (Throwable ignore) {} }
        return null;
    }

    static void invokeVoid(Object target, Object[] args, String... names) {
        if (target == null) { log("no device for setter " + (names.length > 0 ? names[0] : "?")); return; }
        for (String n : names) {
            for (Method m : target.getClass().getMethods()) {
                if (!m.getName().equals(n) || m.getParameterCount() != args.length) continue;
                try {
                    Object[] coerced = coerce(m.getParameterTypes(), args);
                    m.invoke(target, coerced);
                    log("called " + n + " on " + target.getClass().getSimpleName());
                    return;
                } catch (Throwable ignore) {}
            }
        }
        log("no matching setter " + java.util.Arrays.toString(names) + " on " + target.getClass().getSimpleName());
    }

    static Object[] coerce(Class<?>[] types, Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Class<?> t = types[i];
            Object a = args[i];
            if ((t == int.class || t == Integer.class) && a instanceof Number) out[i] = ((Number) a).intValue();
            else if ((t == boolean.class || t == Boolean.class)) out[i] = truthy(a);
            else if ((t == double.class || t == Double.class) && a instanceof Number) out[i] = ((Number) a).doubleValue();
            else if ((t == float.class || t == Float.class) && a instanceof Number) out[i] = ((Number) a).floatValue();
            else out[i] = a;
        }
        return out;
    }

    static String mapGear(Object raw) {
        // TODO[verify-on-car] the real encoding (int vs enum). Common BYD: 1=P,2=R,3=N,4=D.
        if (raw instanceof Number) {
            switch (((Number) raw).intValue()) {
                case 1: return "P"; case 2: return "R"; case 3: return "N"; case 4: return "D";
            }
        }
        String s = String.valueOf(raw).toUpperCase();
        if (s.contains("P")) return "P"; if (s.contains("R")) return "R";
        if (s.contains("N")) return "N"; if (s.contains("D")) return "D";
        return s;
    }

    static void putNum(Map<String, Object> m, String k, Double v) { if (v != null) m.put(k, v); }
    static void putBool(Map<String, Object> m, String k, Object v) { if (v instanceof Boolean) m.put(k, v); else if (v instanceof Number) m.put(k, ((Number) v).intValue() != 0); }
    static void putInt(Map<String, Object> m, String k, String v) { if (v != null) { try { m.put(k, Integer.parseInt(v.trim())); } catch (Throwable ignore) {} } }

    static boolean truthy(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(o));
    }
    static Object numArg(Object o) { return o instanceof Number ? o : 0; }

    static Map<String, Object> diff(Map<String, Object> prev, Map<String, Object> cur) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : cur.entrySet()) {
            Object old = prev.get(e.getKey());
            if (old == null || !old.equals(e.getValue())) d.put(e.getKey(), e.getValue());
        }
        return d;
    }

    static void log(String s) { System.err.println("[yunus-hal] " + s); }

    // ---- minimal JSON (no external libs) ----
    static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        Json.write(sb, o);
        return sb.toString();
    }

    static final class Json {
        static void write(StringBuilder sb, Object o) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String) { writeString(sb, (String) o); return; }
            if (o instanceof Boolean || o instanceof Number) { sb.append(trimNum(o)); return; }
            if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    if (!first) sb.append(','); first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
                return;
            }
            writeString(sb, String.valueOf(o));
        }
        static String trimNum(Object o) {
            if (o instanceof Double) {
                double d = (Double) o;
                if (d == Math.rint(d) && !Double.isInfinite(d)) return Long.toString((long) d);
            }
            return String.valueOf(o);
        }
        static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
            sb.append('"');
        }

        // tiny recursive-descent parser, enough for one flat command object
        static Map<String, Object> parseObject(String s) {
            int[] p = {0};
            Object o = parseValue(s, p);
            if (!(o instanceof Map)) throw new IllegalArgumentException("not an object");
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            return m;
        }
        static Object parseValue(String s, int[] p) {
            skip(s, p);
            char c = s.charAt(p[0]);
            if (c == '{') return parseObj(s, p);
            if (c == '"') return parseStr(s, p);
            if (c == 't' || c == 'f') return parseBool(s, p);
            if (c == 'n') { p[0] += 4; return null; }
            return parseNum(s, p);
        }
        static Map<String, Object> parseObj(String s, int[] p) {
            Map<String, Object> m = new LinkedHashMap<>();
            p[0]++; skip(s, p);
            if (s.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                skip(s, p);
                String k = parseStr(s, p);
                skip(s, p); p[0]++; // colon
                Object v = parseValue(s, p);
                m.put(k, v);
                skip(s, p);
                char c = s.charAt(p[0]++);
                if (c == '}') break;
            }
            return m;
        }
        static String parseStr(String s, int[] p) {
            StringBuilder sb = new StringBuilder();
            p[0]++; // opening quote
            while (true) {
                char c = s.charAt(p[0]++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(p[0]++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }
        static Object parseBool(String s, int[] p) {
            if (s.charAt(p[0]) == 't') { p[0] += 4; return Boolean.TRUE; }
            p[0] += 5; return Boolean.FALSE;
        }
        static Object parseNum(String s, int[] p) {
            int start = p[0];
            while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
            String n = s.substring(start, p[0]);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            try { return Integer.parseInt(n); } catch (NumberFormatException e) { return Long.parseLong(n); }
        }
        static void skip(String s, int[] p) { while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++; }
    }
}
