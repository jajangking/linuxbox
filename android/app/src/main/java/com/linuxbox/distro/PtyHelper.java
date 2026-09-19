package com.linuxbox.distro;

import java.io.File;
import java.io.IOException;
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
        File proot = ProotSession.prootBin(dir);
        File rootfs = ProotSession.rootfsDir(dir);

        requireExecutable(helper, "ptylauncher");
        requireExecutable(proot, "proot");
        if (!rootfs.isDirectory()) {
            throw new IOException("rootfs belum ada di " + rootfs.getAbsolutePath()
                    + " — jalankan 'Install distro' dulu");
        }

        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(helper.getAbsolutePath());
        argv.addAll(command);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(rootfs.exists() ? rootfs : dir);
        pb.environment().clear();
        pb.environment().putAll(env);
        return new PtyHelper(pb.start());
    }

    private static void requireExecutable(File f, String label) throws IOException {
        if (!f.exists()) {
            throw new IOException(label + " tidak ditemukan: " + f.getAbsolutePath());
        }
        if (!f.canExecute() && !f.setExecutable(true)) {
            throw new IOException(label + " tidak bisa dieksekusi: " + f.getAbsolutePath());
        }
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

    /** true kalau proses helper (dan karena itu shell-nya) masih hidup. */
    public boolean isAlive() {
        return process.isAlive();
    }

    /** Exit code, atau null kalau proses masih berjalan. */
    public Integer exitValue() {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return null;
        }
    }

    public void close() {
        try { input.close(); } catch (Exception ignored) {}
        try { process.destroy(); } catch (Exception ignored) {}
        try {
            // jangan menggantung thread relay: beri waktu lalu paksa
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(output);
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) {}
    }
}
