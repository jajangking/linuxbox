#!/bin/bash
# Identity + maintenance guard tests; no Android SDK or Maven downloads required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for tool in java javac; do
    command -v "$tool" >/dev/null || { echo "butuh: $tool (JDK 11+)" >&2; exit 1; }
done
mkdir -p "$ROOT/.cache"
WORK="$(mktemp -d "$ROOT/.cache/rootfs-identity.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/fixtures"
SRC="$ROOT/android/app/src/main/java/com/linuxbox/distro"
javac --release 11 -encoding UTF-8 -d "$WORK/classes" \
    "$SRC/RootfsIdentity.java" "$SRC/RootfsGuard.java" "$ROOT/tests/java/RootfsIdentityTest.java"
java -cp "$WORK/classes" RootfsIdentityTest "$WORK/fixtures"
