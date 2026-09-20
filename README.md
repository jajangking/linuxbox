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

- **Multi-sesi**: tiap tab terminal di browser adalah proses PTY sendiri
  (`SessionManager`), lengkap dengan scrollback dan ukuran terminalnya. Sesi
  tetap berjalan walau tidak ada browser yang menonton, dan otomatis hidup lagi
  kalau shell-nya keluar.
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

### Loader proot wajib ikut dibundel (`PROOT_LOADER`)

proot **tidak** mengeksekusi binary guest secara langsung. Di
`translate_execve_enter()`, argumen `execve()` diganti dengan path **loader
internal proot**, lalu loader itu yang menjalankan program guest. proot Termux
dibangun dengan:

```bash
export PROOT_UNBUNDLE_LOADER=$TERMUX_PREFIX/libexec/proot   # packages/proot/build.sh
```

Jadi kalau env `PROOT_LOADER` tidak diset, proot akan mengeksekusi
`/data/data/com.termux/files/usr/libexec/proot/loader` — direktori data
**aplikasi lain** (mode 0700) yang tidak bisa di-traverse app kita. Kernel
menjawab `EACCES`, dan proot melaporkannya sebagai:

```
proot error: execve("/bin/sh"): Permission denied
```

Perhatikan: ini bukan SELinux (tidak akan ada `avc: denied`) dan bukan path
rootfs. Solusinya: salin loader itu ke `lib/arm64-v8a/` supaya berlabel
`apk_data_file_t`, lalu kirim path-nya lewat `PROOT_LOADER`:

- `scripts/build-apk.sh` → `$PREFIX/libexec/proot/loader` di-stage sebagai
  `libproot_loader.so` (langkah `[2b]`).
- `scripts/fetch-assets.sh` → sama, untuk jalur Gradle
  (`app/src/main/jniLibs/arm64-v8a/`).
- `ProotSession.environment()` → `PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so`.
- `Bootstrap` gagal cepat kalau loader tidak ada di `nativeLibraryDir`.

### Identitas di dalam guest: `-0` (`--root-id`)

`ProotSession.buildCommand()` selalu mengirim `-0`. Tanpa itu proot memakai UID
app host di dalam guest (mis. `uid=10507`), sehingga `apk`/`apt` menolak jalan
karena mengira bukan root dan `HOME=/root` jadi tidak konsisten. `-0` sama
dengan `-i 0:0`: identitas **dipalsukan di dalam guest saja**, hak akses
sebenarnya di host tetap UID aplikasi.

## Multi-sesi & ketahanan

### Modelnya

```
SessionManager
 ├── s1  PtyHelper ── proot ── /bin/sh        scrollback 128 KB, rows/cols sendiri
 ├── s2  PtyHelper ── proot ── /bin/sh        supervisor + auto-restart (backoff 1,5s→15s)
 └── s3  ...
```

- Setiap sesi punya **supervisor sendiri**: shell yang keluar (`exit`, crash)
  dihidupkan ulang dengan backoff, tanpa menunggu browser. Kalau sesi mati
  kurang dari 3 detik setelah start, delay digandakan (mencegah spam restart).
- **Sesi tidak bergantung pada client.** Browser boleh ditutup, HP boleh pindah
  jaringan; proses di dalam sesi tetap jalan. Saat connect ulang, scrollback
  diputar ulang sehingga isi terminal kembali utuh.
- **Keadaan disimpan ke disk** (`files/sessions.json` + `files/sessions/<id>.scroll`,
  ditulis tiap 20 detik dan saat sesi ditutup). Kalau Android membunuh service,
  sesi dikembalikan dengan id dan nama yang sama.
- **Service tahan banting**: `START_STICKY` (dihidupkan ulang dengan pengaturan
  terakhir dari SharedPreferences), `WakeLock` + `WifiLock` supaya socket tidak
  mati saat layar terkunci, dan `srv_wanted=false` saat start gagal total supaya
  tidak terjadi loop restart.
