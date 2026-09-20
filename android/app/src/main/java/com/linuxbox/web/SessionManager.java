package com.linuxbox.web;

import android.content.Context;

import com.linuxbox.distro.DistroCatalog;
import com.linuxbox.distro.ProotSession;
import com.linuxbox.distro.PtyHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mengelola banyak sesi PTY sekaligus (multi-tab terminal).
 *
 * Tiga hal yang membuat sesi "tahan banting":
 *
 * 1. **Sesi hidup mandiri.** Setiap sesi punya supervisor sendiri: kalau shell
 *    keluar (ketik `exit`, crash, atau -0 yang bikin program keluar), sesi
 *    dihidupkan lagi dengan backoff — tanpa menunggu ada browser yang menonton.
 * 2. **Sesi tetap jalan saat tidak ada yang melihat.** Client boleh putus kapan
 *    saja (HP dikunci, ganti Wi-Fi, browser ditutup); proses guest di dalamnya
 *    tidak ikut mati. Saat client connect ulang, scrollback diputar ulang.
 * 3. **Keadaan sesi disimpan ke disk.** Daftar sesi (id, nama, ukuran) dan
 *    scrollback terakhir ditulis berkala. Kalau Android membunuh service lalu
 *    service hidup lagi, sesi dikembalikan dengan id/nama yang sama.
 */
public final class SessionManager {

    public static final int MAX_SESSIONS = 8;
    static final int SCROLLBACK_MAX = 128 * 1024;
    static final int PERSIST_SCROLLBACK_MAX = 64 * 1024;

    private static final long RESTART_MIN_MS = 1500L;
    private static final long RESTART_MAX_MS = 15000L;
    private static final long PERSIST_INTERVAL_MS = 20000L;

    /** Tempat server menaruh data yang dikirim ke browser. */
    public interface Sink {
        void sendBinary(byte[] data, int len);

        void sendText(String text);

        boolean isControl();
    }

    /** Satu tab terminal: proses PTY + scrollback + siapa saja yang menonton. */
    public static final class Session {
        public final String id;
        /** Distro yang dipakai sesi ini; tiap sesi boleh beda (tab alpine + tab ubuntu). */
        public final String distroId;
        public final long createdAt;
        public volatile String name;
        public volatile int rows = 24;
        public volatile int cols = 80;
        public volatile boolean alive;
        public volatile String lastError;
        public volatile long restarts;
        public volatile PtyHelper pty;

        final Scrollback scrollback = new Scrollback(SCROLLBACK_MAX);
        /** Penonton (client websocket) yang sedang menempel ke sesi ini. */
        final List<Sink> viewers = Collections.synchronizedList(new ArrayList<Sink>());
        final AtomicBoolean dying = new AtomicBoolean(false);
        /** true kalau ada output baru yang belum ditulis ke disk. */
        volatile boolean scrollDirty;

        Session(String id, String name, String distroId, long createdAt) {
            this.id = id;
            this.name = name;
            this.distroId = distroId;
            this.createdAt = createdAt;
        }

        public String displayName() {
            return name == null || name.isEmpty() ? id : name;
        }
    }

    private final Context ctx;
    private final Map<String, Session> sessions =
            Collections.synchronizedMap(new LinkedHashMap<String, Session>());
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger seq = new AtomicInteger(1);
    private final File dir;
    private final File metaFile;

