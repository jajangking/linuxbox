package com.linuxbox.distro;

import java.io.IOException;

/** Admission gate: never rename a rootfs while the service/PTY may still use it. */
public final class RootfsGuard {
    private static int services;
    private static boolean changing;
    private RootfsGuard() {}

    public static synchronized boolean beginService() {
        if (changing) return false;
        services++;
        return true;
    }

    public static synchronized void endService() {
        if (services <= 0) throw new IllegalStateException("unbalanced service lease");
        services--;
    }

    public static synchronized Change beginChange() throws IOException {
        if (services > 0) throw new IOException("Stop server dulu, tunggu sesi berhenti, lalu coba lagi.");
        if (changing) throw new IOException("Operasi rootfs lain sedang berjalan.");
        changing = true;
        return new Change();
    }

    public static final class Change implements AutoCloseable {
        private boolean closed;
        private Change() {}
        @Override public void close() {
            synchronized (RootfsGuard.class) {
                if (!closed) { changing = false; closed = true; }
            }
        }
    }
}
