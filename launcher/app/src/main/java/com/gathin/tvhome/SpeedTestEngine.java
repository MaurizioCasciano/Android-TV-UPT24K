package com.gathin.tvhome;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.net.ssl.HttpsURLConnection;

/** Manual LibreSpeed test: dedicated endpoints, four overlapping streams, one shared measurement clock. */
final class SpeedTestEngine {
    static final int DOWNLOAD_BYTES = 32 * 1024 * 1024;
    static final int UPLOAD_BYTES = 8 * 1024 * 1024;
    private static final long WARMUP_NS = 500_000_000L;
    private static final long DOWNLOAD_NS = 4_000_000_000L + WARMUP_NS;
    private static final Endpoint[] SERVERS = {
        new Endpoint("Roma", "https://st-be-rm2.infra.garr.it/"),
        new Endpoint("Francoforte", "https://fra.speedtest.clouvider.net/backend/")
    };
    private final Set<HttpsURLConnection> connectionsInFlight = ConcurrentHashMap.newKeySet();
    private final Connections connections;
    private final LongSupplier clock;
    private final int streams;
    private volatile boolean cancelled;

    interface Connections { HttpsURLConnection open(String url) throws IOException; }
    interface Progress { void update(int phase, double down, double up, double ping, double jitter, float progress); }
    private static final class Endpoint {
        final String name, base;
        Endpoint(String name, String base) { this.name=name; this.base=base; }
    }

    SpeedTestEngine() { this(url -> (HttpsURLConnection)new URL(url).openConnection(), System::nanoTime, 4); }
    SpeedTestEngine(Connections connections, LongSupplier clock) { this(connections, clock, 1); }
    SpeedTestEngine(Connections connections, LongSupplier clock, int streams) {
        if (streams < 1 || streams > 4) throw new IllegalArgumentException("Invalid stream count");
        this.connections=connections; this.clock=clock; this.streams=streams;
    }

    void cancel() {
        cancelled = true;
        for (HttpsURLConnection request : connectionsInFlight) request.disconnect();
    }

    SpeedTestMetrics.Result run(Progress listener) throws IOException {
        check();
        ExecutorService workers = Executors.newFixedThreadPool(streams);
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        watchdog.schedule(this::cancel, 45, TimeUnit.SECONDS);
        try {
            Endpoint server = selectServer();
            double[] latency = new double[6];
            for (int i=0; i<latency.length; i++) {
                latency[i]=probe(server);
                listener.update(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, .15f*(i+1)/latency.length);
            }
            double ping=SpeedTestMetrics.median(latency), jitter=SpeedTestMetrics.jitter(latency);
            listener.update(1,Double.NaN,Double.NaN,ping,jitter,.15f);
            SpeedTestWindow.Sample down=transfer(server,false,workers,listener,Double.NaN,ping,jitter);
            listener.update(2,down.mbps(),Double.NaN,ping,jitter,.65f);
            SpeedTestWindow.Sample up=transfer(server,true,workers,listener,down.mbps(),ping,jitter);
            check();
            return new SpeedTestMetrics.Result(down.mbps(),up.mbps(),ping,jitter,System.currentTimeMillis(),server.name);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Test cancelled",interrupted);
        } finally {
            cancel(); workers.shutdownNow(); watchdog.shutdownNow();
        }
    }

    private Endpoint selectServer() throws IOException {
        Endpoint selected=null; double best=Double.POSITIVE_INFINITY;
        for (Endpoint candidate:SERVERS) {
            check();
            try {
                probe(candidate); // DNS/TLS setup is not a server-selection RTT.
                double rtt=(probe(candidate)+probe(candidate))/2;
                if(rtt<best) { best=rtt; selected=candidate; }
            } catch(IOException unavailable) { check(); }
        }
        if(selected==null)throw new IOException("No speed-test server available");
        return selected;
    }

    private void check() throws IOException {
        if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("Test cancelled");
    }

    private HttpsURLConnection open(String url) throws IOException {
        check();
        // Plain default app routing; never select or bind to the underlying physical network.
        HttpsURLConnection request=connections.open(url);
        connectionsInFlight.add(request);
        request.setConnectTimeout(3500);request.setReadTimeout(3500);
        request.setUseCaches(false);request.setInstanceFollowRedirects(false);
        request.setRequestProperty("Cache-Control","no-store, no-transform");
        request.setRequestProperty("Accept-Encoding","identity");
        request.setRequestProperty("User-Agent","UPT24K-Home/1.0");
        check(); return request;
    }

    private void success(HttpsURLConnection request) throws IOException {
        check();
        int code=request.getResponseCode();
        if(code!=200)throw new IOException("Speed-test HTTP "+code);
    }

