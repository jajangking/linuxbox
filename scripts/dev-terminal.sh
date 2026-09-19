#!/bin/bash
# dev-terminal.sh — PoC cepat di Termux: ttyd (Termux) + proot-distro login
# untuk memvalidasi UX "distro diakses lewat browser" sebelum repot build APK.
set -euo pipefail

DISTRO="${DISTRO:-alpine}"
PORT="${PORT:-8000}"
IP="${IP:-127.0.0.1}"
PIDFILE="${PIDFILE:-$HOME/.linuxbox-dev.pid}"

for c in proot-distro ttyd; do
    command -v "$c" >/dev/null || pkg install -y "$c"
done

# pastikan container ada (install idempotent)
proot-distro install "$DISTRO"

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo ">>> Sudah berjalan: ttyd pid $(cat "$PIDFILE") di http://$IP:$PORT/"
    exit 0
fi

echo ">>> Menjalankan $DISTRO via ttyd di http://$IP:$PORT/"
echo ">>> Hentikan: kill \$(cat $PIDFILE)"
ttyd -p "$PORT" -i "$IP" -W proot-distro login "$DISTRO" >/dev/null 2>&1 &
echo $! > "$PIDFILE"

for i in $(seq 1 20); do
    if command -v curl >/dev/null && curl -fsS -m 1 "http://$IP:$PORT/" >/dev/null 2>&1; then
        echo ">>> Siap. Buka http://$IP:$PORT/"
        exit 0
    fi
    sleep 1
done
echo ">>> ttyd belum merespons; cek: cat $PIDFILE && kill \$(cat $PIDFILE)" >&2
exit 1