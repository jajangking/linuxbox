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
                                            │  http://<ip>:PORT/  (xterm.js UI)
                                            │  ws://<ip>:PORT/ws (byte relay)
                                            ▼
                                    PtyHelper ──ptylauncher (NDK)──> exec PRoot
                                            │
                                            ▼
                                      distro rootfs (proot --rootfs=...)
```

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
curl -s http://127.0.0.1:8770/healthz   # status server + sesi shell
python3 tools/ws-smoke.py 127.0.0.1 8770  # uji end-to-end (handshake/replay/echo/binary)
```

`/healthz` mengembalikan misalnya `{"running":true,"port":8770,"sessionAlive":true,
"clients":1,"shell":"/bin/ash"}`. Kalau `sessionAlive:false`, lihat field
`lastError`.

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
5. **Ukuran terminal tidak mengikuti window.** PTY dibuat dengan winsize bawaan
   kernel (0x0) sehingga `stty size` nol dan tampilan berantakan. Sekarang
   defaultnya 80x24, lalu klien mengirim `{"type":"resize","rows":N,"cols":M}`
   ke `/ctl` setiap kali window/xterm berubah ukuran; kalau sesi shell
   di-restart, server meminta ulang ukuran lewat `{"type":"need-size"}`. Bila
   kanal kontrol (`files/ctrl.sock`) tidak bisa dibuka, terminal tetap jalan di
   80x24 — cek field `resize` di `/healthz`.

6. **Aset `xterm.js` tidak ikut ter-bundle.** `assets/web/` di repo cuma berisi
   `index.html`; kalau build Gradle dari clone bersih tanpa menjalankan
   `fetch-assets.sh`, halaman jadi hitam kosong tanpa pesan. Sekarang tugas
   Gradle `downloadWebAssets` mengunduh `xterm.js/xterm.css/fit.js` otomatis
   sebelum build, dan kalau gagal build-nya error dengan pesan jelas; sisi
   klien juga menampilkan pesan error kalau `Terminal` tidak terdefinisi.

## Status & TODO

- [x] Bootstrap: extract rootfs tar.gz + verify sha256 + copy proot
- [x] PRoot session builder (+ deteksi shell: bash/ash/sh)
- [x] PTY helper (NDK) + relay web terminal
- [x] Relay byte PTY via frame websocket **binary** (aman untuk output non-UTF-8)
- [x] Replay scrollback ke client yang connect belakangan
- [x] Supervisor sesi: auto-restart shell + pesan status ke client
- [x] `/healthz` + `tools/ws-smoke.py` untuk diagnosis
- [x] Resize PTY (TIOCSWINSZ) mengikuti ukuran window browser (kanal `/ctl`)
- [ ] Streaming progress download rootfs tanpa menaruh tar.gz di APK (fallback URL di bootstrap.json)
- [ ] Backup/export rootfs sekali tap
- [ ] Multi-distro picker
- [ ] Hardening: validasi path entry tar (Zip-slip), autentikasi web terminal opsional