package com.linuxbox.distro;

import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Backup rootfs aktif; restore mendeteksi distro dari isi arsip dan meminta konfirmasi tujuan.
 *
 * Backup bisa ditulis apa adanya (tar.gz) atau dienkripsi dengan passphrase
 * (tar.gz + {@link Crypto#EXTENSION}). Versi terenkripsi aman disimpan di
 * Download/Dropbox/dsb karena isinya tidak bisa dibuka tanpa passphrase, dan
 * GCM sekaligus mendeteksi kalau berkasnya dimodifikasi.
 */
public final class BackupManager {

    private BackupManager() {}

    /**
     * Kemas rootfs aktif jadi tar.gz di filesDir/backup, lengkap dengan
     * berkas .sha256, lalu salin ke folder Download (publik) bila memungkinkan.
     *
     * @return berkas tar.gz di penyimpanan internal app
     */
    public static File exportRootfs(Context ctx, TaskLog log) throws IOException {
        return exportRootfs(ctx, log, null);
    }

    /**
     * @param passphrase null/kosong -> backup tar.gz biasa; selain itu berkas
     *                   dienkripsi (AES-256-GCM) dan berakhiran .lbx
     */
    public static File exportRootfs(Context ctx, TaskLog log, char[] passphrase)
            throws IOException {
        boolean encrypt = passphrase != null && passphrase.length > 0;
        File rootfs = ProotSession.activeRootfsDir(ctx);
        if (!rootfs.isDirectory()) {
            throw new IOException("rootfs belum ada: " + rootfs.getAbsolutePath());
        }
        String id = DistroCatalog.activeId(ctx);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        File dir = new File(ctx.getFilesDir(), "backup");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("tidak bisa membuat " + dir.getAbsolutePath());
        }
        String ext = encrypt ? ".tar.gz" + Crypto.EXTENSION : ".tar.gz";
        // Nama unik: mencoba ulang di menit yang sama tidak menimpa backup lama,
        // dan cleanup kegagalan hanya menghapus berkas milik percobaan ini.
        File out = File.createTempFile("linuxbox-" + id + "-" + stamp + "-", ext, dir);
        // Tar ditulis ke berkas sementara dulu: kalau enkripsi gagal di tengah
        // jalan, tidak ada tar.gz setengah jadi yang tertinggal di folder backup.
        File plain = encrypt ? new File(dir, ".tmp-" + out.getName() + ".tar.gz") : out;

        if (log != null) {
            log.log("Membuat backup " + rootfs.getName() + (encrypt ? " (terenkripsi)" : "") + "...");
            if (encrypt) log.log("  " + Crypto.describe());
        }
        long t0 = System.currentTimeMillis();
        int entries;
        boolean ok = false;
        try {
            // Lindungi tahap tar juga: kegagalan library/I/O di sini tidak boleh
            // meninggalkan arsip gagal atau plaintext sementara backup terenkripsi.
            entries = TarUtil.createTarGz(rootfs, plain, log);
            if (entries == 0) throw new IOException("rootfs kosong, backup dibatalkan");
            if (encrypt) {
                if (log != null) log.log("  mengenkripsi...");
                Crypto.encrypt(plain, out, passphrase);
            }
            ok = true;
        } finally {
            if (encrypt) plain.delete();
            if (!ok) out.delete();
        }
        String sha = Downloader.sha256(out);
        writeText(new File(out.getAbsolutePath() + ".sha256"), sha + "  " + out.getName() + "\n");

        if (log != null) {
            log.log("  " + entries + " entri, " + Downloader.human(out.length())
                    + ", " + ((System.currentTimeMillis() - t0) / 1000) + "s");
            log.log("  sha256: " + sha);
            log.log("  berkas: " + out.getAbsolutePath());
        }
        if (log != null && encrypt) {
            log.log("  berkas terenkripsi: simpan baik-baik, tanpa passphrase "
                    + "isi tidak bisa dipulihkan");
        }
        File published = publish(ctx, out, log);
        if (log != null && published != null) log.log("  disalin ke: " + published.getAbsolutePath());
        return out;
    }

    /** Restore dua tahap: ekstrak/identifikasi dulu, minta konfirmasi sebelum swap. */
    public static final class RestorePlan implements AutoCloseable {
        public final String distroId;
        public final boolean replacesExisting;
        private final File filesDir;
        private final File staged;
        private boolean applied;

        private RestorePlan(File filesDir, File staged, String distroId) {
            this.filesDir = filesDir;
            this.staged = staged;
            this.distroId = distroId;
            this.replacesExisting = present(ProotSession.rootfsDir(filesDir, distroId));
        }

        @Override public void close() { TarUtil.deleteRecursively(staged); }
    }

    public static RestorePlan prepareRestore(Context ctx, File archive, TaskLog log,
                                              char[] passphrase) throws IOException {
        if (archive == null || !archive.isFile()) throw new IOException("berkas backup tidak ditemukan");
        File filesDir = ctx.getFilesDir();
        File staged = new File(filesDir, "rootfs-import-" + java.util.UUID.randomUUID());
        File decrypted = null;
        boolean ready = false;
        try (RootfsGuard.Change ignored = RootfsGuard.beginChange()) {
            File plain = archive;
            if (Crypto.isEncrypted(archive)) {
                if (passphrase == null || passphrase.length == 0) {
                    throw new IOException("backup ini terenkripsi — masukkan passphrase-nya");
                }
                decrypted = File.createTempFile("restore-dec-", ".tar.gz", filesDir);
                if (log != null) log.log("Mendekripsi backup...");
                Crypto.decrypt(archive, decrypted, passphrase);
                plain = decrypted;
            }
            if (log != null) log.log("Memeriksa isi backup (belum mengganti distro)...");
            if (TarUtil.extract(plain, staged, log) == 0) throw new IOException("arsip rootfs kosong");
            String id = knownIdentity(ctx, staged);
            RootfsIdentity.validateShell(staged);
            RestorePlan plan = new RestorePlan(filesDir, staged, id);
            if (log != null) log.log("Distro terdeteksi: " + id + " → rootfs-" + id);
            ready = true;
            return plan;
        } finally {
            if (decrypted != null) decrypted.delete();
            if (!ready) TarUtil.deleteRecursively(staged);
        }
    }

    /** Hanya dipanggil sesudah user mengonfirmasi distro tujuan yang terdeteksi. */
    public static void applyRestore(Context ctx, RestorePlan plan, TaskLog log) throws IOException {
        try (RootfsGuard.Change ignored = RootfsGuard.beginChange()) {
            if (plan.applied || !plan.staged.isDirectory()
                    || !ctx.getFilesDir().equals(plan.filesDir)) throw new IOException("rencana restore sudah tidak valid");
            if (!plan.distroId.equals(knownIdentity(ctx, plan.staged))) throw new IOException("identitas backup berubah");
            RootfsIdentity.validateShell(plan.staged);
            File target = ProotSession.rootfsDir(plan.filesDir, plan.distroId);
            boolean exists = present(target);
            if (exists && !plan.replacesExisting) {
                throw new IOException("distro tujuan baru saja dibuat; ulangi restore untuk konfirmasi penggantian");
            }
            File previous = new File(plan.filesDir, target.getName() + ".before-restore-" + java.util.UUID.randomUUID());
            if (exists && !target.renameTo(previous)) throw new IOException("gagal menyimpan rootfs sebelumnya");
            if (!plan.staged.renameTo(target)) {
                if (exists && !previous.renameTo(target)) {
                    throw new IOException("swap gagal; rootfs sebelumnya tetap tersimpan di " + previous.getName());
                }
                throw new IOException("gagal menempatkan rootfs baru; rootfs sebelumnya tidak diganti");
            }
            plan.applied = true;
            // Jangan hapus rootfs sebelumnya: user masih bisa menyelamatkan perubahan
            // yang ternyata belum termasuk arsip backup. Distro lain tidak disentuh.
            if (log != null && exists) log.log("Rootfs sebelumnya disimpan: " + previous.getAbsolutePath());
            if (!DistroCatalog.activateRestored(ctx, plan.distroId)) {
                throw new IOException("rootfs sudah dipulihkan, tetapi gagal menyimpan distro aktif; jangan hapus data aplikasi");
            }
            if (log != null) log.log("Selesai. Distro aktif: " + plan.distroId);
        }
    }

    /** Pemulihan instalasi lama yang Ubuntu-nya telanjur berada di rootfs-alpine. */
    public static void repairActiveIdentity(Context ctx, TaskLog log) throws IOException {
        try (RootfsGuard.Change ignored = RootfsGuard.beginChange()) {
            String oldId = DistroCatalog.activeId(ctx);
            File source = ProotSession.rootfsDir(ctx.getFilesDir(), oldId);
            String actualId = knownIdentity(ctx, source);
            RootfsIdentity.validateShell(source);
            if (oldId.equals(actualId)) {
                if (log != null) log.log("Identitas distro sudah benar: " + actualId);
                return;
            }
            File target = ProotSession.rootfsDir(ctx.getFilesDir(), actualId);
            if (present(target)) {
                throw new IOException("rootfs-" + actualId + " sudah ada; tidak ditimpa. Backup distro aktif dulu, lalu restore dengan konfirmasi tujuan.");
            }
            if (!source.renameTo(target)) throw new IOException("gagal memindahkan rootfs; data asli tidak dihapus");
            if (!DistroCatalog.activateRestored(ctx, actualId)) {
                // Jangan buang data jika preferensi gagal ditulis: direktori tujuan
                // tetap berisi filesystem pengguna secara utuh.
                throw new IOException("data sudah dipindah ke " + target.getName() + ", tetapi gagal menyimpan distro aktif");
            }
            if (log != null) log.log("Identitas diperbaiki: " + oldId + " → " + actualId + ". Isi rootfs tidak diekstrak ulang/dihapus.");
        }
    }

    private static boolean present(File file) {
        return java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    private static String knownIdentity(Context ctx, File root) throws IOException {
        String id = RootfsIdentity.detect(root);
        if (DistroCatalog.find(ctx, id) == null) throw new IOException("distro tidak ada di katalog: " + id);
        return id;
    }

    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** Salin backup ke folder Download publik (tanpa Permission di Android 10+). */
    private static File publish(Context ctx, File src, TaskLog log) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
                cv.put(MediaStore.Downloads.MIME_TYPE,
                        src.getName().endsWith(Crypto.EXTENSION)
                                ? "application/octet-stream" : "application/gzip");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                android.net.Uri uri = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                try (OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                     FileInputStream in = new FileInputStream(src)) {
                    if (os == null) return null;
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                cv.clear();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, cv, null, null);
                return new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), src.getName());
            }
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File dst = new File(dir, src.getName());
            try (FileOutputStream os = new FileOutputStream(dst);
                 FileInputStream in = new FileInputStream(src)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return dst;
        } catch (Exception e) {
            if (log != null) log.log("  (gagal menyalin ke Download: " + e.getMessage() + ")");
            return null;
        }
    }
}
