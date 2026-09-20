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

/** Backup (ekspor) dan restore (impor) rootfs aktif dalam satu tap. */
public final class BackupManager {

    private BackupManager() {}

    /**
     * Kemas rootfs aktif jadi tar.gz di filesDir/backup, lengkap dengan
     * berkas .sha256, lalu salin ke folder Download (publik) bila memungkinkan.
     *
     * @return berkas tar.gz di penyimpanan internal app
     */
    public static File exportRootfs(Context ctx, TaskLog log) throws IOException {
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
        File out = new File(dir, "linuxbox-" + id + "-" + stamp + ".tar.gz");

        if (log != null) log.log("Membuat backup " + rootfs.getName() + "...");
        long t0 = System.currentTimeMillis();
        int entries = TarUtil.createTarGz(rootfs, out, log);
        if (entries == 0) {
            out.delete();
            throw new IOException("rootfs kosong, backup dibatalkan");
        }
        String sha = Downloader.sha256(out);
        writeText(new File(out.getAbsolutePath() + ".sha256"), sha + "  " + out.getName() + "\n");

        if (log != null) {
            log.log("  " + entries + " entri, " + Downloader.human(out.length())
                    + ", " + ((System.currentTimeMillis() - t0) / 1000) + "s");
            log.log("  sha256: " + sha);
            log.log("  berkas: " + out.getAbsolutePath());
        }
        File published = publish(ctx, out, log);
        if (log != null && published != null) log.log("  disalin ke: " + published.getAbsolutePath());
        return out;
    }

    /**
     * Pulihkan rootfs dari tar.gz: ekstrak ke direktori sementara lalu tukar
     * dengan rootfs aktif (yang lama jadi .bak dan dihapus kalau sukses).
     */
    public static void importRootfs(Context ctx, File archive, TaskLog log) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new IOException("berkas backup tidak ditemukan");
        }
        File filesDir = ctx.getFilesDir();
        File active = ProotSession.activeRootfsDir(ctx);
        File tmp = new File(filesDir, "rootfs-import-" + System.currentTimeMillis());
        File bak = new File(filesDir, active.getName() + ".bak-" + System.currentTimeMillis());

        if (log != null) {
            log.log("Memulihkan " + archive.getName() + " (" + Downloader.human(archive.length()) + ")");
        }
        try {
            TarUtil.deleteRecursively(tmp);
            int entries = TarUtil.extract(archive, tmp, log);
            if (entries == 0) {
                throw new IOException("arsip tidak berisi rootfs yang valid");
            }
            if (active.exists() && !active.renameTo(bak)) {
                throw new IOException("gagal memindahkan rootfs lama: " + active.getAbsolutePath());
            }
            if (!tmp.renameTo(active)) {
                if (bak.exists()) bak.renameTo(active);
                throw new IOException("gagal menempatkan rootfs baru");
            }
            TarUtil.deleteRecursively(bak);
            if (log != null) log.log("Selesai. " + entries + " entri dipulihkan ke " + active.getName());
        } finally {
            TarUtil.deleteRecursively(tmp);
        }
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
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/gzip");
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
