package com.linuxbox.distro;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Bootstrap sekali jalan: siapkan proot, ptylauncher, dan rootfs distro. */
public class Bootstrap {

    public interface Log {
        void log(String line);
    }

    private final Context ctx;
    private final Log log;

    public Bootstrap(Context ctx, Log log) {
        this.ctx = ctx;
        this.log = log;
    }

    public void install() throws Exception {
        File dir = ctx.getFilesDir();
        JSONObject json = new JSONObject(readAsset("bootstrap.json"));

        File proot = ProotSession.prootBin(dir);
        File libTalloc = new File(dir, "bin/libtalloc.so.2");
        File libShmem = new File(dir, "bin/libandroid-shmem.so");
        if (!proot.exists() || !libTalloc.exists() || !libShmem.exists()) {
            log.log("Menyalin proot + libnya...");
            if (!proot.exists()) {
                copyAsset("bin/proot", proot);
                proot.setExecutable(true, false);
            }
            if (!libTalloc.exists()) copyAsset("bin/libtalloc.so.2", libTalloc);
            if (!libShmem.exists()) copyAsset("bin/libandroid-shmem.so", libShmem);
        }

        File pty = ProotSession.ptyBin(dir);
        if (!pty.exists()) {
            log.log("Menyalin ptylauncher...");
            copyAsset("bin/ptylauncher", pty);
            pty.setExecutable(true, false);
        }

        File rootfsDir = ProotSession.rootfsDir(dir);
        File bash = new File(rootfsDir, "bin/bash");
        File sh = new File(rootfsDir, "bin/sh");
        if (!bash.exists() && !sh.exists()) {
            File tarGz = new File(dir, "rootfs.tar.gz");
            if (!tarGz.exists()) {
                String url = json.getJSONObject("rootfs").optString("url");
                if (!url.isEmpty()) {
                    log.log("Mendownload rootfs...");
                    download(url, tarGz, json.getJSONObject("rootfs").optLong("sizeBytes"));
                } else {
                    log.log("Menyalin rootfs.tar.gz dari assets...");
                    copyAsset("rootfs.tar.gz", tarGz);
                }
            }
            String sha = sha256(tarGz);
            String expect = json.getJSONObject("rootfs").optString("sha256");
            if (!expect.isEmpty() && !expect.equals(sha)) {
                throw new java.io.IOException("sha256 mismatch\n  dapat  : " + sha + "\n  diharap: " + expect);
            }
            log.log("sha256 ok. Mengekstrak rootfs...");
            extract(tarGz, rootfsDir);
            tarGz.delete();
        }
        log.log("Selesai. Distro siap.");
    }

    private String readAsset(String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        BufferedReader r = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        r.close();
        return sb.toString();
    }

    private void copyAsset(String name, File dest) throws Exception {
        File p = dest.getParentFile();
        if (p != null) p.mkdirs();
        InputStream in = ctx.getAssets().open(name);
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void download(String url, File dest, long sizeBytes) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.connect();
        long total = sizeBytes > 0 ? sizeBytes : Math.max(conn.getContentLengthLong(), 0);
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(dest);
        byte[] buf = new byte[1 << 16];
        long done = 0;
        int n;
        long pct = -1;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (total > 0 && done * 100 / total != pct) {
                pct = done * 100 / total;
                log.log("  " + pct + "%");
            }
        }
        out.close();
        in.close();
        conn.disconnect();
    }

    private void extract(File tarGz, File dest) throws Exception {
        dest.mkdirs();
        TarArchiveInputStream tin =
                new TarArchiveInputStream(new GzipCompressorInputStream(
                        new BufferedInputStream(new FileInputStream(tarGz))));
        try {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry e = tin.getNextTarEntry();
            while (e != null) {
                File f = new File(dest, e.getName());
                if (e.isDirectory()) {
                    f.mkdirs();
                } else if (e.isSymbolicLink()) {
                    File p = f.getParentFile();
                    if (p != null) p.mkdirs();
                    try {
                        java.nio.file.Files.createSymbolicLink(
                                f.toPath(), java.nio.file.Paths.get(e.getLinkName()));
                    } catch (Exception ignored) {
                    }
                } else {
                    File p = f.getParentFile();
                    if (p != null) p.mkdirs();
                    FileOutputStream out = new FileOutputStream(f);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = tin.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    if ((e.getMode() & 0x100) != 0) f.setExecutable(true);
                }
                e = tin.getNextTarEntry();
            }
        } finally {
            tin.close();
        }
    }
}