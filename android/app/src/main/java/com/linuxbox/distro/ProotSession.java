package com.linuxbox.distro;

import java.io.File;

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

    public static File rootfsDir(File dir) {
        return new File(dir, "rootfs");
    }

    public static File prootBin(File dir) {
        return new File(dir, "bin/proot");
    }

    /** ptylauncher (di-compile cross Android) disalin dari asset ke filesDir/bin. */
    public static File ptyBin(File dir) {
        return new File(dir, "bin/ptylauncher");
    }

    /**
     * Pilih shell yang benar-benar ada di rootfs. Fallback terakhir /bin/sh:
     * kalau tidak ada sama sekali, pesan error proot yang tampil di terminal
     * jauh lebih berguna daripada layar hitam kosong.
     */
    public static String detectShell(File dir) {
        File root = rootfsDir(dir);
        for (String candidate : SHELL_CANDIDATES) {
            if (new File(root, candidate).isFile()) return candidate;
        }
        return "/bin/sh";
    }

    public static java.util.List<String> buildCommand(File dir) {
        return buildCommand(dir, new java.util.ArrayList<String>());
    }

    public static java.util.List<String> buildCommand(File dir, java.util.List<String> extraBind) {
        File root = rootfsDir(dir);
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(prootBin(dir).getAbsolutePath());
        cmd.add("--rootfs=" + root.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/dev");
        for (String b : extraBind) {
            cmd.add("-b"); cmd.add(b);
        }
        cmd.add("--kill-on-exit");
        String shell = detectShell(dir);
        cmd.add(shell);
        cmd.add("-l");
        return cmd;
    }

    public static java.util.Map<String, String> environment(File dir) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        String shell = detectShell(dir);
        env.put("HOME", "/root");
        env.put("SHELL", shell);
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "C.UTF-8");
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("LD_LIBRARY_PATH", new File(dir, "bin").getAbsolutePath());
        return env;
    }
}
