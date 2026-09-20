package com.linuxbox.distro;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bootstrap: siapkan proot + ptylauncher, lalu pasang rootfs distro.
 *
 * Rootfs bisa datang dari dua sumber:
 *  - `assets/rootfs.tar.gz` (dibuat `scripts/fetch-assets.sh`, dipakai kalau
 *    entri katalog tidak punya URL), atau
 *  - unduhan langsung (streaming) dari `url` di `distros.json` — tidak perlu
 *    menaruh arsip besar di APK.
 */
public class Bootstrap {

    public interface Log extends TaskLog {
    }

    private final Context ctx;
    private final Log log;

    public Bootstrap(Context ctx, Log log) {
        this.ctx = ctx;
        this.log = log;
    }

    /** Siapkan binary + pastikan distro aktif terpasang. */
    public void install() throws Exception {
        bootstrapBinaries();
        DistroCatalog.Distro d = DistroCatalog.current(ctx);
        if (DistroCatalog.isInstalled(ctx, d.id)) {
            log.log("Distro " + d.id + " sudah terpasang.");
        } else {
            install(d);
        }
        log.log("Selesai. Distro siap.");
    }

    /** Pasang (atau ganti) distro tertentu. */
    public void install(DistroCatalog.Distro d) throws Exception {
        if (d == null) throw new IOException("distro tidak dipilih");
        bootstrapBinaries();
        File filesDir = ctx.getFilesDir();
        File rootfs = ProotSession.rootfsDir(filesDir, d.id);
        File tmp = new File(filesDir, "rootfs-tmp-" + System.currentTimeMillis());
        boolean done = false;
        try {
            File archive = obtainArchive(d);
            try {
                log.log("Mengekstrak ke " + rootfs.getName() + "...");
                TarUtil.deleteRecursively(tmp);
                int entries = TarUtil.extract(archive, tmp, log);
                if (entries == 0) {
                    throw new IOException("arsip rootfs kosong atau tidak dikenal");
                }
            } finally {
                if (d.url != null && !d.url.isEmpty()) archive.delete();
            }

            File old = new File(filesDir, rootfs.getName() + ".old-" + System.currentTimeMillis());
            boolean hadOld = rootfs.exists() && rootfs.renameTo(old);
            if (rootfs.exists()) {
                throw new IOException("masih ada rootfs lama di " + rootfs.getAbsolutePath());
            }
            if (!tmp.renameTo(rootfs)) {
                if (hadOld) old.renameTo(rootfs);
                throw new IOException("gagal menempatkan rootfs baru");
            }
            if (hadOld) TarUtil.deleteRecursively(old);
            DistroCatalog.setActiveId(ctx, d.id);
            done = true;
        } finally {
            if (!done) TarUtil.deleteRecursively(tmp);
        }
        log.log("Distro " + d.id + " siap (" + ProotSession.detectShell(rootfs) + ").");
    }

    private void bootstrapBinaries() throws Exception {
        // Binary ada di nativeLibraryDir (label apk_data_file_t) — satu-satunya
        // yang boleh di-execve oleh untrusted_app_30+/_32 (targetSdk >= 30).
        // Binary semacam ini yang disalin ke filesDir (app_data_file_t) DITOLAK
        // kernel dengan error=13 EPERM di SELinux enforcing.
        String nativeLibDir = ProotSession.nativeLibraryDir(ctx);
        File proot = ProotSession.prootBin(nativeLibDir);
        File libTalloc = new File(nativeLibDir, "libtalloc.so.2");
        File libShmem = new File(nativeLibDir, "libandroid-shmem.so");
        File pty = ProotSession.ptyBin(nativeLibDir);

        for (File f : new File[]{proot, libTalloc, libShmem, pty}) {
            if (!f.isFile()) {
                throw new IOException(f.getName() + " tidak ada di " + nativeLibDir
                        + " — APK ini harus dibangun dengan binary di lib/arm64-v8a/");
            }
            if (!f.canExecute() && !f.setExecutable(true)) {
                throw new IOException(f.getName() + " tidak bisa dieksekusi: " + f.getAbsolutePath());
            }
        }
        // cache fst compute sebenarnya tidak perlu; cukup pastikan hadir.
        log.log("Binary native siap (" + nativeLibDir + ").");
    }

    private File obtainArchive(DistroCatalog.Distro d) throws Exception {
        File filesDir = ctx.getFilesDir();
        if (d.url != null && !d.url.isEmpty()) {
            File out = new File(filesDir, "rootfs-" + d.id + ".tar.gz");
            Downloader.download(d.url, out, d.sizeBytes, d.sha256, log);
            return out;
        }
        // fallback: arsip yang dibundel di assets
        File tarGz = new File(filesDir, "rootfs.tar.gz");
        if (!tarGz.isFile()) {
            log.log("Menyalin rootfs.tar.gz dari assets...");
            copyAsset("rootfs.tar.gz", tarGz);
        }
        String expect = "";
        try {
            JSONObject json = new JSONObject(readAsset("bootstrap.json"));
            expect = json.getJSONObject("rootfs").optString("sha256", "");
        } catch (Exception ignored) {
        }
        if (!expect.isEmpty()) {
            String got = Downloader.sha256(tarGz);
            if (!expect.equalsIgnoreCase(got)) {
                throw new IOException("sha256 mismatch\n  dapat   : " + got + "\n  diharap : " + expect);
            }
            log.log("  sha256 ok");
        }
        return tarGz;
    }

    private String readAsset(String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void copyAsset(String name, File dest) throws Exception {
        File p = dest.getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        InputStream in = ctx.getAssets().open(name);
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }
}
