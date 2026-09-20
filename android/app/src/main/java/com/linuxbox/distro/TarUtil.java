package com.linuxbox.distro;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Paths;

/** Ekstraksi/pembuatan tar.gz rootfs dengan validasi path (Zip-slip). */
public final class TarUtil {

    private TarUtil() {}

    /**
     * Normalisasi nama entry tar. Mengembalikan null kalau entry harus dilewati:
     * entri root ("./"), path absolut, atau path yang keluar dari direktori
     * tujuan ("../"). Ini yang mencegah Zip-slip — arsip jahat tidak boleh bisa
     * menimpa berkas di luar rootfs.
     */
    public static String safeName(String raw) {
        if (raw == null) return null;
        String name = raw.replace('\\', '/').trim();
        while (name.startsWith("./")) name = name.substring(2);
        if (name.isEmpty() || name.equals(".") || name.equals("/")) return null;
        if (name.startsWith("/")) return null;
        if (name.equals("..") || name.startsWith("../") || name.contains("/../") || name.endsWith("/..")) return null;
        // drive letter Windows (C:\...)
        if (name.length() >= 2 && Character.isLetter(name.charAt(0)) && name.charAt(1) == ':') return null;
        return name;
    }

    /**
     * Apakah target symlink aman? Target dinilai relatif terhadap direktori
     * entry-nya, bukan sebagai path berdiri sendiri: rootfs busybox punya
     * banyak symlink seperti `usr/bin/sh -> ../bin/busybox`, yang sah-sah saja
     * selama hasil akhirnya masih di dalam rootfs. Yang ditolak: target kosong,
     * absolut (`/etc/passwd`), drive letter Windows, dan target yang setelah
     * dinormalisasi keluar dari rootfs.
     */
    public static boolean linkStaysInside(String entryName, String target) {
        if (target == null || target.isEmpty()) return false;
        String t = target.replace('\\', '/');
        if (t.length() >= 2 && Character.isLetter(t.charAt(0)) && t.charAt(1) == ':') return false;
        if (t.startsWith("/")) {
            // Target absolut (mis. `bin/sh -> /bin/busybox`, dipakai proot-distro
            // alpine Ubuntu) sah untuk rootfs: proot/chroot me-resolve di dalam
            // guest rootfs. Ditolak hanya kalau setelah dinormalisasi keluar root.
            return normalizePath(t.substring(1)) != null;
        }
        String dir = parentOf(entryName);
        String combined = dir.isEmpty() ? t : dir + "/" + t;
        return normalizePath(combined) != null;
    }

