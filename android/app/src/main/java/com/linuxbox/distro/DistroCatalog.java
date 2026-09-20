package com.linuxbox.distro;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Katalog distro yang bisa dipasang.
 *
 * Data utama ada di `assets/distros.json` (bisa diedit tanpa ubah kode). Kalau
 * asset-nya tidak ada/ rusak, dipakai daftar bawaan di bawah ini.
 *
 * Kolom per distro:
 *   id        -> slug, sekaligus nama direktori rootfs (files/rootfs-<id>)
 *   label     -> teks untuk spinner
 *   url       -> berkas rootfs .tar.gz (atau .tar)
 *   sha256    -> opsional; kalau diisi, unduhan diverifikasi (kosong = tanpa verifikasi)
 *   sizeBytes -> opsional; hanya untuk perkiraan progress
 */
public final class DistroCatalog {

    private static final String PREFS = "linuxbox";
    private static final String KEY_DISTRO = "distro";
    private static final String ASSET = "distros.json";

    public static final class Distro {
        public final String id;
        public final String label;
        public final String url;
        public final String sha256;
        public final long sizeBytes;

        Distro(String id, String label, String url, String sha256, long sizeBytes) {
            this.id = id;
            this.label = label;
            this.url = url;
            this.sha256 = sha256 == null ? "" : sha256;
            this.sizeBytes = sizeBytes;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Fallback kalau assets/distros.json tidak ada — DAN sumber hash cadangan
     * kalau aset di APK ternyata usang. Pernah kejadian: kode Java sudah baru
     * tapi asetnya belum ikut ter-refresh, jadi unduhan jalan tanpa verifikasi
     * sha256. Karena itu BUILTIN juga memuat hash asli upstream.
     */
    private static final Distro[] BUILTIN = {
            new Distro("alpine", "Alpine 3.24.2 (minirootfs, ringan)",
                    // url kosong -> pakai rootfs.tar.gz yang dibundel di assets (offline aman)
                    "",
                    "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773",
                    4L * 1024 * 1024),
            new Distro("ubuntu-2404", "Ubuntu 24.04 LTS (base)",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                    "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
                    29_936_675L),
            new Distro("ubuntu-2604", "Ubuntu 26.04.1 LTS (base)",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz",
                    "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd",
                    33L * 1024 * 1024),
    };

    /** Dari mana katalog terakhir dibaca — supaya bisa ditulis ke log. */
    private static volatile String lastSource = "bawaan Java";

    private DistroCatalog() {}

    public static List<Distro> load(Context ctx) {
        // Mulai dari bawaan, lalu timpa dengan isi aset. Kalau entri aset
        // kosong (aset usang), nilai bawaan tetap dipakai — jadi sha256 tidak
        // pernah hilang cuma karena berkas aset belum ikut terbarukan.
        java.util.LinkedHashMap<String, Distro> map = new java.util.LinkedHashMap<>();
        for (Distro d : BUILTIN) map.put(d.id, d);

        try {
            JSONObject root = new JSONObject(readAsset(ctx, ASSET));
            JSONArray arr = root.getJSONArray("distros");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                Distro b = map.get(id);
                String url = o.optString("url", "");
                if (url.isEmpty() && b != null) url = b.url;
                if (url.isEmpty()) continue;   // tanpa URL & tanpa bawaan: abaikan

                String sha = o.optString("sha256", "");
                if (sha.isEmpty() && b != null) sha = b.sha256;
                long size = o.optLong("sizeBytes", 0);
                if (size <= 0 && b != null) size = b.sizeBytes;

                map.put(id, new Distro(id, o.optString("label", b != null ? b.label : id),
                        url, sha, size));
            }
            lastSource = ASSET;
        } catch (Exception ignored) {
            lastSource = "bawaan Java (assets/" + ASSET + " tidak terbaca)";
        }
        return new ArrayList<>(map.values());
    }

    /**
     * Ringkasan katalog untuk log: sumber berkas, berapa entri yang punya
     * sha256, dan id mana yang tidak punya. Dipakai untuk mendiagnosis
     * "kok verifikasi hash tidak jalan".
     */
    public static String describe(Context ctx) {
        List<Distro> all = load(ctx);
        int withSha = 0;
        StringBuilder tanpa = new StringBuilder();
        for (Distro d : all) {
            if (d.sha256 != null && !d.sha256.trim().isEmpty()) {
                withSha++;
            } else {
                tanpa.append(' ').append(d.id);
            }
        }
        return all.size() + " entri dari " + lastSource + ", sha256 " + withSha + "/" + all.size()
                + (tanpa.length() > 0 ? " (tanpa hash:" + tanpa + ")" : "");
    }

    public static String defaultId(Context ctx) {
        try {
            JSONObject root = new JSONObject(readAsset(ctx, ASSET));
            String d = root.optString("default", "");
            if (!d.isEmpty()) return d;
        } catch (Exception ignored) {
        }
        return BUILTIN[0].id;
    }

    public static Distro find(Context ctx, String id) {
        if (id != null) {
            for (Distro d : load(ctx)) {
                if (d.id.equals(id)) return d;
            }
        }
        return null;
    }

    public static Distro current(Context ctx) {
        Distro d = find(ctx, activeId(ctx));
        return d != null ? d : load(ctx).get(0);
    }

    /** id distro yang sedang dipakai (tersimpan di SharedPreferences). */
    public static String activeId(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString(KEY_DISTRO, null);
        if (id == null || find(ctx, id) == null) {
            id = defaultId(ctx);
            p.edit().putString(KEY_DISTRO, id).apply();
        }
        return id;
    }

    public static void setActiveId(Context ctx, String id) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_DISTRO, id).apply();
    }

    /** Commit identitas + hint sesi sebagai satu pembaruan setelah rootfs ditempatkan. */
    public static boolean activateRestored(Context ctx, String id) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DISTRO, id).putString("restored_session_distro", id).commit();
    }

    public static String restoredSessionDistro(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("restored_session_distro", null);
    }

    public static void clearRestoredSessionDistro(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("restored_session_distro").apply();
    }

    /** true kalau rootfs distro ini sudah terpasang. */
    public static boolean isInstalled(Context ctx, String id) {
        File root = new File(ctx.getFilesDir(), "rootfs-" + id);
        return new File(root, "etc").isDirectory() || new File(root, "bin/sh").isFile();
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        java.io.InputStream is = ctx.getAssets().open(name);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
