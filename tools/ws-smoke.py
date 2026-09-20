#!/usr/bin/env python3
"""Smoke test untuk WebTerminalServer LinuxBox — tanpa dependensi eksternal.

Dipakai untuk memastikan terminal web benar-benar menampilkan command, bukan
layar hitam kosong, dan sekarang juga menguji multi-sesi. Bisa diarahkan ke HP
(adb forward) atau ke server lokal.

    adb forward tcp:8770 tcp:8770
    python3 tools/ws-smoke.py 127.0.0.1 8770
    python3 tools/ws-smoke.py 127.0.0.1 8770 <token>   # kalau auth aktif

Yang dicek:
  1. HTTP  /                -> index.html tersedia (aset web ikut ter-bundling)
  2. HTTP  /healthz         -> status server + jumlah sesi
  3. HTTP  /api/sessions    -> daftar sesi (JSON)
  4. HTTP  POST /api/sessions -> sesi baru bisa dibuat
  5. WS    /ws?session=<id> -> 101 + replay scrollback + echo + frame binary
  6. WS    /ctl?session=<id>-> resize PTY (TIOCSWINSZ)
  7. HTTP  POST /api/sessions/<id>/kill -> sesi benar-benar ditutup
"""
import base64
import json
import os
import socket
import sys
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
TIMEOUT = 3.0


def http_raw(host, port, method, path, body=b""):
    s = socket.create_connection((host, port), timeout=TIMEOUT)
    req = ("%s %s HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\n"
           % (method, path, host, port))
    if body:
        req += "Content-Length: %d\r\n" % len(body)
    req += "\r\n"
    s.sendall(req.encode() + body)
    data = b""
    while True:
        try:
            d = s.recv(65536)
        except socket.timeout:
            break
        if not d:
            break
        data += d
    s.close()
    head = data.split(b"\r\n\r\n", 1)[0].decode("utf-8", "replace")
    parts = head.split(" ")
    code = parts[1] if len(parts) > 1 else "?"
    payload = data.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in data else b""
    return code, payload


def http_get(host, port, path):
    return http_raw(host, port, "GET", path)


def http_post(host, port, path):
    return http_raw(host, port, "POST", path)