- **Keepalive**: server mengirim WebSocket PING tiap 10 detik untuk mendeteksi
  koneksi setengah terbuka; browser juga memanggil `/healthz` tiap 15 detik
  untuk mendeteksi server yang mati/hidup lagi.

### Distro per sesi & akses /sdcard

- **Tiap sesi boleh memakai distro berbeda**: satu tab alpine, tab lain ubuntu.
  `POST /api/sessions?distro=ubuntu-2404` — distro yang belum terpasang ditolak
  dengan 503. Pilihan distro tersedia di dropdown sebelah tombol *+ Sesi*.
- **`/sdcard` di-bind otomatis** ke dalam guest kalau izin penyimpanan sudah
  diberikan (diminta saat aplikasi dibuka). Statusnya tampil di baris status web
  (`sdcard ✓`). Kalau ditolak, perintah di dalam distro tidak melihat berkas HP.

### Tiga mesin: native, proot, proroot

Riset Termux/PRoot menunjukkan biaya terbesar ada di `ptrace()`: tiap syscall
guest memicu context switch (UNIXbench ≈ −44% dibanding chroot). Karena itu
linuxbox menyediakan tiga mesin, dipilih otomatis atau lewat dropdown sesi baru:

| Mesin | Cara kerja | Dipakai untuk | Syarat |
|---|---|---|---|
| **native** | `busybox sh` langsung di atas libc bionic — tanpa proot, tanpa rootfs | pekerjaan ringan yang tidak butuh paket distro; tetap jalan walau distro belum dipasang | `libbusybox.so` di `app/src/main/jniLibs/arm64-v8a/` |
| **proot** (klasik) | intersep syscall via `ptrace()` | semua distro, termasuk musl (Alpine) | `libproot.so` + loader (sudah ada) |
| **proroot** | drop-in proot tanpa ptrace: `LD_PRELOAD` + binary patching `svc #0` | distro **glibc** (Ubuntu) — kecepatan mendekati native | 5 `.so` proroot di `jniLibs/arm64-v8a/` |

Aturan pemilihan (`ProotSession.engineFor()`): sesi distro glibc memakai
**proroot** kalau tersedia, distro musl (Alpine) selalu **proot** karena proroot
menyisipkan loader glibc-nya sendiri. Mesin yang terakhir dipakai dilaporkan di
`/api/sessions` (field `engine`) dan di baris "[sesi baru dimulai: …]".

Perbedaan command proroot yang penting (dua-duanya pernah bikin loop restart di
perangkat):

- **Bind wajib `host:guest`.** proroot menolak format tunggal dengan
  `[proroot] bad bind format (expected host:guest): /proc`. Karena itu
  `/sdcard` dikirim sebagai `-b /storage/emulated/0:/sdcard` untuk kedua mesin.
- **`/proc`, `/sys`, `/dev` tidak di-bind manual** pada proroot: proroot
  menyiapkannya sendiri, dan perintah contoh resminya pun tidak mem-bind-nya.
  proot klasik tetap mem-bind ketiganya.
- proroot memakai `-w /root` dan tidak punya `--kill-on-exit`.

**Fallback otomatis:** kalau sesi mati <3 detik setelah start dua kali
berturut-turut, sesi itu diturunkan ke proot klasik (`forcedEngine`) dan
terminal mencetak "[proroot gagal dua kali, jatuh ke proot klasik untuk sesi
ini]". Jadi proroot yang tidak cocok tidak pernah menjebakmu dalam loop
restart.

**Cara mengaktifkan mesin tambahan** (keduanya opsional; tanpa berkas ini aplikasi
tetap jalan dengan proot klasik):

