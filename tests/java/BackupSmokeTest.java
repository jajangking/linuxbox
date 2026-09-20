import com.linuxbox.distro.Crypto;
import com.linuxbox.distro.TarUtil;
import com.linuxbox.distro.RootfsIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** JVM smoke test of the real TAR/GZIP + encryption code, without Android/real rootfs. */
public final class BackupSmokeTest {
    private static final String LONG_NAME = "a".repeat(160) + ".txt";
    private static final byte[] TEXT = "project OpenCode\nHalo 日本 👋\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BINARY = new byte[131073];

    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        Path root = work.resolve("rootfs");
        Files.createDirectories(root.resolve("usr/bin"));
        Files.createDirectories(root.resolve("root/project"));
        Files.createDirectories(root.resolve("empty"));
        Files.createDirectories(root.resolve("etc"));
        Files.writeString(root.resolve("etc/os-release"), "ID=ubuntu\nVERSION_ID=\"24.04\"\n");
        Files.write(root.resolve("root/project/notes.txt"), TEXT);
        Files.write(root.resolve("root/project/" + LONG_NAME), TEXT);
        for (int i = 0; i < BINARY.length; i++) BINARY[i] = (byte) (i % 251);
        Files.write(root.resolve("usr/bin/tool"), BINARY);
        if (!root.resolve("usr/bin/tool").toFile().setExecutable(true, false)) {
            throw new AssertionError("cannot set executable bit on fixture");
        }
        Files.createSymbolicLink(root.resolve("bin"), Path.of("usr/bin"));
        Files.createSymbolicLink(root.resolve("tool-link"), Path.of("/usr/bin/tool"));

        Path archive = work.resolve("backup.tar.gz");
        int entries = TarUtil.createTarGz(root.toFile(), archive.toFile(), System.out::println);
        check(entries > 0, "backup entries");
        check(!Crypto.isEncrypted(archive.toFile()), "plain archive detected correctly");
        Path restored = work.resolve("plain-restore");
        check(TarUtil.extract(archive.toFile(), restored.toFile(), System.out::println) > 0, "plain restore");
        verify(restored);
        check(Arrays.equals(BINARY, Files.readAllBytes(restored.resolve("tool-link"))), "absolute link restored as rootfs-relative");
        System.out.println("PASS: plain backup/restore, contents, executable, symlinks, long paths");

        char[] pass = "test-only passphrase".toCharArray();
        Path encrypted = work.resolve("backup.tar.gz.lbx");
        Path decrypted = work.resolve("decrypted.tar.gz");
        try {
            Crypto.encrypt(archive.toFile(), encrypted.toFile(), pass);
            check(Crypto.isEncrypted(encrypted.toFile()), "encrypted format detected");
            Crypto.decrypt(encrypted.toFile(), decrypted.toFile(), pass);
            check(Arrays.equals(Files.readAllBytes(archive), Files.readAllBytes(decrypted)), "decrypted bytes");
            Path encryptedRestore = work.resolve("encrypted-restore");
            check(TarUtil.extract(decrypted.toFile(), encryptedRestore.toFile(), null) > 0, "encrypted restore");
            verify(encryptedRestore);
            System.out.println("PASS: encrypted backup/restore");

            Path wrong = work.resolve("wrong-pass.tar.gz");
            try {
                Crypto.decrypt(encrypted.toFile(), wrong.toFile(), "incorrect".toCharArray());
                throw new AssertionError("wrong passphrase accepted");
            } catch (IOException expected) {
                check(!Files.exists(wrong), "failed decryption plaintext removed");
            }
            verify(root); // the input fixture was not modified by either backup
            System.out.println("PASS: wrong passphrase rejected; source contents unchanged");
            Path cleanup = work.resolve("cleanup");
            Path outside = work.resolve("outside");
            Files.createDirectories(cleanup);
            Files.createDirectories(outside);
            Files.writeString(outside.resolve("keep"), "do not delete");
            Files.createSymbolicLink(cleanup.resolve("external"), outside.toAbsolutePath());
            Files.createSymbolicLink(cleanup.resolve("dangling"), Path.of("missing"));
            TarUtil.deleteRecursively(cleanup.toFile());
            check(!Files.exists(cleanup), "staging and dangling links removed");
            check(Files.readString(outside.resolve("keep")).equals("do not delete"), "cleanup never follows external symlink");
            System.out.println("PASS: staging cleanup does not follow symlinks");
        } finally {
            Crypto.wipe(pass);
        }
    }

    private static void verify(Path root) throws IOException {
        check("ubuntu-2404".equals(RootfsIdentity.detect(root.toFile())), "restored identity from contents");
        check(Arrays.equals(TEXT, Files.readAllBytes(root.resolve("root/project/notes.txt"))), "project content");
        check(Arrays.equals(TEXT, Files.readAllBytes(root.resolve("root/project/" + LONG_NAME))), "long path content");
        check(Arrays.equals(BINARY, Files.readAllBytes(root.resolve("usr/bin/tool"))), "binary content");
        check(Files.isExecutable(root.resolve("usr/bin/tool")), "executable bit");
        check(Files.isDirectory(root.resolve("empty")), "empty directory");
        check(Files.isSymbolicLink(root.resolve("bin")), "directory symlink");
        check(Files.isSymbolicLink(root.resolve("tool-link")), "file symlink");
        check(Arrays.equals(BINARY, Files.readAllBytes(root.resolve("bin/tool"))), "directory symlink target");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
