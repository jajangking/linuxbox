#!/usr/bin/env python3
"""Smoke test untuk WebTerminalServer LinuxBox — tanpa dependensi eksternal.

Dipakai untuk memastikan terminal web benar-benar menampilkan command, bukan
layar hitam kosong. Bisa diarahkan ke HP (adb forward) atau ke server lokal.

    adb forward tcp:8770 tcp:8770
    python3 tools/ws-smoke.py 127.0.0.1 8770

Yang dicek:
  1. HTTP  /          -> index.html tersedia (aset web ikut ter-bundling)
  2. HTTP  /healthz   -> status sesi (sessionAlive, shell, lastError)
  3. WS    handshake  -> 101 Switching Protocols
  4. WS    replay     -> scrollback langsung diterima begitu connect (prompt tampil)
  5. WS    echo       -> command yang diketik menghasilkan output
  6. WS    binary     -> byte non-UTF-8 tidak memutuskan koneksi
  7. WS    /ctl       -> resize PTY (TIOCSWINSZ) lewat kanal kontrol
"""
import base64
import os
import socket
import sys
import time

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
TIMEOUT = 3.0


def http_get(host, port, path):
    s = socket.create_connection((host, port), timeout=TIMEOUT)
    s.sendall(("GET %s HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\n\r\n"
               % (path, host, port)).encode())
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
    code = head.split(" ")[1] if len(head.split(" ")) > 1 else "?"
    return code, data.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in data else b""


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
        """Kumpulkan frame masuk; None kalau koneksi ditutup server."""
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
    fails = []

    def check(name, ok, detail=""):
        print("  [%s] %s%s" % ("OK " if ok else "GAGAL", name, (" — " + detail) if detail else ""))
        if not ok:
            fails.append(name)

    print("LinuxBox smoke test -> %s:%d" % (host, port))

    code, body = http_get(host, port, "/")
    check("GET / (index.html)", code == "200" and b"xterm" in body, "HTTP " + code)

    code, body = http_get(host, port, "/healthz")
    health = body.decode("utf-8", "replace").strip()
    check("GET /healthz", code == "200", health[:160])
    if '"sessionAlive":false' in health.replace(" ", ""):
        check("sesi shell hidup", False, "sessionAlive=false — shell tidak jalan, cek lastError")
    elif health:
        check("sesi shell hidup", '"sessionAlive":true' in health.replace(" ", ""))

    ws = Ws(host, port)
    check("handshake websocket", "101" in ws.status, ws.status)

    frames, alive = ws.frames(2.0)
    replay = sum(len(p) for _, p in frames)
    check("replay scrollback saat connect", replay > 0,
          "%d byte / %d frame" % (replay, len(frames)))

    ws.send(b"echo LINUXBOX-SMOKE-OK\n")
    frames, alive = ws.frames(2.5)
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

    # kanal kontrol /ctl: ukuran PTY mengikuti window
    ctl = Ws(host, port, path="/ctl")
    first = b"".join(p for _, p in ctl.frames(2.0)[0]).decode("utf-8", "replace")
    check("handshake /ctl + pesan need-size", "101" in ctl.status and "need-size" in first,
          ctl.status + " " + first[:60])
    rows, cols = 45, 132
    ctl.send(('{"type":"resize","rows":%d,"cols":%d}' % (rows, cols)).encode())
    time.sleep(0.4)
    ws.send(b"stty size\n")
    text = b"".join(p for _, p in ws.frames(2.5)[0]).decode("utf-8", "replace")
    check("resize PTY diterapkan (TIOCSWINSZ)", ("%d %d" % (rows, cols)) in text,
          "" if ("%d %d" % (rows, cols)) in text else "stty size -> %r" % text[-80:])
    ctl.close()

    ws.close()
    print()
    if fails:
        print("HASIL: %d pemeriksaan gagal -> %s" % (len(fails), ", ".join(fails)))
        return 1
    print("HASIL: semua pemeriksaan lolos — terminal seharusnya menampilkan command.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
