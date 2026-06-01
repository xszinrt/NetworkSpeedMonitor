package com.example.networkspeedmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SpeedService extends Service {

    public static final String CHANNEL_ID = "speed_channel";
    public static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_SPEED_UPDATE = "SPEED_UPDATE";

    private Handler handler;
    private Runnable speedRunnable;
    private boolean isRunning = false;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    // خوادم متعددة لقياس السرعة
    private static final String[] TEST_URLS = {
        "https://speed.cloudflare.com/__down?bytes=5000000",
        "https://speedtest.tele2.net/5MB.zip",
        "https://proof.ovh.net/files/5Mb.dat"
    };
    
    private static final String[] PING_URLS = {
        "https://cloudflare.com/cdn-cgi/trace",
        "https://www.google.com/generate_204",
        "https://www.microsoft.com/robots.txt"
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        handler = new Handler(Looper.getMainLooper());
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
                    
                    Intent updateIntent = new Intent(ACTION_SPEED_UPDATE);
                    updateIntent.putExtra("download", downloadSpeed);
                    updateIntent.putExtra("upload", 0.0);
                    updateIntent.putExtra("ping", ping);
                    sendBroadcast(updateIntent);
                    
                    updateNotification(downloadSpeed, 0, ping);
                });

                handler.postDelayed(this, 30000);
            }
        };
        handler.post(speedRunnable);
    }

    private long measurePing() {
        for (String pingUrl : PING_URLS) {
            try {
                long start = System.currentTimeMillis();
                URL url = new URL(pingUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestMethod("HEAD");
                int responseCode = connection.getResponseCode();
                if (responseCode == 200 || responseCode == 204) {
                    connection.disconnect();
                    return System.currentTimeMillis() - start;
                }
                connection.disconnect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return -1;
    }

    private double measureDownloadSpeed() {
        for (String testUrl : TEST_URLS) {
            HttpURLConnection connection = null;
            try {
                long start = System.currentTimeMillis();
                URL url = new URL(testUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "SpeedMonitor/1.0");
                
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    int contentLength = connection.getContentLength();
                    if (contentLength <= 0) continue;
                    
                    // قراءة البيانات لضمان اكتمال التحميل
                    InputStream is = connection.getInputStream();
                    byte[] buffer = new byte[8192];
                    long totalRead = 0;
                    while (is.read(buffer) != -1) {
                        totalRead += buffer.length;
                        if (totalRead >= contentLength) break;
                    }
                    is.close();
                    
                    long end = System.currentTimeMillis();
                    long timeMs = end - start;
                    
                    if (timeMs > 0 && contentLength > 0) {
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
        }
        return 0;
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
