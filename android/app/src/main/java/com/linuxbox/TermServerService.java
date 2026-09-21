package com.linuxbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import com.linuxbox.distro.RootfsGuard;
import com.linuxbox.web.TokenStore;
import com.linuxbox.web.WebTerminalServer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Menjalankan WebTerminalServer sebagai foreground service.
 *
 * Tahan banting:
 *  - START_STICKY: kalau Android membunuh prosesnya (tekanan memori, atau
 *    pengguna menggeser aplikasi dari recents), service dihidupkan lagi dan
 *    server dibuat ulang dari preferensi terakhir.
 *  - WakeLock + WifiLock: menjaga socket tetap hidup walau layar mati, supaya
 *    sesi tidak putus hanya karena HP dikunci.
 *  - Konfigurasi terakhir (port/LAN/token) disimpan, jadi restart otomatis
 *    memakai pengaturan yang sama dengan yang dipakai user terakhir kali.
 */
public class TermServerService extends Service {

    public static final String ACTION_STOP = "com.linuxbox.STOP";
    public static final String ACTION_STATE = "com.linuxbox.STATE";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_SESSIONS = "sessions";
    public static final String EXTRA_ERROR = "error";

    private static final String CHANNEL = "linuxbox-term";
    private static final int NOTIF_ID = 1;
    private static final String TAG = "TermServer";
    private static final String PREFS = "linuxbox";
    /** WakeLock dilepas kalau tidak ada klien selama 5 menit (hemat baterai). */
    private static final long IDLE_RELEASE_MS = 5 * 60 * 1000L;

    private static final AtomicReference<WebTerminalServer> serverRef = new AtomicReference<>();
    private final AtomicBoolean starting = new AtomicBoolean(false);

    /** SessionManager milik server yang sedang berjalan, atau null kalau server mati. */
    public static com.linuxbox.web.SessionManager sessions() {
        WebTerminalServer server = serverRef.get();
        return server != null ? server.sessions() : null;
    }

