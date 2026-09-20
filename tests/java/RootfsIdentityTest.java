import com.linuxbox.distro.RootfsGuard;
import com.linuxbox.distro.RootfsIdentity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** No Android SDK or third-party libraries needed. Only synthetic rootfs fixtures. */
public final class RootfsIdentityTest {
    private static int passed;
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]);
        Path ubuntu = fixture(work, "rootfs-alpine", "ID=ubuntu\nVERSION_ID=\"24.04\"\nPRETTY_NAME=\"Ubuntu 24.04.5 LTS\"\n");
        check("ubuntu-2404".equals(RootfsIdentity.detect(ubuntu.toFile())), "content overrides misleading folder name");
        RootfsIdentity.validateShell(ubuntu.toFile());
        check("alpine".equals(RootfsIdentity.detect(fixture(work, "alpine", "ID='alpine'\nVERSION_ID=3.24.2\n").toFile())), "alpine single quotes");
        check("ubuntu-2604".equals(RootfsIdentity.detect(fixture(work, "new-ubuntu", "ID=ubuntu\nVERSION_ID=26.04\n").toFile())), "ubuntu 26.04");

        Path relative = fixture(work, "relative", "ID=ubuntu\nVERSION_ID=24.04\n");
        Files.createDirectories(relative.resolve("usr/lib"));
        Files.move(relative.resolve("etc/os-release"), relative.resolve("usr/lib/os-release"));
        check("ubuntu-2404".equals(RootfsIdentity.detect(relative.toFile())), "fallback to usr/lib/os-release");
        Files.createSymbolicLink(relative.resolve("etc/os-release"), Path.of("../usr/lib/os-release"));
        check("ubuntu-2404".equals(RootfsIdentity.detect(relative.toFile())), "relative os-release symlink");
        Files.delete(relative.resolve("etc/os-release"));
        Files.createSymbolicLink(relative.resolve("etc/os-release"), Path.of("/usr/lib/os-release"));
        check("ubuntu-2404".equals(RootfsIdentity.detect(relative.toFile())), "absolute symlink stays in guest root");

        Path escape = fixture(work, "escape", "ID=alpine\n");
        Files.delete(escape.resolve("etc/os-release"));
        Files.createSymbolicLink(escape.resolve("etc/os-release"), Path.of("../../outside"));
        reject(() -> RootfsIdentity.detect(escape.toFile()), "relative escape rejected");
        Files.delete(escape.resolve("etc/os-release"));
        Files.createSymbolicLink(escape.resolve("etc/os-release"), Path.of("os-release"));
        reject(() -> RootfsIdentity.detect(escape.toFile()), "symlink loop rejected");

        reject(() -> RootfsIdentity.detect(fixture(work, "empty", "NAME=Ubuntu\n").toFile()), "missing ID rejected");
        reject(() -> RootfsIdentity.detect(fixture(work, "duplicate", "ID=alpine\nID=ubuntu\nVERSION_ID=24.04\n").toFile()), "ambiguous ID rejected");
        reject(() -> RootfsIdentity.detect(fixture(work, "debian", "ID=debian\nVERSION_ID=12\n").toFile()), "unsupported distro rejected, not guessed");
        reject(() -> RootfsIdentity.detect(fixture(work, "injection", "ID=$(touch_pwned)\n").toFile()), "shell substitution not evaluated");
        reject(() -> RootfsIdentity.detect(fixture(work, "large", "#".repeat(16385)).toFile()), "oversized metadata rejected");
        Path noShell = fixture(work, "no-shell", "ID=alpine\n");
        Files.delete(noShell.resolve("bin/sh"));
        reject(() -> RootfsIdentity.validateShell(noShell.toFile()), "incomplete rootfs rejected");

        check(RootfsGuard.beginService(), "service may start normally");
        check(RootfsGuard.beginService(), "second service lease tracked");
        reject(RootfsGuard::beginChange, "restore blocked while service active");
        RootfsGuard.endService();
        reject(RootfsGuard::beginChange, "startup still holds another lease");
        RootfsGuard.endService();
        RootfsGuard.Change change = RootfsGuard.beginChange();
        check(!RootfsGuard.beginService(), "service blocked during filesystem change");
        reject(RootfsGuard::beginChange, "concurrent change rejected");
        change.close(); change.close();
        check(RootfsGuard.beginService(), "service resumes after change, double-close safe");
        RootfsGuard.endService();
        System.out.println("PASS: " + passed + " identity/maintenance assertions");
    }
    private static Path fixture(Path work, String name, String release) throws IOException {
        Path root = work.resolve(name);
        Files.createDirectories(root.resolve("etc"));
        Files.createDirectories(root.resolve("bin"));
        Files.writeString(root.resolve("etc/os-release"), release);
        Files.writeString(root.resolve("bin/sh"), "fixture shell");
        return root;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
    private interface Action { void run() throws Exception; }
    private static void reject(Action action, String message) throws Exception {
        try { action.run(); }
        catch (IOException expected) { passed++; return; }
        throw new AssertionError(message);
    }
}
