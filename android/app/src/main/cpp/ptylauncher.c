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
#include <fcntl.h>
#include <pty.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

/*
 * Kanal kontrol resize: unix datagram socket di $LINUXBOX_CTRL_SOCK.
 * Java tidak punya ioctl(), jadi TIOCSWINSZ dilakukan di sini; app cukup
 * mengirim datagram berisi "<rows> <cols>\n". Dipilih socket (bukan menyisipkan
 * escape sequence ke stdin) supaya perintah kontrol tidak pernah tercampur
 * dengan ketikan user.
 */
static int ctrl_setup(const char *path) {
    if (path == NULL || *path == '\0') return -1;
    unlink(path);
    int fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    int fl = fcntl(fd, F_GETFL);
    if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    return fd;
}

static void ctrl_handle(int fd, int master) {
    char buf[128];
    ssize_t n = recv(fd, buf, sizeof(buf) - 1, 0);
    if (n <= 0) return;
    buf[n] = '\0';
    int rows = 0, cols = 0;
    if (sscanf(buf, "%d %d", &rows, &cols) != 2) return;
    if (rows <= 0 || cols <= 0 || rows > 1000 || cols > 1000) return;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    /* kernel mengirim SIGWINCH ke foreground process group bila ukuran berubah */
    ioctl(master, TIOCSWINSZ, &ws);
}

static void relay(int master, int ctrl_fd) {
    struct pollfd pf[3];
    char buf[4096];
    int nfds = (ctrl_fd >= 0) ? 3 : 2;
    for (;;) {
        pf[0].fd = master;
        pf[0].events = POLLIN;
        pf[0].revents = 0;
        pf[1].fd = STDIN_FILENO;
        pf[1].events = POLLIN;
        pf[1].revents = 0;
        if (ctrl_fd >= 0) {
            pf[2].fd = ctrl_fd;
            pf[2].events = POLLIN;
            pf[2].revents = 0;
        }

        int res = poll(pf, nfds, -1);
        if (res < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (ctrl_fd >= 0 && (pf[2].revents & POLLIN)) {
            ctrl_handle(ctrl_fd, master);
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

/* Diagnostik hanya jalan kalau LINUXBOX_DEBUG=1 (penanda files/.debug di app).
 * Tanpa itu stderr helper dibuang dan terminal tetap bersih. */
static int debug_enabled(void) {
    const char *d = getenv("LINUXBOX_DEBUG");
    return d != NULL && *d != '\0' && (d[0] != '0' || d[1] != '\0');
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: ptylauncher <cmd> [args...]\n");
        return 2;
    }
    if (debug_enabled()) {
        char cwd_buf[1024] = "";
        if (getcwd(cwd_buf, sizeof(cwd_buf) - 1) == NULL) {
            snprintf(cwd_buf, sizeof(cwd_buf), "<getcwd err %s>", strerror(errno));
        }
        fprintf(stderr, "[ptylauncher] cwd=%s argv1=%s\n", cwd_buf, argv[1]);
    }
    const char *ctrl_path = getenv("LINUXBOX_CTRL_SOCK");
    int master, slave;
    if (openpty(&master, &slave, NULL, NULL, NULL) != 0) {
        perror("openpty");
        return 1;
    }
    int ctrl_fd = ctrl_setup(ctrl_path);

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
        if (debug_enabled()) {
            char ccwd[1024] = "";
            if (getcwd(ccwd, sizeof(ccwd) - 1) == NULL) {
                snprintf(ccwd, sizeof(ccwd), "<err %s>", strerror(errno));
            }
            char rl[1024] = "";
            ssize_t rl_n = readlink("/proc/self/cwd", rl, sizeof(rl) - 1);
            if (rl_n < 0) {
                snprintf(rl, sizeof(rl), "<err %s>", strerror(errno));
            } else {
                rl[rl_n] = '\0';
            }
            const char *td = getenv("TMPDIR");
            const char *pw = getenv("PWD");
            fprintf(stderr, "[child] getcwd=%s readlink=%s TMPDIR=%s PWD=%s\n",
                    ccwd, rl, td ? td : "(unset)", pw ? pw : "(unset)");

            /* Bandingkan getcwd() vs realpath() pada cwd saat ini: di sebagian
             * ROM (f2fs) realpath() pada path data app mengembalikan "/" —
             * inilah yang bikin proot gagal inisialisasi cwd. Path diambil
             * generik dari cwd, tidak di-hardcode ke paket tertentu. */
            char rp[1024] = "";
            const char *rr = realpath(ccwd, rp);
            fprintf(stderr, "[diag] realpath(cwd)=%s errno=%d\n",
                    rr ? rp : "(fail)", rr ? 0 : errno);
            struct stat st;
            int rc = stat(ccwd, &st);
            fprintf(stderr, "[diag] stat(cwd) rc=%d errno=%d\n", rc, rc == 0 ? 0 : errno);
            const char *rf = getenv("LINUXBOX_ROOTFS");
            if (rf != NULL) {
                struct stat rst;
                int rrc = stat(rf, &rst);
                fprintf(stderr, "[diag] stat(rootfs=%s) rc=%d errno=%d\n",
                        rf, rrc, rrc == 0 ? 0 : errno);
            }
        }
        execvp(argv[1], &argv[1]);
        fprintf(stderr, "exec %s: %s\n", argv[1], strerror(errno));
        _exit(127);
    }
    close(slave);
    relay(master, ctrl_fd);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
    close(master);
    if (ctrl_fd >= 0) close(ctrl_fd);
    if (ctrl_path != NULL) unlink(ctrl_path);
    return status;
}