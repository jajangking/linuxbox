package com.linuxbox.distro;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Enkripsi backup rootfs dengan passphrase — AES-256-GCM, kunci diturunkan dari
 * passphrase lewat PBKDF2-HMAC-SHA256.
 *
 * Format berkas (semua little-endian tidak berlaku; ini sekadar urutan byte):
 *
 * <pre>
 *   "LBX1"  4 byte   penanda: linuxbox backup terenkripsi, format 1
 *   salt   16 byte   acak tiap kali backup (jadi dua backup dengan passphrase
 *                    sama tetap menghasilkan cipherteks berbeda)
 *   nonce  12 byte   acak, sekali pakai untuk GCM
 *   data    n byte   cipherteks + tag autentikasi GCM 16 byte di ujungnya
 * </pre>
 *
 * GCM dipilih karena sekaligus memberi kerahasiaan <i>dan</i> keutuhan: kalau
 * passphrase salah atau berkas dimodifikasi, proses dekripsi gagal dengan
 * {@code AEADBadTagException} (diterjemahkan jadi "passphrase salah atau
 * berkas rusak"), bukan menghasilkan rootfs acak yang separuh rusak.
 *
 * Catatan: ini melindungi berkas backup yang disalin ke Download/Dropbox/dsb.
 * Ia tidak melindungi rootfs yang sedang dipakai di HP, dan tidak bisa
 * menggantikan enkripsi disk penuh.
 */
public final class Crypto {

    /** Ekstensi berkas backup terenkripsi. */
    public static final String EXTENSION = ".lbx";

    private static final byte[] MAGIC = {'L', 'B', 'X', '1'};
    private static final int SALT_LEN = 16;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    /** Angka OWASP (2023) untuk PBKDF2-HMAC-SHA256; ±0,3–1 detik di HP. */
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int BUF = 1 << 16;

    private Crypto() {}

    /** true kalau 4 byte pertama berkas ini adalah penanda format kita. */
    public static boolean isEncrypted(File f) throws IOException {
        if (f == null || !f.isFile() || f.length() < MAGIC.length + SALT_LEN + NONCE_LEN) {
            return false;
        }
        byte[] head = new byte[MAGIC.length];
        try (InputStream in = new FileInputStream(f)) {
            if (in.read(head) != head.length) return false;
        }
        return Arrays.equals(head, MAGIC);
    }

    /** Enkripsi berkas {@code in} -> {@code out}. Passphrase wajib tidak kosong. */
    public static void encrypt(File in, File out, char[] passphrase) throws IOException {
        requirePassphrase(passphrase);
        if (in == null || !in.isFile()) throw new IOException("berkas sumber tidak ada");

        byte[] salt = new byte[SALT_LEN];
        byte[] nonce = new byte[NONCE_LEN];
        SecureRandom rnd = new SecureRandom();
        rnd.nextBytes(salt);
        rnd.nextBytes(nonce);

        SecretKey key = deriveKey(passphrase, salt);
        boolean ok = false;
        try (InputStream src = new FileInputStream(in);
             OutputStream dst = new FileOutputStream(out)) {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            dst.write(MAGIC);
            dst.write(salt);
            dst.write(nonce);

            byte[] buf = new byte[BUF];
            int n;
            while ((n = src.read(buf)) > 0) {
                byte[] enc = c.update(buf, 0, n);
                if (enc != null && enc.length > 0) dst.write(enc);
            }
            byte[] tail = c.doFinal();
            if (tail != null && tail.length > 0) dst.write(tail);
            dst.flush();
            ok = true;
        } catch (Exception e) {
            throw new IOException("gagal mengenkripsi: " + e.getMessage(), e);
        } finally {
            if (!ok && out != null) out.delete();
        }
    }

    /** Dekripsi berkas {@code in} -> {@code out}. Salah passphrase = IOException. */
    public static void decrypt(File in, File out, char[] passphrase) throws IOException {
        requirePassphrase(passphrase);
        if (in == null || !in.isFile()) throw new IOException("berkas backup tidak ada");
        if (!isEncrypted(in)) throw new IOException("bukan berkas backup terenkripsi linuxbox");

        boolean ok = false;
        try (InputStream src = new FileInputStream(in);
             OutputStream dst = new FileOutputStream(out)) {
            byte[] salt = new byte[SALT_LEN];
            byte[] nonce = new byte[NONCE_LEN];
            skipExactly(src, MAGIC.length);
            readExactly(src, salt);
            readExactly(src, nonce);

            SecretKey key = deriveKey(passphrase, salt);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));

            byte[] buf = new byte[BUF];
            int n;
            while ((n = src.read(buf)) > 0) {
                byte[] dec = c.update(buf, 0, n);
                if (dec != null && dec.length > 0) dst.write(dec);
            }
            byte[] tail = c.doFinal();
            if (tail != null && tail.length > 0) dst.write(tail);
            dst.flush();
            ok = true;
        } catch (javax.crypto.AEADBadTagException e) {
            throw new IOException("passphrase salah atau berkas backup rusak");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("gagal mendekripsi: " + e.getMessage(), e);
        } finally {
            if (!ok && out != null) out.delete();
        }
    }

    /** Kunci AES-256 dari passphrase + salt dengan PBKDF2-HMAC-SHA256. */
    private static SecretKey deriveKey(char[] passphrase, byte[] salt) throws IOException {
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS);
            byte[] raw = f.generateSecret(spec).getEncoded();
            return new SecretKeySpec(raw, "AES");
        } catch (Exception e) {
            throw new IOException("tidak bisa menurunkan kunci: " + e.getMessage(), e);
        }
    }

    private static void requirePassphrase(char[] passphrase) throws IOException {
        if (passphrase == null || passphrase.length == 0) {
            throw new IOException("passphrase kosong");
        }
    }

    /** Hapus isi array passphrase dari memori (char[] sengaja dipakai, bukan String). */
    public static void wipe(char[] passphrase) {
        if (passphrase != null) Arrays.fill(passphrase, '\0');
    }

    private static void readExactly(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) throw new IOException("berkas backup terpotong pada header");
            off += n;
        }
    }

    private static void skipExactly(InputStream in, int count) throws IOException {
        long skipped = 0;
        while (skipped < count) {
            long n = in.skip(count - skipped);
            if (n <= 0) {
                if (in.read() < 0) throw new IOException("berkas backup terpotong");
                n = 1;
            }
            skipped += n;
        }
    }

    /** Ringkasan parameter, untuk ditulis ke log supaya formatnya jelas. */
    public static String describe() {
        return "AES-256-GCM, PBKDF2-HMAC-SHA256 " + PBKDF2_ITERATIONS
                + " iterasi, salt " + SALT_LEN + "B, nonce " + NONCE_LEN + "B";
    }
}
