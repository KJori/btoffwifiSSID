package local.btoffonwifi;

/** Pure logic, no Android classes, so it can be tested on a desktop JVM. */
final class OverrideLogic {
    static final long MAX_MS = 24L * 60 * 60 * 1000;
    /** A Bluetooth-off event this soon after a press is treated as a race, not as the user cancelling. */
    static final long PRESS_GRACE_MS = 5000;
    /** Where the phone is relative to the chosen Wi-Fi. */
    static final int HOME = 0, AWAY = 1, UNKNOWN = 2;

    private OverrideLogic() {}

    /** New end time after one press: extends the running timer by one step, capped at 24 h from now. */
    static long press(long now, long until, int stepMinutes) {
        long step = stepMinutes * 60000L;
        long base = until > now ? until : now;
        long next = base + step;
        if (next - now > MAX_MS) next = now + MAX_MS;
        return next;
    }

    static boolean active(long now, long until) {
        return until > now;
    }

    /** Android reports SSIDs as "name" in quotes, or a placeholder when it will not tell. */
    static String normalizeSsid(String raw) {
        if (raw == null) return null;
        String s = raw;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        s = s.trim();
        if (s.isEmpty() || s.equals("<unknown ssid>") || s.equals("0x")) return null;
        return s;
    }

    /** @param targets one or more Wi-Fi names, one per line */
    static boolean matches(String targets, String current) {
        if (targets == null || current == null) return false;
        String cur = current.trim();
        for (String t : targets.split("\n")) {
            t = t.trim();
            if (!t.isEmpty() && t.equals(cur)) return true;
        }
        return false;
    }

    /** The Wi-Fi names as a short readable list: "Home", "Home, Office". */
    static String describe(String targets) {
        StringBuilder sb = new StringBuilder();
        if (targets != null) {
            for (String t : targets.split("\n")) {
                t = t.trim();
                if (t.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append('"').append(t).append('"');
            }
        }
        return sb.toString();
    }

    /** The list without {@code name} (exact match per line, surrounding spaces ignored). */
    static String removeTarget(String targets, String name) {
        StringBuilder sb = new StringBuilder();
        if (targets != null) {
            for (String t : targets.split("\n")) {
                t = t.trim();
                if (t.isEmpty() || t.equals(name.trim())) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /** The names one by one, blank lines skipped. */
    static java.util.List<String> targetList(String targets) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (targets != null) {
            for (String t : targets.split("\n")) {
                t = t.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    /** Adds a name on its own line unless it is already in the list. */
    static String addTarget(String targets, String name) {
        if (name == null || name.trim().isEmpty() || matches(targets, name)) return targets == null ? "" : targets;
        String t = targets == null ? "" : targets.trim();
        return t.isEmpty() ? name.trim() : t + "\n" + name.trim();
    }

    /**
     * @param currentSsid   normalized SSID, or null if not connected or Android hides it
     * @param wifiConnected whether any Wi-Fi network is connected
     * @return HOME on the chosen Wi-Fi; AWAY on another named Wi-Fi or on no Wi-Fi at all;
     *         UNKNOWN on a Wi-Fi whose name Android will not reveal (never treated as leaving)
     */
    static int place(String target, String currentSsid, boolean wifiConnected) {
        if (matches(target, currentSsid)) return HOME;
        if (currentSsid != null || !wifiConnected) return AWAY;
        return UNKNOWN;
    }

    /** True once a state that started at {@code since} (0 = not started) has lasted {@code ms}. */
    static boolean waited(long now, long since, long ms) {
        return since > 0 && now - since >= ms;
    }

    static String remaining(long now, long until) {
        long min = (until - now + 59999) / 60000;
        if (min < 60) return min + " min";
        return min % 60 == 0 ? (min / 60) + " h" : (min / 60) + " h " + (min % 60) + " min";
    }
}
