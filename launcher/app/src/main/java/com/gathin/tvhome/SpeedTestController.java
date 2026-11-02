package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.annotation.NonNull;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Foreground-only auto test on first validated access, plus explicit OK; last genuine result survives restarts. */
final class SpeedTestController {
    private final Context context;
    private final SpeedTestCardView card;
    private final ConnectivityManager cm;
    private final SharedPreferences preferences;
    private final SpeedTestAutoStart automatic;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SpeedTestSession session = new SpeedTestSession();
    private SpeedTestMetrics.Result lastResult;
    private boolean active, registered;
    private String scheduledKey = "";
    private String runningConnection = "";
    private boolean automaticRun;
    private SpeedTestEngine engine;
    private Future<?> task;
    private final Runnable topology = this::networkChanged;
    private final Runnable autoRun = this::runAutomatic;

    private void runAutomatic() {
        scheduledKey = "";
        if (!active || session.running()) return;
        String key = connectionKey();
        if (automatic.delay(key, SystemClock.elapsedRealtime()) != 0) { networkChanged(); return; }
        beginTest(key,true);
    }
    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network n) { changed(); }
        @Override public void onLost(@NonNull Network n) { changed(); }
        @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) { changed(); }
        @Override public void onLinkPropertiesChanged(@NonNull Network n, @NonNull LinkProperties p) { changed(); }
    };

    SpeedTestController(Context context, SpeedTestCardView card) {
        this.context=context;this.card=card;cm=context.getSystemService(ConnectivityManager.class);
        preferences=context.getSharedPreferences("upt24k_speed_test",Context.MODE_PRIVATE);
        int boot=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        int savedBoot=-2;long savedTime=-1;Set<String> savedKeys=Collections.emptySet();
        try {
            savedBoot=preferences.getInt("auto_boot",-2);
            savedTime=preferences.getLong("auto_time",-1);
            savedKeys=preferences.getStringSet("auto_networks",Collections.emptySet());
            Map<String,String> values=new HashMap<>();
            for(String key:new String[]{"version","download","upload","ping","jitter","time","server"})
                values.put(key,preferences.getString("last_"+key,""));
            lastResult=SpeedTestSavedResult.decode(values);
        } catch(RuntimeException corrupt) { savedBoot=-2;savedTime=-1;savedKeys=Collections.emptySet();lastResult=null; }
        if(lastResult==null||savedKeys==null)savedKeys=Collections.emptySet(); // Recover an interrupted first test after process death.
        automatic=new SpeedTestAutoStart(boot,savedBoot,savedKeys,savedTime,SystemClock.elapsedRealtime());
        card.setOnClickListener(v -> click());
        idle(lastResult==null?R.string.speed_wait_internet:R.string.speed_saved);
    }

    void start() {
        if(active)return;active=true;
        if(cm!=null) {
            try {cm.registerDefaultNetworkCallback(callback);registered=true;}
            catch(RuntimeException unavailable) { /* Still sampled on resume and before starting. */ }
        }
        networkChanged();
    }

    void stop() {
        active=false;main.removeCallbacks(topology);cancelScheduled();
        if(session.running()){
            String retry=automaticRun&&lastResult==null?runningConnection:"";
            cancel();
            if(!retry.isEmpty()){automatic.releaseUnfinished(retry);saveAutomatic();}
            idle(R.string.speed_cancelled);
        }
        if(registered&&cm!=null){cm.unregisterNetworkCallback(callback);registered=false;}
    }

    void close(){stop();worker.shutdownNow();}

    private void changed() {
        main.post(() -> {
            if(!active)return;
            main.removeCallbacks(topology);main.postDelayed(topology,400);
        });
    }

    private Network onlineNetwork() {
        if(cm==null)return null;
        Network n=cm.getActiveNetwork();NetworkCapabilities c=cm.getNetworkCapabilities(n);
        return n!=null&&c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                &&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?n:null;
    }

    private String connectionKey() {
        Network n=onlineNetwork();if(n==null)return "";
        NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c==null)return "";
        // Stored locally only as a boot-local opaque handle and VPN boolean; no IP or SSID.
        return n.getNetworkHandle()+":"+c.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private String route() {
        Network n=onlineNetwork();if(n==null)return "offline";
        NetworkCapabilities c=cm.getNetworkCapabilities(n);if(c==null)return "offline";
        LinkProperties p=cm.getLinkProperties(n);
        return n.getNetworkHandle()+":"+c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                +":"+(p==null?"":p.getLinkAddresses()+":"+p.getRoutes());
    }

    private void networkChanged() {
        if(!active)return;
        String next=route();
        String retry=session.running()&&automaticRun&&lastResult==null?runningConnection:"";
        if(session.selectRoute(next)) {
            stopTransfer();cancelScheduled();automaticRun=false;runningConnection="";
            if(!retry.isEmpty()){automatic.releaseUnfinished(retry);saveAutomatic();}
            idle(next.equals("offline")?R.string.speed_offline:R.string.speed_saved);
        }
        if(next.equals("offline")){cancelScheduled();idle(R.string.speed_offline);return;}
        scheduleAutomatic(connectionKey());
    }

    private void scheduleAutomatic(String key) {
        long delay=automatic.delay(key,SystemClock.elapsedRealtime());
        if(!active||session.running()||delay<0){cancelScheduled();return;}
        if(key.equals(scheduledKey))return;
        cancelScheduled();scheduledKey=key;
        idle(delay>0?R.string.speed_auto_wait:R.string.speed_auto_start);
        main.postDelayed(autoRun,Math.max(1200,delay));
    }

    private void cancelScheduled(){main.removeCallbacks(autoRun);scheduledKey="";}
    private void cancel(){session.cancel();cancelScheduled();stopTransfer();automaticRun=false;runningConnection="";}
    private void stopTransfer(){if(engine!=null)engine.cancel();if(task!=null)task.cancel(true);}

    private void click() {
        if(!active)return;
        if(session.running()){cancel();idle(R.string.speed_cancelled);return;}
        networkChanged();String key=connectionKey();
        if(key.isEmpty()){idle(R.string.speed_offline);return;}
        beginTest(key,false);
    }

    private void saveAutomatic() {
        preferences.edit().putInt("auto_boot",automatic.boot()).putLong("auto_time",automatic.lastAttempt())
                .putStringSet("auto_networks",automatic.attempted()).apply();
    }

    private void beginTest(String connection,boolean fromAutomatic) {
        if(!active||connection.isEmpty()||!connection.equals(connectionKey())||session.running())return;
        String startedRoute=route();if(startedRoute.equals("offline"))return;
        session.selectRoute(startedRoute);cancelScheduled();
        long token=session.begin();if(token<0)return;
        automaticRun=fromAutomatic;runningConnection=connection;
        automatic.claim(connection,SystemClock.elapsedRealtime());
        saveAutomatic();
        engine=new SpeedTestEngine();SpeedTestEngine test=engine;
        preview(Double.NaN,Double.NaN,Double.NaN,Double.NaN,0,0);
        task=worker.submit(() -> {
            // Claim before sending test traffic, so process recreation cannot start it again.
            preferences.edit().commit(); // Flush pending apply writes off the UI thread before network I/O.
            try {
                SpeedTestMetrics.Result result=test.run((phase,down,up,ping,jitter,progress) -> main.post(() -> {
                    if(!active||!session.accepts(token))return;
                    if(!startedRoute.equals(route())){networkChanged();return;}
                    preview(down,up,ping,jitter,progress,phase);
                }));
                main.post(() -> {
                    if(!active||!session.accepts(token))return;
                    if(!startedRoute.equals(route())){networkChanged();return;}
                    if(session.finish(token,result)){
                        automaticRun=false;runningConnection="";
                        lastResult=result;
                        SharedPreferences.Editor edit=preferences.edit();
                        for(Map.Entry<String,String> value:SpeedTestSavedResult.encode(result).entrySet())
                            edit.putString("last_"+value.getKey(),value.getValue());
                        edit.apply();
                        card.show(result.download,result.upload,result.ping,result.jitter,1,
                                context.getString(R.string.speed_finished,DateFormat.getTimeInstance(DateFormat.SHORT)
                                        .format(new Date(result.wallTime)),result.server));
                    }
                });
            } catch(Exception unavailable) {
                main.post(() -> {
                    if(!active||!session.accepts(token))return;
                    if(!startedRoute.equals(route())){networkChanged();return;}
                    if(session.finish(token,null)){automaticRun=false;runningConnection="";idle(R.string.speed_failed);}
                });
            }
        });
    }

    private void preview(double down,double up,double ping,double jitter,float progress,int phase) {
        int status=phase==0?R.string.speed_measuring_ping:phase==1?R.string.speed_measuring_down:R.string.speed_measuring_up;
        if(lastResult==null)card.show(down,up,ping,jitter,progress,context.getString(status));
        else card.show(lastResult.download,lastResult.upload,lastResult.ping,lastResult.jitter,progress,
                context.getString(R.string.speed_previous_during_update,context.getString(status)));
    }

    private void idle(int status) {
        SpeedTestMetrics.Result r=lastResult;
        String text=context.getString(status);
        if(status==R.string.speed_saved)text=r==null?context.getString(R.string.speed_ready)
                :context.getString(R.string.speed_last_result,DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(r.wallTime)));
        else if(r!=null)text=context.getString(R.string.speed_previous_during_update,text);
        card.show(r==null?Double.NaN:r.download,r==null?Double.NaN:r.upload,
                r==null?Double.NaN:r.ping,r==null?Double.NaN:r.jitter,0,text);
    }
}
