package com.linuxbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import com.linuxbox.web.WebTerminalServer;

import java.util.concurrent.atomic.AtomicReference;

public class TermServerService extends Service {

    private static final String CHANNEL = "linuxbox-term";
    private static final int NOTIF_ID = 1;
    private static final String TAG = "TermServer";

    private final AtomicReference<WebTerminalServer> serverRef = new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean starting = new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("stop", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (serverRef.get() == null && starting.compareAndSet(false, true)) {
            int port = intent != null ? intent.getIntExtra("port", 8770) : 8770;
            boolean lan = intent != null && intent.getBooleanExtra("lan", false);
            int finalPort = port;

            // panggil startForeground SEGERA (wajib <5s), perbarui setelah server jalan
            startForeground(NOTIF_ID, buildNotification("http://127.0.0.1:" + finalPort + "/",
                    "LinuxBox memulai terminal..."));
            new Thread(() -> {
                try {
                    WebTerminalServer server = new WebTerminalServer(this, finalPort, lan);
                    server.start();
                    serverRef.set(server);
                    String url = "http://127.0.0.1:" + server.getBoundPort() + "/";
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    nm.notify(NOTIF_ID, buildNotification(url, "Terminal: " + url));
                    sendBroadcast(new Intent("com.linuxbox.URL").putExtra("url", url));
                } catch (Throwable t) {
                    Log.e(TAG, "gagal start server", t);
                    try {
                        java.io.FileOutputStream fos =
                                new java.io.FileOutputStream(new java.io.File(getFilesDir(), "error.txt"));
                        fos.write((t + "\n").getBytes());
                        for (StackTraceElement e : t.getStackTrace()) fos.write(("  at " + e + "\n").getBytes());
                        fos.close();
                    } catch (Exception ignored) {
                    }
                    final String msg = String.valueOf(t.getMessage());
                    starting.set(false);
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            Toast.makeText(this, "Server gagal: " + msg, Toast.LENGTH_LONG).show());
                    stopSelf();
                }
            }, "tty-start").start();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String url, String content) {
        Intent open = new Intent(this, WebViewActivity.class).putExtra("url", url);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, TermServerService.class).putExtra("stop", true);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("LinuxBox aktif")
                .setContentText(content)
                .setContentIntent(openPi)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .addAction(0, "Stop", stopPi)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Terminal Server", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        WebTerminalServer s = serverRef.getAndSet(null);
        if (s != null) s.stop();
        super.onDestroy();
    }
}