/*
 * ptylauncher.c — jalankan command dengan pseudo-terminal (PTY).
 *
 * Mengapa perlu helper C: Java/Android tidak punya forkpty()/openpty().
 * Skenario: proses ini dipanggil via ProcessBuilder (stdin/stdout = pipe).
 *   - buka PTY master+slave
 *   - fork; child: setsid + TIOCSCTTY, dup2(slave -> 0/1/2), execvp(cmd)
 *   - parent (helper): relay bytes  master <-> stdin/stdout pipes
 * Sehingga app melihat "tty" seolah-olah pipe. TODO: protocol resize (TIOCSWINSZ).
 */
#include <errno.h>
#include <pty.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static void relay(int master) {
    struct pollfd pf[2];
    char buf[4096];
    for (;;) {
        pf[0].fd = master;
        pf[0].events = POLLIN;
        pf[0].revents = 0;
        pf[1].fd = STDIN_FILENO;
        pf[1].events = POLLIN;
        pf[1].revents = 0;

        int res = poll(pf, 2, -1);
        if (res < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (pf[0].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(master, buf, sizeof(buf));
            if (n > 0) {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t w = write(STDOUT_FILENO, buf + off, (size_t)(n - off));
                    if (w <= 0) return;
                    off += w;
                }
                fflush(stdout);
            } else {
                break;
            }
        }
        if (pf[1].revents & (POLLIN | POLLHUP | POLLERR)) {
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n > 0) {
                ssize_t off = 0;
                while (off < n) {
                    ssize_t w = write(master, buf + off, (size_t)(n - off));
                    if (w <= 0) break;
                    off += w;
                }
            } else {
                break;
            }
        }
    }
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: ptylauncher <cmd> [args...]\n");
        return 2;
    }
    int master, slave;
    if (openpty(&master, &slave, NULL, NULL, NULL) != 0) {
        perror("openpty");
        return 1;
    }

    /* Ukuran PTY bawaan kernel adalah 0x0. Kalau dibiarkan, shell/pager
       (bash, less, top, ...) mengira terminal lebarnya 0 kolom dan hasilnya
       berantakan. Tidak ada TIOCSWINSZ dari sisi Java, jadi set fallback 80x24
       sebelum fork agar anak mewarisi ukuran yang masuk akal. */
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = 24;
    ws.ws_col = 80;
    if (ioctl(master, TIOCSWINSZ, &ws) < 0) {
        perror("TIOCSWINSZ");
    }

    pid_t pid = fork();
    if (pid < 0) {
        perror("fork");
        return 1;
    }
    if (pid == 0) {
        setsid();
        if (ioctl(slave, TIOCSCTTY, 0) < 0) {
            perror("TIOCSCTTY");
            _exit(1);
        }
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        close(slave);
        close(master);
        execvp(argv[1], &argv[1]);
        fprintf(stderr, "exec %s: %s\n", argv[1], strerror(errno));
        _exit(127);
    }
    close(slave);
    relay(master);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    close(master);
    return status;
}