    private final Object lifecycleLock = new Object();
    private boolean rootfsLease;
    private boolean destroyed;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private Thread notifierThread;
    private volatile boolean lanMode;
    private volatile long idleSince = System.currentTimeMillis();

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && (intent.getBooleanExtra("stop", false)
                || ACTION_STOP.equals(intent.getAction()))) {
            prefs().edit().putBoolean("srv_wanted", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }

        int port;
        boolean lan;
        boolean auth;
        if (intent != null) {
            port = intent.getIntExtra("port", 8770);
            lan = intent.getBooleanExtra("lan", false);
            auth = intent.getBooleanExtra("auth", false);
            prefs().edit()
                    .putInt("srv_port", port)
                    .putBoolean("srv_lan", lan)
                    .putBoolean("srv_auth", auth)
                    .putBoolean("srv_wanted", true)
                    .apply();
        } else {
            // restart oleh sistem (START_STICKY): pakai konfigurasi terakhir
            SharedPreferences p = prefs();
            if (!p.getBoolean("srv_wanted", false)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            port = p.getInt("srv_port", 8770);
            lan = p.getBoolean("srv_lan", false);
            auth = p.getBoolean("srv_auth", false);
        }
        if (port < 1024 || port > 65535) port = 8770;

        if (!rootfsLease) {
            if (!RootfsGuard.beginService()) {
                prefs().edit().putBoolean("srv_wanted", false).apply();
                Toast.makeText(this, "Restore/perbaikan rootfs sedang berjalan. Start setelah selesai.", Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
            rootfsLease = true;
        }
        if (serverRef.get() == null && starting.compareAndSet(false, true)) {
            acquireLocks();
            final int finalPort = port;
            final boolean finalLan = lan;
            final boolean finalAuth = auth;

            lanMode = finalLan;
            startForeground(NOTIF_ID, buildNotification("memulai terminal...", "LinuxBox memulai terminal..."));

            new Thread(() -> {
                WebTerminalServer server = null;
                try {
                    server = new WebTerminalServer(this, finalPort, finalLan, tokenFor(finalLan, finalAuth));
                    server.start();
                    synchronized (lifecycleLock) {
                        if (destroyed) { server.stop(); return; }
                        serverRef.set(server);
                        String url = urlFor(server, finalLan, finalAuth);
                        updateNotification(url, "Terminal aktif: " + url);
                        broadcastState(true, url, server, null);
                        startNotifier(server, url);
                    }
                } catch (Throwable t) {
                    if (server != null) server.stop();
                    Log.e(TAG, "gagal start server", t);
                    // matikan flag "dinyalakan" supaya START_STICKY tidak
                    // menghidupkan service lagi berulang kali saat gagal total
                    prefs().edit().putBoolean("srv_wanted", false).apply();
                    writeError(t);
                    String msg = String.valueOf(t.getMessage());
                    releaseLocks();
                    broadcastState(false, null, null, msg);
                    new android.os.Handler(getMainLooper()).post(() ->
                            Toast.makeText(this, "Server gagal: " + msg, Toast.LENGTH_LONG).show());
                    stopSelf();
                } finally {
                    synchronized (lifecycleLock) {
                        starting.set(false);
                        if (destroyed) releaseRootfsLease();
                    }
                }
            }, "tty-start").start();
        }
        return START_STICKY;
    }

    /** Token hanya dipakai kalau terbuka ke LAN atau diminta lewat opsi Token. */
    private String tokenFor(boolean lan, boolean auth) {
        return (lan || auth) ? TokenStore.getOrCreate(this) : null;
    }

    private String urlFor(WebTerminalServer server, boolean lan, boolean auth) {
        String token = (lan || auth) ? TokenStore.getOrCreate(this) : null;
        String host = lan ? lanHost() : "127.0.0.1";
        return "http://" + host + ":" + server.getBoundPort() + "/"
                + (token != null ? "?token=" + token : "");
    }

    /** Perbarui notifikasi berkala (jumlah sesi bisa berubah tanpa sepengetahuan kita). */
    private void startNotifier(WebTerminalServer server, String url) {
        notifierThread = new Thread(() -> {
            int lastCount = -1;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    return;
                }
                if (serverRef.get() != server) return;
                int alive = server.sessions().aliveCount();
                int total = server.sessions().list().size();

                // Hemat baterai: WakeLock/WifiLock hanya perlu selama ada yang
                // memakai terminal atau server dibuka ke LAN. Sesinya sendiri
                // tetap hidup; cuma CPU yang boleh tidur saat layar mati.
                if (server.clientCount() > 0 || lanMode) {
                    idleSince = System.currentTimeMillis();
                    acquireLocks();
                } else if (System.currentTimeMillis() - idleSince > IDLE_RELEASE_MS) {
                    releaseLocks();
                }

                if (alive == lastCount) continue;
                lastCount = alive;
                updateNotification(url, alive + "/" + total + " sesi hidup — " + url);
                broadcastState(true, url, server, null);
            }
        }, "tty-notifier");
        notifierThread.setDaemon(true);
        notifierThread.start();
    }

    private void broadcastState(boolean running, String url, WebTerminalServer server, String error) {
        Intent i = new Intent(ACTION_STATE)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_SESSIONS, server != null ? server.sessions().list().size() : 0)
                .putExtra(EXTRA_ERROR, error);
        sendBroadcast(i);
    }

    private void updateNotification(String url, String content) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(url, content));
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

    /** Alamat IP Wi-Fi/LAN (tanpa permission) untuk URL mode LAN. */
    private String lanHost() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()
                            && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private Notification buildNotification(String url, String content) {
        Intent open = new Intent(this, WebViewActivity.class).putExtra("url", url);
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
                CHANNEL, "Terminal Server", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linuxbox:term");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "linuxbox:term");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
        try {
            if (wifiLock != null) wifiLock.release();
        } catch (Exception ignored) {
        }
        wifiLock = null;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
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
            WebTerminalServer s = serverRef.getAndSet(null);
            if (s != null) s.stop();
            // Jika Start masih berjalan, thread-nya menutup server hasil Start
            // dulu sebelum melepas lease. Stop tidak boleh balapan dengan restore.
            if (!starting.get()) releaseRootfsLease();
        }
        releaseLocks();
        broadcastState(false, null, null, null);
        super.onDestroy();
    }
}
