package com.linuxbox.distro;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Lokasi & assembly command untuk PRoot session. */
public final class ProotSession {

    /**
     * Loader internal proot. proot TIDAK mengeksekusi binary guest langsung:
     * `translate_execve_enter()` mengganti argumen execve dengan path loadernya
     * sendiri. proot Termux dibangun dengan
     * `PROOT_UNBUNDLE_LOADER=$PREFIX/libexec/proot`, jadi tanpa env
     * PROOT_LOADER ia akan mengeksekusi
     * `/data/data/com.termux/files/usr/libexec/proot/loader` — direktori data
     * aplikasi LAIN (mode 0700) yang tidak bisa kita traverse -> EACCES, yang
     * dilaporkan proot sebagai `execve("/bin/sh"): Permission denied`.
     * Karena itu loader ikut dibundel di lib/arm64-v8a/ (apk_data_file_t) dan
     * pathnya dikirim lewat PROOT_LOADER.
     */
    public static final String LOADER_NAME = "libproot_loader.so";

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

    /**
     * Mesin yang menjalankan sesi. Tiga mode:
     * <ul>
     *   <li>{@link #NATIVE} — shell bionic langsung (busybox), tanpa proot sama
     *       sekali. Paling ringan: nol intersep syscall. Tidak ada <i>chroot</i>,
     *       jadi yang kelihatan adalah sistem Android + peralatan busybox.</li>
     *   <li>{@link #PROOT} — proot klasik berbasis <code>ptrace()</code>: tiap
     *       syscall guest = 2 context switch. Paling kompatibel (musl &amp; glibc).</li>
     *   <li>{@link #PROROOT} — drop-in proot tanpa ptrace (LD_PRELOAD + binary
     *       patching <code>svc #0</code>). Kecepatan mendekati native, tapi baru
     *       mendukung rootfs glibc (Ubuntu) — alpine/musl jatuh ke PROOT.</li>
     * </ul>
     */
    public enum Engine {
        NATIVE("native"), PROOT("proot"), PROROOT("proroot");
        public final String id;
        Engine(String id) { this.id = id; }
    }

    /** Nama berkas binary proroot di lib/&lt;abi&gt;/ (diisi manual, lisensi tidak boleh didistribusi ulang). */
    public static final String PROROOT_NAME = "libproroot.so";

    /** Nama berkas busybox di lib/&lt;abi&gt;/ untuk mode NATIVE. */
    public static final String BUSYBOX_NAME = "libbusybox.so";

    /** Binary proroot (nama gaya Gradle atau polos, sama seperti proot). */
    public static File prorootBin(String nativeLibDir) {
        return pick(nativeLibDir, PROROOT_NAME, "proroot");
    }

    /** Binary busybox untuk mode shell native. */
    public static File busyboxBin(String nativeLibDir) {
        return pick(nativeLibDir, BUSYBOX_NAME, "busybox");
    }

    /** true kalau proroot ikut terpasang di APK (lib/&lt;abi&gt;/libproroot.so). */
    public static boolean hasProroot(String nativeLibDir) {
        return prorootBin(nativeLibDir).isFile();
    }

    /** true kalau busybox ikut terpasang, jadi mode NATIVE tersedia. */
    public static boolean hasNativeShell(String nativeLibDir) {
        return busyboxBin(nativeLibDir).isFile();
    }

    /**
     * Distro berbasis musl (alpine) belum bisa memakai proroot — proroot
     * menyisipkan loader glibc-nya sendiri lewat LD_PRELOAD, jadi musl
     * dijatuhkan ke proot klasik.
     */
    public static boolean isMusl(String distroId) {
        return distroId != null && distroId.toLowerCase(java.util.Locale.US).contains("alpine");
    }

    /**
     * Pilih mesin terbaik yang tersedia untuk distro ini: proroot kalau ada dan
     * rootfs-nya glibc, selain itu proot klasik.
     */
    public static Engine engineFor(String nativeLibDir, String distroId) {
        if (hasProroot(nativeLibDir) && !isMusl(distroId)) return Engine.PROROOT;
        return Engine.PROOT;
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
        return buildCommand(Engine.PROOT, nativeLibDir, rootfs, extraBind);
    }

