package com.example.networkspeedmonitor;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView tvDownload, tvUpload, tvPing, tvLastUpdate;
    private Button btnStart, btnStop;
    private boolean isServiceRunning = false;

    private final BroadcastReceiver speedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            double download = intent.getDoubleExtra("download", 0);
            double upload = intent.getDoubleExtra("upload", 0);
            long ping = intent.getLongExtra("ping", -1);

            tvDownload.setText(String.format(Locale.US, "%.2f", download));
            tvUpload.setText(String.format(Locale.US, "%.2f", upload));
            tvPing.setText(ping > 0 ? String.valueOf(ping) : "N/A");

            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvLastUpdate.setText("Last update: " + time);
        }
    };

    private final ActivityResultLauncher<String> notificationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, "Notification permission required for background monitoring", Toast.LENGTH_LONG).show();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvDownload = findViewById(R.id.tvDownload);
        tvUpload = findViewById(R.id.tvUpload);
        tvPing = findViewById(R.id.tvPing);
        tvLastUpdate = findViewById(R.id.tvLastUpdate);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        btnStart.setOnClickListener(v -> startService());
        btnStop.setOnClickListener(v -> stopService());

        LocalBroadcastManager.getInstance(this).registerReceiver(speedReceiver, new IntentFilter("SPEED_UPDATE"));
    }

    private void startService() {
        Intent intent = new Intent(this, SpeedService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        isServiceRunning = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        Toast.makeText(this, "Speed monitoring started", Toast.LENGTH_SHORT).show();
    }

    private void stopService() {
        Intent intent = new Intent(this, SpeedService.class);
        intent.setAction(SpeedService.ACTION_STOP);
        startService(intent);
        isServiceRunning = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        Toast.makeText(this, "Speed monitoring stopped", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(speedReceiver);
    }
}
