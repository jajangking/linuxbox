package com.linuxbox.web;

import android.content.Context;

import com.linuxbox.distro.DistroCatalog;
import com.linuxbox.distro.ProotSession;

import java.io.ByteArrayOutputStream;
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
 * Server terminal web mandiri: HTTP statis + API + WebSocket (subset RFC6455).
 *
 * Endpoint:
 *   GET  /                       halaman terminal (multi-tab)
 *   GET  /healthz, /api/status   status server + sesi (JSON)
 *   GET  /api/sessions           daftar sesi
 *   POST /api/sessions           buat sesi baru (?name=opsional)
 *   POST /api/sessions/<id>/kill tutup sesi
 *   POST /api/sessions/<id>/rename?name=...
 *   ws   /ws?session=<id>        aliran byte PTY (frame BINARY)
 *   ws   /ctl?session=<id>       kanal kontrol (resize, need-size)
 *
 * Aturan penting yang dulu bikin "command tidak tampil":
 *  - output PTY dikirim sebagai frame BINARY (0x2), bukan TEXT (0x1). Frame
 *    text wajib UTF-8 valid; sekali saja output mengandung byte non-UTF-8
 *    (atau karakter multibyte yang kepotong di batas buffer) browser MEMUTUS
 *    websocket-nya (RFC6455) dan terminal berhenti menampilkan apa pun.
 *  - setiap sesi punya scrollback sendiri yang diputar ulang ke client yang
 *    connect (ulang), jadi prompt/banner tidak hilang.
 *  - sesi diawasi dan dihidupkan ulang oleh SessionManager kalau shell mati.
 */
public class WebTerminalServer {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private static final String CONTROL_PATH = "/ctl";
    private static final String CONTROL_NEED_SIZE = "{\"type\":\"need-size\"}";
    private static final String CONTROL_PONG = "{\"type\":\"pong\"}";

    /** Interval ping: mendeteksi socket setengah terbuka (HP pindah jaringan). */
    private static final long PING_INTERVAL_MS = 10000L;

    /** Batas koneksi bersamaan. Tiap koneksi = 1 thread, jadi harus dibatasi. */
    private static final int MAX_CLIENTS = 32;
    private static final byte[] EMPTY = new byte[0];

    private final Context ctx;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentHashMap<Socket, Client> clients = new ConcurrentHashMap<>();
    private final SessionManager sessions;
    private final long startedAt = System.currentTimeMillis();

    /** null/kosong = tanpa autentikasi (mode localhost). */
    private final String token;
    private final ServerSocket server;

    public WebTerminalServer(Context ctx, int port, boolean lan) throws IOException {
        this(ctx, port, lan, null);
    }

    public WebTerminalServer(Context ctx, int port, boolean lan, String token) throws IOException {
        this.token = token;
        this.ctx = ctx.getApplicationContext();
        this.sessions = new SessionManager(ctx);
        this.server = new ServerSocket();
        server.setReuseAddress(true);
        InetAddress addr = InetAddress.getByName(lan ? "0.0.0.0" : "127.0.0.1");
        try {
            server.bind(new InetSocketAddress(addr, port));
        } catch (java.net.BindException e) {
            // port sibuk (server lama belum mati): pakai port acak agar tetap jalan
            server.bind(new InetSocketAddress(addr, 0));
        }
    }

    public int getBoundPort() {
        return server.getLocalPort();
    }

    public SessionManager sessions() {
        return sessions;
    }

    /** Jumlah koneksi aktif; dipakai service untuk melepas WakeLock saat idle. */
    public int clientCount() {
        return clients.size();
    }

    public void start() throws Exception {
        sessions.restore();
        sessions.startPersistThread();
        new Thread(this::acceptLoop, "tt-accept").start();
        new Thread(this::heartbeatLoop, "tt-hb").start();
    }

    public void stop() {
        running.set(false);
        for (Client c : clients.values()) {
            try { c.socket.close(); } catch (IOException ignored) {}
        }
        clients.clear();
        sessions.closeAll();
        try { server.close(); } catch (IOException ignored) {}
    }

