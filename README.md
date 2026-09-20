# LinuxBox — Linux distro di Android, diakses dari browser

PoC/skeleton app Android (Kotlin + NDK) yang menjalankan distro Linux (via **PRoot**,
tanpa root) di dalam storage app, lalu mengekspos terminal interaktif lewat **web
terminal (HTTP + WebSocket + xterm.js)**. Bisa dibuka dari HP sendiri (WebView) maupun
dari laptop di jaringan yang sama — "desktop Linux di pocket, diakses dari browser mana pun".

## Arsitektur

```
MainActivity ──install──> Bootstrap (unduh/extract rootfs + proot)
      │
      └──start──> TermServerService ──> WebTerminalServer (ServerSocket 127.0.0.1/<lan>)
                                            │  http://<ip>:PORT/  (xterm.js UI, ?token=...)
                                            │  ws://<ip>:PORT/ws  (relay byte, frame binary)
                                            │  ws://<ip>:PORT/ctl (kontrol JSON: resize)
                                            ▼
                                    PtyHelper ──ptylauncher (NDK)──> exec PRoot
                                            │
                                            ▼
                                      distro rootfs (proot --rootfs=...)
```

- **Multi-distro**: setiap distro punya direktori sendiri (`files/rootfs-<id>`) dan
  daftarnya ada di `assets/distros.json` (bisa diedit tanpa ubah kode). Distro
  aktif tersimpan di SharedPreferences.
- **Unduhan streaming**: rootfs diunduh langsung dari `url` di katalog (progress,
  SHA-256 sekali jalan, berkas `.part` dulu) — tidak perlu menaruh tar.gz besar
  di APK. `assets/rootfs.tar.gz` tetap didukung sebagai fallback.
- **Backup/restore**: rootfs aktif dikemas jadi tar.gz (+`.sha256`), disalin ke
  folder Download, dan bisa dipulihkan dari berkas pilihan (SAF).
- **Autentikasi**: saat server dibuka ke LAN, akses web terminal wajib token
  (`?token=...` atau cookie `linuxbox_token`); tanpa token -> halaman 401 yang
  menjelaskan apa yang harus dilakukan.
- **PRoot**: emulasi root/no-root. Binary di-bundle dari Termux build machine.
- **ptylauncher.c**: helper NDK kecil — `openpty()+fork()`, pasang slave ke stdio
  guest, lalu relay `master <-> stdin/stdout` proses. Alasan: Java/Android tidak punya
  `forkpty`, jadi PTY dibuat di sisi C dan bytes dialirkan lewat pipe proses biasa.
  Helper juga membuka kanal kontrol unix-datagram di `$LINUXBOX_CTRL_SOCK`
  (`files/ctrl.sock`) untuk menerima perintah `TIOCSWINSZ` — Java tidak punya
  `ioctl()`, jadi resize PTY dikerjakan di sini (kernel yang mengirim `SIGWINCH`).
- **WebTerminalServer**: server HTTP + WebSocket minimal tanpa library (RFC6455 subset),
  melayani `index.html` + asset dari `assets/web/`, dan relay byte PTY ↔ client.
  Ada dua endpoint websocket: `/ws` (aliran byte terminal, frame binary) dan
  `/ctl` (pesan kontrol JSON, mis. resize).

## Persyaratan build

- Android Studio (AGP 8.5+) + NDK (dipasang otomatis saat diminta).
- Distro/proot binary dihasilkan lewat `scripts/fetch-assets.sh` yang dijalankan di
  **Termux** (mesin pengembang), karena Termux sudah punya `proot` dan `proot-distro`.

## Build

```bash
cd scripts && ./fetch-assets.sh        # jalankan di Termux (isilkan assets)
# lalu build APK di Android Studio (atau gradle assembleDebug)
```

> Catatan: proyek ini belum berisi `gradlew`+`gradle-wrapper.jar`; Android Studio
> akan menawarkan regenerate wrapper saat dibuka ("Gradle wrapper missing").
> Butuh NDK + CMake (Studio akan minta install saat pertama build).

### Kenapa binary native harus di `lib/<abi>/` (jniLibs), bukan `filesDir`

Sejak targetSdk 30, domain SELinux `untrusted_app_30/_32` **tidak boleh
`execve()` berkas berlabel `app_data_file_t`** — yaitu semua yang ada di
`filesDir`. Mencoba menjalankan proot/ptylauncher dari sana berujung
`error=13 EPERM` (atau `Permission denied` tanpa keterangan).

