#!/bin/bash
# Run on Linux/Termux with JDK 11+, curl and Maven Central access. No Android SDK.
# Uses only tiny synthetic fixtures, never the installed Ubuntu/rootfs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for tool in java javac jar curl; do
    command -v "$tool" >/dev/null || { echo "butuh: $tool (JDK 11+)" >&2; exit 1; }
done
mkdir -p "$ROOT/.cache"
WORK="$(mktemp -d "$ROOT/.cache/backup-smoke.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
bash "$ROOT/scripts/fetch-java-deps.sh" "$WORK/libs"
mkdir -p "$WORK/classes" "$WORK/missing-lang3" "$WORK/complete"
SRC="$ROOT/android/app/src/main/java/com/linuxbox/distro"
javac --release 11 -encoding UTF-8 -cp "$WORK/libs/*" -d "$WORK/classes" \
    "$SRC/TaskLog.java" "$SRC/TarUtil.java" "$SRC/Crypto.java" "$SRC/RootfsIdentity.java" \
    "$ROOT/tests/java/BackupSmokeTest.java"

# Prove the test exercises the original missing-SystemProperties failure.
MISSING_CP="$WORK/classes:$WORK/libs/commons-compress.jar:$WORK/libs/commons-io.jar:$WORK/libs/commons-codec.jar"
if java -cp "$MISSING_CP" BackupSmokeTest "$WORK/missing-lang3" > "$WORK/missing.log" 2>&1; then
    echo "GAGAL: test seharusnya gagal jika commons-lang3 tidak ikut runtime" >&2
    exit 1
fi
if ! grep -q 'org/apache/commons/lang3/SystemProperties' "$WORK/missing.log"; then
    cat "$WORK/missing.log" >&2
    echo "GAGAL: test gagal karena alasan lain, bukan SystemProperties" >&2
    exit 1
fi
echo "PASS: missing commons-lang3 reproduces SystemProperties failure"
java -cp "$WORK/classes:$WORK/libs/*" BackupSmokeTest "$WORK/complete"
