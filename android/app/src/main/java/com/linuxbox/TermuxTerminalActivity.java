package com.linuxbox;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.linuxbox.web.SessionManager;
import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Terminal native (Termux TerminalView) yang menempel langsung ke sesi PTY
 * LinuxBox lewat {@link SessionManager} — tanpa WebView/xterm.js.
 *
 * Alur: layanan sesi ({@link TermServerService}) menyimpan SessionManager di
 * proses yang sama, jadi activity tinggal mengambil {@link
 * TermServerService#sessions()} dan melampirkan dirinya sebagai
 * {@link SessionManager.Sink}. Termux {@link TerminalSession} dijalankan dalam modus
 * "PTY eksternal" (patch LINUXBOX di modul termux-terminal): input dari
 * pengguna diteruskan ke SessionManager, output PTY dipompa lewat
 * {@link TerminalSession#appendOutput(byte[], int)}.
 *
 * Semua hal yang bisa diatur pengguna ada di file config (lihat {@link Config})
 * supaya berubah TANPA build/install: ukuran font, warna tema, dan susunan
 * tombol baris bawah.
 */
public final class TermuxTerminalActivity extends Activity
        implements TerminalSessionClient, TerminalViewClient {

    private static final String TAG = "TermuxTerminal";
    /** Baris transcript (scrollback sisi emulator) — pendamping scrollback 128 KB milik SessionManager. */
    private static final int TRANSCRIPT_ROWS = 5000;

    private TerminalView terminalView;
    private TextView statusView;
    private LinearLayout extraKeys;
    private LinearLayout sessionBar;
    private LinearLayout chipsLayout;
    private Button ctrlKey, altKey;
    private boolean ctrlOn, altOn;

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
            // JSON kontrol dulu untuk client web, sekarang tidak dipakai.
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
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle("Terminal");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        buildSessionBar();
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        barLp.gravity = Gravity.TOP;
        root.addView(sessionBar, barLp);

        terminalView = new TerminalView(this, null);
        terminalView.setVisibility(View.GONE);
        FrameLayout.LayoutParams tvLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tvLp.topMargin = dp(38);
        tvLp.bottomMargin = dp(46);
        root.addView(terminalView, tvLp);

        statusView = new TextView(this);
        statusView.setTextColor(0xFF7C8798);
        statusView.setTextSize(14);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("Menghubungkan ke terminal...");
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dp(52);
        statusLp.gravity = Gravity.TOP;
        root.addView(statusView, statusLp);

        buildExtraKeys();
        FrameLayout.LayoutParams ekLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        ekLp.gravity = Gravity.BOTTOM;
        root.addView(extraKeys, ekLp);

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

    /** Layanan belum jalan: hidupkan. Sesi dibuat & dikonfigurasi dari file config. */
    private void ensureServerStarted() {
        Intent intent = new Intent(this, TermServerService.class);
        try {
            startForegroundService(intent);
        } catch (Exception e) {
            try {
                startService(intent);
            } catch (Exception e2) {
                showStatus("Tidak bisa memulai layanan terminal: " + e2.getMessage());
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
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.setClickable(true);
        // Ukuran font & warna bisa diubah dari file config (tanpa rebuild).
        applyConfig();

        // Modus eksternal: emulator menempel ke PTY LinuxBox, bukan anak sendiri.
        terminalSession = new TerminalSession(TRANSCRIPT_ROWS, this);
        terminalSession.setExternalOutputStream(guestStdin());
        terminalSession.setExternalResizeListener(
                (columns, rows) -> manager.resize(lxSession, rows, columns));

        terminalView.attachSession(terminalSession);

        terminalView.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.GONE);
        updateTitle();
        refreshSessionBar();
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
     * Lampirkan sink + putar ulang scrollback.
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
        return takeCtrl();
    }

    @Override
    public boolean readAltKey() {
        return takeAlt();
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

    // ---------------------------------------------- multi sesi (ala Termux)

    /** Bar tab sesi di atas terminal: chip nama + tutup, plus tombol sesi baru. */
    private void buildSessionBar() {
        sessionBar = new LinearLayout(this);
        sessionBar.setOrientation(LinearLayout.HORIZONTAL);
        sessionBar.setBackgroundColor(0xFF0B0E13);
        sessionBar.setPadding(dp(2), dp(4), dp(2), dp(2));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        sessionBar.addView(scroll, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        chipsLayout = new LinearLayout(this);
        chipsLayout.setOrientation(LinearLayout.HORIZONTAL);
        chipsLayout.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(chipsLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button addBtn = new Button(this);
        addBtn.setText("+");
        addBtn.setTextSize(16);
        addBtn.setAllCaps(false);
        addBtn.setMinWidth(0);
        addBtn.setMinHeight(0);
        addBtn.setTextColor(0xFFD8DEE9);
        addBtn.setBackgroundColor(0xFF1D2530);
        addBtn.setFocusable(false);
        addBtn.setPadding(dp(10), 0, dp(10), 0);
        addBtn.setOnClickListener(v -> newSession());
        sessionBar.addView(addBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button menuBtn = new Button(this);
        menuBtn.setText("\u25C0 Menu");
        menuBtn.setTextSize(14);
        menuBtn.setAllCaps(false);
        menuBtn.setMinWidth(0);
        menuBtn.setMinHeight(0);
        menuBtn.setTextColor(0xFFD8DEE9);
        menuBtn.setBackgroundColor(0xFF1D2530);
        menuBtn.setFocusable(false);
        menuBtn.setPadding(dp(8), 0, dp(8), 0);
        menuBtn.setOnClickListener(v -> goToMenu());
        sessionBar.addView(menuBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void refreshSessionBar() {
        if (manager == null || chipsLayout == null) return;
        chipsLayout.removeAllViews();
        for (SessionManager.Session s : manager.list()) {
            chipsLayout.addView(buildSessionChip(s));
        }
    }

    private View buildSessionChip(final SessionManager.Session s) {
        boolean selected = s == lxSession;
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundColor(selected ? 0xFFE69A45 : 0xFF232B38);
        chip.setPadding(dp(8), 0, dp(2), 0);
        chip.setFocusable(false);
        if (!selected) {
            chip.setOnClickListener(v -> switchTo(s));
        }

        TextView label = new TextView(this);
        label.setText(s.displayName());
        label.setTextSize(12);
        label.setTextColor(selected ? 0xFF000000 : 0xFFD8DEE9);
        chip.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextSize(14);
        close.setTextColor(selected ? 0xFF5A3210 : 0xFF9AA5B5);
        close.setGravity(Gravity.CENTER);
        close.setFocusable(false);
        close.setClickable(true);
        close.setOnClickListener(v -> killSession(s));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                dp(36), ViewGroup.LayoutParams.MATCH_PARENT);
        closeLp.setMargins(dp(2), 0, 0, 0);
        chip.addView(close, closeLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(0, 0, dp(3), 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    /** Buat sesi baru: distro aktif, fallback ke shell native kalau belum ada distro. */
    private void newSession() {
        if (manager == null) return;
        try {
            switchTo(manager.create(null));
        } catch (IOException e) {
            try {
                switchTo(manager.createNative(null));
            } catch (IOException e2) {
                showStatus("Sesi baru gagal: " + e2.getMessage());
            }
        }
    }

    /** Perpindahan sesi: lepas sink lama, pasang sesi baru + replay scrollback. */
    private void switchTo(SessionManager.Session s) {
        if (s == null || s == lxSession) return;
        if (manager != null && lxSession != null && sinkAttached) {
            manager.detach(lxSession, sink);
        }
        sinkAttached = false;
        lxSession = s;
        applyConfig();
        terminalSession = new TerminalSession(TRANSCRIPT_ROWS, this);
        terminalSession.setExternalOutputStream(guestStdin());
        terminalSession.setExternalResizeListener(
                (columns, rows) -> manager.resize(lxSession, rows, columns));
        terminalView.attachSession(terminalSession);
        terminalView.requestFocus();
        updateTitle();
        refreshSessionBar();
    }

    private void killSession(final SessionManager.Session s) {
        if (manager == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Tutup sesi")
                .setMessage("Tutup sesi \"" + s.displayName() + "\"?")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Tutup", (d, w) -> removeSession(s))
                .show();
    }

    private void removeSession(SessionManager.Session s) {
        boolean wasCurrent = s == lxSession;
        manager.kill(s.id);
        if (!wasCurrent) {
            refreshSessionBar();
            return;
        }
        List<SessionManager.Session> rest = manager.list();
        SessionManager.Session next = manager.first();
        if (next == null && !rest.isEmpty()) next = rest.get(rest.size() - 1);
        if (next != null) {
            switchTo(next);
        } else {
            try {
                switchTo(manager.createNative(null));
            } catch (IOException e) {
                showStatus("Semua sesi ditutup.");
                onBackPressed();
            }
        }
    }

    private void updateTitle() {
        if (lxSession == null) return;
        setTitle(lxSession.displayName() + (lxSession.isNative()
                ? " (native)" : " (" + lxSession.distroId + ")"));
    }

    // ------------------------------------------------- extra keys (ala Termux)

    private void buildExtraKeys() {
        extraKeys = new LinearLayout(this);
        extraKeys.setOrientation(LinearLayout.HORIZONTAL);
        extraKeys.setBackgroundColor(0xFF0E1116);
        extraKeys.setPadding(dp(2), dp(2), dp(2), dp(2));

        for (String token : Config.get(this).extraKeys()) {
            addTokenKey(token);
        }
    }

    /** Tombol dari config: CTRL/ALT (latch), kode nama, atau satu karakter. */
    private void addTokenKey(String token) {
        switch (token) {
            case "CTRL":
                ctrlKey = keyButton(token, false);
                ctrlKey.setOnClickListener(v -> toggleCtrl());
                extraKeys.addView(ctrlKey, keyLp(1.6f));
                break;
            case "ALT":
                altKey = keyButton(token, false);
                altKey.setOnClickListener(v -> toggleAlt());
                extraKeys.addView(altKey, keyLp(1.6f));
                break;
            case "ESC":
                addKeyCodeKey(token, KeyEvent.KEYCODE_ESCAPE);
                break;
            case "TAB":
                addKeyCodeKey(token, KeyEvent.KEYCODE_TAB);
                break;
            case "◀": case "LEFT":
                addKeyCodeKey("◀", KeyEvent.KEYCODE_DPAD_LEFT);
                break;
            case "▶": case "RIGHT":
                addKeyCodeKey("▶", KeyEvent.KEYCODE_DPAD_RIGHT);
                break;
            case "▲": case "UP":
                addKeyCodeKey("▲", KeyEvent.KEYCODE_DPAD_UP);
                break;
            case "▼": case "DOWN":
                addKeyCodeKey("▼", KeyEvent.KEYCODE_DPAD_DOWN);
                break;
            default:
                if (token.length() == 1) {
                    addCharKey(token, token.charAt(0));
                } else {
                    Log.w(TAG, "token tombol tidak dikenal di config: " + token);
                }
        }
    }

    /**
     * Terapkan tampilan dari file config: ukuran font, warna latar & tema.
     * Dipanggil sebelum membuat TerminalSession (tema dibaca saat emulator
     * dibangun), sehingga mengubah file lalu membuka ulang langsung terasa.
     */
    private void applyConfig() {
        Config cfg = Config.get(this);
        terminalView.setTextSize(dp(cfg.fontSizeDp()));
        String bg = cfg.background();
        try {
            terminalView.setBackgroundColor(bg != null
                    ? android.graphics.Color.parseColor(bg) : 0xFF000000);
        } catch (IllegalArgumentException ignored) {
            terminalView.setBackgroundColor(0xFF000000);
        }
        Map<String, String> theme = cfg.theme();
        if (theme.isEmpty()) return;
        Properties p = new Properties();
        for (Map.Entry<String, String> e : theme.entrySet()) {
            if (validateColor(e.getValue())) p.setProperty(e.getKey(), e.getValue());
        }
        if (p.isEmpty()) return;
        try {
            TerminalColors.COLOR_SCHEME.updateWith(p);
            TerminalColorScheme scheme = TerminalColors.COLOR_SCHEME;
            scheme.setCursorColorForBackground();
        } catch (Exception ignored) {
        }
    }

    private static boolean validateColor(String v) {
        return v != null && v.startsWith("#") && (v.length() == 7);
    }

    private void toggleCtrl() {
        ctrlOn = !ctrlOn;
        paintToggle(ctrlKey, ctrlOn);
    }

    private void toggleAlt() {
        altOn = !altOn;
        paintToggle(altKey, altOn);
    }

    /** Ambil kondisi CTRL dan langsung lepas (latch) supaya tidak tersangkut ON. */
    private boolean takeCtrl() {
        boolean v = ctrlOn;
        if (v) consumeMods();
        return v;
    }

    private boolean takeAlt() {
        boolean v = altOn;
        if (v) consumeMods();
        return v;
    }

    /** Lepas semua toggle modifier setelah satu karakter dipakai (ala Termux). */
    private void consumeMods() {
        boolean any = ctrlOn || altOn;
        ctrlOn = false;
        altOn = false;
        if (any) {
            paintToggle(ctrlKey, false);
            paintToggle(altKey, false);
        }
    }

    private void addCharKey(final String label, final char c) {
        Button b = keyButton(label, true);
        b.setOnClickListener(v -> {
            terminalView.inputCodePoint(c, ctrlOn, altOn);
            if (ctrlOn || altOn) consumeMods();
        });
        extraKeys.addView(b, keyLp());
    }

    private void addKeyCodeKey(final String label, final int keyCode) {
        Button b = keyButton(label, true);
        b.setOnClickListener(v -> {
            terminalView.handleKeyCode(keyCode, keyMods());
            if (ctrlOn || altOn) consumeMods();
        });
        extraKeys.addView(b, keyLp());
    }

    private int keyMods() {
        int mod = 0;
        if (ctrlOn) mod |= KeyHandler.KEYMOD_CTRL;
        if (altOn) mod |= KeyHandler.KEYMOD_ALT;
        return mod;
    }

    private Button keyButton(String label, boolean plain) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setTextSize(13);
        b.setTextColor(0xFFD8DEE9);
        b.setBackgroundColor(plain ? 0xFF1D2530 : 0xFF232B38);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private void paintToggle(Button b, boolean on) {
        if (on) {
            b.setTextColor(0xFF000000);
            b.setBackgroundColor(0xFFE69A45);
        } else {
            b.setTextColor(0xFFD8DEE9);
            b.setBackgroundColor(0xFF232B38);
        }
    }

    private LinearLayout.LayoutParams keyLp(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, w);
        lp.setMargins(dp(1), 0, dp(1), 0);
        return lp;
    }

    private LinearLayout.LayoutParams keyLp() {
        return keyLp(1f);
    }

    @Override
    public void onBackPressed() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        // Kalau keyboard sedang terbuka, Back pertama menutupnya. hideSoftInput
        // mengembalikan true hanya kalau ada keyboard yang benar-benar ditutup,
        // jadi tidak pernah "menelan" Back padahal keyboard sudah hilang.
        if (imm != null && imm.hideSoftInputFromWindow(
                getWindow().getDecorView().getWindowToken(), 0)) {
            return;
        }
        // Tanpa keyboard: tutup terminal (finish). MainActivity (menu) ada di
        // bawahnya di task yang sama, jadi Back otomatis kembali ke menu;
        // sesi tetap hidup di layanan. Dari menu, Back = minimize.
        finish();
    }

    /** Tombol "◀ Menu": kembali ke halaman utama; sesi tetap hidup. */
    private void goToMenu() {
        finish();
    }
}