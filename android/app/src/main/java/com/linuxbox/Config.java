package com.linuxbox;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Konfigurasi yang bisa diubah TANPA build/install ulang.
 *
 * Cara pakai:
 *  - edit file <code>/sdcard/linuxbox/alpine.conf</code> (yang paling mudah
 *    dijangkau dari file manager), atau
 *  - edit <code>&lt;filesDir&gt;/alpine.conf</code> (data aplikasi, via
 *    adb/run-as), atau
 *  - biarkan kosong: dipakai nilai bawaan yang setara perilaku LinuxBox lama.
 *
 * Format: <code>kunci=nilai</code>, satu per baris. Baris diawali '#' = komentar.
 * Ada tiga macam kunci:
 *  - kunci polos        : font_size, sessions, extra_keys, dll.
 *  - <code>env.NAMA</code> : variabel lingkungan sesi (env.PATH, env.TERM, ...)
 *  - <code>bind.HOST</code>: bind tambahan; nilainya path tamu.
 *    Contoh: <code>bind./sdcard=/sdcard</code> sama seperti bawaan; tambahkan
 *    <code>bind./storage/emulated/0/Download=/download</code> dst.
 */
public final class Config {

    private static final String TAG = "LinuxBoxConfig";
    private static final String EXTERNAL_DIR = "linuxbox";
    private static final String EXTERNAL_FILE = "alpine.conf";

    public static final String KEY_FONT = "font_size";
    public static final String KEY_SESSIONS = "sessions";
    public static final String KEY_EXTRA_KEYS = "extra_keys";
    public static final String KEY_BACKGROUND = "background";
    public static final String KEY_FOREGROUND = "foreground";
    public static final String KEY_CURSOR = "cursor";
    public static final String KEY_SHELL = "shell";

    private static final String DEFAULT_EXTRA_KEYS = "CTRL ALT ESC TAB / - | ◀ ▶ ▲ ▼";

    private final Map<String, String> values = new HashMap<>();

    private Config(Map<String, String> values) {
        this.values.putAll(values);
    }

    // ------------------------------------------------------------ muat

    /** Baca config: /sdcard/linuxbox/alpine.conf menimpa filesDir/alpine.conf. */
    public static Config get(Context ctx) {
        Map<String, String> map = new HashMap<>();
        // filesDir dulu = "template awal". /sdcard diparse terakhir sehingga
        // edit yang dilakukan user di luar menang atas nilai bawaan contoh.
        parseFile(read(internalFile(ctx)), map);
        parseFile(read(primaryFile()), map);
        return new Config(map);
    }

