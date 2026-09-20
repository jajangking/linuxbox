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
 * besar di APK) dengan tiga perlindungan yang dulu tidak ada:
 *
 * <ol>
 *   <li><b>Lanjutkan unduhan yang putus</b> lewat header {@code Range}: berkas
 *       setengah jadi disimpan sebagai {@code .part} dan dipakai lagi pada
 *       percobaan berikutnya, jadi unduhan 30 MB tidak mulai dari nol cuma
 *       karena sinyal turun di detik terakhir.</li>
 *   <li><b>Coba lagi sendiri</b> dengan jeda mengembang (1s, 2s, 4s … 30s),
 *       maksimal {@link #MAX_ATTEMPTS} kali.</li>
 *   <li><b>Verifikasi SHA-256</b> terhadap nilai di {@code distros.json}.
 *       Kalau tidak cocok, berkas dibuang — rootfs yang rusak/berubah tidak
 *       pernah sampai ke tahap ekstraksi.</li>
 * </ol>
 */
public final class Downloader {

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** Berapa kali mencoba lagi kalau koneksi putus di tengah. */
    private static final int MAX_ATTEMPTS = 6;
    /** Jeda awal sebelum mencoba lagi; mengembang 1s -> 2s -> 4s ... maks 30s. */
    private static final long RETRY_BASE_MS = 1_000L;
    private static final long RETRY_MAX_MS = 30_000L;

    private Downloader() {}

    /** Isi berkas ternyata bukan yang diharapkan — penanda supaya tidak diulang. */
    private static final class HashMismatch extends IOException {
        HashMismatch(String msg) { super(msg); }
    }

    /**
     * Kegagalan yang tidak akan membaik dengan mencoba lagi: HTTP 4xx selain
     * 408 (timeout), 425 (terlalu dini) dan 429 (kebanyakan permintaan).
     * Tanpa penanda ini, URL yang salah bikin aplikasi menunggu 31 detik untuk
     * lima percobaan yang ujung-ujungnya gagal juga.
     */
    private static final class HttpError extends IOException {
        HttpError(String msg) { super(msg); }
    }

    /**
     * @param expectedSha SHA-256 yang diharapkan; kosong/null = lewati verifikasi
     *                    (log akan mengingatkan).
     */
    public static void download(String url, File dest, long sizeHint, String expectedSha,
                                TaskLog log) throws IOException {
        boolean verify = expectedSha != null && !expectedSha.trim().isEmpty();
        File part = new File(dest.getAbsolutePath() + ".part");
        if (log != null) {
            log.log("Mengunduh " + url);
            if (verify) {
                log.log("  sha256 yang diharapkan: " + expectedSha.trim().toLowerCase());
            } else {
                log.log("  (tanpa sha256: isi tidak diverifikasi)");
            }
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long resumeFrom = part.length();
            try {
                attemptDownload(url, part, sizeHint, expectedSha, verify, log, attempt);
                promote(part, dest, log);
                return;
            } catch (HashMismatch e) {
                // Isi salah: buang .part. Kalau tadi melanjutkan unduhan lama,
                // coba sekali lagi dari nol — bisa saja sisa berkas itu yang
                // rusak, bukan servernya.
                part.delete();
                if (resumeFrom == 0 || attempt >= MAX_ATTEMPTS) throw e;
                if (log != null) {
                    log.log("  isi tidak cocok, ulang dari awal tanpa sisa berkas");
                }
                lastError = e;
            } catch (HttpError e) {
                throw e;   // mencoba lagi tidak ada gunanya
            } catch (IOException e) {
                lastError = e;
                if (attempt >= MAX_ATTEMPTS) break;
                long wait = Math.min(RETRY_BASE_MS << (attempt - 1), RETRY_MAX_MS);
                if (log != null) {
                    log.log("  putus: " + e.getMessage() + " — coba lagi "
                            + (wait / 1000) + "s (percobaan " + (attempt + 1)
                            + "/" + MAX_ATTEMPTS + ")");
                }
                sleepQuietly(wait);
            }
        }
        // .part sengaja dibiarkan: percobaan berikutnya (mis. tombol Install
        // ditekan lagi) akan melanjutkan dari yang sudah terunduh.
        if (log != null && part.isFile()) {
            log.log("  disimpan sementara: " + Downloader.human(part.length())
                    + " di " + part.getName() + " (akan dilanjutkan)");
        }
        throw lastError != null ? lastError
                : new IOException("gagal mengunduh " + url);
    }

    /** Satu percobaan unduh (bisa menyambung berkas .part yang sudah ada). */
    private static void attemptDownload(String url, File part, long sizeHint,
                                        String expectedSha, boolean verify, TaskLog log,
                                        int attempt) throws IOException {
        long resumeFrom = part.length();
        if (resumeFrom > 0 && log != null) {
            log.log("  melanjutkan dari " + human(resumeFrom) + " (percobaan " + attempt + ")");
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        conn.connect();
        try {
            int code = conn.getResponseCode();
            if (code == 416) {
                // Range tidak bisa dipenuhi: .part lebih besar dari berkas
                // aslinya (atau servernya sudah ganti isi).
                part.delete();
                throw new IOException("server menolak sisa unduhan (HTTP 416), mulai dari awal");
            }
            boolean partial = code == 206;
            if (code == 200 && resumeFrom > 0) {
                // Server tidak mendukung Range: balasannya mulai dari byte 0,
                // jadi sisa berkas lama tidak berharga — kosongkan dulu.
                if (log != null) log.log("  server tanpa dukungan Range, unduh dari awal");
                truncate(part);
                resumeFrom = 0;
            } else if (code != 200 && code != 206) {
                if (code >= 400 && code < 500 && code != 408 && code != 425 && code != 429) {
                    throw new HttpError("HTTP " + code + " untuk " + url);
                }
                throw new IOException("HTTP " + code + " untuk " + url);
            }

            // total = patokan resmi dari header (dipakai untuk menilai lengkap
            // atau tidak). sizeHint dari katalog cuma tebakan, jadi hanya
            // dipakai untuk menggambar progress kalau header tidak menyebut
            // ukuran sama sekali.
            long total = expectedTotal(conn, partial, resumeFrom);
            long showTotal = total > 0 ? total : sizeHint;
            if (total > 0 && resumeFrom > total) {
                part.delete();
                throw new IOException("berkas sementara lebih besar dari aslinya ("
                        + human(resumeFrom) + " > " + human(total) + "), mulai dari awal");
            }

            long done = resumeFrom;
            long lastReport = 0;
            int lastPct = -1;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, resumeFrom > 0)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (log != null) {
                        if (showTotal > 0) {
                            int pct = (int) Math.min(100, done * 100 / showTotal);
                            if (pct / 5 != lastPct / 5) {
                                lastPct = pct;
                                log.log("  " + pct + "% (" + human(done) + " / " + human(showTotal) + ")");
                            }
                        } else if (done - lastReport > (8L << 20)) {
                            lastReport = done;
                            log.log("  " + human(done) + " terunduh");
                        }
                    }
                }
            }

            long size = part.length();
            if (total > 0 && size != total) {
                throw new IOException("unduhan tidak lengkap: " + size + " dari " + total + " byte");
            }

            if (verify) {
                String got = sha256(part);
                if (!expectedSha.trim().equalsIgnoreCase(got)) {
                    throw new HashMismatch("sha256 tidak cocok\n  dapat   : " + got
                            + "\n  diharap : " + expectedSha.trim());
                }
                if (log != null) log.log("  sha256 ok (" + human(size) + ")");
            } else if (log != null) {
                log.log("  selesai (" + human(size) + ")");
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Ukuran akhir yang benar, diambil dari header (bukan dari sizeHint):
     * sizeHint cuma tebakan di katalog dan boleh meleset, jadi dipakai hanya
     * untuk menggambar progress.
     */
    private static long expectedTotal(HttpURLConnection conn, boolean partial,
                                      long resumeFrom) {
        if (partial) {
            String cr = conn.getHeaderField("Content-Range"); // bytes 100-999/1000
            if (cr != null) {
                int slash = cr.lastIndexOf('/');
                String tail = slash >= 0 ? cr.substring(slash + 1).trim() : "";
                if (tail.matches("\\d+")) return Long.parseLong(tail);
                int dash = cr.indexOf('-');
                int space = cr.indexOf(' ');
                if (dash > space && space >= 0) {
                    String end = cr.substring(dash + 1).replaceAll("[^0-9]", "");
                    if (!end.isEmpty()) return Long.parseLong(end) + 1;
                }
            }
            long len = conn.getContentLengthLong();
            return len > 0 ? resumeFrom + len : -1;
        }
        long len = conn.getContentLengthLong();
        return len > 0 ? len : -1;   // tidak diketahui -> jangan cek panjang
    }

    /** Pindahkan .part jadi berkas jadi. Dipanggil hanya kalau hash sudah cocok. */
    private static void promote(File part, File dest, TaskLog log) throws IOException {
        if (dest.exists() && !dest.delete()) {
            throw new IOException("tidak bisa menimpa " + dest.getAbsolutePath());
        }
        if (!part.renameTo(dest)) {
            throw new IOException("gagal memindahkan " + part.getName() + " -> " + dest.getName());
        }
        if (log != null) log.log("  selesai -> " + dest.getName());
    }

    private static void truncate(File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.flush();
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

    /** true kalau isi berkas cocok dengan sha256 yang diminta (kosong = anggap cocok). */
    public static boolean matches(File f, String expectedSha) throws IOException {
        if (expectedSha == null || expectedSha.trim().isEmpty()) return true;
        return expectedSha.trim().equalsIgnoreCase(sha256(f));
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
