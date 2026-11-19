package local.btoffonwifi;

/** Pure logic, no Android classes, so it can be tested on a desktop JVM. */
final class OverrideLogic {
    static final long MAX_MS = 24L * 60 * 60 * 1000;
    /** A Bluetooth-off event this soon after a press is treated as a race, not as the user cancelling. */
    static final long PRESS_GRACE_MS = 5000;
    /** How long the phone must be away from the chosen Wi-Fi before Bluetooth is turned back on,
     *  so a router restart or a short drop-out does not count as leaving. */
    static final long LEAVE_GRACE_MS = 60_000;

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

    static boolean matches(String target, String current) {
        if (target == null || current == null) return false;
        String t = target.trim();
        return !t.isEmpty() && t.equals(current.trim());
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

    /** True once the phone has been away for the whole grace period. */
    static boolean awayLongEnough(long now, long awaySince) {
        return awaySince > 0 && now - awaySince >= LEAVE_GRACE_MS;
    }

    static String remaining(long now, long until) {
        long min = (until - now + 59999) / 60000;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
