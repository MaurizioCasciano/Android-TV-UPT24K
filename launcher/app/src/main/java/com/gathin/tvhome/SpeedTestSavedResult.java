package com.gathin.tvhome;

import java.util.HashMap;
import java.util.Map;

/** Versioned local record of genuine completed measurements. Contains no IP, SSID, account or device ID. */
final class SpeedTestSavedResult {
    static Map<String, String> encode(SpeedTestMetrics.Result result) {
        Map<String, String> values = new HashMap<>();
        if (!valid(result)) return values;
        values.put("version", "1");
        values.put("download", Double.toString(result.download));
        values.put("upload", Double.toString(result.upload));
        values.put("ping", Double.toString(result.ping));
        values.put("jitter", Double.toString(result.jitter));
        values.put("time", Long.toString(result.wallTime));
        values.put("server", result.server);
        return values;
    }

    static SpeedTestMetrics.Result decode(Map<String, String> values) {
        if (!"1".equals(values.get("version"))) return null;
        try {
            SpeedTestMetrics.Result result = new SpeedTestMetrics.Result(Double.parseDouble(values.get("download")),
                    Double.parseDouble(values.get("upload")), Double.parseDouble(values.get("ping")),
                    Double.parseDouble(values.get("jitter")), Long.parseLong(values.get("time")), values.get("server"));
            return valid(result) ? result : null;
        } catch (RuntimeException corrupt) { return null; }
    }

    private static boolean valid(SpeedTestMetrics.Result result) {
        return result != null && Double.isFinite(result.download) && result.download > 0
                && Double.isFinite(result.upload) && result.upload > 0
                && Double.isFinite(result.ping) && result.ping >= 0
                && Double.isFinite(result.jitter) && result.jitter >= 0 && result.wallTime > 0
                && result.server != null && result.server.matches("[\\p{L} -]{1,40}");
    }
}
