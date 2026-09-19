package com.linuxbox.distro;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Membungkus ptylauncher (binary Android dari NDK cross-compile) lewat ProcessBuilder.
 * Helper melakukan openpty+fork+exec lalu relay master <-> pipe stdin/stdout,
 * sehingga app membaca "keluaran tty" dari process.input dan menulis ke process.input (pipanya).
 */
public class PtyHelper {

    private final Process process;
    private final OutputStream input;
    private final InputStream output;

    private PtyHelper(Process p) {
        this.process = p;
        this.input = p.getOutputStream();
        this.output = p.getInputStream();
    }

    public static PtyHelper start(File dir, java.util.List<String> command,
                                  java.util.Map<String, String> env) throws Exception {
        File helper = ProotSession.ptyBin(dir);
        File rootfs = ProotSession.rootfsDir(dir);
        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(helper.getAbsolutePath());
        argv.addAll(command);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(rootfs.exists() ? rootfs : dir);
        pb.environment().clear();
        pb.environment().putAll(env);
        return new PtyHelper(pb.start());
    }

    public OutputStream getInput() {
        return input;
    }

    public InputStream getOutput() {
        return output;
    }

    public InputStream getError() {
        return process.getErrorStream();
    }

    public void close() {
        try { process.destroy(); } catch (Exception ignored) {}
        try { process.waitFor(); } catch (Exception ignored) {}
    }
}