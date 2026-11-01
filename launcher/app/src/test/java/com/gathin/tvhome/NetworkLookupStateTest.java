package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkLookupStateTest {
    private static NetworkLookupState.Result answer() {
        return new NetworkLookupState.Result("203.0.113.4", "IT", "Example", 0);
    }

    @Test public void cacheAvoidsRepeatedLookupsUntilFiveMinutes() {
        NetworkLookupState state = new NetworkLookupState();
        state.selectNetwork("wifi");
        long token = state.begin(1000, false);
        assertTrue(state.succeed(token, answer(), 2000));
        assertEquals(299_000, state.delay(3000, false));
        assertEquals(-1, state.begin(3000, false));
        assertTrue(state.begin(302_000, false) >= 0);
    }

    @Test public void manualRefreshCannotHammerProvider() {
        NetworkLookupState state = new NetworkLookupState();
        long token = state.begin(0, false);
        assertTrue(state.succeed(token, answer(), 10));
        assertEquals(14_000, state.delay(1000, true));
        assertEquals(-1, state.begin(1000, true));
        assertTrue(state.begin(15_000, true) >= 0);
        assertEquals(-1, state.begin(30_000, true)); // still in flight
    }

    @Test public void changedNetworkClearsOldCountryAndRejectsLateAnswer() {
        NetworkLookupState state = new NetworkLookupState();
        state.selectNetwork("wifi");
        long first = state.begin(0, false);
        state.succeed(first, answer(), 1);
        long refreshing = state.begin(15_000, true);
        assertTrue(state.selectNetwork("vpn"));
        assertNull(state.result());
        assertFalse(state.succeed(refreshing, answer(), 16_000));
        assertEquals(14_000, state.delay(16_000, true));
        long current = state.begin(30_000, false);
        assertTrue(state.succeed(current, answer(), 31_000));
        assertFalse(state.selectNetwork("vpn"));
        assertNotNull(state.result());
    }

    @Test public void leavingHomeCancelsDeliveryButKeepsSameNetworkCache() {
        NetworkLookupState state = new NetworkLookupState();
        long first = state.begin(0, false);
        state.succeed(first, answer(), 1);
        long pending = state.begin(15_000, true);
        state.cancel();
        assertFalse(state.succeed(pending, answer(), 16_000));
        assertFalse(state.fail(pending, 16_000, 60_000));
        assertNotNull(state.result());
    }

    @Test public void retryAfterSurvivesNetworkChangesAndManualRefresh() {
        NetworkLookupState state = new NetworkLookupState();
        state.selectNetwork("wifi");
        long token = state.begin(0, false);
        assertTrue(state.fail(token, 1000, 3_600_000));
        state.selectNetwork("vpn");
        assertEquals(3_599_000, state.delay(2000, true));
        assertEquals(-1, state.begin(2000, true));
    }

    @Test public void retryAfterSupportsSecondsDatesAndInvalidValues() {
        assertEquals(120_000, NetworkLookupState.retryAfterMillis("120", 0));
        assertEquals(NetworkLookupState.MAX_RETRY_MS,
                NetworkLookupState.retryAfterMillis("9223372036854775807", 0));
        assertEquals(120_000, NetworkLookupState.retryAfterMillis("Thu, 1 Jan 1970 00:02:00 GMT", 0));
        for (String value : new String[]{null, "", "invalid", "-10", "0", "Thu, 1 Jan 1970 00:00:00 GMT"})
            assertEquals(60_000, NetworkLookupState.retryAfterMillis(value, 0));
    }
}
