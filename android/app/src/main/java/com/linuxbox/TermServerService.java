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

import com.linuxbox.distro.RootfsGuard;
import com.linuxbox.web.SessionManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Layanan foreground yang menjaga sesi terminal tetap hidup.
 *
 * Tidak lagi membawa server web/WS, token, port, WakeLock, atau URL — semua
 * itu dibuang. Yang tersisa cuma roda penggerak sesi:
 *
 *  - {@link SessionManager} diciptakan, dipulihkan dari disk, dan dijaga
 *    (startPersistThread) selama service hidup.
 *  - START_STICKY: kalau Android membunuh proses, service hidup lagi dan sesi
 *    dikembalikan dengan id/nama yang sama.
 *  - Konfigurasi dibaca dari file (lihat {@link Config}) — mengubah file tidak
 *    perlu build/install ulang.
 */
public class TermServerService extends Service {

    public static final String ACTION_STOP = "com.linuxbox.STOP";
    public static final String ACTION_STATE = "com.linuxbox.STATE";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_SESSIONS = "sessions";
    public static final String EXTRA_ERROR = "error";

    private static final String CHANNEL = "linuxbox-term";
    private static final int NOTIF_ID = 1;
    private static final String TAG = "TermSession";

    private static final AtomicReference<SessionManager> managerRef = new AtomicReference<>();
    private final AtomicBoolean starting = new AtomicBoolean(false);

    /** SessionManager milik layanan yang sedang berjalan, atau null. */
    public static SessionManager sessions() {
        return managerRef.get();
    }

    private final Object lifecycleLock = new Object();
    private boolean rootfsLease;
    private boolean destroyed;
    private Thread notifierThread;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Config.ensureExampleInternal(this);
        Config.ensureExampleOnExternal(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && (intent.getBooleanExtra("stop", false)
                || ACTION_STOP.equals(intent.getAction()))) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (managerRef.get() != null) {
            startForeground(NOTIF_ID, buildNotification("LinuxBox aktif"));
            return START_STICKY;
        }
        if (!starting.compareAndSet(false, true)) return START_STICKY;

        if (!rootfsLease) {
            if (!RootfsGuard.beginService()) {
                Toast.makeText(this, "Restore/perbaikan rootfs sedang berjalan. Coba lagi nanti.",
                        Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            rootfsLease = true;
        }

        startForeground(NOTIF_ID, buildNotification("Menghidupkan terminal..."));

        new Thread(() -> {
            SessionManager manager = null;
            try {
                manager = new SessionManager(this);
                manager.restore();
                manager.startPersistThread();
                synchronized (lifecycleLock) {
                    if (destroyed) { manager.closeAll(); return; }
                    managerRef.set(manager);
                    updateNotification("LinuxBox aktif");
                    broadcastState(true, null);
                    startNotifier();
                }
            } catch (Throwable t) {
                if (manager != null) manager.closeAll();
                Log.e(TAG, "gagal memulai layanan sesi", t);
                writeError(t);
                broadcastState(false, String.valueOf(t.getMessage()));
                new android.os.Handler(getMainLooper()).post(() ->
                        Toast.makeText(this, "Gagal: " + t.getMessage(), Toast.LENGTH_LONG).show());
                stopSelf();
            } finally {
                synchronized (lifecycleLock) {
                    starting.set(false);
                    if (destroyed) releaseRootfsLease();
                }
            }
        }, "tty-start").start();

        return START_STICKY;
    }

    /** Sinkronkan notifikasi & broadcast dengan jumlah sesi yang hidup. */
    private void startNotifier() {
        notifierThread = new Thread(() -> {
            int lastCount = -1;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    return;
                }
                SessionManager m = managerRef.get();
                if (m == null) return;
                int alive = m.aliveCount();
                int total = m.list().size();
                if (alive != lastCount || total != lastCount) {
                    lastCount = total;
                    updateNotification((alive + "/" + total + " sesi hidup"));
                    broadcastState(true, null);
                }
            }
        }, "tty-notifier");
        notifierThread.setDaemon(true);
        notifierThread.start();
    }

    private void broadcastState(boolean running, String error) {
        SessionManager m = managerRef.get();
        int sessions = m != null ? m.list().size() : 0;
        Intent i = new Intent(ACTION_STATE)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_SESSIONS, sessions)
                .putExtra(EXTRA_ERROR, error);
        sendBroadcast(i);
    }

    private void updateNotification(String content) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(content));
    }

    private void writeError(Throwable t) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "error.txt");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write((t + "\n").getBytes());
            for (StackTraceElement e : t.getStackTrace()) fos.write(("  at " + e + "\n").getBytes());
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification(String content) {
        Intent open = new Intent(this, TermuxTerminalActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, TermServerService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("LinuxBox aktif")
                .setContentText(content)
                .setContentIntent(openPi)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(0, "Stop", stopPi)
                .build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Terminal", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // jangan ikut mati saat aplikasi digeser dari recents: sesi tetap hidup
        super.onTaskRemoved(rootIntent);
    }

    private void releaseRootfsLease() {
        if (rootfsLease) { RootfsGuard.endService(); rootfsLease = false; }
    }

    @Override
    public void onDestroy() {
        synchronized (lifecycleLock) {
            destroyed = true;
            if (notifierThread != null) notifierThread.interrupt();
            SessionManager m = managerRef.getAndSet(null);
            if (m != null) m.closeAll();
            if (!starting.get()) releaseRootfsLease();
        }
        super.onDestroy();
    }
}