Karena itu binary di-stage ke dalam APK pada `lib/arm64-v8a/`:

- **Jalur Gradle/Android Studio**: `scripts/fetch-assets.sh` menaruh
  `libproot.so`, `libtalloc.so.2`, `libandroid-shmem.so` di
  `app/src/main/jniLibs/arm64-v8a/`; `libptylauncher.so` datang dari CMake/NDK
  (`CMakeLists.txt` sengaja `SHARED` supaya ikut ter-packaging meski ber-`main()`).
- **Jalur `scripts/build-apk.sh`** (aapt2 manual): langkah `[2b]` men-stage
  binary ke `$WORK/jni/lib/arm64-v8a/` dan langkah `[10]` menyuntikkannya ke APK
  dengan `jar uf unsigned.apk lib` — **sebelum** penandatanganan di `[11]`.

Saat instalasi, PackageManager mengekstrak `lib/<abi>/` ke `nativeLibraryDir`
(`/data/app/<pkg>/lib/arm64`) dengan label `apk_data_file_t`, yang **boleh**
di-`execve`. Kode Java mengambil path itu lewat
`ProotSession.nativeLibraryDir(ctx)` dan memakainya juga untuk
`LD_LIBRARY_PATH`. Kalau binary tidak ketemu di sana, `Bootstrap` gagal cepat
dengan pesan yang menyuruh mem-build ulang dengan `lib/arm64-v8a/`.

`ProotSession.pick()` menerima nama `libproot.so` maupun `proot`, jadi APK yang
dibangun sebelum penamaan ini berlaku tetap bisa jalan.

### Diagnostik di device

Kalau proot gagal jalan di HP, jalankan skrip ini **dari Termux di HP itu
sendiri** (bukan dari PC/agent — agent tidak punya akses adb), lalu kirim
keluarannya:

```bash
bash scripts/device-diag.sh                       # device tunggal
bash scripts/device-diag.sh 10.141.58.141:45307   # kalau pakai wireless adb
```

Skrip itu mengumpulkan: info device + status SELinux, isi `nativeLibraryDir`
beserta labelnya (`ls -Z`, harus `apk_data_file_t`), kelayakan rootfs, logcat,
dan — yang terpenting — **tiga varian eksekusi proot** (`TMPDIR=/tmp` vs
`files/tmp`, dengan dan tanpa `--cwd=/`) supaya kelihatan varian mana yang
masih mengeluh.

### Diagnostik helper (opsional)

`ptylauncher` bisa menulis info cwd/`realpath`/`TMPDIR` ke terminal untuk
menyelidiki kegagalan proot. Aktifkan dengan menaruh penanda, lalu start ulang
server:

```bash
adb shell run-as com.linuxbox touch files/.debug   # hapus berkas ini untuk mematikan
```

## Jalankan cepat (PoC tanpa build APK)

Di Termux, validasi konsep langsung di HP:

```bash
pkg install proot-distro ttyd
scripts/dev-terminal.sh      # login distro + ttyd :8000
# buka http://127.0.0.1:8000 di browser
```

## Troubleshooting: "command tidak tampil" / layar terminal kosong

Urutan pemeriksaan paling cepat:

```bash
adb forward tcp:8770 tcp:8770
curl -s "http://127.0.0.1:8770/healthz?token=TOKEN"   # status server + sesi shell
python3 tools/ws-smoke.py 127.0.0.1 8770 [token]      # uji end-to-end
```

`/healthz` mengembalikan misalnya `{"running":true,"port":8770,"sessionAlive":true,
"clients":1,"resize":true,"auth":true,"distro":"alpine","shell":"/bin/ash"}`.
Kalau `sessionAlive:false`, lihat field `lastError`. Token (kalau aktif) ada di
log aplikasi / URL yang disiarkan; tanpa token, request apa pun mendapat
halaman 401.

Penyebab yang sudah pernah terjadi (dan sudah diperbaiki di kode ini):

1. **Shell di-hardcode ke `/bin/bash`.** Alpine (distro default) tidak punya bash,
   jadi proot gagal `exec`, sesi mati sebelum browser sempat connect, dan client
   yang connect belakangan tidak pernah menerima apa pun — terminal bisu total.
   Sekarang `ProotSession.detectShell()` memilih `/bin/bash` → `/bin/ash` →
   `/bin/sh` berdasar isi rootfs.
2. **Tidak ada replay output.** Shell sudah jalan (dan sudah mencetak prompt)
   0,5 detik sebelum halaman web selesai dimuat; semua byte itu hilang karena
   tidak ada yang menyimpannya → layar hitam sampai user mengetik sesuatu.
   Sekarang 256 KB output terakhir disimpan di buffer scrollback dan dikirim
   ulang ke client saat websocket connect.
