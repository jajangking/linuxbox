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

    /** Fallback kalau assets/distros.json tidak ada. */
    private static final Distro[] BUILTIN = {
            new Distro("alpine", "Alpine 3.24 (minirootfs, ringan)",
                    "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
                    "", 4L * 1024 * 1024),
            new Distro("ubuntu-2404", "Ubuntu 24.04 LTS (base)",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                    "", 29L * 1024 * 1024),
            new Distro("ubuntu-2604", "Ubuntu 26.04.1 LTS (base)",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz",
                    "", 33L * 1024 * 1024),
    };

    private DistroCatalog() {}

    public static List<Distro> load(Context ctx) {
        List<Distro> out = new ArrayList<>();
        try {
            String json = readAsset(ctx, ASSET);
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("distros");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", "");
                String url = o.optString("url", "");
                if (id.isEmpty() || url.isEmpty()) continue;
                out.add(new Distro(id, o.optString("label", id), url,
                        o.optString("sha256", ""), o.optLong("sizeBytes", 0)));
            }
        } catch (Exception ignored) {
            // asset tidak ada / JSON rusak -> pakai bawaan
        }
        if (out.isEmpty()) {
            for (Distro d : BUILTIN) out.add(d);
        }
        return out;
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
