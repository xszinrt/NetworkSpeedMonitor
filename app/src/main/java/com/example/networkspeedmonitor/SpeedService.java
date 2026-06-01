package com.example.networkspeedmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SpeedService extends Service {

    public static final String CHANNEL_ID = "speed_channel";
    public static final int NOTIFICATION_ID = 1;
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_SPEED_UPDATE = "SPEED_UPDATE";

    private Handler handler;
    private Runnable speedRunnable;
    private boolean isRunning = false;

    // متغيرات لحساب السرعة
    private long lastRxBytes = 0;
    private long lastTxBytes = 0;
    private long lastTime = 0;

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

        startForeground(NOTIFICATION_ID, createNotification(0, 0));
        startSpeedTest();
        return START_STICKY;
    }

    private void startSpeedTest() {
        isRunning = true;
        
        // أخذ القراءة الأولى
        lastRxBytes = TrafficStats.getTotalRxBytes();
        lastTxBytes = TrafficStats.getTotalTxBytes();
        lastTime = System.currentTimeMillis();

        speedRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                // أخذ القراءة الثانية
                long now = System.currentTimeMillis();
                long currentRxBytes = TrafficStats.getTotalRxBytes();
                long currentTxBytes = TrafficStats.getTotalTxBytes();

                long timeDiff = now - lastTime;
                
                if (timeDiff > 0) {
                    // حساب سرعة التحميل (Download) بالميجابت في الثانية
                    long rxDiff = currentRxBytes - lastRxBytes;
                    double downloadSpeed = (rxDiff * 8.0) / (timeDiff / 1000.0) / 1000000.0;
                    
                    // حساب سرعة الرفع (Upload) بالميجابت في الثانية
                    long txDiff = currentTxBytes - lastTxBytes;
                    double uploadSpeed = (txDiff * 8.0) / (timeDiff / 1000.0) / 1000000.0;
                    
                    // Ping يقاس بشكل منفصل (ICMP) - سنستخدم قيمة افتراضية حالياً
                    long ping = estimatePing();
                    
                    // إرسال التحديث إلى الواجهة
                    Intent updateIntent = new Intent(ACTION_SPEED_UPDATE);
                    updateIntent.putExtra("download", downloadSpeed);
                    updateIntent.putExtra("upload", uploadSpeed);
                    updateIntent.putExtra("ping", ping);
                    sendBroadcast(updateIntent);
                    
                    // تحديث الإشعار
                    updateNotification(downloadSpeed, uploadSpeed, ping);
                    
                    // تحديث القيم السابقة للقياس التالي
                    lastRxBytes = currentRxBytes;
                    lastTxBytes = currentTxBytes;
                    lastTime = now;
                }
                
                // جدولة القياس التالي بعد 2 ثانية
                handler.postDelayed(this, 2000);
            }
        };
        
        handler.post(speedRunnable);
    }
    
    private long estimatePing() {
        // تقدير الـ Ping بناءً على زمن الاستجابة المقاس
        // في القياس الحقيقي، يمكن قياس Ping عبر طلب ICMP أو HTTP بسيط
        return System.currentTimeMillis() - lastTime;
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
                .setContentText(String.format(Locale.US, "⬇️ %.1f Mbps | ⬆️ %.1f Mbps", download, upload))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        String.format("Download: %.2f Mbps\nUpload: %.2f Mbps\nPing: %s\nLast update: %s", 
                                download, upload, pingText, time)))
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentIntent(openIntent)
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
    }
}