    /** Salin contoh config ke /sdcard/linuxbox/alpine.conf kalau belum ada. */
    public static void ensureExampleOnExternal(Context ctx) {
        File dir = externalDir();
        if (dir == null) return;
        File f = new File(dir, EXTERNAL_FILE);
        if (f.isFile()) return;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            writeExample(f);
        } catch (Exception e) {
            Log.w(TAG, "tidak bisa menulis contoh config di " + f, e);
        }
    }

    /** Salin contoh config ke filesDir/alpine.conf (jalur cadangan). */
    public static void ensureExampleInternal(Context ctx) {
        File f = internalFile(ctx);
        if (f.isFile()) return;
        try {
            writeExample(f);
        } catch (Exception e) {
            Log.w(TAG, "tidak bisa menulis contoh config internal", e);
        }
    }

    private static File externalDir() {
        File ext = android.os.Environment.getExternalStorageDirectory();
        if (ext == null) return null;
        return new File(ext, EXTERNAL_DIR);
    }

    private static File primaryFile() {
        File dir = externalDir();
        return dir != null ? new File(dir, EXTERNAL_FILE) : null;
    }

    private static File internalFile(Context ctx) {
        return new File(ctx.getFilesDir(), EXTERNAL_FILE);
    }

    private static String read(File f) {
        if (f == null || !f.isFile() || f.length() > 1 << 20) return null;
        try {
            byte[] data = new byte[(int) f.length()];
            InputStream in = new java.io.FileInputStream(f);
            try {
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
                return new String(data, 0, off, java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void parseFile(String content, Map<String, String> map) {
        if (content == null) return;
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) map.put(key, value);
        }
    }

    private static void writeExample(File f) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write((exampleConfig() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    /** Teks contoh — dipakai kalau file config belum dibuat. */
    public static String exampleConfig() {
        return "# LinuxBox — ubah di sini, TANPA rebuild/install.\n"
                + "# Setiap baris: kunci=nilai. '#' = komentar.\n"
                + "# Setelah mengubah, tutup & buka lagi terminal untuk menerapkan.\n"
                + "\n"
                + "# ---- tampilan terminal ----\n"
                + "# font_size    : ukuran font terminal (dp/pixel density).\n"
                + "font_size=12\n"
                + "# Warna (hex). kalau kosong dipakai default gelap.\n"
                + "# background=#000000\n"
                + "# foreground=#ffffff\n"
                + "# cursor=#00ff00\n"
                + "# color0 = #000000\n"
                + "\n"
                + "# ---- sesi ----\n"
                + "# sessions   : jumlah tab terminal yang dibuat otomatis saat\n"
                + "#              pertama kali (baru dibuat kalau belum ada).\n"
                + "sessions=1\n"
                + "# shell      : path shell di dalam rootfs (default otomatis).\n"
                + "# shell=/bin/ash\n"
                + "\n"
                + "# ---- baris tombol bawah (extra keys) ----\n"
                + "# CTRL dan ALT = tombol kunci (latch).\n"
                + "# ESC/TAB/LEFT/RIGHT/UP/DOWN = tombol kode.\n"
                + "# satu karakter saja (mis. / - |...) = tombol ketik.\n"
                + "extra_keys=CTRL ALT ESC TAB / - | ◀ ▶ ▲ ▼\n"
                + "\n"
                + "# ---- lingkungan shell ----\n"
                + "# env.NAMA   : variabel lingkungan (boleh banyak baris).\n"
                + "# env.TERM=screen-256color\n"
                + "# env.LANG=C.UTF-8\n"
                + "# env.PS1=localhost:/# \n"
                + "\n"
                + "# ---- bind tambahan (host:tamu) ----\n"
                + "# bind.HOST  : mount path host ke path tamu.\n"
                + "# bind./storage/emulated/0/Download=/download\n"
                + "# bind./storage/emulated/0/Documents=/documents\n";
    }

    // ------------------------------------------------------------ akses

    public String get(String key, String fallback) {
        String v = values.get(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }

    public String get(String key) {
        return values.get(key);
    }

    public int getInt(String key, int fallback) {
        String v = values.get(key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Banyak tab sesi otomatis saat pertama kali (>=1). */
    public int sessions() {
        int n = getInt(KEY_SESSIONS, 1);
        return Math.max(1, Math.min(n, com.linuxbox.web.SessionManager.MAX_SESSIONS));
    }

    /** Ukuran font terminal dalam dp. */
    public int fontSizeDp() {
        int n = getInt(KEY_FONT, 12);
        return Math.max(8, Math.min(n, 32));
    }

    /** Path shell override di dalam rootfs, atau null = otomatis. */
    public String shell() {
        return get(KEY_SHELL);
    }

    /** Warna latar terminal; null = default hitam. */
    public String background() {
        return get(KEY_BACKGROUND);
    }

    /**
     * Daftar tombol di baris bawah. Token: CTRL, ALT, ESC, TAB, LEFT/RIGHT/UP/
     * DOWN/◀▶▲▼, atau satu karakter ketik.
     */
    public List<String> extraKeys() {
        String raw = get(KEY_EXTRA_KEYS, DEFAULT_EXTRA_KEYS);
        List<String> out = new ArrayList<>();
        for (String t : raw.split("[ \\t]+")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Bind tambahan format "host:tamu" dari kunci bind.*. */
    public List<String> binds() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().startsWith("bind.")) {
                String b = e.getValue().trim();
                if (!b.isEmpty()) out.add(b + ":" + e.getKey().substring(5));
            }
        }
        return out;
    }

    /** Override lingkungan: kunci env.* dikembalikan sebagai map NAMA -> nilai. */
    public Map<String, String> environment() {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getKey().startsWith("env.")) {
                String name = e.getKey().substring(4);
                if (!name.isEmpty()) out.put(name, e.getValue());
            }
        }
        return out;
    }

    /** Pengaturan warna tema terminal (bukan null kalau ada setidaknya satu). */
    public Map<String, String> theme() {
        Map<String, String> out = new HashMap<>();
        if (values.containsKey(KEY_BACKGROUND)) out.put("background", values.get(KEY_BACKGROUND));
        if (values.containsKey(KEY_FOREGROUND)) out.put("foreground", values.get(KEY_FOREGROUND));
        if (values.containsKey(KEY_CURSOR)) out.put("cursor", values.get(KEY_CURSOR));
        for (Map.Entry<String, String> e : values.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("color") && k.length() == 6) {
                try {
                    int idx = Integer.parseInt(k.substring(5));
                    if (idx >= 0 && idx <= 15) out.put(k, e.getValue());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Path file config luar (/sdcard), atau "(tidak ada)" kalau storage tak tersedia. */
    public static String externalPathText() {
        File dir = externalDir();
        return dir != null
                ? new File(dir, EXTERNAL_FILE).getAbsolutePath()
                : "(tidak tersedia)";
    }

    /** Path file config internal (data aplikasi). */
    public static String internalPathText(Context ctx) {
        return internalFile(ctx).getAbsolutePath();
    }

    /** Ringkasan config efektif (nilai yang benar-benar dipakai) untuk ditampilkan. */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("font_size  = ").append(fontSizeDp()).append("\n");
        sb.append("sessions   = ").append(sessions()).append("\n");
        sb.append("extra_keys = ").append(String.join(" ", extraKeys())).append("\n");
        sb.append("shell      = ").append(shell() == null ? "(otomatis)" : shell()).append("\n");

        String bg = background();
        if (bg != null) sb.append("background = ").append(bg).append("\n");
        String fg = get(KEY_FOREGROUND);
        if (fg != null) sb.append("foreground = ").append(fg).append("\n");
        String cur = get(KEY_CURSOR);
        if (cur != null) sb.append("cursor     = ").append(cur).append("\n");

        List<String> colors = new ArrayList<>(theme().keySet());
        Collections.sort(colors);
        for (String k : colors) {
            if (k.startsWith("color")) sb.append(k).append("     = ").append(values.get(k)).append("\n");
        }

        List<String> envs = new ArrayList<>(environment().keySet());
        Collections.sort(envs);
        for (String name : envs) sb.append("env.").append(name).append(" = ").append(values.get("env." + name)).append("\n");

        for (String b : binds()) sb.append("bind       = ").append(b).append("\n");

        if (sb.length() == 0) sb.append("(semua nilai bawaan)\n");
        return sb.toString();
    }

    public static List<String> emptyList() {
        return Collections.emptyList();
    }
}