    /** Ubah path absolut (di dalam rootfs) jadi target relatif terhadap dir link. */
    public static String relativize(String fromDir, String toPath) {
        String[] from = fromDir == null || fromDir.isEmpty() ? new String[0] : fromDir.split("/");
        String[] to = toPath == null ? new String[0] : toPath.split("/");
        if (to.length == 0) return null;
        int common = 0;
        while (common < from.length && common < to.length - 1 && from[common].equals(to[common])) {
            common++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < from.length; i++) sb.append("../");
        for (int i = common; i < to.length; i++) {
            if (i > common) sb.append('/');
            sb.append(to[i]);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Normalisasi path relatif; null kalau keluar dari root (".." berlebih). */
    private static String normalizePath(String path) {
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (stack.isEmpty()) return null;
                stack.removeLast();
            } else {
                stack.addLast(seg);
            }
        }
        if (stack.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String seg : stack) {
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    private static String parentOf(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('/');
        return i < 0 ? "" : name.substring(0, i);
    }

    /** Ekstrak tar.gz (atau tar polos) ke dest. Mengembalikan jumlah entri ditulis. */
    public static int extract(File archive, File dest, TaskLog log) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IOException("tidak bisa membuat " + dest.getAbsolutePath());
        }
        File canonicalDest = dest.getCanonicalFile();
        int files = 0;
        int skipped = 0;
        int links = 0;

        InputStream raw = new BufferedInputStream(new FileInputStream(archive), 1 << 16);
        InputStream src = raw;
        boolean gzipped = isGzip(archive);
        if (gzipped) src = new GzipCompressorInputStream(raw);

        try (TarArchiveInputStream tin = new TarArchiveInputStream(src)) {
            TarArchiveEntry e;
            while ((e = tin.getNextTarEntry()) != null) {
                String name = safeName(e.getName());
                if (name == null) {
                    skipped++;
                    continue;
                }
                File f = new File(canonicalDest, name);
                if (!isInside(canonicalDest, f)) {
                    skipped++;
                    continue;
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();

                if (e.isSymbolicLink()) {
                    String target = e.getLinkName();
                    if (!linkStaysInside(name, target)) {
                        skipped++;
                        continue;
                    }
                    try { Files.deleteIfExists(f.toPath()); } catch (Exception ignored) {}
                    try {
                        Files.createSymbolicLink(f.toPath(), Paths.get(target));
                        links++;
                    } catch (Exception ignored) {
                        // filesystem tidak mendukung symlink -> masuk daftar lewati
                        skipped++;
                    }
                    continue;
                }
                if (e.isLink()) {
                    // hardlink: tidak direproduksi (jarang dipakai rootfs, aman dilewati)
                    skipped++;
                    continue;
                }

                try (FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = tin.read(buf)) > 0) out.write(buf, 0, n);
                }
                if ((e.getMode() & 0x100) != 0) {
                    try { f.setExecutable(true, false); } catch (Exception ignored) {}
                }
                files++;
                if (log != null && files % 1000 == 0) log.log("  " + files + " berkas...");
            }
        }
        if (log != null && (skipped > 0 || links > 0)) {
            log.log("  entri: " + files + " berkas, " + links + " symlink, " + skipped + " dilewati");
        }
        return files + links;
    }

    /** Buat tar.gz dari sebuah direktori (untuk backup rootfs). */
    public static int createTarGz(File dir, File out, TaskLog log) throws IOException {
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        int[] count = {0};
        File canonicalRoot = dir.getCanonicalFile();
        try (TarArchiveOutputStream tout = new TarArchiveOutputStream(
                new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16)))) {
            tout.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tout.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            writeTree(canonicalRoot, canonicalRoot, tout, count, log);
        }
        return count[0];
    }

    private static void writeTree(File root, File current, TarArchiveOutputStream tout,
                                  int[] count, TaskLog log) throws IOException {
        File[] kids = current.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String rel = relative(root, k);
            if (rel == null) continue;

            if (Files.isSymbolicLink(k.toPath())) {
                String target = Files.readSymbolicLink(k.toPath()).toString();
                String dir = parentOf(rel);
                if (target.startsWith("/")) {
                    // absolut (mis. /bin/busybox) -> relatif terhadap direktori link
                    // target absolut berarti "relatif terhadap root rootfs"
                    File abs = new File(root, target.replaceFirst("^/+", ""));
                    target = isInside(root, abs) ? relativize(dir, relative(root, abs)) : null;
                } else if (!linkStaysInside(rel, target)) {
                    target = null;   // menunjuk keluar rootfs, jangan diikutsertakan
                }
                if (target == null) continue;
                TarArchiveEntry e = new TarArchiveEntry(rel, TarArchiveEntry.LF_SYMLINK);
                e.setLinkName(target);
                tout.putArchiveEntry(e);
                tout.closeArchiveEntry();
                count[0]++;
                continue;
            }

            if (k.isDirectory()) {
                // commons-compress: nama berakhir "/" = entri direktori (DIRTYPE)
                TarArchiveEntry e = new TarArchiveEntry(rel + "/");
                e.setMode(040755);
                tout.putArchiveEntry(e);
                tout.closeArchiveEntry();
                count[0]++;
                writeTree(root, k, tout, count, log);
                continue;
            }
            if (!k.isFile()) continue;

            TarArchiveEntry e = new TarArchiveEntry(rel);
            e.setSize(k.length());
            e.setMode(k.canExecute() ? 0100755 : 0100644);
            e.setModTime(k.lastModified());
            tout.putArchiveEntry(e);
            try (FileInputStream in = new FileInputStream(k)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) tout.write(buf, 0, n);
            }
            tout.closeArchiveEntry();
            count[0]++;
            if (log != null && count[0] % 1000 == 0) log.log("  " + count[0] + " entri...");
        }
    }

    private static String relative(File root, File f) {
        String rootPath = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(rootPath)) return null;
        if (p.startsWith(rootPath + File.separator)) return p.substring(rootPath.length() + 1);
        return f.getName();
    }

    private static boolean isInside(File dir, File f) {
        try {
            File p = f.getCanonicalFile();
            File d = dir.getCanonicalFile();
            while (p != null) {
                if (p.equals(d)) return true;
                p = p.getParentFile();
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static boolean isGzip(File f) throws IOException {
        byte[] magic = new byte[2];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(magic);
            return n == 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b;
        }
    }

    /** Hapus direktori berikut isinya (dipakai saat swap rootfs). */
    public static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        f.delete();
    }

    @SuppressWarnings("unused")
    private static boolean exists(File f) {
        return f != null && Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }
}
