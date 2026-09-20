package com.linuxbox.distro;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Membungkus ptylauncher (binary Android dari NDK cross-compile) lewat ProcessBuilder.
 * Helper melakukan openpty+fork+exec lalu relay master <-> pipe stdin/stdout,
 * sehingga app membaca "keluaran tty" dari process.input dan menulis ke process.input (pipanya).
 *
 * Selain relay byte, helper juga membuka kanal kontrol unix-datagram
 * ($LINUXBOX_CTRL_SOCK) untuk perintah resize PTY (TIOCSWINSZ) — Java tidak
 * punya ioctl(), jadi ukuran terminal diset oleh proses helper.
 */
public class PtyHelper {

    /** Env yang memberi tahu ptylauncher di mana harus bind socket kontrol. */
    public static final String CTRL_ENV = "LINUXBOX_CTRL_SOCK";
    private static final int CTRL_CONNECT_RETRIES = 12;
    private static final long CTRL_CONNECT_DELAY_MS = 100L;
    private static final int MAX_DIMENSION = 1000;

    private final Process process;
    private final OutputStream input;
    private final InputStream output;
    private final File ctrlSock;

    private LocalSocket ctrlSocket;
    private OutputStream ctrlOut;

    private PtyHelper(Process p, File ctrlSock) {
        this.process = p;
        this.input = p.getOutputStream();
        this.output = p.getInputStream();
        this.ctrlSock = ctrlSock;
    }

    public static PtyHelper start(File filesDir, String nativeLibDir, File rootfs,
                                  java.util.List<String> command,
                                  java.util.Map<String, String> env) throws Exception {
        File helper = ProotSession.ptyBin(nativeLibDir);
        File proot = ProotSession.prootBin(nativeLibDir);

        requireExecutable(helper, "ptylauncher");
        requireExecutable(proot, "proot");
        if (!rootfs.isDirectory()) {
            throw new IOException("rootfs belum ada di " + rootfs.getAbsolutePath()
                    + " — jalankan 'Install distro' dulu");
        }

        File ctrl = new File(filesDir, "ctrl.sock");
        java.util.Map<String, String> e = new java.util.HashMap<>(env);
        e.put(CTRL_ENV, ctrl.getAbsolutePath());
        // Diagnostik ptylauncher hanya aktif kalau ada penanda files/.debug,
        // supaya stderr helper tidak membanjiri terminal saat dipakai normal.
        if (new File(filesDir, ".debug").isFile()) e.put("LINUXBOX_DEBUG", "1");

        java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(helper.getAbsolutePath());
        argv.addAll(command);
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(rootfs.exists() ? rootfs : filesDir);
        pb.environment().clear();
        pb.environment().putAll(e);

        PtyHelper h = new PtyHelper(pb.start(), ctrl);
        h.openControl();
        return h;
    }

    /**
     * Sambung ke socket kontrol helper. Best-effort: kalau gagal (Android versi
     * tertentu, path terlalu panjang, helper telat bind), terminal tetap jalan
     * hanya dengan ukuran 80x24.
     */
    private void openControl() {
        for (int attempt = 0; attempt < CTRL_CONNECT_RETRIES; attempt++) {
            try {
                LocalSocket s = new LocalSocket(LocalSocket.SOCKET_DGRAM);
                s.connect(new LocalSocketAddress(ctrlSock.getAbsolutePath(),
                        LocalSocketAddress.Namespace.FILESYSTEM));
                ctrlSocket = s;
                ctrlOut = s.getOutputStream();
                return;
            } catch (Exception ignored) {
                sleepQuietly(CTRL_CONNECT_DELAY_MS);
            }
        }
        ctrlSocket = null;
        ctrlOut = null;
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

    /** true kalau kanal kontrol resize tersedia. */
    public boolean hasControl() {
        return ctrlOut != null;
    }

    /**
     * Ubah ukuran PTY (TIOCSWINSZ) lewat kanal kontrol helper.
     * Kernel yang mengirim SIGWINCH ke foreground process group bila ukuran berubah.
     */
    public boolean resize(int rows, int cols) {
        OutputStream o = ctrlOut;
        if (o == null) return false;
        int r = clamp(rows);
        int c = clamp(cols);
        try {
            o.write((r + " " + c + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static int clamp(int v) {
        if (v < 1) return 1;
        return Math.min(v, MAX_DIMENSION);
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
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(output);
        closeQuietly(ctrlOut);
        try { if (ctrlSocket != null) ctrlSocket.close(); } catch (Exception ignored) {}
        try { if (ctrlSock != null) ctrlSock.delete(); } catch (Exception ignored) {}
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) {}
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