    public SessionManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.dir = new File(ctx.getFilesDir(), "sessions");
        this.metaFile = new File(ctx.getFilesDir(), "sessions.json");
        if (!dir.isDirectory()) dir.mkdirs();
    }

    // ---------- daftar sesi ----------

    public List<Session> list() {
        synchronized (sessions) {
            return new ArrayList<>(sessions.values());
        }
    }

    public Session get(String id) {
        if (id == null) return null;
        return sessions.get(id);
    }

    /** Sesi pertama yang masih hidup (fallback kalau client tidak menyebut id). */
    public Session first() {
        List<Session> all = list();
        for (Session s : all) {
            if (s.alive) return s;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    public int aliveCount() {
        int n = 0;
        for (Session s : list()) if (s.alive) n++;
        return n;
    }

    /**
     * Pulihkan sesi yang tersimpan, lalu jalankan semuanya. Kalau belum pernah
     * ada sesi, buat satu supaya user langsung mendapat prompt.
     */
    public void restore() {
        String json = readFile(metaFile);
        if (json != null) {
            int idx = json.indexOf("\"sessions\"");
            if (idx >= 0) {
                int open = json.indexOf('[', idx);
                int close = json.indexOf(']', open + 1);
                if (open > 0 && close > open) {
                    for (String part : json.substring(open + 1, close).split("\\},\\{")) {
                        String obj = part.replace('{', ' ').replace('}', ' ');
                        String id = jsonString(obj, "id");
                        if (id == null || id.isEmpty()) continue;
                        String name = sanitize(jsonString(obj, "name"), "shell");
                        long created = jsonLong(obj, "created");
                        String distro = jsonString(obj, "distro");
                        if (distro == null || distro.isEmpty()
                                || !DistroCatalog.isInstalled(this.ctx, distro)) {
                            distro = DistroCatalog.activeId(this.ctx);
                        }
                        Session s = new Session(id, name, distro,
                                created > 0 ? created : System.currentTimeMillis());
                        int rows = (int) jsonLong(obj, "rows");
                        int cols = (int) jsonLong(obj, "cols");
                        if (rows > 0) s.rows = rows;
                        if (cols > 0) s.cols = cols;
                        s.scrollback.loadFrom(new File(dir, id + ".scroll"));
                        sessions.put(id, s);
                        int n = numberSuffix(id);
                        if (n + 1 > seq.get()) seq.set(n + 1);
                    }
                }
            }
        }
        for (Session s : list()) startSupervisor(s);
        if (sessions.isEmpty()) {
            try {
                create("shell 1");
            } catch (IOException ignored) {
            }
        }
    }

    public Session create(String name) throws IOException {
        return create(name, null);
    }

    /**
     * @param name     nama tab; null/kosong -> "shell <n>"
     * @param distroId distro untuk sesi ini; null -> distro aktif. Harus sudah
     *                 terpasang, kalau tidak dilempar IOException.
     */
    public Session create(String name, String distroId) throws IOException {
        synchronized (sessions) {
            if (sessions.size() >= MAX_SESSIONS) {
                throw new IOException("maksimal " + MAX_SESSIONS + " sesi; tutup salah satu dulu");
            }
        }
        String distro = (distroId == null || distroId.isEmpty())
                ? DistroCatalog.activeId(ctx) : distroId;
        if (!DistroCatalog.isInstalled(ctx, distro)) {
            throw new IOException("distro " + distro + " belum terpasang");
        }
        String id = "s" + seq.getAndIncrement();
        while (sessions.containsKey(id)) id = "s" + seq.getAndIncrement();
        Session s = new Session(id, sanitize(name, id), distro, System.currentTimeMillis());
        sessions.put(id, s);
        startSupervisor(s);
        persistAsync();
        return s;
    }

    /** Tutup sesi: hentikan PTY, buang scrollback, hapus dari daftar. */
    public boolean kill(String id) {
        Session s = sessions.remove(id);
        if (s == null) return false;
        s.dying.set(true);
        PtyHelper p = s.pty;
        if (p != null) {
            try { p.close(); } catch (Exception ignored) {}
        }
        s.pty = null;
        s.alive = false;
        new File(dir, id + ".scroll").delete();
        synchronized (s.viewers) {
            s.viewers.clear();
        }
        persistAsync();
        return true;
    }

    public void closeAll() {
        running.set(false);
        for (Session s : list()) {
            s.dying.set(true);
            PtyHelper p = s.pty;
            if (p != null) {
                try { p.close(); } catch (Exception ignored) {}
            }
            s.pty = null;
            s.alive = false;
        }
        persist();
    }

    // ---------- lampiran client ----------

    public void attach(Session s, Sink sink) {
        synchronized (s.viewers) {
            if (!s.viewers.contains(sink)) s.viewers.add(sink);
        }
    }

    public void detach(Session s, Sink sink) {
        synchronized (s.viewers) {
            s.viewers.remove(sink);
        }
    }

    // ---------- I/O ----------

    public byte[] snapshot(Session s) {
        return s.scrollback.snapshot();
    }

    /** Ketikan user -> stdin PTY sesi ini. */
    public void write(Session s, byte[] data) {
        PtyHelper p = s.pty;
        if (p == null || !p.isAlive()) {
            notice(s, "\r\n[sesi belum jalan, menunggu shell hidup...]\r\n");
            return;
        }
        try {
            p.getInput().write(data);
            p.getInput().flush();
        } catch (IOException ignored) {
        }
    }

    public void resize(Session s, int rows, int cols) {
        if (rows <= 0 || cols <= 0) return;
        s.rows = rows;
        s.cols = cols;
        PtyHelper p = s.pty;
        if (p != null) p.resize(rows, cols);
    }

    /** Output PTY -> scrollback + semua penonton sesi ini (frame BINARY). */
    public void broadcast(Session s, byte[] data, int len) {
        if (len <= 0) return;
        s.scrollback.add(data, len);
        s.scrollDirty = true;
        List<Sink> copy;
        synchronized (s.viewers) {
            copy = new ArrayList<>(s.viewers);
        }
        for (Sink sink : copy) {
            if (sink.isControl()) continue;
            sink.sendBinary(data, len);
        }
    }

    /** Pesan status ke sesi (mis. "sesi dimulai ulang") — teks, bukan data PTY. */
    public void notice(Session s, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        s.scrollback.add(data, data.length);
        s.scrollDirty = true;
        List<Sink> copy;
        synchronized (s.viewers) {
            copy = new ArrayList<>(s.viewers);
        }
        for (Sink sink : copy) {
            if (sink.isControl()) continue;
            sink.sendBinary(data, data.length);
        }
    }

    /** Minta browser mengirim ulang ukuran terminalnya (setelah shell restart). */
    public void requestSize(Session s) {
        List<Sink> copy;
        synchronized (s.viewers) {
            copy = new ArrayList<>(s.viewers);
        }
        for (Sink sink : copy) {
            if (sink.isControl()) sink.sendText("{\"type\":\"need-size\"}");
        }
    }

    // ---------- supervisor ----------

    private void startSupervisor(Session s) {
        Thread t = new Thread(() -> supervise(s), "sess-" + s.id);
        t.setDaemon(true);
        t.start();
    }

    private void supervise(Session s) {
        long delay = RESTART_MIN_MS;
        while (running.get() && !s.dying.get()) {
            long startedAt = System.currentTimeMillis();
            PtyHelper p;
            try {
                p = openSession(s);
            } catch (Exception e) {
                s.lastError = String.valueOf(e.getMessage());
                s.alive = false;
                notice(s, "\r\n[gagal memulai sesi: " + s.lastError + "]\r\n"
                        + "[mencoba lagi " + (delay / 1000) + "s]\r\n");
                sleepQuietly(delay);
                delay = Math.min(delay * 2, RESTART_MAX_MS);
                continue;
            }
            s.pty = p;
            s.alive = true;
            s.lastError = null;
            notice(s, "\r\n[sesi baru dimulai: " + s.distroId + " "
                    + ProotSession.detectShell(ProotSession.rootfsDir(ctx.getFilesDir(), s.distroId))
                    + "]\r\n");
            requestSize(s);

            pump(s, p);

            s.alive = false;
            s.restarts++;
            s.pty = null;
            try { p.close(); } catch (Exception ignored) {}

            if (!running.get() || s.dying.get()) break;

            // Shell yang mati <3 detik setelah start = kegagalan, jangan spam.
            if (System.currentTimeMillis() - startedAt < 3000L) {
                delay = Math.min(delay * 2, RESTART_MAX_MS);
            } else {
                delay = RESTART_MIN_MS;
            }
            notice(s, "\r\n[sesi berakhir, memulai ulang...]\r\n");
            sleepQuietly(delay);
        }
        s.alive = false;
    }

    /**
     * Bind tambahan untuk guest. /sdcard hanya dibind kalau pengguna sudah
     * memberikan izin penyimpanan (tanpa itu proot akan gagal membacanya dan
     * perintah di dalam guest hanya melihat direktori kosong).
     */
    static java.util.List<String> extraBinds(Context ctx) {
        java.util.List<String> binds = new java.util.ArrayList<>();
        if (hasStoragePermission(ctx)) {
            java.io.File ext = android.os.Environment.getExternalStorageDirectory();
            if (ext != null && ext.isDirectory()) {
                binds.add(ext.getAbsolutePath());
                binds.add("/sdcard");
            }
        }
        return binds;
    }

    /** true kalau izin baca/tulis penyimpanan bersama sudah diberikan. */
    public static boolean hasStoragePermission(Context ctx) {
        try {
            return ctx.checkSelfPermission(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private PtyHelper openSession(Session s) throws Exception {
        java.io.File filesDir = ctx.getFilesDir();
        String nativeLibDir = ProotSession.nativeLibraryDir(ctx);
        java.io.File rootfs = ProotSession.rootfsDir(filesDir, s.distroId);
        return PtyHelper.start(s.id, filesDir, nativeLibDir, rootfs,
                ProotSession.buildCommand(nativeLibDir, rootfs, extraBinds(ctx)),
                ProotSession.environment(filesDir, nativeLibDir, rootfs));
    }

    private void pump(Session s, PtyHelper p) {
        Thread errThread = new Thread(() -> pumpStream(s, p.getError(), 512), "sess-" + s.id + "-err");
        errThread.setDaemon(true);
        errThread.start();
        pumpStream(s, p.getOutput(), 8192);
    }

    private void pumpStream(Session s, InputStream in, int bufSize) {
        byte[] buf = new byte[bufSize];
        try {
            int n;
            while (running.get() && (n = in.read(buf)) >= 0) {
                if (n > 0) broadcast(s, buf, n);
            }
        } catch (IOException ignored) {
        }
    }

    // ---------- persistensi ----------

    /** Simpan daftar sesi + scrollback (terakhir PERSIST_SCROLLBACK_MAX byte). */
    public void persist() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"next\":").append(seq.get()).append(",\"sessions\":[");
        boolean first = true;
        for (Session s : list()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(s.id).append('"')
                    .append(",\"name\":\"").append(sanitize(s.name, s.id)).append('"')
                    .append(",\"rows\":").append(s.rows)
                    .append(",\"cols\":").append(s.cols)
                    .append(",\"distro\":\"").append(s.distroId).append('"')
                    .append(",\"created\":").append(s.createdAt)
                    .append('}');
        }
        sb.append("]}");
        writeFile(metaFile, sb.toString());

        // Simpan hanya 64 KB terakhir dan hanya kalau ada output baru:
        // menulis 8 sesi x 128 KB tiap 20 detik cuma menguras baterai & f2fs.
        for (Session s : list()) {
            File f = new File(dir, s.id + ".scroll");
            if (!s.scrollDirty && f.isFile()) continue;
            byte[] all = s.scrollback.snapshot();
            int from = Math.max(0, all.length - PERSIST_SCROLLBACK_MAX);
            int len = all.length - from;
            byte[] tail = new byte[len];
            System.arraycopy(all, from, tail, 0, len);
            writeBytes(f, tail);
            s.scrollDirty = false;
        }
    }

    private static void writeBytes(File f, byte[] data) {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(data);
            } finally {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (IOException ignored) {
        }
    }

    private void persistAsync() {
        new Thread(() -> persist(), "sess-persist").start();
    }

    /** Thread penyimpan berkala supaya scrollback tidak hilang saat service mati. */
    public void startPersistThread() {
        Thread t = new Thread(() -> {
            while (running.get()) {
                sleepQuietly(PERSIST_INTERVAL_MS);
                if (!running.get()) break;
                try {
                    persist();
                } catch (RuntimeException ignored) {
                }
            }
        }, "sess-persist-loop");
        t.setDaemon(true);
        t.start();
    }

    // ---------- util ----------

    /** JSON untuk endpoint /api/sessions. */
    public String sessionsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"max\":").append(MAX_SESSIONS).append(",\"sessions\":[");
        boolean first = true;
        for (Session s : list()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(s.id).append('"')
                    .append(",\"name\":\"").append(sanitize(s.displayName(), s.id)).append('"')
                    .append(",\"alive\":").append(s.alive)
                    .append(",\"clients\":").append(s.viewers.size())
                    .append(",\"rows\":").append(s.rows)
                    .append(",\"cols\":").append(s.cols)
                    .append(",\"restarts\":").append(s.restarts)
                    .append(",\"distro\":\"").append(s.distroId).append('"')
                    .append(",\"created\":").append(s.createdAt)
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    static String sanitize(String name, String fallback) {
        if (name == null) return fallback;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 32; i++) {
            char c = name.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? fallback : out;
    }

    private static int numberSuffix(String id) {
        int n = 0;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c >= '0' && c <= '9') n = n * 10 + (c - '0');
        }
        return n;
    }

    private static String jsonString(String obj, String key) {
        int at = obj.indexOf("\"" + key + "\"");
        if (at < 0) return null;
        int colon = obj.indexOf(':', at);
        if (colon < 0) return null;
        int open = obj.indexOf('"', colon + 1);
        if (open < 0) return null;
        int close = obj.indexOf('"', open + 1);
        if (close < 0) return null;
        return obj.substring(open + 1, close);
    }

    private static long jsonLong(String obj, String key) {
        int at = obj.indexOf("\"" + key + "\"");
        if (at < 0) return -1;
        int colon = obj.indexOf(':', at);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < obj.length() && (obj.charAt(i) == ' ' || obj.charAt(i) == '"')) i++;
        int start = i;
        while (i < obj.length() && Character.isDigit(obj.charAt(i))) i++;
        if (i == start) return -1;
        try {
            return Long.parseLong(obj.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String readFile(File f) {
        if (!f.isFile()) return null;
        try {
            byte[] data = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(data, 0, off, StandardCharsets.UTF_8);
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeFile(File f, String content) {
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (IOException ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