3. **Output PTY dikirim sebagai frame websocket TEXT.** Frame text wajib UTF-8
   valid; begitu output berisi byte non-UTF-8, atau karakter multibyte yang
   kepotong di batas `read()` 8192 byte, browser **memutus koneksi**
   (RFC6455) — output berhenti di tengah jalan. Sekarang semua output PTY
   dikirim sebagai frame **BINARY** (0x2) dan `index.html` membacanya dengan
   `Uint8Array`.
4. **Sesi tidak pernah dimulai ulang.** Sekali shell keluar (ketik `exit`,
   crash, atau gagal exec), server tetap hidup tapi terminal selamanya bisu.
   Sekarang ada supervisor: sesi di-restart dengan backoff 1,5s → 15s dan
   statusnya dikirim ke client.
5. **proot gagal dengan `Unable to create temp directory for f2fs bug probe`
   atau `can't chdir(.../rootfs/./.)`.** proot membuat direktori sementara untuk
   probe f2fs **sebelum** guest rootfs aktif, jadi `TMPDIR` harus berupa path
   host yang benar-benar ada dan bisa ditulis — bukan `/tmp` (tidak ada di
   Android). `ProotSession.environment()` kini memakai `filesDir/tmp` dan
   membuatnya kalau perlu, serta selalu mengirim `--cwd=/` supaya proot tidak
   perlu menebak guest-cwd (di sebagian ROM `realpath()` pada path data app
   mengembalikan `/`).

6. **Ukuran terminal tidak mengikuti window.** PTY dibuat dengan winsize bawaan
   kernel (0x0) sehingga `stty size` nol dan tampilan berantakan. Sekarang
   defaultnya 80x24, lalu klien mengirim `{"type":"resize","rows":N,"cols":M}`
   ke `/ctl` setiap kali window/xterm berubah ukuran; kalau sesi shell
   di-restart, server meminta ulang ukuran lewat `{"type":"need-size"}`. Bila
   kanal kontrol (`files/ctrl.sock`) tidak bisa dibuka, terminal tetap jalan di
   80x24 — cek field `resize` di `/healthz`.

7. **Aset `xterm.js` tidak ikut ter-bundle.** `assets/web/` di repo cuma berisi
   `index.html`; kalau build Gradle dari clone bersih tanpa menjalankan
   `fetch-assets.sh`, halaman jadi hitam kosong tanpa pesan. Sekarang tugas
   Gradle `downloadWebAssets` mengunduh `xterm.js/xterm.css/fit.js` otomatis
   sebelum build, dan kalau gagal build-nya error dengan pesan jelas; sisi
   klien juga menampilkan pesan error kalau `Terminal` tidak terdefinisi.

## Catatan upgrade

Sejak dukungan multi-distro, rootfs tidak lagi di `files/rootfs` melainkan
`files/rootfs-<id>` (mis. `files/rootfs-alpine`). Kalau kamu sudah pernah
memasang distro dengan versi sebelumnya, jalankan **'Pasang distro'** sekali lagi
(setelah itu terminal akan memakai direktori baru).

## Status & TODO

- [x] Bootstrap: extract rootfs tar.gz + verify sha256 + copy proot
- [x] PRoot session builder (+ deteksi shell: bash/ash/sh)
- [x] PTY helper (NDK) + relay web terminal
- [x] Relay byte PTY via frame websocket **binary** (aman untuk output non-UTF-8)
- [x] Replay scrollback ke client yang connect belakangan
- [x] Supervisor sesi: auto-restart shell + pesan status ke client
- [x] `/healthz` + `tools/ws-smoke.py` untuk diagnosis
- [x] Resize PTY (TIOCSWINSZ) mengikuti ukuran window browser (kanal `/ctl`)
- [x] Unduhan streaming rootfs dari URL katalog (progress + sha256 + `.part`)
- [x] Backup/restore rootfs satu tap (tar.gz + `.sha256`, ekspor ke Download)
- [x] Multi-distro picker (`assets/distros.json`, rootfs per-`<id>`)
- [x] Hardening: validasi path entry tar (Zip-slip) + symlink escape, autentikasi token
- [ ] Verifikasi tanda tangan (GPG/SHA256SUMS) saat mengunduh distro
- [ ] Lanjutkan unduhan yang terputus (HTTP Range)
- [ ] Enkripsi backup rootfs