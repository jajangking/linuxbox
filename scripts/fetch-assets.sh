#!/bin/bash
# fetch-assets.sh — jalankan di Termux (buid machine).
# Mengisi android/app/src/main/assets dengan binary proot, rootfs distro, dan xterm.js.
set -euo pipefail

SDIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$SDIR/../android/app/src/main/assets"
ROOTFS_DISTRO="${ROOTFS_DISTRO:-alpine}"
XTERM_VER="${XTERM_VER:-6.0.0}"
FIT_VER="${FIT_VER:-0.11.0}"

mkdir -p "$ASSETS/bin" "$ASSETS/web"

echo "== 1. proot =="
PROOT_SRC="${PROOT_SRC:-$(command -v proot || true)}"
if [ -z "$PROOT_SRC" ]; then
    echo "proot tidak ditemukan. Bunyikan: pkg install proot" >&2; exit 1
fi
cp "$PROOT_SRC" "$ASSETS/bin/proot"
chmod 755 "$ASSETS/bin/proot"
echo "  proot: $(file -b "$ASSETS/bin/proot" 2>/dev/null | cut -d, -f1) (sumber: $PROOT_SRC)"

# Jalur Gradle wajib jniLibs: binary HARUS diekstrak PackageManager ke
# nativeLibraryDir (label apk_data_file_t) supaya bisa di-execve — menyalin ke
# filesDir (app_data_file_t) ditolak SELinux (EPERM). ptylauncher datang dari
# CMake/NDK, jadi jangan diduplikasi di sini.
JNILIBS="$SDIR/../android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS"
cp "$PROOT_SRC" "$JNILIBS/libproot.so"
chmod 755 "$JNILIBS/libproot.so"
for lib in "$PREFIX"/lib/libtalloc.so.2 "$PREFIX"/lib/libandroid-shmem.so; do
    [ -e "$lib" ] && cp "$lib" "$JNILIBS/" && echo "  jniLibs: $(basename "$lib")"
done
echo "  jniLibs siap di $JNILIBS"

echo "== 2. rootfs ($ROOTFS_DISTRO) =="
ROOTFS_SRC="$PREFIX/var/lib/proot-distro/installed-rootfs/$ROOTFS_DISTRO"
if [ ! -x "$ROOTFS_SRC/bin/sh" ]; then
    echo "  installing $ROOTFS_DISTRO via proot-distro..."
    command -v proot-distro >/dev/null || pkg install -y proot-distro
    proot-distro install "$ROOTFS_DISTRO"
fi
tar -C "$ROOTFS_SRC" -czf "$ASSETS/rootfs.tar.gz" .
SHA=$(sha256sum "$ASSETS/rootfs.tar.gz" | cut -d' ' -f1)
SIZE=$(stat -c%s "$ASSETS/rootfs.tar.gz")
echo "  rootfs.tar.gz: $SIZE bytes, sha256=$SHA"

echo "== 3. web terminal (xterm.js) =="
curl -fsSL -o "$ASSETS/web/xterm.js"   "https://cdn.jsdelivr.net/npm/@xterm/xterm@$XTERM_VER/lib/xterm.js"
curl -fsSL -o "$ASSETS/web/xterm.css"  "https://cdn.jsdelivr.net/npm/@xterm/xterm@$XTERM_VER/lib/xterm.css"
# paket scoped @xterm/addon-fit (paket lama @xterm/xterm-addon-fit sudah deprecated)
curl -fsSL -o "$ASSETS/web/fit.js"     "https://cdn.jsdelivr.net/npm/@xterm/addon-fit@$FIT_VER/lib/addon-fit.js"

# verifikasi: file kosong/berisi halaman error = APK cuma menampilkan layar hitam
for f in xterm.js xterm.css fit.js; do
    [ -s "$ASSETS/web/$f" ] || { echo "  GAGAL: $f kosong/tidak terunduh" >&2; exit 1; }
done
grep -q "Terminal" "$ASSETS/web/xterm.js" || { echo "  GAGAL: xterm.js bukan bundle UMD" >&2; exit 1; }
echo "  xterm.js/xterm.css/fit.js ok"

echo "== 4. update bootstrap.json =="
JF="$ASSETS/bootstrap.json"
sed -i -E "s|(\"sha256\" *: *\")[^\"]*|\1$SHA|; s|(\"sizeBytes\" *: *)[0-9]+|\1$SIZE|" "$JF"
sed -i -E "s|(\"distro\" *: *\")[^\"]*|\1$ROOTFS_DISTRO|" "$JF"

echo "== selesai. Buka android/ di Android Studio. =="