class Ws:
    def __init__(self, host, port, path="/ws"):
        self.sock = socket.create_connection((host, port), timeout=TIMEOUT)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((
            "GET %s HTTP/1.1\r\nHost: %s:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, host, port, key)
        ).encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            d = self.sock.recv(4096)
            if not d:
                raise RuntimeError("koneksi ditutup saat handshake")
            buf += d
        head, self.buf = buf.split(b"\r\n\r\n", 1)
        self.status = head.decode("utf-8", "replace").split("\r\n")[0]

    def _need(self, n):
        while len(self.buf) < n:
            d = self.sock.recv(65536)
            if not d:
                return False
            self.buf += d
        return True

    def send(self, payload):
        n = len(payload)
        mask = os.urandom(4)
        hdr = bytearray([0x81])
        if n < 126:
            hdr.append(0x80 | n)
        elif n < 65536:
            hdr.append(0x80 | 126)
            hdr += n.to_bytes(2, "big")
        else:
            hdr.append(0x80 | 127)
            hdr += n.to_bytes(8, "big")
        hdr += mask
        self.sock.sendall(bytes(hdr) + bytes(payload[i] ^ mask[i % 4] for i in range(n)))

    def frames(self, seconds=2.0):
        """Kumpulkan frame masuk; flag kedua = koneksi masih hidup."""
        out = []
        end = time.time() + seconds
        while time.time() < end:
            try:
                if not self._need(2):
                    return out, False
                b0, b1 = self.buf[0], self.buf[1]
                op, ln, off = b0 & 0x0F, b1 & 0x7F, 2
                if ln == 126:
                    if not self._need(4):
                        return out, False
                    ln = int.from_bytes(self.buf[2:4], "big")
                    off = 4
                elif ln == 127:
                    if not self._need(10):
                        return out, False
                    ln = int.from_bytes(self.buf[2:10], "big")
                    off = 10
                if not self._need(off + ln):
                    return out, False
                out.append((op, self.buf[off:off + ln]))
                self.buf = self.buf[off + ln:]
                end = time.time() + 0.5
            except socket.timeout:
                break
        return out, True

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8770
    token = sys.argv[3] if len(sys.argv) > 3 else ""
    query = ("?token=" + token) if token else ""
    fails = []

    def check(name, ok, detail=""):
        print("  [%s] %s%s" % ("OK  " if ok else "GAGAL", name, (" — " + detail) if detail else ""))
        if not ok:
            fails.append(name)
        return ok

    print("LinuxBox smoke test -> %s:%d" % (host, port))

    code, body = http_get(host, port, "/" + query)
    check("GET / (index.html)", code == "200" and b"xterm" in body, "HTTP " + code)

    code, body = http_get(host, port, "/healthz" + query)
    health = body.decode("utf-8", "replace").strip()
    check("GET /healthz", code == "200" and health.startswith("{"), health[:180])
    flat = health.replace(" ", "")
    if health.startswith("{"):
        alive = '"sessionsAlive":0' not in flat
        check("minimal satu sesi hidup", alive,
              "" if alive else "sessionsAlive=0 — shell tidak jalan, cek lastError")

    # ---------------- multi-sesi ----------------
    code, body = http_get(host, port, "/api/sessions" + query)
    try:
        data = json.loads(body.decode("utf-8", "replace"))
        existing = [s["id"] for s in data.get("sessions", [])]
    except Exception:
        data, existing = {}, []
    check("GET /api/sessions", code == "200" and isinstance(data.get("sessions"), list),
          "%d sesi: %s" % (len(existing), ", ".join(existing) or "-"))

    code, body = http_post(host, port, "/api/sessions?name=smoke" + ("&token=" + token if token else ""))
    new_id = None
    try:
        new_id = json.loads(body.decode("utf-8", "replace")).get("id")
    except Exception:
        pass
    if not check("POST /api/sessions (sesi baru)", code == "200" and bool(new_id),
                 "id=%s" % new_id):
        print("\nHASIL: server tidak mengizinkan pembuatan sesi.")
        return 1

    path = "/ws?session=%s%s" % (new_id, ("&token=" + token) if token else "")
    ws = Ws(host, port, path)
    if not check("handshake websocket sesi baru", "101" in ws.status, ws.status):
        print("\nHASIL: server menolak websocket — cek token (?token=...) atau server belum jalan.")
        return 1

    frames, _ = ws.frames(2.0)
    replay = sum(len(p) for _, p in frames)
    check("replay scrollback saat connect", replay > 0, "%d byte / %d frame" % (replay, len(frames)))

    ws.send(b"echo LINUXBOX-SMOKE-OK\n")
    frames, _ = ws.frames(2.5)
    text = b"".join(p for _, p in frames).decode("utf-8", "replace")
    check("command menghasilkan output", "LINUXBOX-SMOKE-OK" in text,
          "" if "LINUXBOX-SMOKE-OK" in text else "output: %r" % text[-160:])

    ws.send(b"printf '\\377\\376'\n")
    frames, alive = ws.frames(2.5)
    raw = b"".join(p for _, p in frames)
    ops = sorted({op for op, _ in frames})
    # Browser WAJIB memutus koneksi kalau frame TEXT berisi byte non-UTF-8
    # (RFC6455). Output PTY mentah hanya aman di frame BINARY (0x2).
    check("output PTY dikirim sebagai frame binary", bool(ops) and ops == [0x2],
          "opcode %s" % (", ".join(hex(o) for o in ops) if ops else "-")
          + (" (frame text + byte non-UTF-8 = browser putus koneksi)" if 0x1 in ops else ""))
    check("byte non-UTF-8 tidak memutus koneksi", alive and b"\xff" in raw,
          "koneksi tetap hidup" if alive else "KONEKSI DIPUTUS (frame text?)")

    ctl_path = "/ctl?session=%s%s" % (new_id, ("&token=" + token) if token else "")
    ctl = Ws(host, port, ctl_path)
    first = b"".join(p for _, p in ctl.frames(2.0)[0]).decode("utf-8", "replace")
    if not check("handshake /ctl", "101" in ctl.status, ctl.status):
        ctl.close()
        ws.close()
        print("\nHASIL: kanal kontrol /ctl ditolak server.")
        return 1
    check("server minta ukuran (need-size)", "need-size" in first, first[:60])
    rows, cols = 45, 132
    ctl.send(('{"type":"resize","rows":%d,"cols":%d}' % (rows, cols)).encode())
    time.sleep(0.4)
    ws.send(b"stty size\n")
    text = b"".join(p for _, p in ws.frames(2.5)[0]).decode("utf-8", "replace")
    check("resize PTY diterapkan (TIOCSWINSZ)", ("%d %d" % (rows, cols)) in text,
          "" if ("%d %d" % (rows, cols)) in text else "stty size -> %r" % text[-80:])
    ctl.close()
    ws.close()

    # sesi yang ditutup harus hilang dari daftar
    kill_path = "/api/sessions/%s/kill%s" % (new_id, query)
    code, body = http_post(host, port, kill_path)
    check("POST /api/sessions/<id>/kill", code == "200", "HTTP " + code)
    time.sleep(0.3)
    code, body = http_get(host, port, "/api/sessions" + query)
    try:
        left = [s["id"] for s in json.loads(body.decode("utf-8", "replace")).get("sessions", [])]
    except Exception:
        left = []
    check("sesi yang ditutup hilang dari daftar", new_id not in left, "sisa: %s" % (", ".join(left) or "-"))

    print()
    if fails:
        print("HASIL: %d pemeriksaan GAGAL -> %s" % (len(fails), ", ".join(fails)))
        return 1
    print("HASIL: semua pemeriksaan lolos.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
