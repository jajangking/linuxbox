#!/bin/bash
# fetch-assets.sh — jalankan di Termux (buid machine).
# Mengisi android/app/src/main/assets dengan binary proot, rootfs distro, dan xterm.js.
set -euo pipefail

SDIR="$(cd "$(dirname "$0")" && pwd)"
ASSETS="$SDIR/../android/app/src/main/assets"
ROOTFS_DISTRO="${ROOTFS_DISTRO:-alpine}"
XTERM_VER="${XTERM_VER:-5.3.0}"
FIT_VER="${FIT_VER:-0.8.0}"

mkdir -p "$ASSETS/bin" "$ASSETS/web"

echo "== 1. proot =="
PROOT_SRC="${PROOT_SRC:-$(command -v proot || true)}"
if [ -z "$PROOT_SRC" ]; then
    echo "proot tidak ditemukan. Bunyikan: pkg install proot" >&2; exit 1
fi
cp "$PROOT_SRC" "$ASSETS/bin/proot"
chmod 755 "$ASSETS/bin/proot"
echo "  proot: $(file -b "$ASSETS/bin/proot" 2>/dev/null | cut -d, -f1) (sumber: $PROOT_SRC)"

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
curl -fsSL -o "$ASSETS/web/fit.js"     "https://cdn.jsdelivr.net/npm/@xterm/xterm-addon-fit@$FIT_VER/lib/addon-fit/fit.js"
echo "  xterm.js/xterm.css/fit.js ok"

echo "== 4. update bootstrap.json =="
JF="$ASSETS/bootstrap.json"
sed -i -E "s|(\"sha256\" *: *\")[^\"]*|\1$SHA|; s|(\"sizeBytes\" *: *)[0-9]+|\1$SIZE|" "$JF"
sed -i -E "s|(\"distro\" *: *\")[^\"]*|\1$ROOTFS_DISTRO|" "$JF"

echo "== selesai. Buka android/ di Android Studio. =="