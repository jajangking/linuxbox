package com.linuxbox.distro;

import java.io.File;

/** Lokasi & assembly command untuk PRoot session. */
public final class ProotSession {

    private ProotSession() {}

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
        cmd.add("/bin/bash");
        cmd.add("-l");
        return cmd;
    }

    public static java.util.Map<String, String> environment(File dir) {
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("HOME", "/root");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("LD_LIBRARY_PATH", new File(dir, "bin").getAbsolutePath());
        return env;
    }
}