    /** Ringkasan status untuk /healthz dan UI (bisa dibuka dari laptop). */
    public String statusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"running\":").append(running.get())
                .append(",\"port\":").append(getBoundPort())
                .append(",\"uptime\":").append((System.currentTimeMillis() - startedAt) / 1000L)
                .append(",\"sessions\":").append(sessions.list().size())
                .append(",\"sessionsAlive\":").append(sessions.aliveCount())
                .append(",\"clients\":").append(clients.size())
                .append(",\"resize\":true")
                .append(",\"auth\":").append(token != null && !token.isEmpty())
                .append(",\"distro\":\"").append(DistroCatalog.activeId(ctx)).append('"')
                .append(",\"shell\":\"")
                .append(ProotSession.detectShell(ProotSession.activeRootfsDir(ctx)))
                .append('"')
                .append(",\"storage\":").append(SessionManager.hasStoragePermission(ctx));
        String err = firstError();
        if (err != null) {
            sb.append(",\"lastError\":\"").append(err.replace("\"", "'").replace("\n", " ")).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    /** Daftar distro + status terpasang, untuk pemilih distro di UI. */
    private String distrosJson() {
        String active = DistroCatalog.activeId(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"active\":\"").append(active).append("\",\"distros\":[");
        boolean first = true;
        for (DistroCatalog.Distro d : DistroCatalog.load(ctx)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":\"").append(d.id).append('"')
                    .append(",\"label\":\"").append(d.label.replace("\"", "'")).append('"')
                    .append(",\"installed\":").append(DistroCatalog.isInstalled(ctx, d.id))
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private String firstError() {
        for (SessionManager.Session s : sessions.list()) {
            if (s.lastError != null && !s.lastError.isEmpty()) return s.lastError;
        }
        return null;
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

    private void heartbeatLoop() {
        while (running.get()) {
            sleepQuietly(PING_INTERVAL_MS);
            if (!running.get()) break;
            for (Client c : clients.values()) {
                if (c.dead) {
                    clients.remove(c.socket);
                    if (c.session != null) sessions.detach(c.session, c);
                    closeQuietly(c);
                    continue;
                }
                synchronized (c.writeLock) {
                    try {
                        writeFrame(c.out, OP_PING, EMPTY, 0);
                    } catch (IOException e) {
                        c.dead = true;
                    }
                }
            }
        }
    }

    // ---------- HTTP ----------

    private void handle(Socket sock) {
        Client client = null;
        try {
            sock.setSoTimeout(0);
            // WAJIB dibungkus buffered: readHttpLine() membaca header byte demi
            // byte. Tanpa buffer itu berarti satu syscall read() per byte header.
            InputStream input = new java.io.BufferedInputStream(sock.getInputStream(), 8192);
            OutputStream output = new java.io.BufferedOutputStream(sock.getOutputStream(), 8192);

            if (clients.size() >= MAX_CLIENTS) {
                httpError(output, 503, "too many connections");
                return;
            }

            String requestLine = readHttpLine(input);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String target = parts.length > 1 ? parts[1] : "/";
            int qm = target.indexOf('?');
            String path = qm >= 0 ? target.substring(0, qm) : target;
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

            Map<String, String> headers = new ConcurrentHashMap<>();
            String line;
            while ((line = readHttpLine(input)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.put(line.substring(0, idx).trim().toLowerCase(),
                        line.substring(idx + 1).trim());
            }

            if (!authorized(target, headers)) {
                serveUnauthorized(output);
                return;
            }

            // POST: buang body supaya sisa request tidak mengotori koneksi
            int contentLength = 0;
            try {
                contentLength = Integer.parseInt(String.valueOf(headers.get("content-length")));
            } catch (Exception ignored) {
                contentLength = 0;
            }
            if (contentLength > 0) skip(input, contentLength);

            if ("/healthz".equals(path) || "/api/status".equals(path)) {
                serveJson(output, statusJson());
            } else if ("/api/sessions".equals(path)) {
                if ("POST".equals(method)) {
                    String name = queryParam(target, "name");
                    String distro = queryParam(target, "distro");
                    try {
                        SessionManager.Session s = sessions.create(name, distro);
                        serveJson(output, "{\"id\":\"" + s.id + "\",\"name\":\""
                                + SessionManager.sanitize(s.displayName(), s.id)
                                + "\",\"distro\":\"" + s.distroId + "\"}");
                    } catch (IOException e) {
                        serveJson(output, "{\"error\":\"" + String.valueOf(e.getMessage())
                                .replace("\"", "'") + "\"}", 503);
                    }
                } else {
                    serveJson(output, sessions.sessionsJson());
                }
            } else if ("/api/distros".equals(path)) {
                serveJson(output, distrosJson());
            } else if (path.startsWith("/api/sessions/")) {
                handleSessionAction(path, target, method, output);
            } else if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                client = doWebSocket(sock, input, output, headers, target, path);
                if (client != null) wsLoop(input, client);
            } else if ("/ws".equals(path) || "/ctl".equals(path)) {
                httpError(output, 400, "websocket upgrade expected");
            } else if (!"GET".equals(method) && !"HEAD".equals(method)) {
                httpError(output, 405);
            } else {
                serveStatic(path, output);
            }
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) {
        } finally {
            if (client != null) {
                clients.remove(client.socket);
                if (client.session != null) sessions.detach(client.session, client);
            }
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    private void handleSessionAction(String path, String target, String method,
                                     OutputStream output) throws IOException {
        String rest = path.substring("/api/sessions/".length());
        String[] seg = rest.split("/");
        String id = seg.length > 0 ? seg[0] : "";
        String action = seg.length > 1 ? seg[1] : "";
        SessionManager.Session s = sessions.get(id);
        if (s == null) {
            serveJson(output, "{\"error\":\"sesi tidak ada\"}", 404);
            return;
        }
        if ("kill".equals(action) || "DELETE".equals(method)) {
            sessions.kill(id);
            serveJson(output, "{\"ok\":true}");
            return;
        }
        if ("rename".equals(action)) {
            String name = queryParam(target, "name");
            s.name = SessionManager.sanitize(name, s.id);
            sessions.persist();
            serveJson(output, "{\"ok\":true,\"name\":\"" + s.displayName() + "\"}");
            return;
        }
        if ("resize".equals(action)) {
            sessions.resize(s, intParam(target, "rows", -1),
                    intParam(target, "cols", -1));
            serveJson(output, "{\"ok\":true}");
            return;
        }
        serveJson(output, "{\"id\":\"" + s.id + "\",\"name\":\"" + s.displayName()
                + "\",\"alive\":" + s.alive + ",\"rows\":" + s.rows + ",\"cols\":" + s.cols + "}");
    }

    private void serveJson(OutputStream output, String json) throws IOException {
        serveJson(output, json, 200);
    }

    private void serveJson(OutputStream output, String json, int code) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        output.write(("HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    /** Halaman 401 yang menjelaskan apa yang harus dilakukan (bukan layar kosong). */
    private void serveUnauthorized(OutputStream output) throws IOException {
        byte[] body = bytes("<html lang=\"id\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>401 - butuh token</title></head>"
                + "<body style=\"font-family:monospace;background:#0b0e14;color:#c8c8c8;padding:24px\">"
                + "<h2 style=\"color:#f07178\">401 - butuh token</h2>"
                + "<p>Terminal ini dilindungi token.</p>"
                + "<p>Buka URL <b>lengkap</b> yang ada di notifikasi atau log LinuxBox "
                + "(ada <code>?token=...</code>-nya), atau matikan opsi <b>Token</b> "
                + "di aplikasi lalu start ulang server.</p></body></html>");
        output.write(("HTTP/1.1 401 Unauthorized\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(body);
        output.flush();
    }

    private void httpError(OutputStream output, int code) throws IOException {
        httpError(output, code, "");
    }

    private void httpError(OutputStream output, int code, String reason) throws IOException {
        output.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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

    private void skip(InputStream input, int n) throws IOException {
        int left = n;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int r = input.read(buf, 0, Math.min(buf.length, left));
            if (r < 0) return;
            left -= r;
        }
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
                               Map<String, String> headers, String target, String path)
            throws IOException {
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

        boolean control = CONTROL_PATH.equals(path);
        Client client = new Client(sock, output, control);
        client.session = resolveSession(target, control);
        clients.put(sock, client);
        if (client.session != null) {
            sessions.attach(client.session, client);
            if (control) {
                sessions.requestSize(client.session);
            } else {
                byte[] history = sessions.snapshot(client.session);
                if (history.length > 0) client.sendBinary(history, history.length);
            }
        }
        return client;
    }

    private SessionManager.Session resolveSession(String target, boolean control) {
        String id = queryParam(target, "session");
        SessionManager.Session s = id != null ? sessions.get(id) : null;
        if (s == null && !control) {
            // client tanpa id (mis. bookmark lama): tempel ke sesi pertama
            s = sessions.first();
        }
        if (s == null && !control) {
            try {
                s = sessions.create("shell 1");
            } catch (IOException ignored) {
            }
        }
        return s;
    }

    private void wsLoop(InputStream input, Client client) throws IOException {
        ByteArrayOutputStream fragments = null;
        int fragmentOpcode = 0;

        while (running.get() && !client.dead) {
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
                    if (fragments == null) break;
                    fragments.write(payload, 0, payload.length);
                    if (fin) {
                        byte[] full = fragments.toByteArray();
                        fragments = null;
                        if (client.control) handleControl(full, client);
                        else deliver(full, client);
                    }
                    break;
                case OP_TEXT:
                case OP_BINARY:
                    if (!fin) {
                        fragments = new ByteArrayOutputStream();
                        fragments.write(payload, 0, payload.length);
                        fragmentOpcode = opcode;
                    } else if (client.control) {
                        handleControl(payload, client);
                    } else {
                        deliver(payload, client);
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
                case OP_PONG:
                    client.lastPong = System.currentTimeMillis();
                    break;
                default:
                    break;
            }
        }
    }

    private void deliver(byte[] payload, Client client) {
        SessionManager.Session s = client.session;
        if (s == null || client.control) return;
        sessions.write(s, payload);
    }

    // ---------- kanal kontrol (/ctl) ----------

    private void handleControl(byte[] payload, Client client) {
        String msg = new String(payload, StandardCharsets.UTF_8);
        SessionManager.Session s = client.session;
        if (s == null) return;
        if (msg.contains("\"ping\"")) {
            client.sendText(CONTROL_PONG);
            return;
        }
        if (!msg.contains("resize")) return;
        int rows = jsonInt(msg, "rows");
        int cols = jsonInt(msg, "cols");
        if (rows <= 0 || cols <= 0) return;
        sessions.resize(s, rows, cols);
    }

    private static int jsonInt(String json, String key) {
        if (json == null) return -1;
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) return -1;
        int colon = json.indexOf(':', at);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) i++;
        int start = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (i == start) return -1;
        try {
            return Integer.parseInt(json.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int intParam(String target, String key, int def) {
        String v = queryParam(target, key);
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------- frame writer ----------

    private void writeFrame(OutputStream output, int opcode, byte[] data, int len)
            throws IOException {
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
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(mime).append("; charset=utf-8\r\n")
                .append("Content-Length: ").append(bytes.length).append("\r\n")
                .append("Cache-Control: no-cache\r\n");
        if (token != null && !token.isEmpty() && "text/html".equals(mime)) {
            head.append("Set-Cookie: linuxbox_token=").append(token)
                    .append("; Path=/; HttpOnly; SameSite=Lax\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        output.write(head.toString().getBytes(StandardCharsets.UTF_8));
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

    // ---------- autentikasi ----------

    private boolean authorized(String target, Map<String, String> headers) {
        if (token == null || token.isEmpty()) return true;
        String fromQuery = queryParam(target, "token");
        if (fromQuery != null && tokenMatches(fromQuery)) return true;
        String cookie = headers.get("cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                if (part.substring(0, eq).trim().equals("linuxbox_token")
                        && tokenMatches(part.substring(eq + 1).trim())) return true;
            }
        }
        return false;
    }

    private boolean tokenMatches(String candidate) {
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String queryParam(String target, String key) {
        int q = target.indexOf('?');
        if (q < 0) return null;
        for (String part : target.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            if (!part.substring(0, eq).equals(key)) continue;
            String v = part.substring(eq + 1);
            try {
                return java.net.URLDecoder.decode(v, "UTF-8");
            } catch (Exception e) {
                return v;
            }
        }
        return null;
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

    /** Satu koneksi websocket; writeLock menjaga agar frame tidak tumpang tindih. */
    private final class Client implements SessionManager.Sink {
        final Socket socket;
        final OutputStream out;
        final boolean control;
        final Object writeLock = new Object();
        volatile SessionManager.Session session;
        volatile boolean dead;
        volatile long lastPong = System.currentTimeMillis();

        Client(Socket socket, OutputStream out, boolean control) {
            this.socket = socket;
            this.out = out;
            this.control = control;
        }

        @Override
        public boolean isControl() {
            return control;
        }

        @Override
        public void sendBinary(byte[] data, int len) {
            if (control) return;   // data PTY tidak pernah lewat kanal kontrol
            write(OP_BINARY, data, len);
        }

        @Override
        public void sendText(String text) {
            byte[] data = bytes(text);
            write(OP_TEXT, data, data.length);
        }

        private void write(int opcode, byte[] data, int len) {
            synchronized (writeLock) {
                try {
                    writeFrame(out, opcode, data, len);
                } catch (IOException e) {
                    dead = true;
                }
            }
        }
    }
}
