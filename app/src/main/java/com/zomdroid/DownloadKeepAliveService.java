package com.zomdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Tiny foreground service that runs ONLY to keep the app process at foreground-service priority while a
 * Steam download is in progress. The actual download runs on its own thread in the same process; this
 * service stops Android from freezing/killing that process (and its connection-manager websocket) when
 * the app is backgrounded.
 *
 * Ported from RimDroid (MIT). Uses the DATA_SYNC foreground type (declared in the manifest) +
 * a low-importance ongoing notification.
 */
public class DownloadKeepAliveService extends Service {

    private static final String CHANNEL_ID = "zd_download";
    private static final int NOTIF_ID = 4242;

    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx.getApplicationContext(),
                new Intent(ctx.getApplicationContext(), DownloadKeepAliveService.class));
    }

    public static void stop(Context ctx) {
        ctx.getApplicationContext().stopService(
                new Intent(ctx.getApplicationContext(), DownloadKeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Zomdroid")
                .setContentText("Downloading from Steam — keep the app open")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
