package com.gathin.tvhome;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import javax.net.ssl.HttpsURLConnection;
import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedTestParallelTest {
    private static final class Network {
        final AtomicLong received=new AtomicLong(),sent=new AtomicLong(),downloads=new AtomicLong(),uploads=new AtomicLong();
        final boolean truncated;
        final boolean blocking;
        final CountDownLatch reading=new CountDownLatch(4), disconnected=new CountDownLatch(4);
        Network(boolean truncated){this(truncated,false);}
        Network(boolean truncated,boolean blocking){this.truncated=truncated;this.blocking=blocking;}
        HttpsURLConnection open(String url) throws java.io.IOException {
            assertTrue(url.startsWith("https://"));assertFalse(url.contains("cloudflare"));
            boolean download=url.contains("garbage.php");
            if(download)downloads.incrementAndGet();
            return new HttpsURLConnection(new URL(url)) {
                final CountDownLatch closed=new CountDownLatch(1);
                @Override public int getResponseCode(){return 200;}
                @Override public String getContentType(){return "application/octet-stream";}
                @Override public InputStream getInputStream(){
                    if(!download)return new ByteArrayInputStream(new byte[0]);
                    return new InputStream(){
                        int left=truncated?65536:SpeedTestEngine.DOWNLOAD_BYTES/4;
                        @Override public int read(){throw new AssertionError("Bulk reads required");}
                        @Override public int read(byte[] b,int off,int len)throws java.io.IOException{
                            if(blocking){
                                reading.countDown();
                                try{if(!closed.await(2,TimeUnit.SECONDS))throw new java.io.IOException("Cancel did not close socket");}
                                catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                                throw new java.io.IOException("Socket closed");
                            }
                            if(left==0)return -1;int n=Math.min(left,len);left-=n;received.addAndGet(n);return n;
                        }
                    };
                }
                @Override public OutputStream getOutputStream(){
                    uploads.incrementAndGet();return new OutputStream(){
                        @Override public void write(int b){sent.incrementAndGet();}
                        @Override public void write(byte[] b,int off,int len){sent.addAndGet(len);}
                    };
                }
                @Override public String getCipherSuite(){return "test";}
                @Override public Certificate[] getLocalCertificates(){return null;}
                @Override public Certificate[] getServerCertificates(){return new Certificate[0];}
                @Override public void disconnect(){if(download&&blocking&&closed.getCount()!=0){closed.countDown();disconnected.countDown();}}
                @Override public boolean usingProxy(){return false;}
                @Override public void connect(){}
            };
        }
    }
    @Test public void fourStreamsShareTheOriginalTotalByteBudget() throws Exception {
        Network network=new Network(false);
        SpeedTestMetrics.Result r=new SpeedTestEngine(network::open,System::nanoTime,4)
                .run((phase,down,up,ping,jitter,progress)->{});
        assertEquals(4,network.downloads.get());assertEquals(4,network.uploads.get());
        assertEquals(SpeedTestEngine.DOWNLOAD_BYTES,network.received.get());
        assertEquals(SpeedTestEngine.UPLOAD_BYTES,network.sent.get());
        assertTrue(Double.isFinite(r.download));assertTrue(Double.isFinite(r.upload));assertFalse(r.server.isEmpty());
    }
    @Test public void truncatedParallelResponsesCannotBecomeAValidSpeed() throws Exception {
        Network network=new Network(true);
        try {
            new SpeedTestEngine(network::open,System::nanoTime,4).run((phase,down,up,ping,jitter,progress)->{});
            fail("Truncated response accepted");
        }catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("Truncated"));}
    }
    @Test public void cancellationClosesEveryActiveStreamAndPublishesNoResult() throws Exception {
        Network network=new Network(false,true);
        SpeedTestEngine engine=new SpeedTestEngine(network::open,System::nanoTime,4);
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            Future<?> run=executor.submit(()->{
                try {engine.run((phase,down,up,ping,jitter,progress)->{});fail("Cancelled test completed");}
                catch(java.io.IOException expected){}
            });
            assertTrue(network.reading.await(2,TimeUnit.SECONDS));
            engine.cancel();
            run.get(2,TimeUnit.SECONDS);
            assertTrue(network.disconnected.await(2,TimeUnit.SECONDS));
            assertEquals(0,network.uploads.get());
        }finally{engine.cancel();executor.shutdownNow();}
    }
}
