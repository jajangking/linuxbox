# HANDOFF — LinuxBox proot session di Termux (arena, lanjut dari sini)

Semua di bawah adalah state TERAKHIR yang sudah terverifikasi langsung di device
(Infinix X6855, Android 13, /data = f2fs, Transsion ROM). Worktree =
`/data/data/com.termux/files/usr/tmp/opencode/linuxbox-build`, POHON `07e71b9`
+ 9 file modifikasi LOKAL belum di-commit (lihat `git diff`).

adb: `adb -s 10.141.58.141:45307` (wireless; adbd jalan di phone yang sama).

## Status
- Server web `http://127.0.0.1:8770` hidup, `/healthz` → `sessionAlive:false`.
- SELinux exec fix (jniLibs) **SELESAI & terverifikasi**: proot+ptylauncher
  diextract ke nativeLibraryDir `/data/app/<x>/lib/arm64`, label
  `u:object_r:apk_data_file_t`, exec OK dari `untrusted_app_32`.
- Rootfs Alpine **lengkap**: `files/rootfs-alpine` dengan `bin/busybox`,
  `bin/sh -> /bin/busybox`, `lib/ld-musl-aarch64.so.1`, dst (symlink fix di
  `TarUtil.linkStaysInside` sudah aktif).
- **BLOCKER**: proot sendiri TIDAK jalan. Urutan stderr (via ptylauncher, WS
  port 8770):

```
proot warning: can't canonicalize /data/data/com.termux/files/usr/tmp/: No such file or directory
proot warning: Unable to create temp directory for f2fs bug probe: No such file or directory
proot warning: can't chdir("/data/data/com.linuxbox/files/rootfs-alpine/./.") in the guest rootfs: No such file or directory
proot info: default working directory is now "/"
proot error: execve("/bin/sh"): No such file or directory
fatal error: see `proot --help`.
```

MISTERI UTAMA: proot (dan proot manual via run-as, dengan env dibersihkan)
menganggap working directory-nya `/data/data/com.termux/files/usr/tmp/`
(= TMPDIR Termux, bukan dir proses) MESKIPUN:
- `[ptylauncher] cwd=/data/data/com.linuxbox/files/rootfs-alpine` (sebelum fork)
- `[child] getcwd=/data/.../rootfs-alpine readlink=/proc/self/cwd=<sama> TMPDIR=/tmp PWD=(unset)` (di child)
- `--cwd=/` tetap tidak mengubah warning tsb.
- `run-as com.linuxbox sh -c 'cd files/rootfs-alpine && LD_LIBRARY_PATH=$BASE/lib/arm64 $BASE/lib/arm64/proot ...'` → SAMA persis (jadi bukan spesifik app domain).

Artinya proot tidak membaca getcwd/PWD/readlink proses untuk path itu.

## Petunjuk terbaru (lihat di bawah cara reproduksi)
Diag dalam child app domain (ptylauncher):
```
[diag] stat(.../rootfs-alpine/bin) rc=0 errno=0
[diag] realpath(/data/data/com.linuxbox/files/rootfs-alpine)=/          << anomali
[diag] chdir+getcwd=.../rootfs-alpine  (OK)
[diag] mkdir files/.proot_diag OK
```
`realpath()` dari binary app pada path data sendiri mengembalikan **`/`** —
inkonsisten dengan stat/chdir yang benar. Hipotesis kuat: ROM/f2fs/bionic
`realpath` atau `getcwd`-machinery rusak/throttled untuk proses `untrusted_app`
(Termux-proot sangat bergantung pada walk path realpath/realdir saat inisialisasi
cwd dan probe f2fs). Entah media di `/data` atau policy init yang bikin stat-path
meledak pada sebagian casus. Ini perlu diverifikasi arena.

## Reproduksi cepat
1. `adb -s 10.141.58.141:45307 shell "am force-stop com.linuxbox; monkey -p com.linuxbox -c android.intent.category.LAUNCHER 1"` (monkey Wajib: Termux sering di foreground)
2. `adb -s 10.141.58.141:45307 shell input tap 790 358` (Start, bounds `[540,292][1041,424]`)
3. `curl -s http://127.0.0.1:8770/healthz`
4. Lihat stderr proot via WS client minimal (python socket, handshake RFC6455, frame text unmasked) di `127.0.0.1:8770/ws`.

Manual tanpa app (selalu sertakan `LD_LIBRARY_PATH=$BASE/lib/arm64`!):
```
export BASE=$(adb shell pm path com.linuxbox | sed 's#package:##;s#/base.apk##' | tr -d '\r')
# tulis stdin untuk proot dulu (input must be prepared outside guest):
adb shell "echo id > /data/local/tmp/x"
adb shell "run-as com.linuxbox sh -c 'cd files/rootfs-alpine && LD_LIBRARY_PATH='"$BASE"'/lib/arm64 '"$BASE"'/lib/arm64/proot --rootfs=/data/user/0/com.linuxbox/files/rootfs-alpine --link2symlink -b /proc -b /sys -b /dev --kill-on-exit /bin/sh < /data/user/0/com.linuxbox/files/t.in'"
```
(Naikkan file stdin via `adb push` ke `/data/local/tmp` lalu `run-as ... cp /data/local/tmp/x files/t.in`; run-as TIDAK bisa baca `/data/local/tmp` langsung.)

