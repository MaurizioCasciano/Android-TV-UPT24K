package com.gathin.tvhome;

/** Main-thread ownership of a foreground test; a paused or different network cannot receive a late result. */
final class SpeedTestSession {
    private String route = "";
    private long generation;
    private boolean running;
    private SpeedTestMetrics.Result result;

    boolean selectRoute(String next) {
        if (route.equals(next)) return false;
        route = next;
        result = null;
        cancel();
        return true;
    }

    long begin() {
        if (running) return -1;
        running = true;
        result = null;
        return ++generation;
    }

    boolean accepts(long token) { return running && token == generation; }
    boolean running() { return running; }
    SpeedTestMetrics.Result result() { return result; }

    boolean finish(long token, SpeedTestMetrics.Result next) {
        if (!accepts(token)) return false;
        result = next;
        running = false;
        return true;
    }

    void cancel() {
        generation++;
        running = false;
    }
}
