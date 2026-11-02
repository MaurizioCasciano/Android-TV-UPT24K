package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.HttpsURLConnection;

/** UPT24K personal build: one HTTPS provider, no persistence, no service, no VPN bypass. */
final class NetworkSummaryController {
    private static final String ENDPOINT = "https://ipwho.is/?fields=success,ip,country_code,connection.isp,connection.org";
    private final Context context;
    private final View card;
    private final TextView country, flag, publicIp, localIp, operator, updated;
    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final NetworkLookupState state = new NetworkLookupState();
    private Future<?> task;
    private volatile HttpsURLConnection connection;
    private boolean active, registered, online, failed;
    private String route = "", lan = "—";
    private final Runnable topology = () -> refresh(false);
    private final Runnable automatic = () -> attempt(false);
    private final Runnable manual = () -> attempt(true);

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network network) { changed(); }
        @Override public void onLost(@NonNull Network network) { changed(); }
        @Override public void onCapabilitiesChanged(@NonNull Network network,
                @NonNull NetworkCapabilities capabilities) { changed(); }
        @Override public void onLinkPropertiesChanged(@NonNull Network network,
                @NonNull LinkProperties properties) { changed(); }
    };

    NetworkSummaryController(Context context, View card) {
        this.context = context;
        this.card = card;
        cm = context.getSystemService(ConnectivityManager.class);
        country = card.findViewById(R.id.netCountry);
        flag = card.findViewById(R.id.netFlag);
        publicIp = card.findViewById(R.id.netPublic);
        localIp = card.findViewById(R.id.netLocal);
        operator = card.findViewById(R.id.netOperator);
        updated = card.findViewById(R.id.netUpdated);
        card.setOnClickListener(v -> refresh(true));
    }

    void start() {
        if (active) return;
        active = true;
        if (cm != null) {
            try {
                cm.registerDefaultNetworkCallback(callback);
                registered = true;
            } catch (RuntimeException unavailable) { /* onResume still samples the connection. */ }
        }
        refresh(false);
    }

    void stop() {
        active = false;
        main.removeCallbacks(topology);
        main.removeCallbacks(automatic);
        main.removeCallbacks(manual);
        cancelRequest();
        if (registered && cm != null) {
            cm.unregisterNetworkCallback(callback);
            registered = false;
        }
    }

    void close() {
        stop();
        worker.shutdownNow();
    }

    private void changed() {
        main.post(() -> {
            if (!active) return;
            main.removeCallbacks(topology);
            main.postDelayed(topology, 600);
        });
    }

    private void cancelRequest() {
        state.cancel();
        if (task != null) task.cancel(true);
        HttpsURLConnection current = connection;
        if (current != null) current.disconnect();
    }

    private void refresh(boolean force) {
        if (!active) return;
        sampleNetwork();
        if (state.selectNetwork(route)) {
            cancelRequest();
            failed = false;
        }
        render(false);
        schedule(force);
    }

    private void schedule(boolean force) {
        main.removeCallbacks(automatic);
        main.removeCallbacks(manual);
        if (!active || !online || state.busy()) return;
        main.postDelayed(force ? manual : automatic, state.delay(SystemClock.elapsedRealtime(), force));
    }

    private void attempt(boolean force) {
        if (!active) return;
        // Re-read just before connecting: a network event may still be in the debounce window.
        sampleNetwork();
        if (state.selectNetwork(route)) {
            cancelRequest();
            failed = false;
        }
        if (!online) { render(false); return; }
        long token = state.begin(SystemClock.elapsedRealtime(), force);
        if (token < 0) { render(false); schedule(force); return; }
        render(true);
        task = worker.submit(() -> lookup(token));
    }

    private void lookup(long token) {
        HttpsURLConnection request = null;
        NetworkLookupState.Result result = null;
        long retry = NetworkLookupState.RETRY_MS;
        try {
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            if (Thread.currentThread().isInterrupted()) return;
            // Default routing respects per-app VPN rules. Never bind to the underlying Wi-Fi network.
            request = (HttpsURLConnection) new URL(ENDPOINT).openConnection();
            connection = request;
            request.setConnectTimeout(5000);
            request.setReadTimeout(5000);
            request.setInstanceFollowRedirects(false);
            request.setUseCaches(false);
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("User-Agent", "UPT24K-Home/1.0");
            if (Thread.currentThread().isInterrupted()) return;
            int code = request.getResponseCode();
            if (code == 429 || code == 503)
                retry = NetworkLookupState.retryAfterMillis(request.getHeaderField("Retry-After"), System.currentTimeMillis());
            if (code != 200) throw new java.io.IOException("Lookup unavailable");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = request.getInputStream()) {
                byte[] buffer = new byte[2048];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted() || body.size() + count > 16_384
                            || SystemClock.elapsedRealtime() > deadline)
                        throw new java.io.IOException("Lookup interrupted or oversized");
                    body.write(buffer, 0, count);
                }
            }
            JSONObject json = new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
            String ip = clean(json.optString("ip"));
            String cc = clean(json.optString("country_code")).toUpperCase(Locale.ROOT);
            if (!json.optBoolean("success") || !NumericIpAddress.isValid(ip)
                    || !cc.matches("[A-Z]{2}")) throw new java.io.IOException("Invalid lookup");
            JSONObject net = json.optJSONObject("connection");
            String isp = net == null ? "" : clean(net.optString("isp"));
            if (isp.isEmpty() && net != null) isp = clean(net.optString("org"));
            result = new NetworkLookupState.Result(ip, cc, isp, System.currentTimeMillis());
        } catch (Exception unavailable) {
            // No IPs or response bodies are logged, including errors.
        } finally {
            if (request != null) request.disconnect();
            if (connection == request) connection = null;
        }
        NetworkLookupState.Result answer = result;
        long backoff = retry;
        main.post(() -> {
            if (!active || !state.current(token)) return;
            String requestedRoute = route;
            sampleNetwork();
            if (!requestedRoute.equals(route)) { refresh(false); return; }
            if (answer != null) {
                state.succeed(token, answer, SystemClock.elapsedRealtime());
                failed = false;
            } else {
                state.fail(token, SystemClock.elapsedRealtime(), backoff);
                failed = true;
            }
            render(false);
            schedule(false);
        });
    }

    private void sampleNetwork() {
        online = false;
        route = "offline";
        lan = "—";
        if (cm == null) return;
        Network activeNetwork = cm.getActiveNetwork();
        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
        LinkProperties link = cm.getLinkProperties(activeNetwork);
        if (activeNetwork != null && caps != null) {
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            route = activeNetwork.getNetworkHandle() + ":" + online + ":"
                    + caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) + ":"
                    + (link == null ? "" : link.getInterfaceName() + ":" + link.getLinkAddresses() + ":" + link.getRoutes());
        }
        // Show the physical LAN address separately from a VPN tunnel's address.
        String wifi = "—";
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            String address = address(cm.getLinkProperties(network));
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && !address.equals("—")) {
                lan = address;
                return;
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = address;
        }
        lan = wifi;
    }

    private static String address(LinkProperties properties) {
        if (properties == null) return "—";
        String ipv6 = "—";
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress ip = link.getAddress();
            if (ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isAnyLocalAddress()) continue;
            String host = ip.getHostAddress();
            if (host == null) continue;
            if (ip instanceof Inet4Address) return host;
            ipv6 = host.split("%", 2)[0];
        }
        return ipv6;
    }

    private static String clean(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        return cleaned.substring(0, Math.min(120, cleaned.length()));
    }

    private void render(boolean loading) {
        NetworkLookupState.Result value = state.result();
        String countryName = value == null ? context.getString(R.string.network_title)
                : new Locale("", value.countryCode).getDisplayCountry(Locale.getDefault());
        country.setText(countryName);
        String emoji = value == null ? "◎" : new String(Character.toChars(0x1F1E6 + value.countryCode.charAt(0) - 'A'))
                + new String(Character.toChars(0x1F1E6 + value.countryCode.charAt(1) - 'A'));
        Paint font = flag.getPaint();
        flag.setText(value != null && !font.hasGlyph(emoji) ? value.countryCode : emoji);
        publicIp.setText(value == null ? "—" : value.ip);
        localIp.setText(context.getString(R.string.network_local, lan));
        operator.setText(value == null || value.operator.isEmpty() ? "—" : value.operator);
        String status;
        if (!online) status = context.getString(R.string.network_offline);
        else if (loading || state.busy()) status = context.getString(R.string.network_loading);
        else if (failed) status = context.getString(value == null ? R.string.network_failed : R.string.network_stale);
        else if (value == null) status = context.getString(R.string.network_waiting);
        else status = context.getString(R.string.network_updated_compact,
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(value.wallTime)));
        updated.setText(status);
        card.setContentDescription(context.getString(R.string.network_description,
                countryName, value == null ? "—" : value.ip, lan, operator.getText(), status));
    }
}
