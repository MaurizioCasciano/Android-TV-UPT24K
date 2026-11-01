package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedTestSessionTest {
    private static SpeedTestMetrics.Result result() { return new SpeedTestMetrics.Result(50, 10, 20, 2, 1000); }
    @Test public void onlyOneManualTestCanRun() {
        SpeedTestSession session = new SpeedTestSession();
        long first = session.begin();
        assertTrue(session.accepts(first));
        assertEquals(-1, session.begin());
        assertTrue(session.finish(first, result()));
        assertFalse(session.accepts(first));
        assertNotNull(session.result());
    }
    @Test public void leavingHomeRejectsLateProgressAndResults() {
        SpeedTestSession session = new SpeedTestSession();
        long first = session.begin();
        session.cancel();
        assertFalse(session.accepts(first));
        assertFalse(session.finish(first, result()));
        assertNull(session.result());
        long second = session.begin();
        assertTrue(session.accepts(second));
        assertFalse(session.finish(first, result()));
    }
    @Test public void changedVpnOrNetworkClearsPreviousMeasurementsAndCancelsRunningTest() {
        SpeedTestSession session = new SpeedTestSession();
        session.selectRoute("wifi");
        long first = session.begin();
        session.finish(first, result());
        assertFalse(session.selectRoute("wifi"));
        assertNotNull(session.result());
        long next = session.begin();
        assertTrue(session.selectRoute("vpn"));
        assertNull(session.result());
        assertFalse(session.finish(next, result()));
        assertFalse(session.running());
    }
    @Test public void failedOrCancelledNewTestDoesNotPresentThePreviousResultAsNew() {
        SpeedTestSession session = new SpeedTestSession();
        long first = session.begin();
        session.finish(first, result());
        long second = session.begin();
        assertNull(session.result());
        assertTrue(session.finish(second, null));
        assertNull(session.result());
    }
}
