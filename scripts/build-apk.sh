#!/bin/bash
# build-apk.sh — bangun & pasang LinuxBox di Termux tanpa Android Studio/Gradle.
# Pipeline: proot/rootfs dari Termux -> compile C (NDK cross) ->
#           javac + commons-compress -> d8 -> aapt2 -> apksigner -> adb install.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/android/app/src/main"
TT_SRC="$ROOT/android/termux-terminal/src/main"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
WORK="$ROOT/out"
GEN="$WORK/gen"
ASSETS="$WORK/assets"
R_GEN="$WORK/rgen"
RES_APP="$WORK/res-app.zip"
RES_TT="$WORK/res-tt.zip"
DISTRO="${DISTRO:-alpine}"
ANDROID_JAR="${ANDROID_JAR:-$HOME/androidjar/android-13/android.jar}"
AAPT_FRAMEWORK="${AAPT_FRAMEWORK:-/system/framework/framework-res.apk}"
CC_TARGET="${CC_TARGET:-aarch64-linux-android35}"
DEX_API="${DEX_API:-26}"
# androidx.annotation dipakai modul termux-terminal (TerminalView) — compile-time only.
# Catatan: 1.7.0 sudah jadi KMP (jar-nya metadata, bukan class Android); versi 1.5.0
# adalah jar polos yang berisi androidx.annotation.RequiresApi. Selaraskan ke koordinat
# Gradle bila 1.7.0 mulai menerbitkan variant Android yang dimengerti jalur manual.
ANDROIDX_ANNOTATION_URL="${ANDROIDX_ANNOTATION_URL:-https://dl.google.com/android/maven2/androidx/annotation/annotation/1.5.0/annotation-1.5.0.jar}"

cmds=(javac jar d8 aapt2 apksigner adb curl keytool clang tar sha256sum)
for c in "${cmds[@]}"; do command -v "$c" >/dev/null || { echo "butuh: $c" >&2; exit 1; }; done

rm -rf "$WORK"; mkdir -p "$GEN" "$ASSETS/bin" "$WORK/dex" "$WORK/libs" "$WORK/jni/lib/arm64-v8a"

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

echo "[4] bootstrap.json (sha-inject)"
cat > "$ASSETS/bootstrap.json" <<EOF
{
  "distro": "$DISTRO",
  "rootfs": { "url": "", "sha256": "$SHA", "sizeBytes": $SIZE },
  "proot": { "url": "", "sha256": "" }
}
EOF

echo "[6] deps"
bash "$ROOT/scripts/fetch-java-deps.sh" "$WORK/libs"
echo "  androidx-annotation (compile-time)"
curl -fsSL --retry 2 -o "$WORK/libs/androidx-annotation.jar" "$ANDROIDX_ANNOTATION_URL"
if ! jar tf "$WORK/libs/androidx-annotation.jar" 2>/dev/null | grep -q "androidx/annotation/RequiresApi.class"; then
    echo "GAGAL: androidx.annotation tidak valid (butuh kelas RequiresApi)." >&2
    exit 1
fi

echo "[7] res (aapt2 compile) + link (aapt2, + R.java)"
mkdir -p "$R_GEN"
aapt2 compile --dir "$SRC/res" -o "$RES_APP"
aapt2 compile --dir "$TT_SRC/res" -o "$RES_TT"
aapt2 link -o "$WORK/unsigned.apk" \
  --manifest "$MANIFEST" \
  -A "$ASSETS" \
  -I "$AAPT_FRAMEWORK" \
  -R "$RES_APP" -R "$RES_TT" \
  --java "$R_GEN" \
  --min-sdk-version "$DEX_API" \
  --target-sdk-version 33 \
  --version-code 1 --version-name 0.1.0 \
  --auto-add-overlay --package-id 0x7f --allow-reserved-package-id
# Resource modul termux-terminal digabung ke package com.linuxbox oleh link di
# atas. Kode modul mereferensikannya lewat com.termux.view.R; hasil delegasi
# (nilai int sama, resolusi runtime memakai tabel resource yang sama).
mkdir -p "$R_GEN/com/termux/view"
cat > "$R_GEN/com/termux/view/R.java" <<EOF
package com.termux.view;
/** Delegasi ke resource termux-terminal yang sudah digabung ke package com.linuxbox. */
public final class R {
    public static final class string {
        public static final int copy_text = com.linuxbox.R.string.copy_text;
        public static final int paste_text = com.linuxbox.R.string.paste_text;
        public static final int text_selection_more = com.linuxbox.R.string.text_selection_more;
    }
    public static final class drawable {
        public static final int text_select_handle_left_material = com.linuxbox.R.drawable.text_select_handle_left_material;
        public static final int text_select_handle_right_material = com.linuxbox.R.drawable.text_select_handle_right_material;
    }
}
EOF

echo "[8] javac"
JAVAS=$(find "$SRC/java" "$TT_SRC/java" -name '*.java')
R_JAVAS=$(find "$R_GEN" -name '*.java')
javac --release 11 -encoding UTF-8 -cp "$ANDROID_JAR:$WORK/libs/*" -d "$GEN" $JAVAS $R_JAVAS

echo "[9] d8"
jar cf "$WORK/classes.jar" -C "$GEN" .
d8 --min-api "$DEX_API" --output "$WORK/dex" \
   "$WORK/classes.jar" "$WORK"/libs/*.jar \
   --lib "$ANDROID_JAR"

echo "[10] inject dex + jniLibs"
(
# Dependensi tambahan dapat menghasilkan multidex. Masukkan semuanya, bukan
# hanya classes.dex; minSdk >= 26 mendukung multidex secara native.
cd "$WORK/dex"
jar uf "$WORK/unsigned.apk" classes*.dex
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
