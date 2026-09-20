package com.linuxbox.distro;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Identitas dari isi rootfs, bukan nama arsip/folder. Tidak menjalankan shell. */
public final class RootfsIdentity {
    private RootfsIdentity() {}

    public static String detect(File root) throws IOException {
        Path release = resolve(root, "etc/os-release");
        if (!Files.exists(release, LinkOption.NOFOLLOW_LINKS)) release = resolve(root, "usr/lib/os-release");
        if (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("rootfs tidak memiliki os-release yang valid");
        }
        byte[] buf = new byte[16385];
        int count = 0;
        try (InputStream in = Files.newInputStream(release)) {
            int n;
            while (count < buf.length && (n = in.read(buf, count, buf.length - count)) > 0) count += n;
        }
        if (count > 16384) throw new IOException("os-release terlalu besar");
        Map<String, String> values = new HashMap<>();
        for (String line : new String(buf, 0, count, StandardCharsets.UTF_8).split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            if (!key.equals("ID") && !key.equals("VERSION_ID")) continue;
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.matches("[a-zA-Z0-9._-]+") || values.put(key, value) != null) {
                throw new IOException("identitas os-release tidak valid/ambigu");
            }
        }
        String id = values.get("ID"), version = values.get("VERSION_ID");
        if ("alpine".equals(id)) return "alpine";
        if ("ubuntu".equals(id) && version != null && version.matches("[0-9]{2}\\.[0-9]{2}")) {
            return "ubuntu-" + version.replace(".", "");
        }
        throw new IOException("distro backup belum didukung: " + id + " " + version);
    }

    public static void validateShell(File root) throws IOException {
        for (String name : new String[]{"bin/bash", "bin/ash", "bin/sh", "bin/zsh"}) {
            if (Files.isRegularFile(resolve(root, name), LinkOption.NOFOLLOW_LINKS)) return;
        }
        throw new IOException("rootfs tidak memiliki shell; restore dibatalkan");
    }

    /** Resolve symlink dengan semantik guest: /usr/... berarti DI DALAM rootfs. */
    static Path resolve(File root, String name) throws IOException {
        if (Files.isSymbolicLink(root.toPath())) throw new IOException("rootfs tidak boleh berupa symlink");
        Path base = root.getCanonicalFile().toPath();
        Path current = base;
        Deque<String> pending = new ArrayDeque<>();
        for (String part : name.split("/")) pending.addLast(part);
        int links = 0;
        while (!pending.isEmpty()) {
            String part = pending.removeFirst();
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (current.equals(base)) throw new IOException("symlink keluar rootfs");
                current = current.getParent();
                continue;
            }
            Path next = current.resolve(part);
            if (Files.isSymbolicLink(next)) {
                if (++links > 40) throw new IOException("symlink rootfs berulang");
                Path target = Files.readSymbolicLink(next);
                if (target.isAbsolute()) current = base;
                String[] parts = target.toString().split("/");
                for (int i = parts.length - 1; i >= 0; i--) pending.addFirst(parts[i]);
            } else {
                current = next;
            }
        }
        return current;
    }
}
