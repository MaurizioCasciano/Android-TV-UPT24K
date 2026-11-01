package com.gathin.tvhome;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.cert.Certificate;
import javax.net.ssl.HttpsURLConnection;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedTestTimingTest {
    private static final class Network {
        long now;
        final long closeDelay;
        Network(long closeDelay) { this.closeDelay = closeDelay; }

        HttpsURLConnection open(String path) throws IOException {
            boolean download = path.contains("garbage.php");
            return new HttpsURLConnection(new URL("https://example.invalid/")) {
                @Override public int getResponseCode() { now += 10_000_000; return 200; }
                @Override public String getContentType() { return "application/octet-stream"; }
                @Override public InputStream getInputStream() {
                    if (!download) return new ByteArrayInputStream(new byte[0]);
                    return new InputStream() {
                        int left = SpeedTestEngine.DOWNLOAD_BYTES;
                        @Override public int read() { throw new AssertionError("Use bulk transfer"); }
                        @Override public int read(byte[] b, int off, int len) {
                            if (left == 0) return -1;
                            int count = Math.min(left, len);
                            left -= count;
                            now += 10_000_000;
                            return count;
                        }
                        @Override public void close() { now += closeDelay; }
                    };
                }
                @Override public OutputStream getOutputStream() {
                    return new OutputStream() {
                        @Override public void write(int b) { now += 1000; }
                        @Override public void write(byte[] b, int off, int len) { now += len * 1000L; }
                    };
                }
                @Override public String getCipherSuite() { return "test"; }
                @Override public Certificate[] getLocalCertificates() { return null; }
                @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
                @Override public void disconnect() { }
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() { }
            };
        }
    }

    private SpeedTestMetrics.Result measure(long closeDelay) throws IOException {
        Network network = new Network(closeDelay);
        return new SpeedTestEngine(network::open, () -> network.now).run((phase, down, up, ping, jitter, progress) -> { });
    }

    @Test public void socketCleanupDoesNotLowerTheMeasuredDownloadRate() throws IOException {
        SpeedTestMetrics.Result normal = measure(0);
        SpeedTestMetrics.Result slowClose = measure(5_000_000_000L);
        assertEquals(52.4288, normal.download, .000001);
        assertEquals(normal.download, slowClose.download, .000001);
    }

    @Test public void parallelStreamsUseOneSharedIntervalNotASumOfDurations() {
        SpeedTestWindow window=new SpeedTestWindow(0,0);
        for(int i=0;i<4;i++)window.add(1_000_000,1_000_000_000L);
        assertEquals(32,window.finish().mbps(),.000001);
    }

    @Test public void warmupBytesAndTimeAreBothExcluded() {
        SpeedTestWindow window=new SpeedTestWindow(0,500_000_000L);
        window.add(500_000,500_000_000L);window.add(2_000_000,2_500_000_000L);
        SpeedTestWindow.Sample result=window.finish();
        assertEquals(2_000_000,result.bytes);assertEquals(2_000_000_000L,result.nanos);
        assertEquals(8,result.mbps(),.000001);
    }

    @Test public void missingOrInvalidSamplesDoNotBecomeAValidZeroSpeed() {
        SpeedTestWindow window=new SpeedTestWindow(0,0);
        assertTrue(Double.isNaN(window.preview(0).mbps()));
        try { window.add(-1,100); fail("Invalid sample accepted"); }
        catch (IllegalArgumentException expected) { }
        window.add(1_000_000,1_000_000_000L);assertEquals(8,window.finish().mbps(),0);
        window.add(100_000_000,20_000_000_000L);
        assertEquals(8,window.finish().mbps(),0); // Late cleanup/callbacks cannot alter a frozen sample.
    }
}
