#!/data/data/com.termux/files/usr/bin/bash
# device-diag.sh — kumpulkan semua bukti blocker proot LinuxBox.
#
# DIPAKAI DI TERMUX (di HP), BUKAN di PC/sandbox agent:
#   bash scripts/device-diag.sh                       # device tunggal (adb devices)
#   bash scripts/device-diag.sh 10.141.58.141:45307   # wireless adb
#
# Yang dikumpulkan:
#   1. Info device + path APK + isi nativeLibraryDir (label SELinux via ls -Z)
#   2. Kelayakan rootfs (bin/sh, bin/busybox, /tmp)
#   3. Tiga varian eksekusi proot (sebelum/sesudah fix TMPDIR + --cwd=/)
#   4. logcat terkait com.linuxbox
#
# Keluaran ditulis ke ~/linuxbox-diag-<waktu>.txt dan dicetak ke layar.
set -u

DEV="${1:-}"
# Pakai array: kalau ADB="adb -s <dev>" (string) lalu dipanggil "$ADB",
# shell menganggapnya satu kata perintah -> "command not found".
if [ -n "$DEV" ]; then ADB=(adb -s "$DEV"); else ADB=(adb); fi

PKG="com.linuxbox"
OUT="$HOME/linuxbox-diag-$(date +%Y%m%d-%H%M%S).txt"
TMPD="$(mktemp -d)"
trap 'rm -rf "$TMPD"' EXIT

sh_adb() { "${ADB[@]}" shell "$@" 2>&1 | tr -d '\r'; }
log() { echo "$*" | tee -a "$OUT"; }
hr()  { printf '%s\n' "------------------------------------------------------------" | tee -a "$OUT"; }

: > "$OUT"
hr
log "== LINUXBOX DIAG $(date -Is) =="
log "adb target : ${DEV:-default (adb devices)}"
hr

log "== 1. DEVICE =="
sh_adb "getprop ro.build.version.release; getprop ro.product.model; getprop ro.build.type" | tee -a "$OUT"
sh_adb "getenforce" | tee -a "$OUT"
hr

log "== 2. APK + nativeLibraryDir (harus berlabel apk_data_file_t) =="
BASE=$(sh_adb "pm path $PKG" | sed 's#package:##; s#/base.apk##')
log "BASE    : $BASE"
# dumpsys sering hanya punya legacyNativeLibraryDir -> nativeLibraryDir kosong.
# Yang dipakai PackageManager selalu <base.apk dir>/lib/arm64.
NATIVE="$BASE/lib/arm64"
log "nativeLibDir: ${NATIVE:-<(tidak ketemu)>}"
sh_adb "ls -lZ ${NATIVE:-\$NATIVE}" 2>&1 | tee -a "$OUT"
hr

log "-- loader proot (PENTING: ini yang dieksekusi proot, bukan /bin/sh) --"
sh_adb "ls -lZ ${NATIVE:-/data/app}/libproot_loader.so" 2>&1 | tee -a "$OUT"
log "   proot Termux dikompilasi PROOT_UNBUNDLE_LOADER=/data/data/com.termux/files/usr/libexec/proot"
log "   -> tanpa PROOT_LOADER, proot mengeksekusi path milik Termux itu -> EACCES."
log "   PROOT_LOADER yang dikirim app: ${NATIVE:-\$NATIVE}/libproot_loader.so"
hr

log "== 3. FILES + ROOTFS =="
sh_adb "run-as $PKG ls -l files" | tee -a "$OUT"
ROOTFS=$(sh_adb "run-as $PKG sh -c 'ls -d files/rootfs-* 2>/dev/null | head -1'")
ROOTFS=$(echo "$ROOTFS" | tr -d '\r')
log "rootfs (relatif files/): ${ROOTFS:-<(tidak ada)>}"
if [ -n "$ROOTFS" ]; then
  sh_adb "run-as $PKG ls -l $ROOTFS/bin/sh $ROOTFS/bin/busybox $ROOTFS/tmp 2>&1 | head -20" | tee -a "$OUT"
fi
hr

log "== 4. Siapkan files/tmp (TMPDIR host-side) + berkas stdin =="
sh_adb "run-as $PKG mkdir -p files/tmp && run-as $PKG ls -ld files/tmp" | tee -a "$OUT"
printf 'echo DIAG-OK id\n' > "$TMPD/t.in"
"${ADB[@]}" push "$TMPD/t.in" /data/local/tmp/t.in >/dev/null 2>&1
sh_adb "run-as $PKG cp /data/local/tmp/t.in files/t.in && run-as $PKG ls -l files/t.in" | tee -a "$OUT"

# ---------------------------------------------------------------- inner script
INNER="$TMPD/inner.sh"
if [ -z "$BASE" ] || [ -z "$NATIVE" ]; then
  log "!! BASE atau nativeLibraryDir kosong — lewati bagian 5."
