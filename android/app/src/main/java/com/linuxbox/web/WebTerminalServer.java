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
 */
public class WebTerminalServer {

    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Context ctx;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ConcurrentHashMap<Socket, OutputStream> clients = new ConcurrentHashMap<>();
    private volatile PtyHelper pty;
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
        File dir = ctx.getFilesDir();
        pty = PtyHelper.start(dir, ProotSession.buildCommand(dir), ProotSession.environment(dir));
        new Thread(this::relayLoop, "tt-output").start();
        new Thread(this::stderrLoop, "tt-stderr").start();
        new Thread(this::acceptLoop, "tt-accept").start();
    }

    private void stderrLoop() {
        PtyHelper p = pty;
        if (p == null) return;
        byte[] buf = new byte[512];
        try {
            int n;
            while (running.get() && (n = p.getError().read(buf)) >= 0) {
                if (n > 0) broadcast(buf, n);
            }
        } catch (IOException ignored) {
        }
    }

    public void stop() {
        running.set(false);
        for (Socket s : clients.keySet()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        clients.clear();
        if (pty != null) pty.close();
        pty = null;
        try { server.close(); } catch (Exception ignored) {}
    }

    // ---------- relay PTY -> broadcast ----------

    private void relayLoop() {
        PtyHelper p = pty;
        if (p == null) return;
        InputStream in = p.getOutput();
        byte[] buf = new byte[8192];
        int n;
        try {
            while (running.get() && (n = in.read(buf)) >= 0) {
                if (n > 0) broadcast(buf, n);
            }
        } catch (IOException ignored) {
        }
        byte[] bye = "\r\n[proses PTY berakhir]\r\n".getBytes(StandardCharsets.UTF_8);
        broadcast(bye, bye.length);
    }

    private void broadcast(byte[] data, int len) {
        java.util.Iterator<Map.Entry<Socket, OutputStream>> it = clients.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Socket, OutputStream> e = it.next();
            try {
                writeTextFrame(e.getValue(), data, len);
            } catch (IOException ex) {
                it.remove();
                try { e.getKey().close(); } catch (IOException ignored) {}
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

            if (!method.equals("GET")) {
                httpError(output, 405);
            } else if ("websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                doWebSocket(sock, input, output, headers);
            } else if (path.equals("/ws")) {
                httpError(output, 400, "websocket upgrade expected");
            } else {
                serveStatic(path, output);
            }
        } catch (IOException ignored) {
        } catch (RuntimeException ignored) {
        } finally {
            clients.remove(sock);
            try { sock.close(); } catch (IOException ignored) {}
        }
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

    private void doWebSocket(Socket sock, InputStream input, OutputStream output, Map<String, String> headers)
            throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            httpError(output, 400);
            return;
        }
        byte[] hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            hash = md.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            httpError(output, 500);
            return;
        }
        String accept = Base64.getEncoder().encodeToString(hash);
        output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();

        clients.put(sock, output);
        wsLoop(input, output);
    }

    private void wsLoop(InputStream input, OutputStream output) throws IOException {
        while (running.get()) {
            int b0 = input.read();
            if (b0 < 0) return;
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

            byte[] mask = new byte[4];
            if (masked && !readFully(input, mask)) return;
            byte[] payload = new byte[(int) payloadLen];
            if (!readFully(input, payload)) return;
            if (masked) for (int i = 0; i < payload.length; i++)
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);

            switch (opcode) {
                case 0x1:
                case 0x2:
                    writeToPty(payload);
                    break;
                case 0x8:
                    writeControlFrame(output, 0x8);
                    return;
                case 0x9:
                    writeFrame(output, 0x0A, payload, payload.length);
                    break;
                default:
                    break;
            }
        }
    }

    private void writeToPty(byte[] payload) {
        PtyHelper p = pty;
        if (p == null) return;
        try {
            p.getInput().write(payload);
            p.getInput().flush();
        } catch (IOException ignored) {
        }
    }

    // ---------- frame writer (server -> client, unmasked) ----------

    private void writeTextFrame(OutputStream output, byte[] data, int len) throws IOException {
        writeFrame(output, 0x1, data, len);
    }

    private void writeControlFrame(OutputStream output, int opcode) throws IOException {
        output.write(0x80 | opcode);
        output.write(0);
        output.flush();
    }

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
}