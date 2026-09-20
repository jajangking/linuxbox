#!/bin/bash
# build-apk.sh — bangun & pasang LinuxBox di Termux tanpa Android Studio/Gradle.
# Pipeline: proot/rootfs/xterm.js dari Termux -> compile C (NDK cross) ->
#           javac + commons-compress -> d8 -> aapt2 -> apksigner -> adb install.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android/app/src/main"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
WORK="$ROOT/out"
GEN="$WORK/gen"
ASSETS="$WORK/assets"
DISTRO="${DISTRO:-alpine}"
XTERM_VER="${XTERM_VER:-6.0.0}"
FIT_VER="${FIT_VER:-0.11.0}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/androidjar/android-13/android.jar}"
AAPT_FRAMEWORK="${AAPT_FRAMEWORK:-/system/framework/framework-res.apk}"
CC_TARGET="${CC_TARGET:-aarch64-linux-android35}"
DEX_API="${DEX_API:-26}"
COMMONS_URL="${COMMONS_URL:-https://repo1.maven.org/maven2/org/apache/commons/commons-compress/1.26.2/commons-compress-1.26.2.jar}"
COMMONS_IO_URL="${COMMONS_IO_URL:-https://repo1.maven.org/maven2/commons-io/commons-io/2.16.1/commons-io-2.16.1.jar}"

cmds=(javac jar d8 aapt2 apksigner adb curl keytool clang tar sha256sum)
for c in "${cmds[@]}"; do command -v "$c" >/dev/null || { echo "butuh: $c" >&2; exit 1; }; done

rm -rf "$WORK"; mkdir -p "$GEN" "$ASSETS/bin" "$ASSETS/web" "$WORK/dex" "$WORK/libs" "$WORK/jni/lib/arm64-v8a"

echo "[1] proot binary + libs"
command -v proot >/dev/null || pkg install -y proot
cp "$(command -v proot)" "$ASSETS/bin/proot"
chmod 755 "$ASSETS/bin/proot"
# proot (Termux) butuh libtalloc + libandroid-shmem; bundle biar jalan di app sandbox
for lib in "$PREFIX"/lib/libtalloc.so.2* "$PREFIX"/lib/libandroid-shmem.so; do
    [ -e "$lib" ] && cp "$lib" "$ASSETS/bin/"
done

echo "[2] ptylauncher (cross Android $CC_TARGET)"
clang --target="$CC_TARGET" -O2 -o "$ASSETS/bin/ptylauncher" "$SRC/cpp/ptylauncher.c"

echo "[2b] jniLibs (lib/arm64-v8a, label apk_data_file_t -> boleh di-execve)"
# Penting: binary harus ada di lib/<abi>/ APK, bukan cuma di assets/bin. App
# ber-targetSdk>=30 (domain untrusted_app_32) TIDAK boleh execve file berlabel
# app_data_file_t (filesDir). PackageManager mengekstrak lib/ ke nativeLibraryDir
# berlabel apk_data_file_t yang DIIZINKAN di-execve.
# Nama berawalan "lib" + ".so": paling aman untuk AGP (jalur Gradle) dan tetap
# dikenali PackageManager di jalur aapt2 manual. ProotSession.pick() menerima
# nama polos maupun nama lib*.so, jadi APK lama tidak rusak.
stage_native() {  # stage_native <berkas-sumber> <nama-di-lib>
    if [ ! -s "$1" ]; then
        echo "GAGAL: $1 tidak ada/kosong." >&2
        echo "  Di Termux jalankan: pkg install proot libtalloc libandroid-shmem" >&2
        exit 1
    fi
    cp "$1" "$WORK/jni/lib/arm64-v8a/$2"
}
stage_native "$ASSETS/bin/proot"              libproot.so
stage_native "$ASSETS/bin/ptylauncher"        libptylauncher.so
stage_native "$ASSETS/bin/libtalloc.so.2"     libtalloc.so.2
stage_native "$ASSETS/bin/libandroid-shmem.so" libandroid-shmem.so
# Loader proot: proot tidak exec binary guest langsung, ia exec loader ini.
# proot Termux dikompilasi dengan PROOT_UNBUNDLE_LOADER=$PREFIX/libexec/proot,
# jadi tanpa PROOT_LOADER ia mencari /data/data/com.termux/.../loader -> EACCES.
stage_native "$PREFIX/libexec/proot/loader" libproot_loader.so
# proroot (opsional, proprietary — boleh dipakai, tidak boleh didistribusi ulang):
# 5 .so dari https://github.com/coderredlab/proroot (release binary, v1.2.8+).
# Kalau tidak ada, app memakai proot klasik (fallback). PROROOT_DIR harusnya di
# luar repo supaya tidak ter-commit.
PROROOT_DIR="${PROROOT_DIR:-$HOME/proroot/arm64-v8a}"
proroot_libs="libproroot.so libproroot-runtime.so libproroot-linker.so libproroot-bridge.so libproroot-stub-loader.so"
found=0
for lib in $proroot_libs; do
    if [ -s "$PROROOT_DIR/$lib" ]; then
        cp "$PROROOT_DIR/$lib" "$WORK/jni/lib/arm64-v8a/$lib"
        found=$((found+1))
    else
        echo "  proroot: $lib tidak ada (di $PROROOT_DIR) -> fallback proot"
    fi
