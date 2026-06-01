package com.example.networkspeedmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.HttpURLConnection;
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

    private static final String TEST_URL = "https://speed.cloudflare.com/__down?bytes=5000000";
    private static final String PING_URL = "https://cloudflare.com/cdn-cgi/trace";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
        
        // ✅ طلب تعطيل تحسين البطارية عند بدء الخدمة لأول مرة
        requestDisableBatteryOptimizations();
    }

    private void requestDisableBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }
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

        speedRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                executor.execute(() -> {
                    long ping = measurePing();
                    double downloadSpeed = measureDownloadSpeed();
                    updateUI(downloadSpeed, 0, ping);
                    updateNotification(downloadSpeed, 0, ping);
                });

                handler.postDelayed(this, 30000);
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

    private double measureDownloadSpeed() {
        HttpURLConnection connection = null;
        try {
            long start = System.currentTimeMillis();
            URL url = new URL(TEST_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "SpeedMonitor/1.0");
            
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                int contentLength = connection.getContentLength();
                if (contentLength <= 0) return 0;
                
                connection.getInputStream().close();
                long end = System.currentTimeMillis();
                long timeMs = end - start;
                
                if (timeMs > 0) {
                    double megabits = (contentLength * 8.0) / 1000000.0;
                    double seconds = timeMs / 1000.0;
                    return megabits / seconds;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return 0;
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
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());

        Intent stopIntent = new Intent(this, SpeedService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📡 Speed Monitor")
                .setContentText(String.format(Locale.US, "⬇️ %.1f Mbps | 🕒 %s", download, pingText))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        String.format("Download: %.2f Mbps\nPing: %s\nLast update: %s", download, pingText, time)))
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
