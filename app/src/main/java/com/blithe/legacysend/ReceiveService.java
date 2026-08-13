package com.blithe.legacysend;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.blithe.legacysend.ui.MainActivity;

public final class ReceiveService extends Service {

    @SuppressWarnings("deprecation")
    @Override public void onCreate() {
        super.onCreate();
        
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        
        String title = getString(R.string.notif_receive_title);
        String text = getString(R.string.notif_receive_text);
        
        Notification notification;
        
        if (Build.VERSION.SDK_INT >= 16) {
            notification = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pending)
                    .setOngoing(true)
                    .build();
        } else if (Build.VERSION.SDK_INT >= 11) {
            notification = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pending)
                    .setOngoing(true)
                    .getNotification();
        } else { 
            notification = new Notification(
                    android.R.drawable.stat_sys_upload_done,
                    title,
                    System.currentTimeMillis()
            );
            notification.flags |= Notification.FLAG_ONGOING_EVENT;
            notification.setLatestEventInfo(this, title, text, pending);
        }
        
        startForeground(53317, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ((LegacySendApp) getApplication()).startReceiving();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { 
        return null; 
    }
}
