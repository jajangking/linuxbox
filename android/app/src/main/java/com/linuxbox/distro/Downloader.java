package com.linuxbox.distro;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Unduh berkas rootfs langsung dari jaringan (streaming, tidak menaruh tar.gz
 * besar di APK): progress, hash SHA-256 sekali jalan, dan penulisan ke berkas
 * sementara .part supaya arsip setengah jadi tidak pernah dipakai.
 */
public final class Downloader {

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private Downloader() {}

    /**
     * @param expectedSha SHA-256 yang diharapkan; kosong/null = lewati verifikasi
     *                    (log akan mengingatkan).
     */
    public static void download(String url, File dest, long sizeHint, String expectedSha,
                                TaskLog log) throws IOException {
        boolean verify = expectedSha != null && !expectedSha.trim().isEmpty();
        if (log != null) {
            log.log("Mengunduh " + url);
            if (!verify) log.log("  (tanpa sha256: isi tidak diverifikasi)");
        }
        File part = new File(dest.getAbsolutePath() + ".part");
        if (part.exists()) part.delete();

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.connect();
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " untuk " + url);
            }
            long total = sizeHint > 0 ? sizeHint : Math.max(conn.getContentLengthLong(), 0);
            MessageDigest md = verify ? MessageDigest.getInstance("SHA-256") : null;

            long done = 0;
            long lastReport = 0;
            int lastPct = -1;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (md != null) md.update(buf, 0, n);
                    done += n;
                    if (log != null) {
                        if (total > 0) {
                            int pct = (int) Math.min(100, done * 100 / total);
                            if (pct / 5 != lastPct / 5) {
                                lastPct = pct;
                                log.log("  " + pct + "% (" + human(done) + " / " + human(total) + ")");
                            }
                        } else if (done - lastReport > (8L << 20)) {
                            lastReport = done;
                            log.log("  " + human(done) + " terunduh");
                        }
                    }
                }
            }

            if (total > 0 && done != total) {
                throw new IOException("unduhan tidak lengkap: " + done + " dari " + total + " byte");
            }

            if (md != null) {
                String got = hex(md.digest());
                if (!expectedSha.trim().equalsIgnoreCase(got)) {
                    throw new IOException("sha256 tidak cocok\n  dapat   : " + got
                            + "\n  diharap : " + expectedSha.trim());
                }
                if (log != null) log.log("  sha256 ok");
            }

            if (dest.exists() && !dest.delete()) {
                throw new IOException("tidak bisa menimpa " + dest.getAbsolutePath());
            }
            if (!part.renameTo(dest)) {
                throw new IOException("gagal memindahkan " + part.getName() + " -> " + dest.getName());
            }
            if (log != null) log.log("  selesai (" + human(done) + ")");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 tidak tersedia: " + e.getMessage());
        } finally {
            conn.disconnect();
            if (part.exists() && !dest.exists()) part.delete();
        }
    }

    public static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            try (InputStream in = new java.io.FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 tidak tersedia: " + e.getMessage());
        }
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
