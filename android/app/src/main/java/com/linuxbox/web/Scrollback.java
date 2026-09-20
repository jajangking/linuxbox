package com.linuxbox.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Ring buffer byte untuk scrollback terminal.
 *
 * Sesi menyimpan output terakhir supaya client yang connect belakangan (atau
 * connect ulang setelah jaringan putus) langsung melihat isi terminal, bukan
 * layar kosong. Isinya byte mentah PTY, jadi disimpan apa adanya — tidak
 * dikonversi ke String (output boleh mengandung byte non-UTF-8).
 *
 * Buffer juga bisa disimpan ke disk: kalau Android membunuh service dan
 * server hidup lagi, scrollback sesi bisa dipulihkan.
 */
public final class Scrollback {

    private final byte[] buf;
    private int start;   // index byte tertua
    private int size;    // jumlah byte terpakai

    public Scrollback(int capacity) {
        buf = new byte[capacity];
    }

    public synchronized void add(byte[] data, int len) {
        if (data == null || len <= 0) return;
        if (len >= buf.length) {
            System.arraycopy(data, len - buf.length, buf, 0, buf.length);
            start = 0;
            size = buf.length;
            return;
        }
        int end = (start + size) % buf.length;
        int firstPart = Math.min(len, buf.length - end);
        System.arraycopy(data, 0, buf, end, firstPart);
        if (len > firstPart) {
            System.arraycopy(data, firstPart, buf, 0, len - firstPart);
        }
        size += len;
        if (size > buf.length) {
            start = (start + (size - buf.length)) % buf.length;
            size = buf.length;
        }
    }

    public synchronized byte[] snapshot() {
        byte[] out = new byte[size];
        int firstPart = Math.min(size, buf.length - start);
        System.arraycopy(buf, start, out, 0, firstPart);
        if (size > firstPart) {
            System.arraycopy(buf, 0, out, firstPart, size - firstPart);
        }
        return out;
    }

    public synchronized void clear() {
        start = 0;
        size = 0;
    }

    public synchronized int size() {
        return size;
    }

    /** Simpan isi buffer ke berkas (dipanggil berkala dan saat sesi ditutup). */
    public synchronized void saveTo(File file) {
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try {
            File parent = tmp.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                byte[] data = snapshot();
                out.write(data);
            } finally {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
            }
        } catch (IOException ignored) {
        }
    }

    /** Muat isi buffer dari berkas; gagal diam-diam (scrollback bukan data kritis). */
    public synchronized void loadFrom(File file) {
        if (file == null || !file.isFile()) return;
        long len = file.length();
        if (len <= 0 || len > Integer.MAX_VALUE) return;
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] data = new byte[(int) len];
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                clear();
                add(data, off);
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
    }
}
