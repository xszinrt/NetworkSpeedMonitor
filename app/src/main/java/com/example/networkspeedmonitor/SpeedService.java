package com.example.networkspeedmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SpeedService extends Service {

    public static final String CHANNEL_ID = "speed_channel";
    public static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STOP = "ACTION_STOP";

    private Handler handler;
    private Runnable speedRunnable;
    private boolean isRunning = false;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSpeedTest();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification(0, 0, 0));
        startSpeedTest();
        return START_STICKY;
    }

    private void startSpeedTest() {
        isRunning = true;
        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        lastTime = System.currentTimeMillis();

        speedRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                long now = System.currentTimeMillis();
                long currentRxBytes = TrafficStats.getTotalRxBytes();
                long currentTxBytes = TrafficStats.getTotalTxBytes();

                long timeDiff = now - lastTime;
                if (timeDiff > 0) {
                    // Download speed (Mbps)
                    double rxSpeed = (currentRxBytes - lastRxBytes) * 8.0 / (timeDiff / 1000.0) / 1000000.0;
                    // Upload speed (Mbps)
                    double txSpeed = (currentTxBytes - lastTxBytes) * 8.0 / (timeDiff / 1000.0) / 1000000.0;

                    // Measure ping
                    executor.execute(() -> {
                        long ping = measurePing();
                        updateUI(rxSpeed, txSpeed, ping);
                        updateNotification(rxSpeed, txSpeed, ping);
                    });

                    lastRxBytes = currentRxBytes;
                    lastTxBytes = currentTxBytes;
                    lastTime = now;
                }

                // Update every 2 seconds
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(speedRunnable);
    }

    private long measurePing() {
        try {
            long start = System.currentTimeMillis();
            InetAddress address = InetAddress.getByName("8.8.8.8");
            boolean reachable = address.isReachable(3000);
            if (reachable) {
                return System.currentTimeMillis() - start;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return -1;
    }

    private void updateUI(double download, double upload, long ping) {
        Intent intent = new Intent("SPEED_UPDATE");
        intent.putExtra("download", download);
        intent.putExtra("upload", upload);
        intent.putExtra("ping", ping);
        sendBroadcast(intent);
    }

    private void updateNotification(double download, double upload, long ping) {
        Notification notification = createNotification(download, upload, ping);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, notification);
        startForeground(NOTIFICATION_ID, notification);
    }

    private Notification createNotification(double download, double upload, long ping) {
        String pingText = ping > 0 ? ping + " ms" : "N/A";

        Intent stopIntent = new Intent(this, SpeedService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📡 Speed Monitor")
                .setContentText(String.format(Locale.US, "⬇️ %.1f Mbps | ⬆️ %.1f Mbps | 🕒 %s", download, upload, pingText))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        String.format("Download: %.2f Mbps\nUpload: %.2f Mbps\nPing: %s", download, upload, pingText)))
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentIntent(openPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void stopSpeedTest() {
        isRunning = false;
        if (handler != null && speedRunnable != null) {
            handler.removeCallbacks(speedRunnable);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Speed Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Network speed monitoring");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSpeedTest();
        executor.shutdown();
    }
}