    private double probe(Endpoint server) throws IOException {
        HttpsURLConnection request=open(server.base+"empty.php?r="+ThreadLocalRandom.current().nextLong());
        boolean complete=false;
        try {
            long start=clock.getAsLong(); success(request); long elapsed=clock.getAsLong()-start;
            try(InputStream input=request.getInputStream()) {
                if(input.read()!=-1)throw new IOException("Invalid ping response");
            }
            complete=true; return elapsed/1_000_000.0;
        } finally {
            if(!complete||cancelled)request.disconnect();
            connectionsInFlight.remove(request);
        }
    }

    private SpeedTestWindow.Sample transfer(Endpoint server, boolean upload, ExecutorService workers,
            Progress listener,double down,double ping,double jitter) throws IOException,InterruptedException {
        int perStream=(upload?UPLOAD_BYTES:DOWNLOAD_BYTES)/streams;
        CountDownLatch ready=new CountDownLatch(streams), start=new CountDownLatch(1);
        AtomicReference<SpeedTestWindow> window=new AtomicReference<>();
        AtomicReference<IOException> failure=new AtomicReference<>();
        List<Future<?>> jobs=new ArrayList<>();
        for(int i=0;i<streams;i++) jobs.add(workers.submit(() -> {
            HttpsURLConnection request=null; boolean signaled=false;
            try {
                String path=upload?"empty.php":"garbage.php?ckSize="+(perStream/(1024*1024))+"&r="+ThreadLocalRandom.current().nextLong();
                request=open(server.base+path);
                if(upload) {
                    request.setRequestMethod("POST");request.setDoOutput(true);
                    request.setRequestProperty("Content-Type","application/octet-stream");
                    request.setFixedLengthStreamingMode(perStream);
                    try(OutputStream output=request.getOutputStream()) {
                        ready.countDown();signaled=true;start.await();check();
                        byte[] buffer=new byte[65536];new Random().nextBytes(buffer);
                        for(int sent=0;sent<perStream;) {
                            check();int count=Math.min(buffer.length,perStream-sent);
                            output.write(buffer,0,count);sent+=count;
                            window.get().add(count,clock.getAsLong());
                        }
                    }
                    success(request);
                    try(InputStream input=request.getInputStream()) {
                        if(input.read()!=-1)throw new IOException("Invalid upload response");
                    }
                    // Wait for the server to acknowledge the complete body. No socket-buffer speed claims.
                    window.get().add(0,clock.getAsLong());
                } else {
                    success(request);
                    String encoding=request.getContentEncoding(), type=request.getContentType();
                    if((encoding!=null&&!encoding.equalsIgnoreCase("identity"))||type==null
                            ||!type.startsWith("application/octet-stream"))throw new IOException("Invalid test body");
                    try(InputStream input=request.getInputStream()) {
                        ready.countDown();signaled=true;start.await();check();
                        byte[] buffer=new byte[65536];long received=0;
                        long begin=clock.getAsLong();
                        while(received<perStream) {
                            check();int count=input.read(buffer,0,(int)Math.min(buffer.length,perStream-received));
                            if(count<0)throw new IOException("Truncated speed-test body");
                            received+=count;long now=clock.getAsLong();
                            window.get().add(count,now);
                            if(now-begin>=DOWNLOAD_NS)break;
                        }
                    }
                }
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt(); failure.compareAndSet(null,new IOException("Test cancelled",interrupted));
            } catch(IOException problem) { failure.compareAndSet(null,problem); }
            finally {
                if(!signaled)ready.countDown();
                if(request!=null){request.disconnect();connectionsInFlight.remove(request);}
            }
        }));
        if(!ready.await(10,TimeUnit.SECONDS))throw new IOException("Stream setup timeout");
        check();if(failure.get()!=null)throw failure.get();
        long begin=clock.getAsLong();
        window.set(new SpeedTestWindow(begin,upload?0:WARMUP_NS));start.countDown();
        while(true) {
            check();if(failure.get()!=null)throw failure.get();
            boolean done=true;for(Future<?> job:jobs)done&=job.isDone();
            if(done)break;
            SpeedTestWindow.Sample sample=window.get().preview(clock.getAsLong());
            float progress=upload?Math.min(1,sample.totalBytes/(float)UPLOAD_BYTES)
                    :Math.min(1,(clock.getAsLong()-begin)/(float)DOWNLOAD_NS);
            listener.update(upload?2:1,upload?down:sample.mbps(),upload?sample.mbps():Double.NaN,
                    ping,jitter,(upload?.65f:.15f)+(upload?.35f:.5f)*progress);
            Thread.sleep(200);
        }
        for(Future<?> job:jobs) {
            try {job.get();}catch(ExecutionException problem){throw new IOException("Transfer failed",problem.getCause());}
        }
        if(failure.get()!=null)throw failure.get();check();
        SpeedTestWindow.Sample sample=window.get().finish();
        if(!Double.isFinite(sample.mbps()))throw new IOException("No bandwidth measured");
        return sample;
    }
}
