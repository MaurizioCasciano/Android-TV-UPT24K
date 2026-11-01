package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedTestMetricsTest {
    @Test public void byteRatesUseDecimalMegabitsAndMonotonicNanoseconds() {
        assertEquals(64.0, SpeedTestMetrics.mbps(1_000_000, 125_000_000), .000001);
        assertEquals(8.388608, SpeedTestMetrics.mbps(1_048_576, 1_000_000_000), .000001);
    }
    @Test public void MissingTransfersAreNotDisplayedAsSuccessfulZeroSpeeds() {
        assertTrue(Double.isNaN(SpeedTestMetrics.mbps(0, 100)));
        assertTrue(Double.isNaN(SpeedTestMetrics.mbps(100, 0)));
        assertTrue(Double.isNaN(SpeedTestMetrics.mbps(-1, 100)));
    }
    @Test public void medianDoesNotReorderChronologicalSamplesNeededByJitter() {
        double[] samples = {30, 10, 20, 40};
        assertEquals(25, SpeedTestMetrics.median(samples), 0);
        assertArrayEquals(new double[]{30, 10, 20, 40}, samples, 0);
        assertEquals(20, SpeedTestMetrics.median(new double[]{30, 10, 20}), 0);
    }
    @Test public void jitterUsesSuccessiveRttDifferencesAndHandlesMissingSamples() {
        assertEquals(5, SpeedTestMetrics.jitter(new double[]{10, 20, 15, 15}), 0);
        assertEquals(0, SpeedTestMetrics.jitter(new double[]{10, 10}), 0);
        assertTrue(Double.isNaN(SpeedTestMetrics.median(new double[0])));
        assertTrue(Double.isNaN(SpeedTestMetrics.jitter(new double[]{10})));
    }
}
