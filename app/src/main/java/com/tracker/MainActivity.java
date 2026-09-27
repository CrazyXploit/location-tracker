package com.tracker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_CODE = 42;
    private final Handler handler = new Handler(Looper.getMainLooper());

   @Override
protected void onCreate(Bundle b) {
    super.onCreate(b);

    // Hide everything — we only want the permission dialog
    getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    try {
        getSupportActionBar().hide();
    } catch (Exception ignored) { }
    setContentView(new android.view.View(this));

    if (hasFineLocation()) {
        startTracker();
        finish();
        return;
    }

    ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    }, REQ_CODE);
}

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code != REQ_CODE) return;

        boolean granted = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            handler.post(() -> ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_CODE + 1));
            return;
        }

        startTracker();
        finish();
    }

    private void startTracker() {
        Intent i = new Intent(this, TrackerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }
}
