package com.gathin.tvhome;

/** One common monotonic interval for all streams. Socket cleanup never extends the measured interval. */
final class SpeedTestWindow {
    private final long start, warmup;
    private long bytes, warmBytes, last;
    private boolean frozen;

    SpeedTestWindow(long start, long warmup) { this.start = last = start; this.warmup = warmup; }

    synchronized void add(long count, long now) {
        if (count < 0 || now < start) throw new IllegalArgumentException("Invalid transfer sample");
        if (frozen) return;
        bytes += count;
        if (now <= start + warmup) warmBytes += count;
        last = Math.max(last, now);
    }

    synchronized Sample finish() { frozen = true; return sample(last); }
    synchronized Sample preview(long now) { return sample(Math.max(last, now)); }

    private Sample sample(long end) {
        boolean warmed = end - start > warmup + 500_000_000L;
        return new Sample(warmed ? bytes - warmBytes : bytes,
                Math.max(0, end - start - (warmed ? warmup : 0)), bytes);
    }

    static final class Sample {
        final long bytes, nanos, totalBytes;
        Sample(long bytes, long nanos, long totalBytes) { this.bytes=bytes; this.nanos=nanos; this.totalBytes=totalBytes; }
        double mbps() { return SpeedTestMetrics.mbps(bytes, nanos); }
    }
}
