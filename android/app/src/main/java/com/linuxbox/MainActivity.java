package com.linuxbox;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.linuxbox.distro.BackupManager;
import com.linuxbox.distro.Crypto;
import com.linuxbox.distro.Bootstrap;
import com.linuxbox.distro.DistroCatalog;
import com.linuxbox.distro.ProotSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * Layar utama: pasang distro, jalankan/hentikan server, backup/restore.
 *
 * Perubahan UX dari versi sebelumnya:
 *  - kartu status yang jelas: server hidup/mati, URL, jumlah sesi, terakhir
 *    diperbarui — bukan sekadar deretan baris log.
 *  - URL bisa disalin dan dibuka langsung; tombol dinonaktifkan saat tidak
 *    relevan (mis. "Buka terminal" sebelum server jalan).
 *  - pilihan port/LAN/token dan distro diingat (SharedPreferences), jadi
 *    membuka ulang aplikasi tidak mengulang setelan dari awal.
 */
public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 8770;
    private static final int REQ_RESTORE = 4242;
    private static final int REQ_STORAGE = 4243;
    private static final String PREFS = "linuxbox";

    private TextView log;
    private ScrollView scroll;
    private Button installBtn;
    private Button startBtn;
    private Button stopBtn;
    private Button openBtn;
    private Button copyBtn;
    private Button backupBtn;
    private Button restoreBtn;
    private EditText portView;
    private CheckBox lanView;
    private CheckBox authView;
    private Spinner distroView;

    private TextView stateView;
    private TextView urlView;
    private TextView metaView;

    private volatile String currentUrl;
    private volatile boolean running;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TermServerService.ACTION_STATE.equals(intent.getAction())) {
                running = intent.getBooleanExtra(TermServerService.EXTRA_RUNNING, false);
                String url = intent.getStringExtra(TermServerService.EXTRA_URL);
                String err = intent.getStringExtra(TermServerService.EXTRA_ERROR);
                int sessions = intent.getIntExtra(TermServerService.EXTRA_SESSIONS, 0);
                if (url != null) currentUrl = url;
                if (err != null && !err.isEmpty()) {
                    append("!! " + err);
                    showState(false, null, 0);
                } else {
                    showState(running, currentUrl, sessions);
                    if (running) append("Siap: " + currentUrl);
                }
                return;
            }
            // broadcast lama (URL)
            String url = intent.getStringExtra("url");
            if (url != null) {
                currentUrl = url;
                running = true;
                showState(true, url, 0);
                append("Terminal: " + url);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("LinuxBox");

        int pad = dp(14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // ---- kartu status -------------------------------------------------
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(cardBg());
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        root.addView(card, cardLp);

        stateView = new TextView(this);
        stateView.setTextSize(14);
        stateView.setTypeface(null, Typeface.BOLD);
        card.addView(stateView);

        urlView = new TextView(this);
        urlView.setTextSize(12);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setTextIsSelectable(true);
        urlView.setAutoLinkMask(Linkify.WEB_URLS);
        urlView.setMovementMethod(LinkMovementMethod.getInstance());
        urlView.setPadding(0, dp(4), 0, 0);
        card.addView(urlView);

        metaView = new TextView(this);
        metaView.setTextSize(11);
        metaView.setTextColor(0xFF7C8798);
        metaView.setPadding(0, dp(4), 0, 0);
        card.addView(metaView);

        // ---- tombol utama --------------------------------------------------
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        installBtn = button("Pasang distro");
        startBtn = button("Start");
        stopBtn = button("Stop");
        row1.addView(installBtn, lp(1));
        row1.addView(startBtn, lp(1));
        row1.addView(stopBtn, lp(1));
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        openBtn = button("Buka terminal");
        copyBtn = button("Salin URL");
        row2.addView(openBtn, lp(1));
        row2.addView(copyBtn, lp(1));
        root.addView(row2);

        LinearLayout row2b = new LinearLayout(this);
        row2b.setOrientation(LinearLayout.HORIZONTAL);
        row2b.setPadding(0, dp(6), 0, 0);
        backupBtn = button("Backup");
        restoreBtn = button("Restore…");
        row2b.addView(backupBtn, lp(1));
        row2b.addView(restoreBtn, lp(1));
        root.addView(row2b);

        // ---- opsi ----------------------------------------------------------
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(10), 0, 0);
        portView = new EditText(this);
        portView.setText(String.valueOf(prefs().getInt("port", DEFAULT_PORT)));
        portView.setInputType(InputType.TYPE_CLASS_NUMBER);
        portView.setHint("port");
        lanView = check("LAN");
        lanView.setChecked(prefs().getBoolean("lan", false));
        authView = check("Token");
        authView.setChecked(prefs().getBoolean("auth", false));
        row3.addView(portView, lp(1));
        row3.addView(lanView, lp(1));
        row3.addView(authView, lp(1));
        root.addView(row3);

        lanView.setOnCheckedChangeListener((b, checked) -> {
            // terminal yang terbuka ke LAN wajib ber-token
            if (checked) authView.setChecked(true);
        });

        // ---- distro --------------------------------------------------------
        TextView distroLabel = new TextView(this);
        distroLabel.setText("Distro");
        distroLabel.setTextSize(11);
        distroLabel.setTextColor(0xFF7C8798);
        distroLabel.setPadding(0, dp(10), 0, dp(2));
        root.addView(distroLabel);

        distroView = new Spinner(this);
        List<DistroCatalog.Distro> distros = DistroCatalog.load(this);
        ArrayAdapter<DistroCatalog.Distro> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, distros);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        distroView.setAdapter(adapter);
        String active = DistroCatalog.activeId(this);
        for (int i = 0; i < distros.size(); i++) {
            if (distros.get(i).id.equals(active)) distroView.setSelection(i);
        }
        root.addView(distroView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- log -----------------------------------------------------------
        TextView logLabel = new TextView(this);
        logLabel.setText("Log");
        logLabel.setTextSize(11);
        logLabel.setTextColor(0xFF7C8798);
        logLabel.setPadding(0, dp(10), 0, dp(2));
        root.addView(logLabel);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextColor(0xFFC8C8C8);
        log.setBackground(logBg());
        log.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(log);

        scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        installBtn.setOnClickListener(v -> install());
        startBtn.setOnClickListener(v -> startServer());
        stopBtn.setOnClickListener(v -> stopServer());
        openBtn.setOnClickListener(v -> openTerminal());
        copyBtn.setOnClickListener(v -> copyUrl());
        backupBtn.setOnClickListener(v -> backup());
        restoreBtn.setOnClickListener(v -> pickBackup());

        showState(false, null, 0);
        refreshInfo();
        ensureStoragePermission();

        append("LinuxBox siap.");
        if (prefs().getBoolean("srv_wanted", false)) {
            append("(server terakhir kali menyala — tekan Start untuk menjalankan lagi)");
        }
    }

    // ---------------------------------------------------------------- UI

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
    }

    private CheckBox check(String text) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        return c;
    }

    private GradientDrawable cardBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF11151F);
        d.setCornerRadius(dp(8));
        d.setStroke(Math.max(1, dp(1)), 0xFF1B2230);
        return d;
    }

    private GradientDrawable logBg() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF0E1219);
        d.setCornerRadius(dp(6));
        d.setStroke(Math.max(1, dp(1)), 0xFF1B2230);
        return d;
    }

    private void showState(final boolean isRunning, final String url, final int sessions) {
        runOnUiThread(() -> {
            running = isRunning;
            if (isRunning) {
                stateView.setText("● Server aktif");
                stateView.setTextColor(0xFF4EC9B0);
            } else {
                stateView.setText("○ Server berhenti");
                stateView.setTextColor(0xFF7C8798);
            }
            urlView.setText(url != null ? url : "—");
            String info = DistroCatalog.activeId(MainActivity.this) + " · "
                    + ProotSession.detectShell(ProotSession.activeRootfsDir(MainActivity.this));
            if (sessions > 0) info += " · " + sessions + " sesi";
            metaView.setText(info);
            openBtn.setEnabled(isRunning);
            copyBtn.setEnabled(url != null);
            stopBtn.setEnabled(isRunning);
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams lp(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private void append(String line) {
        runOnUiThread(() -> {
            log.append(line + "\n");
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- aksi

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(TermServerService.ACTION_STATE);
        f.addAction("com.linuxbox.URL");
        registerReceiver(stateReceiver, f);
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        prefs().edit()
                .putInt("port", portNumber())
                .putBoolean("lan", lanView.isChecked())
                .putBoolean("auth", authView.isChecked())
                .apply();
        super.onStop();
    }

    private int portNumber() {
        try {
            int p = Integer.parseInt(portView.getText().toString());
            if (p >= 1024 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
        }
        return DEFAULT_PORT;
    }

    private DistroCatalog.Distro selectedDistro() {
        Object o = distroView.getSelectedItem();
        return o instanceof DistroCatalog.Distro
                ? (DistroCatalog.Distro) o : DistroCatalog.current(this);
    }

    private void install() {
        final DistroCatalog.Distro d = selectedDistro();
        stopServer();
        append("-- memasang " + d.id);
        busy(() -> {
            try {
                new Bootstrap(this, this::append).install(d);
                append("-- selesai. Distro aktif: " + DistroCatalog.activeId(this));
                refreshInfo();
            } catch (Throwable t) {
                append("!! GAGAL: " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    private void startServer() {
        String distro = DistroCatalog.activeId(this);
        if (!DistroCatalog.isInstalled(this, distro)) {
            append("!! Distro " + distro + " belum terpasang. Tap 'Pasang distro' dulu.");
            Toast.makeText(this, "Pasang distro dulu", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ProotSession.ptyBin(ProotSession.nativeLibraryDir(this)).exists()) {
            append("!! ptylauncher belum ada. Jalankan 'Pasang distro'.");
            return;
        }
        boolean lan = lanView.isChecked();
        boolean auth = authView.isChecked();
        Intent intent = new Intent(this, TermServerService.class)
                .putExtra("port", portNumber())
                .putExtra("lan", lan)
                .putExtra("auth", auth);
        try {
            startForegroundService(intent);
        } catch (Exception e) {
            // Android 12+ menolak start dari latar belakang; fallback
            try {
                startService(intent);
            } catch (Exception e2) {
                append("!! tidak bisa memulai service: " + e2.getMessage());
                return;
            }
        }
        append("Memulai server web terminal..."
                + (lan ? " (LAN: wajib token)" : (auth ? " (token aktif)" : "")));
    }

    private void stopServer() {
        try {
            startService(new Intent(this, TermServerService.class)
                    .setAction(TermServerService.ACTION_STOP));
        } catch (Exception ignored) {
        }
        currentUrl = null;
        showState(false, null, 0);
    }

    private void openTerminal() {
        if (currentUrl == null) {
            Toast.makeText(this, "Server belum jalan", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, WebViewActivity.class).putExtra("url", currentUrl));
    }

    private void copyUrl() {
        if (currentUrl == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("LinuxBox URL", currentUrl));
        Toast.makeText(this, "URL disalin", Toast.LENGTH_SHORT).show();
    }

    /** Backup: tanpa passphrase = tar.gz biasa, dengan passphrase = .lbx (AES-GCM). */
    private void backup() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("passphrase (kosongkan = tanpa enkripsi)");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Backup rootfs")
                .setMessage("Isi passphrase untuk mengenkripsi backup "
                        + "(AES-256-GCM). Kosongkan untuk backup tar.gz biasa. "
                        + "Simpan passphrase baik-baik: tanpa itu isi tidak bisa dipulihkan.")
                .setView(input)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Backup", (d, w) -> {
                    char[] pass = passphraseOf(input);
                    append("-- backup rootfs" + (pass.length > 0 ? " (terenkripsi)" : ""));
                    busy(() -> {
                        try {
                            File out = BackupManager.exportRootfs(this, this::append,
                                    pass.length > 0 ? pass : null);
                            append("-- backup selesai: " + out.getName());
                        } catch (Throwable t) {
                            append("!! GAGAL: " + t.getMessage());
                            t.printStackTrace();
                        } finally {
                            Crypto.wipe(pass);
                        }
                    });
                })
                .show();
    }

    /** Ambil passphrase dari kotak teks (tanpa dipangkas: spasi boleh berarti). */
    private static char[] passphraseOf(EditText input) {
        String s = input.getText() == null ? "" : input.getText().toString();
        return s.isEmpty() ? new char[0] : s.toCharArray();
    }

    /** Salin isi URI ke berkas sementara (cache), kembalikan ukurannya. */
    private long copyUriToFile(android.net.Uri uri, File dst) throws java.io.IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new java.io.IOException("berkas tidak bisa dibuka");
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        out.close();
        in.close();
        return total;
    }

    /** Jalankan pemulihan dari berkas (polos atau terenkripsi) di thread latar. */
    private void restoreFrom(File file, char[] passphrase) {
        append("-- restore dari " + file.getName()
                + (passphrase != null && passphrase.length > 0 ? " (terenkripsi)" : ""));
        busy(() -> {
            try {
                BackupManager.importRootfs(MainActivity.this, file,
                        MainActivity.this::append, passphrase);
                append("-- selesai. Start ulang server untuk memakai rootfs baru.");
            } catch (Throwable t) {
                append("!! GAGAL: " + t.getMessage());
                t.printStackTrace();
            } finally {
                Crypto.wipe(passphrase);
                file.delete();
            }
        });
    }

    /** Minta passphrase untuk backup .lbx, lalu pulihkan. */
    private void askPassphraseRestore(File file) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("passphrase backup");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Backup terenkripsi")
                .setMessage("Berkas ini dienkripsi (" + Crypto.describe()
                        + "). Masukkan passphrase-nya.")
                .setView(input)
                .setCancelable(false)
                .setNegativeButton("Batal", (d, w) -> file.delete())
                .setPositiveButton("Pulihkan", (d, w) -> {
                    char[] pass = passphraseOf(input);
                    restoreFrom(file, pass.length > 0 ? pass : null);
                })
                .show();
    }

    private void pickBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            // "*/*" supaya backup terenkripsi (.lbx) juga bisa dipilih;
            // deteksi formatnya dilakukan dari 4 byte pertama berkas.
            i.setType("*/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(i, "Pilih berkas backup"), REQ_RESTORE);
        } catch (Exception e) {
            append("!! tidak bisa membuka pemilih berkas: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            android.net.Uri uri = data.getData();
            append("-- restore dari " + uri.getLastPathSegment());
            busy(() -> {
                File tmp = new File(getCacheDir(),
                        "restore-" + System.currentTimeMillis() + ".bin");
                boolean handedOff = false;
                try {
                    long total = copyUriToFile(uri, tmp);
                    append("  disalin " + (total / (1024 * 1024)) + " MB");
                    if (Crypto.isEncrypted(tmp)) {
                        // Butuh passphrase: serahkan ke dialog di thread UI.
                        // Berkas sengaja tidak dihapus di sini.
                        handedOff = true;
                        runOnUiThread(() -> askPassphraseRestore(tmp));
                        return;
                    }
                    handedOff = true;
                    // restoreFrom() menyentuh tombol lewat busy(), jadi harus
                    // dijalankan dari thread UI, bukan dari thread ini.
                    runOnUiThread(() -> restoreFrom(tmp, null));
                } catch (Throwable t) {
                    append("!! GAGAL: " + t.getMessage());
                    t.printStackTrace();
                } finally {
                    if (!handedOff) tmp.delete();
                }
            });
        }
    }

    /**
     * Izin penyimpanan diperlukan supaya /sdcard bisa di-bind ke dalam guest.
     * Tanpa itu, perintah di dalam distro hanya melihat direktori kosong.
     */
    private void ensureStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        }, REQ_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_STORAGE) {
            boolean granted = results != null && results.length > 0
                    && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            append(granted
                    ? "  /sdcard akan di-bind ke sesi baru"
                    : "  izin penyimpanan ditolak — /sdcard tidak bisa diakses dari guest");
        }
    }

    private void busy(final Runnable task) {
        setButtons(false);
        new Thread(() -> {
            try {
                task.run();
            } finally {
                runOnUiThread(() -> setButtons(true));
            }
        }, "linuxbox-task").start();
    }

    private void setButtons(boolean enabled) {
        runOnUiThread(() -> {
            installBtn.setEnabled(enabled);
            startBtn.setEnabled(enabled);
            backupBtn.setEnabled(enabled);
            restoreBtn.setEnabled(enabled);
        });
    }

    private void refreshInfo() {
        runOnUiThread(() -> {
            String info = DistroCatalog.activeId(this) + " · "
                    + ProotSession.detectShell(ProotSession.activeRootfsDir(this));
            metaView.setText(info);
            append("  distro : " + DistroCatalog.activeId(this));
            append("  rootfs : " + ProotSession.activeRootfsDir(this).getAbsolutePath());
            append("  shell  : " + ProotSession.detectShell(ProotSession.activeRootfsDir(this)));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /** Dipanggil saat tombol kembali ditekan: biarkan server tetap jalan. */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}