else
cat > "$INNER" <<EOF
#!/system/bin/sh
BASE="$BASE"
NATIVE="$NATIVE"
ROOTFS_ABS="/data/data/com.linuxbox/\${1:-files/rootfs-alpine}"
echo "-- proot --version --"
LD_LIBRARY_PATH=\$BASE/lib/arm64 \$BASE/lib/arm64/libproot.so --version 2>&1
echo
for V in A B C; do
  echo "======== VARIAN \$V ========"
  case \$V in
    A) EXTRA=""        ; TD="/tmp" ;;                                     # kondisi lama
    B) EXTRA=""        ; TD="/data/data/com.linuxbox/files/tmp" ;;         # + PROOT_TMP_DIR
    C) EXTRA="--cwd=/" ; TD="/data/data/com.linuxbox/files/tmp" ;;         # + --cwd=/
  esac
  cd /data/data/com.linuxbox/files || exit 1
  TMPDIR=\$TD PROOT_TMP_DIR=\$TD LD_LIBRARY_PATH=\$BASE/lib/arm64 \\
  \$BASE/lib/arm64/libproot.so \\
      --rootfs=\$ROOTFS_ABS --link2symlink \\
      -b /proc -b /sys -b /dev --kill-on-exit \$EXTRA /bin/sh -l \\
      < /data/data/com.linuxbox/files/t.in
  echo "---- exit=\$? ----"
  echo
done
EOF
"${ADB[@]}" push "$INNER" /data/local/tmp/lb_inner.sh >/dev/null 2>&1
sh_adb "run-as $PKG cp /data/local/tmp/lb_inner.sh files/lb_inner.sh" >/dev/null 2>&1

log "== 5. TIGA VARIAN EKSEKUSI PROOT (stderr ikut tertangkap) =="
log "   A = TMPDIR=/tmp (kondisi lama)"
log "   B = TMPDIR=files/tmp (fix #1)"
log "   C = TMPDIR=files/tmp + --cwd=/ (fix #1 + #2)"
hr
sh_adb "run-as $PKG sh files/lb_inner.sh ${ROOTFS#files/}" 2>&1 | tee -a "$OUT"
fi
hr

log "== 6. UJI W^X: bolehkah app mengeksekusi binary di filesDir? =="
log "   (Android 10+: app targetSdk >= 29 DILARANG execve berkas app_data_file)"
sh_adb "run-as $PKG sh -c 'cat /proc/self/attr/current'" | tee -a "$OUT"
sh_adb "run-as $PKG cp /system/bin/toybox files/toybox_probe 2>&1; run-as $PKG chmod 755 files/toybox_probe 2>&1" | tee -a "$OUT"
sh_adb "run-as $PKG sh -c './files/toybox_probe echo WX-OK-filesDir'" 2>&1 | tee -a "$OUT"
log "   jika 'Permission denied'/'error=13' -> W^X aktif: rootfs di filesDir"
log "   TIDAK akan pernah bisa dieksekusi, berapa pun perbaikan path-nya."
sh_adb "run-as $PKG rm -f files/toybox_probe" >/dev/null 2>&1
hr

log "== 7. realpath() di domain app (sumber anomali '/' di HANDOFF) =="
for PTH in "/data/data/$PKG/files" "/data/data/$PKG/files/${ROOTFS#files/}" \
           "/data/data/$PKG/files/${ROOTFS#files/}/bin/sh" \
           "/data/data/$PKG/files/${ROOTFS#files/}/lib/ld-musl-aarch64.so.1"; do
  log "-- $PTH"
  sh_adb "run-as $PKG sh -c 'ls -ld \"$PTH\" 2>&1; /system/bin/toybox realpath \"$PTH\" 2>&1; echo rc=\$?'" | tee -a "$OUT"
done
sh_adb "run-as $PKG sh -c 'ls /data/data/$PKG/files/${ROOTFS#files/}/bin | head -20'" | tee -a "$OUT"
hr

log "== 8. PROOT DENGAN PROOT_TMP_DIR + verbose (path translation) =="
if [ -n "${BASE:-}" ] && [ -n "${NATIVE:-}" ]; then
  log "   proot -v 9 --rootfs=... --cwd=/ /bin/sh -l   (PROOT_TMP_DIR=files/tmp)"
  sh_adb "run-as $PKG sh -c 'cd files && TMPDIR=/data/data/$PKG/files/tmp PROOT_TMP_DIR=/data/data/$PKG/files/tmp LD_LIBRARY_PATH=$BASE/lib/arm64 $BASE/lib/arm64/libproot.so -v 9 --rootfs=/data/data/$PKG/files/${ROOTFS#files/} --link2symlink -b /proc -b /sys -b /dev --kill-on-exit --cwd=/ /bin/sh -l < files/t.in'" 2>&1 | tail -60 | tee -a "$OUT"
else
  log "   dilewati (BASE/NATIVE tidak ketemu)"
fi
hr

log "== 9. LOGCAT (com.linuxbox, 200 baris terakhir) =="
sh_adb "logcat -d -t 200 | grep -i -E 'linuxbox|proot|ptylauncher|avc: denied' | tail -60" 2>&1 | tee -a "$OUT"
hr

log "SELESAI. Salin isi $OUT"
log "  (atau: cat $OUT | termux-share -a send)"
