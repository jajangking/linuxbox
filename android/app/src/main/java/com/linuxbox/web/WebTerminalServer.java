package com.linuxbox.web;

import android.content.Context;

import com.linuxbox.distro.ProotSession;
import com.linuxbox.distro.PtyHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server terminal web mandiri: HTTP statis + WebSocket (subset RFC6455),
 * melayani index.html/xterm.js dan relay byte PTY <-> client.
 *
 * Beberapa aturan penting yang dulu bikin "command tidak tampil":
 *  - output PTY dikirim sebagai frame BINARY (0x2), bukan TEXT (0x1).
 *    Frame text wajib UTF-8 valid; sekali saja output mengandung byte non-UTF-8
 *    (atau karakter multibyte yang kepotong di batas read 8192 byte) browser
 *    MEMUTUS koneksi websocket-nya (RFC6455) dan terminal berhenti menampilkan apa pun.
 *  - output terakhir disimpan di buffer scrollback dan diputar ulang ke client
 *    yang baru connect. Dulu prompt/banner hilang begitu saja karena shell sudah
 *    jalan sebelum browser sempat connect -> terminal terlihat mati/blank.
 *  - sesi shell diawasi dan dimulai ulang kalau mati (mis. /bin/bash tidak ada
 *    di alpine). Dulu sekali shell mati, server tetap hidup tapi terminal bisu
 *    selamanya.
 */
public class WebTerminalServer {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private static final int SCROLLBACK_MAX = 256 * 1024;
    private static final long RESTART_MIN_DELAY_MS = 1500L;
    private static final long RESTART_MAX_DELAY_MS = 15000L;

    private final Context ctx;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentHashMap<Socket, Client> clients = new ConcurrentHashMap<>();
    private final Object sessionLock = new Object();
    private final Scrollback scrollback = new Scrollback(SCROLLBACK_MAX);

    private volatile PtyHelper pty;
    private volatile String lastError;
    private final ServerSocket server;

    public WebTerminalServer(Context ctx, int port, boolean lan) throws IOException {
        this.ctx = ctx;
        this.server = new ServerSocket();
        server.setReuseAddress(true);
        InetAddress addr = InetAddress.getByName(lan ? "0.0.0.0" : "127.0.0.1");
        try {
            server.bind(new InetSocketAddress(addr, port));
        } catch (java.net.BindException e) {
            server.bind(new InetSocketAddress(addr, 0));
        }
    }

    public int getBoundPort() {
        return server.getLocalPort();
    }

    public void start() throws Exception {
        // fail-fast: kalau proot/rootfs/ptylauncher bermasalah, exception ini
        // sampai ke TermServerService dan ditampilkan ke user (bukan layar blank).
        PtyHelper first = openSession();
        synchronized (sessionLock) {
            pty = first;
        }
        new Thread(() -> sessionLoop(first), "tt-session").start();
        new Thread(this::acceptLoop, "tt-accept").start();
    }

    public void stop() {
        running.set(false);
        for (Client c : clients.values()) {
            closeQuietly(c);
        }
        clients.clear();
        synchronized (sessionLock) {
            if (pty != null) pty.close();
            pty = null;
        }
        try { server.close(); } catch (IOException ignored) {}
    }

