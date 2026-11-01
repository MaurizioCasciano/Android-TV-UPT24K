package com.gathin.tvhome;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class SpeedTestEngineTest {
    @Test public void cancelledTestMakesNoRequestAndPublishesNoMeasurement() {
        SpeedTestEngine engine = new SpeedTestEngine();
        engine.cancel();
        try {
            engine.run((phase, down, up, ping, jitter, progress) -> fail("Cancelled test emitted progress"));
            fail("Cancelled test completed");
        } catch (IOException expected) {
            assertEquals("Test cancelled", expected.getMessage());
        }
    }
}