## Uji yang sudah dibuang (negatif)
- Tidak ada env (PWD/TMPDIR/HOME/PREFIX/PROOT_* di app maupun run-as) yang memuat `/data/data/com.termux/...`.
- `readlink /proc/self/cwd` di run-as = rootfs (benar); di app = rootfs (benar).
- `proot --version` jalan di run-as (binary OK, libs OK dgn LD_LIBRARY_PATH).
- `--link2symlink` dicoba tanpa → sama.
- Binds hanya `-b /proc -b /sys -b /dev`.

## Modifikasi lokal (belum di-commit)
- `scripts/build-apk.sh` — stage proot/ptylauncher/libtalloc/libandroid-shmem ke `$WORK/jni/lib/arm64-v8a/` + inject `lib` ke unsigned.apk (langkah [2b], [10]).
- `ProotSession` — `nativeLibraryDir(ctx)` + `prootBin/ptyBin(String)` + `buildCommand/environment(String,File)` (LD_LIBRARY_PATH=nativeLibraryDir).
- `PtyHelper`, `WebTerminalServer` — pakai nativeLibraryDir.
- `Bootstrap` — verifikasi native binary (bukan copy asset).
- `MainActivity:297` — nativeLibraryDir.
- `DistroCatalog` — BUILTIN alpine `url=""` (offline, ekstrak `assets/rootfs.tar.gz`; download URL masih rawan).
- `TarUtil` — izinkan target symlink absolut (di dalam root).
- `ptylauncher.c` — print `[ptylauncher]`/`[child]`/`[diag]` + `chlvl`. (Diagnostic; hapus setelah blocker lulus.)

## Saran langkah berikut (belum dicoba)
1. Bongkar sumber proot binary yang dipakai (Termux `proot` pkg, build dgn patch f2fs `_Bool probe_f2fs_bug(const Tracee*)`, path `path/f2fs-bug.c`) → cari dari mana string cwd diambil (kemungkinan `realpath()` host-side). Bila termbuki realpath rusak di untrusted_app pada ROM ini: ganti proot dengan yang tidak bergantung realpath, atau jalankan proot lewat satu lapis ekstra (mis. `setpriv`/bubblewrap tidak ada di Android).
2. Reproduksi `realpath()->/` anomali dalam satu binary kecil porter di app-domain (bukan mix business) — pastikan bukan artifact.
3. Alternatif bypass proot: terminal langsung ke `busybox sh` (tanpa chroot) untuk UAT fungsional, lalu tangani proot terpisah.

## Level prioritas
1. Blocker proot runtime (usia: beberapa siklus).
2. Hapus debug `ptylauncher.c`, commit fixes yang sudah terverifikasi, push ke `origin/arena/01a0ba70-linuxbox`.
3. Drain `files/` bersih (`.part` dari ekstraksi lama) jika ragu.

---

## Update agent (setelah `16a967f`) — commit berikutnya

Perbaikan lanjutan berbasis catatan di atas. Tidak ada perubahan pada pendekatan
jniLibs (sudah terverifikasi di device), hanya konsistensi + dua hal yang
menyasar blocker:

1. **TMPDIR host-side** (`ProotSession.environment`) — dulu `/tmp` (path guest,
   tidak ada di Android) => `Unable to create temp directory for f2fs bug probe`.
   Sekarang `filesDir/tmp` (dibuat otomatis). Ini kemungkinan besar sumber
   warning #1 dan #2.
2. **`--cwd=/` eksplisit** di `buildCommand()` — proot tidak perlu menebak
   guest-cwd dari `realpath(host cwd)`, yang di ROM ini mengembalikan `/`
   (anomali yang kamu temukan). Menghilangkan
   `can't chdir(".../rootfs-alpine/./.")` -> `execve("/bin/sh")`.
3. **Penamaan native lib**: jniLibs sekarang `libproot.so` /
   `libptylauncher.so` (aman untuk AGP); `ProotSession.pick()` menerima nama
   `lib*.so` DAN nama polos, jadi APK lama tetap jalan.
4. **Instrumentasi ptylauncher digate** di belakang `LINUXBOX_DEBUG`
   (penanda `files/.debug`), path tidak lagi di-hardcode ke
   `/data/data/com.linuxbox/...` — diambil dari cwd + `LINUXBOX_ROOTFS`.
   Terminal bersih saat dipakai normal; nyalakan dengan:
   `adb shell run-as com.linuxbox touch files/.debug`
5. **Jalur Gradle ikut diperbaiki**: `fetch-assets.sh` sekarang men-stage
   `libproot.so` + libtalloc + libandroid-shmem ke
   `app/src/main/jniLibs/arm64-v8a/` (sebelumnya hanya ke `assets/bin/`, yang
   sudah tidak dibaca app -> jalur Android Studio pasti gagal di
   `bootstrapBinaries()`).
6. `distros.json` alpine `url=""` (offline dari `assets/rootfs.tar.gz`),
   konsisten dengan `DistroCatalog.BUILTIN`; Ubuntu tetap unduh dari CDN.

### Ulangi di device setelah update ini
```bash
adb -s <dev> shell "am force-stop com.linuxbox"
# (opsional) aktifkan diagnostik:
adb -s <dev> shell run-as com.linuxbox touch files/.debug
# start server, lalu lihat stderr proot via ws://127.0.0.1:8770/ws
```
Yang diharapkan hilang: warning `can't canonicalize .../com.termux/...tmp`,
`Unable to create temp directory`, dan `can't chdir(.../rootfs/./.)`.
Kalau `execve("/bin/sh")` masih gagal padahal `files/rootfs-alpine/bin/sh` ada,
sisanya murni masalah resolusi path proot (lihat saran #1 di atas: bongkar
`path/f2fs-bug.c` / inisialisasi cwd di proot Termux).