```sh
# proroot — lisensi proprietary: boleh dipakai, tidak boleh didistribusi ulang
# dalam bentuk modifikasi, jadi TIDAK di-commit ke repo ini.
cp libproroot.so libproroot-runtime.so libproroot-linker.so \
   libproroot-bridge.so libproroot-stub-loader.so \
   android/app/src/main/jniLibs/arm64-v8a/

# shell native — busybox dari APK Termux (perhatikan lisensi GPLv2 bila mau
# ikut mendistribusikan binary-nya di APK kamu sendiri)
unzip -p termux-app.apk lib/arm64-v8a/libbusybox.so \
  > android/app/src/main/jniLibs/arm64-v8a/libbusybox.so
```

### Unduhan tahan putus + verifikasi SHA-256

- **`distros.json` kini berisi SHA-256 asli** dari upstream (Alpine memakai
  berkas `.sha256` resminya, Ubuntu memakai `SHA256SUMS`), jadi verifikasi
  jalan sungguhan, bukan cuma dicatat di log. Nilai yang dipakai saat ini:
  | distro | berkas | sha256 |
  |---|---|---|
  | alpine | `alpine-minirootfs-3.24.2-aarch64.tar.gz` | `9bf70a7f…2ce773` |
  | ubuntu-2404 | `ubuntu-base-24.04.5-base-arm64.tar.gz` | `a91d5a93…f05914f2` |
  | ubuntu-2604 | `ubuntu-base-26.04.1-base-arm64.tar.gz` | `5a190679…c5b219fd` |
- **Lanjutkan unduhan yang putus** lewat header `Range: bytes=<pos>-`: sisa
  berkas disimpan sebagai `.part` dan dipakai lagi pada percobaan berikutnya,
  dengan jeda mengembang 1s→2s→4s…30s (maksimal 6 percobaan). Kalau server
  tidak mendukung `Range` (balas 200, bukan 206), berkas diulang dari awal.
  HTTP 4xx tidak dicoba ulang — URL salah langsung gagal, tidak menunggu 31 s.
- Ukuran akhir selalu diambil dari header (`Content-Range`/`Content-Length`),
  **bukan** dari `sizeBytes` di katalog (itu cuma tebakan untuk progress bar).
- Arsip lama di `files/rootfs-<id>.tar.gz` dicek hash-nya sebelum dipakai:
  kalau tidak cocok, dihapus dan diunduh ulang — rootfs yang setengah rusak
  tidak pernah sampai ke tahap ekstraksi.
- **Hash juga ada di `DistroCatalog.BUILTIN`** (daftar cadangan di Java).
  `load()` menggabungkan aset + bawaan per id: entri aset yang `sha256`-nya
  kosong mewarisi hash bawaan. Jadi kalau `assets/distros.json` di APK belum
  ikut ter-refresh saat build, verifikasi tetap jalan (URL & ukuran tetap dari
  aset).
- Saat memasang distro, log mencetak `Katalog distro: 3 entri dari
  distros.json, sha256 3/3` (atau `… dari bawaan Java (assets/distros.json
  tidak terbaca) …`). Kalau baris itu menunjukkan `sha256 0/3`, unduhan tidak
  akan diverifikasi — itu tanda aset di APK usang.

### Backup terenkripsi (passphrase)

Tombol *Backup* menanyakan passphrase. Kosongkan → `tar.gz` biasa seperti
sebelumnya; diisi → berkas `…tar.gz.lbx` terenkripsi.

```
"LBX1" 4B | salt 16B (acak) | nonce 12B (acak) | cipherteks + tag GCM 16B
AES-256-GCM, kunci = PBKDF2-HMAC-SHA256(passphrase, salt, 210.000 iterasi)
```

GCM dipilih karena sekaligus menjaga keutuhan: passphrase salah atau berkas
dimodifikasi → pesan "passphrase salah atau berkas backup rusak", bukan rootfs
acak yang separuh rusak. *Restore* mendeteksi format dari 4 byte pertama, jadi
berkas `.lbx` maupun `.tar.gz` sama-sama bisa dipilih.

Butuh membuka backup di komputer? Formatnya sengaja sederhana:

