package com.gathin.tvhome;

import java.util.LinkedHashSet;
import java.util.Set;

/** Deduplicates automatic tests per network/boot; interrupted first tests can retry after a cooldown. */
final class SpeedTestAutoStart {
    static final long MIN_INTERVAL_MS = 60_000;
    private final int boot;
    private final LinkedHashSet<String> attempted = new LinkedHashSet<>();
    private long lastAttempt = -MIN_INTERVAL_MS;

    SpeedTestAutoStart(int boot, int savedBoot, Set<String> savedKeys, long savedTime, long now) {
        this.boot = boot;
        if (boot == savedBoot && savedTime >= 0 && savedTime <= now) {
            for (String key : savedKeys) if (key.matches("[0-9]+:(true|false)") && attempted.size() < 64) attempted.add(key);
            lastAttempt = savedTime;
        }
    }

    long delay(String connection, long now) {
        if (connection.isEmpty() || attempted.contains(connection)) return -1;
        return Math.max(0, lastAttempt + MIN_INTERVAL_MS - now);
    }

    void claim(String connection, long now) {
        if (connection.isEmpty()) throw new IllegalArgumentException("Offline connection");
        attempted.add(connection);
        while (attempted.size() > 64) attempted.remove(attempted.iterator().next());
        lastAttempt = now;
    }

    void releaseUnfinished(String connection) { attempted.remove(connection); }

    int boot() { return boot; }
    long lastAttempt() { return lastAttempt; }
    Set<String> attempted() { return new LinkedHashSet<>(attempted); }
}
