package com.gathin.tvhome;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** In-memory cache and request budget. Times are monotonic, except parsing HTTP dates. */
final class NetworkLookupState {
    static final long CACHE_MS = 5 * 60_000L;
    static final long MIN_INTERVAL_MS = 15_000L;
    static final long RETRY_MS = 60_000L;
    static final long MAX_RETRY_MS = 24 * 60 * 60_000L;

    static final class Result {
        final String ip, countryCode, operator;
        final long wallTime;
        Result(String ip, String countryCode, String operator, long wallTime) {
            this.ip = ip;
            this.countryCode = countryCode;
            this.operator = operator;
            this.wallTime = wallTime;
        }
    }

    private String network;
    private long generation, lastAttempt = -MIN_INTERVAL_MS, blockedUntil, updatedAt;
    private boolean inFlight;
    private Result result;

    boolean selectNetwork(String next) {
        if (next.equals(network)) return false;
        network = next;
        result = null;
        cancel();
        return true;
    }

    void cancel() {
        generation++;
        inFlight = false;
    }

    long delay(long now, boolean force) {
        long due = Math.max(blockedUntil, lastAttempt + MIN_INTERVAL_MS);
        if (!force && result != null) due = Math.max(due, updatedAt + CACHE_MS);
        return Math.max(0, due - now);
    }

    long begin(long now, boolean force) {
        if (inFlight || delay(now, force) > 0) return -1;
        lastAttempt = now;
        inFlight = true;
        return ++generation;
    }

    boolean current(long token) {
        return inFlight && token == generation;
    }

    boolean succeed(long token, Result value, long now) {
        if (!current(token)) return false;
        inFlight = false;
        result = value;
        updatedAt = now;
        return true;
    }

    boolean fail(long token, long now, long retry) {
        if (!current(token)) return false;
        inFlight = false;
        blockedUntil = now + Math.min(MAX_RETRY_MS, Math.max(RETRY_MS, retry));
        return true;
    }

    Result result() { return result; }
    boolean busy() { return inFlight; }

    static long retryAfterMillis(String header, long wallTime) {
        if (header == null) return RETRY_MS;
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds <= 0) return RETRY_MS;
            return Math.max(RETRY_MS, Math.min(MAX_RETRY_MS / 1000, seconds) * 1000);
        } catch (NumberFormatException ignored) {
            try {
                long due = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
                return Math.max(RETRY_MS, Math.min(MAX_RETRY_MS, due - wallTime));
            } catch (RuntimeException invalidDate) {
                return RETRY_MS;
            }
        }
    }
}