```python
# pip install cryptography
import sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes

src, dst, pw = sys.argv[1], sys.argv[2], sys.argv[3].encode()
raw = open(src, "rb").read()
assert raw[:4] == b"LBX1", "bukan backup linuxbox"
key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32,
                 salt=raw[4:20], iterations=210_000).derive(pw)
open(dst, "wb").write(AESGCM(key).decrypt(raw[20:32], raw[32:], None))
```

### Endpoint API

| Endpoint | Kegunaan |
|---|---|
| `GET /healthz`, `GET /api/status` | status server: port, uptime, jumlah sesi, distro, shell, `lastError` |
| `GET /api/sessions` | daftar sesi: id, nama, hidup/mati, jumlah penonton, ukuran |
| `GET /api/distros` | daftar distro + status terpasang + distro aktif |
| `POST /api/sessions` | buat sesi baru (`?name=`, `?distro=`, `?kind=native`) |
| `GET /api/engines` | mesin yang tersedia (native/distro) + status proroot |
| `POST /api/sessions/<id>/kill` | tutup sesi |
| `POST /api/sessions/<id>/rename?name=` | ganti nama tab |
| `ws /ws?session=<id>` | aliran byte PTY (frame **binary**) |
| `ws /ctl?session=<id>` | kanal kontrol: resize, `need-size`, ping |

### Antarmuka web

- Tab sesi di bagian atas: `+ sesi baru` untuk menambah, `×` untuk menutup,
  klik dua kali untuk mengganti nama. Titik hijau = sesi hidup.
- Menyambung ulang otomatis dengan backoff saat koneksi putus (pesan
  "[sambungan putus, menyambung ulang…]"), dan mendeteksi server yang mati
  lewat `/healthz` lalu menyambung lagi begitu server kembali.
- Dropdown sesi baru memilih **shell cepat (native)** atau distro tertentu.
- **Cari di terminal** (Ctrl-F atau tombol *Cari*) memakai addon
  `@xterm/addon-search`; tautan `http(s)://` bisa diklik
  (`@xterm/addon-web-links`). Keduanya opsional — tanpa berkas addon, terminal
  tetap berjalan normal.
- **Salin sebagian teks di HP**: tahan teks sekitar 0,4 detik lalu tarik.
  Marker awal (di atas teks) dan akhir (di bawah teks) bisa ditarik lagi untuk
  mempersempit/memperluas seleksi. Seleksi tetap ada saat jari dilepas atau
  layar digeser, termasuk pada scrollback. Tekan **Salin** untuk menyalin,
  **Batal** untuk menutup seleksi, atau **Ketik** untuk membuka keyboard lagi.
  Menyeleksi/menyalin tidak otomatis membuka keyboard; jika clipboard ditolak
  browser, seleksi tetap tersedia untuk dicoba ulang. Resize grid/PTY ditunda
  selama seleksi aktif agar animasi penutupan keyboard tidak menghapus seleksi.
- Tombol: bersihkan layar, `A−`/`A+` ukuran huruf, sambung ulang, bantuan.
- Di HP muncul baris tombol sentuh (esc, tab, `^C`, `^D`, `^Z`, panah, `/`, `|`)
  karena keyboard virtual tidak punya tombol itu.
- Indikator status di bawah: terhubung / menyambung ulang / server tidak
  merespons, plus jumlah sesi, distro, dan shell.

### Pengujian seleksi terminal

Pengujian browser memakai xterm 6 dan FitAddon asli, dengan backend PTY dan
clipboard pengganti (tidak memerlukan Android SDK atau sesi Linux aktif):

```bash
npm ci
npx playwright install --with-deps chromium
npm run test:web
# Opsional: gunakan Chromium yang sudah terpasang
# CHROMIUM_PATH=/path/to/chromium npm run test:web
```

