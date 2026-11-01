package com.gathin.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Manual-only network estimate. Leaving Home cancels transfers; results stay in memory for this route. */
final class SpeedTestController {
    private final Context context;
    private final SpeedTestCardView card;
    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SpeedTestSession session = new SpeedTestSession();
    private boolean active, registered;
    private SpeedTestEngine engine;
    private Future<?> task;
    private final Runnable topology = this::networkChanged;
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network n) { changed(); }
        @Override public void onLost(@NonNull Network n) { changed(); }
        @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) { changed(); }
        @Override public void onLinkPropertiesChanged(@NonNull Network n, @NonNull LinkProperties p) { changed(); }
    };

    SpeedTestController(Context context, SpeedTestCardView card) {
        this.context = context;
        this.card = card;
        cm = context.getSystemService(ConnectivityManager.class);
        card.setOnClickListener(v -> click());
        idle(R.string.speed_ready);
    }

    void start() {
        if (active) return;
        active = true;
        if (cm != null) {
            try { cm.registerDefaultNetworkCallback(callback); registered = true; }
            catch (RuntimeException unavailable) { /* Rechecked on click and before delivery. */ }
        }
        networkChanged();
    }

    void stop() {
        active = false;
        main.removeCallbacks(topology);
        if (session.running()) {
            cancel();
            idle(R.string.speed_cancelled);
        }
        if (registered && cm != null) { cm.unregisterNetworkCallback(callback); registered = false; }
    }

    void close() { stop(); worker.shutdownNow(); }

    private void changed() {
        main.post(() -> {
            if (!active) return;
            main.removeCallbacks(topology);
            main.postDelayed(topology, 400);
        });
    }

    private String route() {
        if (cm == null) return "offline";
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        if (n == null || c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return "offline";
        LinkProperties p = cm.getLinkProperties(n);
        return n.getNetworkHandle() + ":" + c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                + ":" + (p == null ? "" : p.getLinkAddresses() + ":" + p.getRoutes());
    }

    private void networkChanged() {
        if (!active) return;
        String next = route();
        if (session.selectRoute(next)) {
            stopTransfer();
            idle(next.equals("offline") ? R.string.speed_offline : R.string.speed_ready);
        }
    }

    private void cancel() { session.cancel(); stopTransfer(); }
    private void stopTransfer() {
        if (engine != null) engine.cancel();
        if (task != null) task.cancel(true);
    }

    private void click() {
        if (!active) return;
        if (session.running()) { cancel(); idle(R.string.speed_cancelled); return; }
        networkChanged();
        String startedRoute = route();
        if (startedRoute.equals("offline")) { idle(R.string.speed_offline); return; }
        long token = session.begin();
        if (token < 0) return;
        engine = new SpeedTestEngine();
        SpeedTestEngine test = engine;
        card.show(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, context.getString(R.string.speed_measuring_ping));
        task = worker.submit(() -> {
            try {
                SpeedTestMetrics.Result result = test.run((phase, down, up, ping, jitter, progress) -> main.post(() -> {
                    if (!active || !session.accepts(token)) return;
                    if (!startedRoute.equals(route())) { networkChanged(); return; }
                    card.show(down, up, ping, jitter, progress, context.getString(phase == 0 ? R.string.speed_measuring_ping
                            : phase == 1 ? R.string.speed_measuring_down : R.string.speed_measuring_up));
                }));
                main.post(() -> {
                    if (!active || !session.accepts(token)) return;
                    if (!startedRoute.equals(route())) { networkChanged(); return; }
                    if (session.finish(token, result)) card.show(result.download, result.upload, result.ping, result.jitter, 1,
                            context.getString(R.string.speed_finished, DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(result.wallTime)), result.server));
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (!active || !session.accepts(token)) return;
                    if (!startedRoute.equals(route())) { networkChanged(); return; }
                    if (session.finish(token, null)) idle(R.string.speed_failed);
                });
            }
        });
    }

    private void idle(int status) {
        SpeedTestMetrics.Result r = session.result();
        card.show(r == null ? Double.NaN : r.download, r == null ? Double.NaN : r.upload,
                r == null ? Double.NaN : r.ping, r == null ? Double.NaN : r.jitter,
                r == null ? 0 : 1, context.getString(status));
    }
}
