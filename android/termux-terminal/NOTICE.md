# NOTICE — terminal emulator & view (vendor: termux-app v0.118.1)

Modul ini berisi kode yang di-vendor dari
[`termux/termux-app`](https://github.com/termux/termux-app) (tag v0.118.1),
khususnya modul `terminal-emulator` dan `terminal-view`:

- `com.termux.terminal.*` (mesin emulasi terminal)
- `com.termux.view.*` (TerminalView dan pendukungnya)

Mengikuti catatan lisensi resmi termux-app (LICENSE.md):

> Terminal Emulator for Android (jackpal/Android-Terminal-Emulator) code is
> used which is released under Apache License 2.0. Check `terminal-view` and
> `terminal-emulator` libraries.

Artinya: seluruh kode `com.termux.terminal` dan `com.termux.view` di bawah
bawah lisensi **Apache License 2.0**
(https://www.apache.org/licenses/LICENSE-2.0).

## Perubahan dari upstream

1. `JNI.java` dipertahankan sebagai stub deklarasi native (untuk menjaga diff
   minimal pada `TerminalSession`), tapi native library `libtermux.so` TIDAK
   dibangun. Jalan `createSubprocess()` hanya terpakai di modus subprocess
   bawaan; LinuxBox memakai modus eksternal yang tidak pernah menyentuh JNI.
2. `src/main/jni` (kode C `termux.c`) TIDAK ikut di-vendor.
3. `TerminalSession.java` ditambah modus "external streams" (anotasi
   `LINUXBOX`): konstruktor tanpa subprocess, `setExternalOutputStream`,
   `setExternalResizeListener`, dan `appendOutput(byte[], int)`. PTY eksternal
   difork dan dipompa oleh `SessionManager` LinuxBox.