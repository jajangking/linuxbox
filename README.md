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
- **WebTerminalServer**: server HTTP + WebSocket minimal tanpa library (RFC6455 subset),
  melayani `index.html` + asset dari `assets/web/`, dan relay byte PTY ↔ client.

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

## Status & TODO

- [x] Bootstrap: extract rootfs tar.gz + verify sha256 + copy proot
- [x] PRoot session builder
- [x] PTY helper (NDK) + relay web terminal
- [ ] Resize PTY (TIOCSWINSZ) saat window resize — sekarang fix 80x24
- [ ] Streaming progress download rootfs tanpa menaruh tar.gz di APK (fallback URL di bootstrap.json)
- [ ] Backup/export rootfs sekali tap
- [ ] Multi-distro picker
- [ ] Hardening: validasi path entry tar (Zip-slip), autentikasi web terminal opsional