Cakupan: long-press, seleksi terbalik/lintas baris, kedua marker termasuk satu
karakter di tepi layar, scrollback, scroll saat seleksi, pembatalan gestur,
kegagalan clipboard, fokus Cari/Ketik, resize saat keyboard menutup, perubahan
font, ganti sesi/bersihkan, dan seleksi mouse desktop.

Tetap lakukan uji pada HP setelah rebuild APK dan muat ulang halaman terminal:
1. Buka keyboard, tahan sebagian output, lalu tarik: keyboard menutup dan
   highlight beserta kedua marker tetap muncul setelah animasi selesai.
2. Tarik masing-masing marker, tekan **Salin**, lalu tempel di aplikasi lain:
   hanya teks terpilih yang tersalin dan keyboard tidak muncul saat menyalin.
3. Ulangi pada output lama di scrollback dan seleksi lintas baris.
4. **Batal** menutup seleksi tanpa keyboard; **Ketik** membuka keyboard dan
   input terminal kembali normal.

Emulasi browser hanya memverifikasi fokus/input dan perubahan viewport, bukan
IME/clipboard sistem Android yang sebenarnya.

### Kenapa `targetSdk` dipatok 28

Sejak Android 10, app dengan **targetSdk ≥ 29** (domain SELinux
`untrusted_app_29/_30/_32/_33`) dilarang `execve()` berkas berlabel
`app_data_file`, yaitu **seluruh isi `filesDir`** — termasuk `bin/busybox` dan
`ld-musl-aarch64.so.1` di dalam rootfs. Gejalanya: `proot` sendiri mau jalan
(karena dia diekstrak ke `nativeLibraryDir` berlabel `apk_data_file_t`), tapi
setiap perintah guest gagal `execve("/bin/sh")` dengan EACCES/ENOENT.

Termux mematok `targetSdkVersion 28` persis karena alasan ini — lihat
[Termux and Android 10](https://gitlab.com/termux-mirror/termux-dev-wiki/-/blob/master/Termux-and-Android-10.md).
Karena itu `build.gradle.kts` dan `AndroidManifest.xml` di sini sama-sama
memakai **28**. Kalau dinaikkan, distro yang rootfs-nya di `filesDir` berhenti
bekerja.

> Domain SELinux ditentukan **saat instalasi** berdasar targetSdk. Setelah
> menurunkan nilai ini: **uninstall dulu, reboot, lalu install ulang**. Verifikasi:
>
> ```bash
> adb shell run-as com.linuxbox sh -c 'cat /proc/self/attr/current'
> # harus: u:r:untrusted_app_27:s0:...  atau  ..._28:...  (BUKAN ..._33)
> adb shell "run-as com.linuxbox cp /system/bin/toybox files/tb; run-as com.linuxbox chmod 755 files/tb"
> adb shell "run-as com.linuxbox sh -c './files/tb echo WX-OK'"   # harus cetak WX-OK
> ```

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
- [x] Multi-sesi (tab terminal) + auto-restart per sesi + persistensi keadaan
- [x] Service tahan banting (START_STICKY, WakeLock/WifiLock, keepalive PING)
- [x] Efisiensi: I/O ber-buffer, batas koneksi, WakeLock dilepas saat idle
- [x] Distro per sesi + bind `/sdcard` (izin runtime)
- [x] Pencarian terminal (Ctrl-F) + tautan web bisa diklik
- [x] Mesin pluggable: shell native (bionic) / proot / proroot
- [x] Verifikasi SHA-256 unduhan distro (hash asli upstream, dicek juga untuk arsip lama)
- [x] Lanjutkan unduhan yang putus lewat HTTP Range + retry berjeda
- [x] Enkripsi backup rootfs dengan passphrase (AES-256-GCM + PBKDF2)
- [ ] Verifikasi tanda tangan (GPG/SHA256SUMS) saat mengunduh distro
- [ ] Lanjutkan unduhan yang terputus (HTTP Range)
- [ ] Enkripsi backup rootfs