done
[ "$found" -eq 5 ] && echo "  proroot: 5 .so disalin (engine proroot aktif untuk distro glibc)"
chmod 755 "$WORK"/jni/lib/arm64-v8a/*
echo "  jniLibs: $(ls "$WORK/jni/lib/arm64-v8a" | tr '\n' ' ')"

echo "[3] rootfs ($DISTRO)"
if [ -d "$PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs" ]; then
    ROOTFS_SRC="$PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs"
elif [ -d "$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO" ]; then
    ROOTFS_SRC="$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO"
else
    command -v proot-distro >/dev/null || pkg install -y proot-distro
    proot-distro install "$DISTRO" || true
    if [ -d "$PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs" ]; then
        ROOTFS_SRC="$PREFIX/var/lib/proot-distro/containers/$DISTRO/rootfs"
    else
        ROOTFS_SRC="$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO"
    fi
fi
[ -d "$ROOTFS_SRC" ] || { echo "rootfs tidak ketemu di $ROOTFS_SRC" >&2; exit 1; }
tar -C "$ROOTFS_SRC" -czf "$ASSETS/rootfs.tar.gz" .
SHA=$(sha256sum "$ASSETS/rootfs.tar.gz" | cut -d' ' -f1)
SIZE=$(stat -c%s "$ASSETS/rootfs.tar.gz")

echo "[4] web assets (xterm.js)"
curl -fsSL -o "$ASSETS/web/xterm.js"  "https://cdn.jsdelivr.net/npm/@xterm/xterm@$XTERM_VER/lib/xterm.js"
curl -fsSL -o "$ASSETS/web/xterm.css" "https://cdn.jsdelivr.net/npm/@xterm/xterm@$XTERM_VER/css/xterm.css"
curl -fsSL -o "$ASSETS/web/fit.js"    "https://cdn.jsdelivr.net/npm/@xterm/addon-fit@$FIT_VER/lib/addon-fit.js"
for f in xterm.js xterm.css fit.js; do
    [ -s "$ASSETS/web/$f" ] || { echo "GAGAL: aset web $f kosong/tidak terunduh" >&2; exit 1; }
done
grep -q "Terminal" "$ASSETS/web/xterm.js" || { echo "GAGAL: xterm.js bukan bundle UMD" >&2; exit 1; }

echo "[5] index.html + bootstrap.json (sha-inject)"
cp "$SRC/assets/web/index.html" "$ASSETS/web/index.html"
cat > "$ASSETS/bootstrap.json" <<EOF
{
  "distro": "$DISTRO",
  "rootfs": { "url": "", "sha256": "$SHA", "sizeBytes": $SIZE },
  "proot": { "url": "", "sha256": "" }
}
EOF

echo "[6] deps"
curl -fsSL -o "$WORK/libs/commons-compress.jar" "$COMMONS_URL"
curl -fsSL -o "$WORK/libs/commons-io.jar" "$COMMONS_IO_URL"

echo "[7] javac"
JAVAS=$(find "$SRC/java" -name '*.java')
javac --release 11 -cp "$ANDROID_JAR:$WORK/libs/commons-compress.jar" -d "$GEN" $JAVAS

echo "[8] d8"
jar cf "$WORK/classes.jar" -C "$GEN" .
d8 --min-api "$DEX_API" --output "$WORK/dex" \
   "$WORK/classes.jar" "$WORK/libs/commons-compress.jar" "$WORK/libs/commons-io.jar" \
   --lib "$ANDROID_JAR" 2>/dev/null
cp "$WORK"/dex/*.dex "$WORK/classes.dex"

echo "[9] aapt2 link (manifest + assets)"
aapt2 link -o "$WORK/unsigned.apk" \
  --manifest "$MANIFEST" \
  -A "$ASSETS" \
  -I "$AAPT_FRAMEWORK" \
  --min-sdk-version "$DEX_API" \
  --target-sdk-version 33 \
  --version-code 1 --version-name 0.1.0 \
  --auto-add-overlay --package-id 0x7f --allow-reserved-package-id

echo "[10] inject dex + jniLibs"
(
cd "$WORK"
jar uf unsigned.apk classes.dex
)
(
cd "$WORK/jni"
jar uf "$WORK/unsigned.apk" lib
)

echo "[11] sign"
KS="${KEYSTORE:-$ROOT/keystore/linuxbox.jks}"
mkdir -p "$(dirname "$KS")"
[ -f "$KS" ] || keytool -genkey -v -keystore "$KS" -alias linuxbox -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass linuxboxpw -keypass linuxboxpw -dname "CN=LinuxBox,OU=App,O=LinuxBox,C=ID" 2>/dev/null
apksigner sign --ks "$KS" --ks-key-alias linuxbox \
  --ks-pass pass:linuxboxpw --key-pass pass:linuxboxpw "$WORK/unsigned.apk"
cp "$WORK/unsigned.apk" "$WORK/linuxbox.apk"
apksigner verify "$WORK/linuxbox.apk" && echo "VERIFY OK"

echo "APK: $WORK/linuxbox.apk ($(stat -c%s "$WORK/linuxbox.apk") bytes)"
echo "Install: adb install -r $WORK/linuxbox.apk"