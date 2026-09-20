package com.linuxbox.distro;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Lokasi & assembly command untuk PRoot session. */
public final class ProotSession {

    private ProotSession() {}

    /**
     * Kandidat shell di dalam rootfs, urut prioritas.
     * Penting: alpine (distro default) TIDAK punya /bin/bash — cuma busybox ash.
     * Dulu /bin/bash di-hardcode, akibatnya sesi langsung mati dan terminal web
     * cuma menampilkan layar kosong (client yang connect belakangan tidak pernah
     * menerima apa pun).
     */
    private static final String[] SHELL_CANDIDATES = {
            "/bin/bash",
            "/bin/ash",
            "/bin/sh",
            "/bin/zsh",
    };

    /**
     * Direktori native library hasil ekstraksi PackageManager. Binary di sini
     * berlabel apk_data_file_t — satu-satunya label yang boleh di-execve oleh
     * domain untrusted_app_30/_32 (targetSdk >= 30). Binary yang disalin ke
     * filesDir (app_data_file_t) TIDAK bisa dieksekusi di SELinux enforcing
     * (error=13 EPERM).
     */
    public static String nativeLibraryDir(Context ctx) {
        return ctx.getApplicationInfo().nativeLibraryDir;
    }

    /**
     * Nama berkas di lib/<abi>/ bergantung jalur build:
     *  - Gradle/Android Studio: CMake menghasilkan `libptylauncher.so`, dan
     *    fetch-assets.sh menaruh `libproot.so` (AGP lebih aman dengan nama
     *    berawalan `lib` + ekstensi `.so`).
     *  - build-apk.sh (aapt2 manual): nama polos `proot` / `ptylauncher`.
     * Dua-duanya didukung supaya APK lama tetap jalan.
     */
    public static File prootBin(String nativeLibDir) {
        return pick(nativeLibDir, "libproot.so", "proot");
    }

    public static File ptyBin(String nativeLibDir) {
        return pick(nativeLibDir, "libptylauncher.so", "ptylauncher");
    }

    /** Pilih nama pertama yang ada; fallback ke nama terakhir kalau tidak ada. */
    private static File pick(String dir, String... names) {
        for (String n : names) {
            File f = new File(dir, n);
            if (f.exists()) return f;
        }
        return new File(dir, names[names.length - 1]);
    }

    /** Setiap distro punya direktori sendiri: files/rootfs-<id>. */
    public static File rootfsDir(File filesDir, String distroId) {
        return new File(filesDir, "rootfs-" + distroId);
    }

    /** @deprecated pakai {@link #rootfsDir(File, String)} — multi-distro. */
    public static File rootfsDir(File filesDir) {
        return rootfsDir(filesDir, "alpine");
    }

    /** Direktori rootfs distro yang sedang aktif. */
    public static File activeRootfsDir(Context ctx) {
        return rootfsDir(ctx.getFilesDir(), DistroCatalog.activeId(ctx));
    }

    /**
     * Pilih shell yang benar-benar ada di rootfs. Fallback terakhir /bin/sh:
     * kalau tidak ada sama sekali, pesan error proot yang tampil di terminal
     * jauh lebih berguna daripada layar hitam kosong.
     */
    public static String detectShell(File rootfs) {
        for (String candidate : SHELL_CANDIDATES) {
            if (new File(rootfs, candidate).isFile()) return candidate;
        }
        return "/bin/sh";
    }

    public static List<String> buildCommand(String nativeLibDir, File rootfs) {
        return buildCommand(nativeLibDir, rootfs, new ArrayList<String>());
    }

    public static List<String> buildCommand(String nativeLibDir, File rootfs, List<String> extraBind) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBin(nativeLibDir).getAbsolutePath());
        cmd.add("--rootfs=" + rootfs.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/dev");
        if (extraBind != null) {
            for (String b : extraBind) {
                cmd.add("-b"); cmd.add(b);
            }
        }
        cmd.add("--kill-on-exit");
        // cwd explicit: proot menebak guest-cwd dari host-cwd lewat realpath(),
        // dan realpath() di beberapa ROM (f2fs/Transsion) mengembalikan "/" untuk
        // path data app -> "can't chdir(.../rootfs/./.)" lalu execve gagal.
        cmd.add("--cwd=/");
        cmd.add(detectShell(rootfs));
        cmd.add("-l");
        return cmd;
    }

    /** Direktori sementara host-side untuk proot (dibuat kalau belum ada). */
    public static File tmpDir(File filesDir) {
        File tmp = new File(filesDir, "tmp");
        if (!tmp.isDirectory()) tmp.mkdirs();
        return tmp;
    }

    public static java.util.Map<String, String> environment(File filesDir, String nativeLibDir,
                                                            File rootfs) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        String shell = detectShell(rootfs);
        env.put("HOME", "/root");
        env.put("SHELL", shell);
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "C.UTF-8");
        // PENTING: proot membuat temp dir untuk probe f2fs SEBELUM guest rootfs
        // aktif, jadi TMPDIR harus path host yang benar-benar ada dan bisa
        // ditulis. "/tmp" tidak ada di Android -> proot warning "Unable to create
        // temp directory for f2fs bug probe" lalu gagal chdir/execve.
        env.put("TMPDIR", tmpDir(filesDir).getAbsolutePath());
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("LD_LIBRARY_PATH", nativeLibDir);
        return env;
    }
}
