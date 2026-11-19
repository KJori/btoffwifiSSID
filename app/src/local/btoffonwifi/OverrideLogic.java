package local.btoffonwifi;

/** Pure logic, no Android classes, so it can be tested on a desktop JVM. */
final class OverrideLogic {
    static final long MAX_MS = 24L * 60 * 60 * 1000;
    /** A Bluetooth-off event this soon after a press is treated as a race, not as the user cancelling. */
    static final long PRESS_GRACE_MS = 5000;

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

    static boolean matches(String target, String current) {
        if (target == null || current == null) return false;
        String t = target.trim();
        return !t.isEmpty() && t.equals(current.trim());
    }

    static String remaining(long now, long until) {
        long min = (until - now + 59999) / 60000;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