    /**
     * Susun command sesuai mesin. bedanya memang sengaja sedikit — proroot
     * meniru CLI proot (dokumentasinya menyebut drop-in replacement), tapi
     * tidak punya --kill-on-exit dan memakai -w untuk working directory.
     */
    public static List<String> buildCommand(Engine engine, String nativeLibDir, File rootfs,
                                            List<String> extraBind) {
        if (engine == Engine.PROROOT) return prorootCommand(nativeLibDir, rootfs, extraBind);
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBin(nativeLibDir).getAbsolutePath());
        cmd.add("--rootfs=" + rootfs.getAbsolutePath());
        cmd.add("--link2symlink");
        // -0 / --root-id: di dalam guest tampil sebagai uid/gid 0. Tanpa ini
        // guest memakai UID app host (mis. 10507) dan package manager seperti
        // apk/apt menolak jalan ("must be run as root"), sementara HOME=/root
        // yang kita set jadi tidak konsisten. Hak akses host tidak berubah:
        // proot hanya memalsukan identitas di dalam guest.
        cmd.add("-0");
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

    /** Command proroot: tanpa ptrace, jadi tanpa --kill-on-exit dan pakai -w. */
    private static List<String> prorootCommand(String nativeLibDir, File rootfs,
                                               List<String> extraBind) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prorootBin(nativeLibDir).getAbsolutePath());
        cmd.add("-r"); cmd.add(rootfs.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/dev");
        if (extraBind != null) {
            for (String b : extraBind) {
                cmd.add("-b"); cmd.add(b);
            }
        }
        cmd.add("-w"); cmd.add("/");
        cmd.add(detectShell(rootfs));
        cmd.add("-l");
        return cmd;
    }

    /**
     * Command untuk mode NATIVE: busybox berjalan di atas libc bionic tanpa
     * rootfs. Tidak ada chroot, jadi ini BUKAN distro Linux — ini shell cepat
     * untuk pekerjaan yang tidak butuh paket distro (mirip model Termux:
     * binary Android-native + utilitas ringkas).
     */
    public static List<String> nativeCommand(String nativeLibDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(busyboxBin(nativeLibDir).getAbsolutePath());
        cmd.add("sh");
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
        return environment(Engine.PROOT, filesDir, nativeLibDir, rootfs);
    }

    public static java.util.Map<String, String> environment(Engine engine, File filesDir,
                                                            String nativeLibDir, File rootfs) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        String shell = detectShell(rootfs);
        env.put("HOME", "/root");
        env.put("SHELL", shell);
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "C.UTF-8");
        // PENTING: proot mengambil direktori temp dari env PROOT_TMP_DIR
        // (src/path/temp.c: getenv("PROOT_TMP_DIR")), BUKAN TMPDIR. Kalau tidak
        // diset ia memakai P_tmpdir yang di Termux berisi
        // /data/data/com.termux/files/usr/tmp -> "can't canonicalize ..." lalu
        // "Unable to create temp directory for f2fs bug probe". Keduanya harus
        // path host yang benar-benar ada dan bisa ditulis (bukan "/tmp").
        String hostTmp = tmpDir(filesDir).getAbsolutePath();
        env.put("TMPDIR", hostTmp);
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("LD_LIBRARY_PATH", nativeLibDir);
        if (engine == Engine.PROROOT) {
            // proroot membaca PROROOT_TMP_DIR (bukan PROOT_TMP_DIR) dan tidak
            // butuh PROOT_LOADER — loader-nya milik proroot sendiri.
            env.put("PROROOT_TMP_DIR", hostTmp);
            env.put("PROOT_TMP_DIR", hostTmp);
            return env;
        }
        // proot klasik mengambil direktori temp dari PROOT_TMP_DIR, dan butuh
        // loader yang dieksekusi saat execve() guest.
        env.put("PROOT_TMP_DIR", hostTmp);
        File loader = new File(nativeLibDir, LOADER_NAME);
        if (loader.isFile()) {
            env.put("PROOT_LOADER", loader.getAbsolutePath());
        }
        return env;
    }

    /**
     * Environment untuk mode NATIVE: HOME &amp; TMPDIR milik aplikasi, PATH
     * mencakup direktori native (tempat busybox &amp; utilitas lain berada) plus
     * binary sistem Android.
     */
    public static java.util.Map<String, String> nativeEnvironment(File filesDir,
                                                                  String nativeLibDir) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        File home = new File(filesDir, "home");
        if (!home.isDirectory()) home.mkdirs();
        String hostTmp = tmpDir(filesDir).getAbsolutePath();
        env.put("HOME", home.getAbsolutePath());
        env.put("SHELL", busyboxBin(nativeLibDir).getAbsolutePath() + " sh");
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "C.UTF-8");
        env.put("TMPDIR", hostTmp);
        env.put("PATH", nativeLibDir + ":/system/bin:/system/xbin:/vendor/bin:"
                + "/product/bin:/apex/com.android.runtime/bin");
        env.put("LD_LIBRARY_PATH", nativeLibDir);
        return env;
    }
}
