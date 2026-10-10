package dev.norihy.discordmobile;

import android.app.*;
import android.content.Intent;
import android.os.*;

/** Keeps a user-started, bounded WebView session alive; never restarts an uncertain send. */
public final class WatchService extends Service {
    static Runnable onStopRequested;
    private PowerManager.WakeLock wakeLock;
    private Runnable sessionCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable expire = this::stopSession;

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("watch", "Surveillance du salon", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction()) || onStopRequested == null) {
            stopSession(); return START_NOT_STICKY;
        }
        long remaining = intent.getLongExtra("deadline", 0) - System.currentTimeMillis();
        if (remaining <= 0) { stopSession(); return START_NOT_STICKY; }
        sessionCallback = onStopRequested;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, WatchService.class)
            .setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "watch")
            .setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("Surveillance Discord active")
            .setContentText("Continue en arrière-plan jusqu’à la fin de la fenêtre")
            .setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Arrêter", stop).build()).build());
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "discordmobile:watch");
        wakeLock.acquire(remaining + 15000);
        handler.removeCallbacks(expire); handler.postDelayed(expire, remaining);
        return START_NOT_STICKY;
    }

    private void stopSession() {
        Runnable callback = sessionCallback;
        sessionCallback = null;
        if (callback != onStopRequested) callback = null;
        if (callback != null) onStopRequested = null;
        if (callback != null) callback.run();
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) { stopSession(); }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        Runnable callback = sessionCallback; sessionCallback = null;
        if (callback != onStopRequested) callback = null;
        if (callback != null) onStopRequested = null;
        if (callback != null) callback.run();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
