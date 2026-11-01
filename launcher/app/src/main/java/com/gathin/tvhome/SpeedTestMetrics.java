package com.gathin.tvhome;

import java.util.Arrays;

/** HTTP measurements, decimal Mbps and milliseconds; missing measurements are NaN, never fake zeroes. */
final class SpeedTestMetrics {
    static double mbps(long bytes, long nanos) {
        return bytes <= 0 || nanos <= 0 ? Double.NaN : bytes * 8000.0 / nanos;
    }

    static double median(double[] milliseconds) {
        if (milliseconds.length == 0) return Double.NaN;
        double[] copy = milliseconds.clone();
        Arrays.sort(copy);
        int mid = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[mid - 1] + copy[mid]) / 2 : copy[mid];
    }

    /** Mean absolute change between successive HTTP round trips, not packet loss. */
    static double jitter(double[] milliseconds) {
        if (milliseconds.length < 2) return Double.NaN;
        double sum = 0;
        for (int i = 1; i < milliseconds.length; i++) sum += Math.abs(milliseconds[i] - milliseconds[i - 1]);
        return sum / (milliseconds.length - 1);
    }

    static final class Result {
        final double download, upload, ping, jitter;
        final long wallTime;
        final String server;
        Result(double download, double upload, double ping, double jitter, long wallTime) {
            this(download,upload,ping,jitter,wallTime,"");
        }
        Result(double download, double upload, double ping, double jitter, long wallTime, String server) {
            this.download = download;
            this.upload = upload;
            this.ping = ping;
            this.jitter = jitter;
            this.wallTime = wallTime;
            this.server = server;
        }
    }
}