    /** Ringkasan status buatan /healthz — berguna saat debugging dari laptop. */
    public String statusJson() {
        PtyHelper p = pty;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"running\":").append(running.get())
                .append(",\"port\":").append(getBoundPort())
                .append(",\"sessionAlive\":").append(p != null && p.isAlive())
                .append(",\"clients\":").append(clients.size())
                .append(",\"shell\":\"").append(ProotSession.detectShell(ctx.getFilesDir())).append('"');
        String err = lastError;
        if (err != null) {
            sb.append(",\"lastError\":\"").append(err.replace("\"", "'").replace("\n", " ")).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    // ---------- sesi PTY: diawasi + auto restart ----------

    private PtyHelper openSession() throws Exception {
        File dir = ctx.getFilesDir();
        PtyHelper p = PtyHelper.start(dir, ProotSession.buildCommand(dir), ProotSession.environment(dir));
        lastError = null;
        return p;
    }

    private void sessionLoop(PtyHelper current) {
        long delay = RESTART_MIN_DELAY_MS;
        while (running.get()) {
            PtyHelper p = current;
            current = null;

            if (p == null) {
                long startedAt = System.currentTimeMillis();
                try {
                    p = openSession();
                    synchronized (sessionLock) {
                        pty = p;
                    }
                    delay = RESTART_MIN_DELAY_MS;
                    broadcast(bytes("\r\n[sesi shell baru dimulai: "
                            + ProotSession.detectShell(ctx.getFilesDir()) + "]\r\n"));
                } catch (Exception e) {
                    lastError = String.valueOf(e.getMessage());
                    synchronized (sessionLock) {
                        pty = null;
                    }
                    broadcast(bytes("\r\n[gagal memulai sesi: " + lastError + "]\r\n"
                            + "[mencoba lagi " + (delay / 1000) + "s]\r\n"));
                    sleepQuietly(delay);
                    delay = Math.min(delay * 2, RESTART_MAX_DELAY_MS);
                    continue;
                }
                // kalau sesi langsung mati (<3 detik) -> anggap gagal, pakai backoff
                if (!p.isAlive() && System.currentTimeMillis() - startedAt < 3000L) {
                    delay = Math.min(delay * 2, RESTART_MAX_DELAY_MS);
                }
            }

            pumpSession(p);

            synchronized (sessionLock) {
                if (pty == p) pty = null;
            }
            try { p.close(); } catch (Exception ignored) {}

            if (!running.get()) break;
            broadcast(bytes("\r\n[sesi shell berakhir, memulai ulang...]\r\n"));
            sleepQuietly(delay);
            delay = Math.min(delay * 2, RESTART_MAX_DELAY_MS);
        }
    }

    /** Baca output PTY sampai EOF (shell keluar), sambil terus menyiarkan ke client. */
    private void pumpSession(PtyHelper p) {
        Thread errThread = new Thread(() -> pump(p.getError(), 512), "tt-stderr");
        errThread.setDaemon(true);
        errThread.start();
        pump(p.getOutput(), 8192);
    }

    private void pump(InputStream in, int bufSize) {
        byte[] buf = new byte[bufSize];
        try {
            int n;
            while (running.get() && (n = in.read(buf)) >= 0) {
                if (n > 0) {
                    scrollback.add(buf, n);
                    broadcast(buf, n);
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ---------- relay PTY -> broadcast ----------

    private void broadcast(byte[] data) {
        broadcast(data, data.length);
    }

    private void broadcast(byte[] data, int len) {
        for (Client c : clients.values()) {
            synchronized (c.writeLock) {
                try {
                    // BINARY frame: byte PTY mentah boleh apa saja, tidak wajib UTF-8.
                    writeFrame(c.out, OP_BINARY, data, len);
                } catch (IOException ex) {
                    clients.remove(c.socket);
                    closeQuietly(c);
                }
            }
        }
    }

    // ---------- accept loop ----------

    private void acceptLoop() {
        while (running.get()) {
            Socket sock;
            try {
                sock = server.accept();
            } catch (IOException e) {
                break;
            }
            new Thread(() -> handle(sock), "tt-conn").start();
        }
    }

    private void handle(Socket sock) {
        Client client = null;
        try {
            sock.setSoTimeout(0);
            InputStream input = sock.getInputStream();
            OutputStream output = sock.getOutputStream();

            String requestLine = readHttpLine(input);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            Map<String, String> headers = new ConcurrentHashMap<>();
            String line;
            while ((line = readHttpLine(input)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.put(line.substring(0, idx).trim().toLowerCase(),
                        line.substring(idx + 1).trim());
            }

            if ("/healthz".equals(path)) {
                serveHealth(output);
            } else if (!method.equals("GET")) {
                httpError(output, 405);
            } else if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                client = doWebSocket(sock, input, output, headers);
                if (client != null) wsLoop(input, client);
            } else if (path.equals("/ws")) {
                httpError(output, 400, "websocket upgrade expected");
            } else {
                serveStatic(path, output);
            }
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) {
        } finally {
            if (client != null) clients.remove(client.socket);
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    private void serveHealth(OutputStream output) throws IOException {
        byte[] body = statusJson().getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private void httpError(OutputStream output, int code) throws IOException {
        httpError(output, code, "");
    }

    private void httpError(OutputStream output, int code, String reason) throws IOException {
        output.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private String readHttpLine(InputStream input) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) >= 0) {
            if (b == '\n') return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            if (b != '\r') buf.write(b);
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private boolean readFully(InputStream input, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = input.read(buf, off, buf.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    // ---------- web socket ----------

    private Client doWebSocket(Socket sock, InputStream input, OutputStream output,
                               Map<String, String> headers) throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            httpError(output, 400, "missing Sec-WebSocket-Key");
            return null;
        }
        byte[] hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            hash = md.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            httpError(output, 500);
            return null;
        }
        String accept = Base64.getEncoder().encodeToString(hash);
        output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();

        Client client = new Client(sock, output);
        clients.put(sock, client);

        // putar ulang output terakhir supaya client yang connect belakangan
        // langsung melihat prompt/banner, bukan layar kosong.
        byte[] history = scrollback.snapshot();
        if (history.length > 0) {
            synchronized (client.writeLock) {
                try {
                    writeFrame(client.out, OP_BINARY, history, history.length);
                } catch (IOException ignored) {
                }
            }
        }
        return client;
    }

    private void wsLoop(InputStream input, Client client) throws IOException {
        ByteArrayOutputStream fragments = null;
        int fragmentOpcode = 0;

        while (running.get()) {
            int b0 = input.read();
            if (b0 < 0) return;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;

            int b1 = input.read();
            if (b1 < 0) return;
            boolean masked = (b1 & 0x80) != 0;
            long payloadLen = b1 & 0x7f;

            if (payloadLen == 126) {
                payloadLen = (input.read() << 8) | input.read();
            } else if (payloadLen == 127) {
                long l = 0;
                for (int i = 0; i < 8; i++) l = (l << 8) | (long) input.read();
                payloadLen = l;
            }
            if (payloadLen < 0 || payloadLen > Integer.MAX_VALUE - 16) return;

            byte[] mask = new byte[4];
            if (masked && !readFully(input, mask)) return;
            byte[] payload = new byte[(int) payloadLen];
            if (!readFully(input, payload)) return;
            if (masked) for (int i = 0; i < payload.length; i++)
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);

            switch (opcode) {
                case OP_CONTINUATION:
                    if (fragments == null) break;  // frame lanjutan tanpa awal -> abaikan
                    fragments.write(payload, 0, payload.length);
                    if (fin) {
                        byte[] full = fragments.toByteArray();
                        fragments = null;
                        deliver(fragmentOpcode, full, client);
                    }
                    break;
                case OP_TEXT:
                case OP_BINARY:
                    if (!fin) {
                        fragments = new ByteArrayOutputStream();
                        fragments.write(payload, 0, payload.length);
                        fragmentOpcode = opcode;
                    } else {
                        deliver(opcode, payload, client);
                    }
                    break;
                case OP_CLOSE:
                    synchronized (client.writeLock) {
                        writeFrame(client.out, OP_CLOSE, payload, payload.length);
                    }
                    return;
                case OP_PING:
                    synchronized (client.writeLock) {
                        writeFrame(client.out, OP_PONG, payload, payload.length);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private void deliver(int opcode, byte[] payload, Client client) {
        writeToPty(payload, client);
    }

    private void writeToPty(byte[] payload, Client client) {
        PtyHelper p = pty;
        if (p == null || !p.isAlive()) {
            // jangan diam saja: beri tahu client yang sedang mengetik kalau
            // shell-nya belum hidup (kalau tidak, tombol terasa "mati").
            byte[] msg = bytes("\r\n[sesi shell belum jalan, menunggu restart...]\r\n");
            synchronized (client.writeLock) {
                try {
                    writeFrame(client.out, OP_BINARY, msg, msg.length);
                } catch (IOException ignored) {
                }
            }
            return;
        }
        try {
            p.getInput().write(payload);
            p.getInput().flush();
        } catch (IOException ignored) {
        }
    }

    // ---------- frame writer (server -> client, unmasked) ----------

    private void writeFrame(OutputStream output, int opcode, byte[] data, int len) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x80 | opcode);
        if (len < 126) {
            header.write(len);
        } else if (len < 65536) {
            header.write(126);
            header.write((len >> 8) & 0xff);
            header.write(len & 0xff);
        } else {
            header.write(127);
            long l = len;
            for (int i = 0; i < 8; i++) header.write((int) ((l >> (56 - i * 8)) & 0xff));
        }
        output.write(header.toByteArray());
        output.write(data, 0, len);
        output.flush();
    }

    // ---------- static files ----------

    private void serveStatic(String path, OutputStream output) throws IOException {
        if (path.contains("..")) {
            httpError(output, 404);
            return;
        }
        String name = ("/".equals(path) || "/index.html".equals(path))
                ? "web/index.html"
                : "web/" + path.replaceFirst("^/", "");
        byte[] bytes;
        try {
            bytes = readAsset(name);
        } catch (IOException e) {
            httpError(output, 404);
            return;
        }
        String mime = name.endsWith(".css") ? "text/css"
                : name.endsWith(".js") ? "application/javascript"
                : name.endsWith(".json") ? "application/json"
                : "text/html";
        output.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + mime + "; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.flush();
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream is = ctx.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
        is.close();
        return out.toByteArray();
    }

    // ---------- util ----------

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Client c) {
        try { c.socket.close(); } catch (IOException ignored) {}
    }

    /** Ring buffer sederhana untuk scrollback terminal. */
    private static final class Scrollback {
        private final byte[] buf;
        private int start;   // index byte tertua
        private int size;    // jumlah byte terpakai

        Scrollback(int capacity) {
            buf = new byte[capacity];
        }

        synchronized void add(byte[] data, int len) {
            if (len <= 0) return;
            if (len >= buf.length) {
                System.arraycopy(data, len - buf.length, buf, 0, buf.length);
                start = 0;
                size = buf.length;
                return;
            }
            int end = (start + size) % buf.length;
            int firstPart = Math.min(len, buf.length - end);
            System.arraycopy(data, 0, buf, end, firstPart);
            if (len > firstPart) {
                System.arraycopy(data, firstPart, buf, 0, len - firstPart);
            }
            size += len;
            if (size > buf.length) {
                start = (start + (size - buf.length)) % buf.length;
                size = buf.length;
            }
        }

        synchronized byte[] snapshot() {
            byte[] out = new byte[size];
            int firstPart = Math.min(size, buf.length - start);
            System.arraycopy(buf, start, out, 0, firstPart);
            if (size > firstPart) {
                System.arraycopy(buf, 0, out, firstPart, size - firstPart);
            }
            return out;
        }
    }

    /** Satu koneksi websocket; writeLock menjaga agar frame tidak tumpang tindih. */
    private static final class Client {
        final Socket socket;
        final OutputStream out;
        final Object writeLock = new Object();

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }
    }
}
