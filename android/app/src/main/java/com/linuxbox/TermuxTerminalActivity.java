package com.linuxbox;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.linuxbox.web.SessionManager;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Terminal native (Termux TerminalView) yang menempel langsung ke sesi PTY
 * LinuxBox lewat SessionManager — tanpa WebView/xterm.js.
 *
 * Alur: service menyimpan WebTerminalServer (yang punya SessionManager) di
 * proses yang sama, jadi activity tinggal mengambil {@link
 * TermServerService#sessions()} dan melampirkan dirinya sebagai
 * {@link SessionManager.Sink}. Termux {@link TerminalSession} dijalankan dalam modus
 * "PTY eksternal" (patch LINUXBOX di modul termux-terminal): input dari
 * pengguna diteruskan ke SessionManager, output PTY dipompa lewat
 * {@link TerminalSession#appendOutput(byte[], int)}.
 */
public final class TermuxTerminalActivity extends Activity
        implements TerminalSessionClient, TerminalViewClient {

    private static final String TAG = "TermuxTerminal";
    /** Baris transcript (scrollback sisi emulator) — pendamping scrollback 128 KB milik SessionManager. */
    private static final int TRANSCRIPT_ROWS = 5000;

    private TerminalView terminalView;
    private TextView statusView;

    private SessionManager manager;
    private SessionManager.Session lxSession;
    private TerminalSession terminalSession;
    private boolean sinkAttached;
    private boolean destroyed;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Sink data: setiap frame PTY yang disiarkan SessionManager dipompa ke emulator. */
    private final SessionManager.Sink sink = new SessionManager.Sink() {
        @Override
        public void sendBinary(byte[] data, int len) {
            final TerminalSession session = terminalSession;
            if (session == null) return;
            runOnUiThread(() -> {
                if (session == terminalSession && len > 0) session.appendOutput(data, len);
            });
        }

        @Override
        public void sendText(String text) {
            // JSON kontrol hanya untuk client web.
        }

        @Override
        public boolean isControl() {
            return false;
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TermServerService.ACTION_STATE.equals(intent.getAction())) return;
            if (intent.getBooleanExtra(TermServerService.EXTRA_RUNNING, false)) {
                SessionManager m = TermServerService.sessions();
                if (m != null) onServerReady(m);
            } else if (manager == null) {
                showStatus("Server terminal sedang berhenti.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Terminal");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        terminalView = new TerminalView(this, null);
        terminalView.setVisibility(View.GONE);
        root.addView(terminalView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(0xFF7C8798);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("Menghubungkan ke terminal...");
        root.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        IntentFilter filter = new IntentFilter(TermServerService.ACTION_STATE);
        registerReceiver(stateReceiver, filter);

        SessionManager m = TermServerService.sessions();
        if (m != null) {
            onServerReady(m);
        } else {
            ensureServerStarted();
        }
    }

    /** Server belum jalan: hidupkan dengan setelan terakhir yang disimpan MainActivity. */
    private void ensureServerStarted() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("linuxbox", MODE_PRIVATE);
        Intent intent = new Intent(this, TermServerService.class)
                .putExtra("port", prefs.getInt("port", 8770))
                .putExtra("lan", prefs.getBoolean("lan", false))
                .putExtra("auth", prefs.getBoolean("auth", false));
        try {
            startForegroundService(intent);
        } catch (Exception e) {
            try {
                startService(intent);
            } catch (Exception e2) {
                showStatus("Tidak bisa memulai server: " + e2.getMessage());
            }
        }
    }

    private void onServerReady(SessionManager m) {
        if (manager != null || destroyed) return;
        manager = m;
        lxSession = selectSession(m);
        if (lxSession == null) {
            showStatus("Tidak ada sesi terminal yang tersedia.\n"
                    + "Pasang distro lalu buka dari layar utama.");
            return;
        }
        attachTerminal();
    }

    /** Pilih sesi: id dari intent > sesi hidup pertama > (fallback) sesi distro baru. */
    private SessionManager.Session selectSession(SessionManager m) {
        String id = getIntent().getStringExtra("session");
        if (id != null) {
            SessionManager.Session s = m.get(id);
            if (s != null) return s;
        }
        SessionManager.Session s = m.first();
        if (s != null) return s;
        try {
            return m.create("shell 1");
        } catch (IOException e) {
            return m.list().isEmpty() ? null : m.list().get(0);
        }
    }

    private void attachTerminal() {
        terminalView.setTerminalViewClient(this);
        // Ukuran font skala density (px). Nilai mendekati default Termux (14dp).
        terminalView.setTextSize(dp(12));
        terminalView.setBackgroundColor(0xFF000000);

        // Modus eksternal: emulator menempel ke PTY LinuxBox, bukan anak sendiri.
        terminalSession = new TerminalSession(TRANSCRIPT_ROWS, this);
        terminalSession.setExternalOutputStream(guestStdin());
        terminalSession.setExternalResizeListener(
                (columns, rows) -> manager.resize(lxSession, rows, columns));

        terminalView.attachSession(terminalSession);

        terminalView.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.GONE);
        setTitle(lxSession.displayName() + (lxSession.isNative()
                ? " (native)" : " (" + lxSession.distroId + ")"));
        terminalView.requestFocus();
        mainHandler.post(() -> terminalView.setTerminalCursorBlinkerState(true, true));
    }

    /** Input pengguna (dari TerminalView) diteruskan ke stdin PTY guest. */
    private OutputStream guestStdin() {
        return new OutputStream() {
            @Override
            public void write(int oneByte) {
                manager.write(lxSession, new byte[]{(byte) oneByte});
            }

            @Override
            public void write(byte[] buffer, int offset, int count) {
                byte[] copy = new byte[count];
                System.arraycopy(buffer, offset, copy, 0, count);
                manager.write(lxSession, copy);
            }
        };
    }

    // ---------------------------------------------------------------- Sink

    /**
     * Dipanggil TerminalView saat emulator siap (size pertama / ukuran berubah).
     * Lampirkan sink + putar ulang scrollback — urutan sama dengan WebTerminalServer.
     */
    @Override
    public void onEmulatorSet() {
        if (sinkAttached) return;
        if (manager == null || lxSession == null || terminalSession == null) return;
        sinkAttached = true;
        manager.attach(lxSession, sink);
        byte[] history = manager.snapshot(lxSession);
        if (history.length > 0) terminalSession.appendOutput(history, history.length);
    }

    // -------------------------------------------------- TerminalSessionClient

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (terminalView != null && changedSession == terminalSession) {
            terminalView.onScreenUpdated();
        }
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        if (changedSession != terminalSession) return;
        String title = changedSession.getTitle();
        setTitle(title == null || title.isEmpty()
                ? lxSession.displayName() : title);
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        // Sesi PTY eksternal tidak pernah "selesai": shell yang mati dihidupkan
        // lagi oleh SessionManager dan dikirim lewat broadcast notice.
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Terminal", text));
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        if (terminalSession == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null && text.length() > 0) {
            terminalSession.write(text.toString());
        }
    }

    @Override
    public void onBell(TerminalSession session) {
        // tidak ada speaker yang terlalu ribut; diam saja.
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        if (terminalView != null) terminalView.setTerminalCursorBlinkerState(state, true);
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;
    }

    // ----------------------------------------------------- TerminalViewClient

    @Override
    public float onScale(float scale) {
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        terminalView.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        // Tombol Back = kembali ke layar utama, bukan ESC (agar pengguna bisa keluar).
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false; // biarkan long-press masuk ke mode seleksi teks
    }

    @Override
    public boolean readControlKey() {
        return false;
    }

    @Override
    public boolean readAltKey() {
        return false;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    // ---------------------------------------------------------------- log

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, e.getMessage(), e);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        if (terminalView != null) terminalView.setTerminalCursorBlinkerState(true, true);
    }

    @Override
    protected void onPause() {
        if (terminalView != null) terminalView.setTerminalCursorBlinkerState(false, false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        if (manager != null && lxSession != null) manager.detach(lxSession, sink);
        if (terminalView != null) terminalView.setTerminalCursorBlinkerState(false, false);
        sinkAttached = false;
        terminalSession = null;
        super.onDestroy();
    }

    private void showStatus(final String text) {
        runOnUiThread(() -> {
            statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}