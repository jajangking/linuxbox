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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
 * Layar depan minimal LinuxBox (versi tanpa web terminal).
 *
 *  - Saat dibuka: kalau distro (alpine) sudah terpasang, layanan sesi
 *    dihidupkan di latar belakang (sesi tetap hidup) tapi app berhenti di
 *    halaman ini. Terminal native dibuka manual lewat tombol "Buka Terminal".
 *    Kalau belum terpasang, rootfs bawaan dipasang otomatis lebih dulu.
 *  - Tombol di layar ini hanya untuk pekerjaan yang jarang: pasang ulang,
 *    backup, restore, perbaiki identitas distro.
 *  - Semua kustomisasi (font, warna, tombol bawah, env, bind) lewat file
 *    config yang bisa diedit tanpa build/install ulang (lihat {@link Config}).
 */
public class MainActivity extends Activity {

    private static final int REQ_RESTORE = 4242;
    private static final int REQ_STORAGE = 4243;
    private static final String PREFS = "linuxbox";

    private TextView log;
    private ScrollView scroll;
    private Button installBtn;
    private Button nativeBtn;
    private Button toggleBtn;
    private Button configBtn;
    private Button backupBtn;
    private Button restoreBtn;
    private Button repairBtn;
    private BackupManager.RestorePlan pendingRestore;
    private Spinner distroView;
    private TextView stateView;
    private TextView metaView;
    private boolean serviceRunning = false;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TermServerService.ACTION_STATE.equals(intent.getAction())) return;
            boolean running = intent.getBooleanExtra(TermServerService.EXTRA_RUNNING, false);
            String err = intent.getStringExtra(TermServerService.EXTRA_ERROR);
            int sessions = intent.getIntExtra(TermServerService.EXTRA_SESSIONS, 0);
            serviceRunning = running;
            updateToggle();
            if (err != null && !err.isEmpty()) {
                append("!! " + err);
                showState(false, sessions);
            } else {
                showState(running, sessions);
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

        metaView = new TextView(this);
        metaView.setTextSize(11);
        metaView.setTextColor(0xFF7C8798);
        metaView.setPadding(0, dp(4), 0, 0);
        card.addView(metaView);

        nativeBtn = button("Buka Terminal");
        root.addView(nativeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Baris: Start/Stop layanan + lihat config.
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setPadding(0, dp(8), 0, 0);
        toggleBtn = button("Start");
        configBtn = button("Config");
        toggleRow.addView(toggleBtn, lp(1));
        toggleRow.addView(configBtn, lp(1));
        root.addView(toggleRow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        backupBtn = button("Backup");
        restoreBtn = button("Restore…");
        repairBtn = button("Perbaiki");
        row.addView(backupBtn, lp(1));
        row.addView(restoreBtn, lp(1));
        row.addView(repairBtn, lp(1));
        root.addView(row);

        installBtn = button("Pasang ulang distro");
        installBtn.setPadding(0, dp(0), 0, 0);
        LinearLayout.LayoutParams installLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        installLp.topMargin = dp(4);
        root.addView(installBtn, installLp);

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

        nativeBtn.setOnClickListener(v -> {
            startSessionService();
            openNativeTerminal();
        });
        toggleBtn.setOnClickListener(v -> toggleService());
        configBtn.setOnClickListener(v -> showConfig());
        backupBtn.setOnClickListener(v -> backup());
        restoreBtn.setOnClickListener(v -> pickBackup());
        repairBtn.setOnClickListener(v -> repairDistro());
        installBtn.setOnClickListener(v -> install());

        showState(false, 0);
        ensureStoragePermission(); // untuk /sdcard (config luar + bind)
        Config.ensureExampleInternal(this);
        Config.ensureExampleOnExternal(this);

        append("LinuxBox siap.");
        autoStart();
    }

    // ---------------------------------------------------------------- UI

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setPadding(dp(6), 0, dp(6), 0);
        return b;
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

    private void showState(final boolean running, final int sessions) {
        runOnUiThread(() -> {
            if (running) {
                stateView.setText("● Terminal aktif");
                stateView.setTextColor(0xFF4EC9B0);
            } else {
                stateView.setText("○ Menunggu terminal...");
                stateView.setTextColor(0xFF7C8798);
            }
            String info = DistroCatalog.activeId(MainActivity.this) + " · "
                    + ProotSession.detectShell(ProotSession.activeRootfsDir(MainActivity.this));
            if (sessions > 0) info += " · " + sessions + " sesi";
            metaView.setText(info);
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

    // ---------------------------------------------------------------- start

    /** Jalur start: pasang distro kalau perlu, lalu hidupkan layanan sesi di latar belakang. */
    private void autoStart() {
        busy(() -> {
            try {
                String activeId = DistroCatalog.activeId(this);
                if (!DistroCatalog.isInstalled(this, activeId)) {
                    append("-- distro belum terpasang, memasang " + activeId + "...");
                    new Bootstrap(this, this::append).install(DistroCatalog.current(this));
                } else if (!ProotSession.ptyBin(ProotSession.nativeLibraryDir(this)).exists()) {
                    throw new IllegalStateException("ptylauncher belum ada — jalankan 'Pasang ulang distro'");
                } else {
                    append("-- distro " + activeId + " sudah siap");
                }
                startSessionService();
            } catch (Throwable t) {
                append("!! " + t.getMessage());
            }
        });
    }

    private void startSessionService() {
        Intent intent = new Intent(this, TermServerService.class);
        try {
            startForegroundService(intent);
        } catch (Exception e) {
            try {
                startService(intent);
            } catch (Exception e2) {
                append("!! tidak bisa memulai layanan terminal: " + e2.getMessage());
            }
        }
    }

    /** Terminal native (Termux TerminalView) — langsung menempel ke sesi. */
    private void openNativeTerminal() {
        Intent i = new Intent(this, TermuxTerminalActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    /** Hidupkan/hentikan layanan sesi. Sesi selesai semua saat stop. */
    private void toggleService() {
        if (serviceRunning) {
            append("-- menghentikan layanan terminal...");
            try {
                startService(new Intent(this, TermServerService.class)
                        .setAction(TermServerService.ACTION_STOP));
            } catch (Exception e) {
                append("!! tidak bisa mengirim stop: " + e.getMessage());
            }
            serviceRunning = false;
            updateToggle();
        } else {
            startSessionService();
        }
    }

    private void updateToggle() {
        runOnUiThread(() -> toggleBtn.setText(serviceRunning ? "Stop" : "Start"));
    }

    /** Tampilkan lokasi file config + config efektif saat ini. */
    private void showConfig() {
        Config cfg = Config.get(this);
        StringBuilder sb = new StringBuilder();
        sb.append("Lokasi:\n");
        sb.append("  1. ").append(Config.externalPathText()).append("  ← yang diprioritaskan\n");
        sb.append("  2. ").append(Config.internalPathText(this)).append("   (cadangan)\n");
        sb.append("\nConfig saat ini:\n");
        sb.append(cfg.dump());
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setText(sb.toString());
        int pad = dp(14);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Konfigurasi")
                .setView(sv)
                .setNegativeButton("Tutup", null)
                .show();
    }

    // ---------------------------------------------------------------- aksi

    private DistroCatalog.Distro selectedDistro() {
        DistroCatalog.Distro active = DistroCatalog.current(this);
        if (distroView != null) {
            Object o = distroView.getSelectedItem();
            if (o instanceof DistroCatalog.Distro) return (DistroCatalog.Distro) o;
        }
        return active;
    }

    private void install() {
        final DistroCatalog.Distro d = selectedDistro();
        append("-- memasang " + d.id);
        busy(() -> {
            try {
                new Bootstrap(this, this::append).install(d);
                append("-- selesai. Distro aktif: " + DistroCatalog.activeId(this));
                startSessionService();
            } catch (Throwable t) {
                append("!! GAGAL: " + t.getMessage());
                t.printStackTrace();
            }
        });
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

    private static char[] passphraseOf(EditText input) {
        String s = input.getText() == null ? "" : input.getText().toString();
        return s.isEmpty() ? new char[0] : s.toCharArray();
    }

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

    private void restoreFrom(File file, char[] passphrase) {
        append("-- memeriksa backup " + file.getName());
        busy(() -> {
            try {
                BackupManager.RestorePlan plan = BackupManager.prepareRestore(this, file,
                        this::append, passphrase);
                runOnUiThread(() -> confirmRestore(plan));
            } catch (Throwable t) {
                append("!! GAGAL: " + t.getMessage());
                t.printStackTrace();
            } finally {
                Crypto.wipe(passphrase);
                file.delete();
            }
        });
    }

    private void confirmRestore(BackupManager.RestorePlan plan) {
        if (isFinishing() || isDestroyed()) {
            new Thread(plan::close, "restore-cleanup").start();
            return;
        }
        pendingRestore = plan;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Pulihkan " + plan.distroId + "?")
                .setMessage("Identitas dibaca dari isi backup. Tujuan: rootfs-" + plan.distroId
                        + (plan.replacesExisting
                        ? "\n\nDistro tujuan sudah ada dan akan diganti. Rootfs sebelumnya disimpan di folder .before-restore dalam data aplikasi."
                        : "\n\nDistro tujuan belum ada; akan dibuat.")
                        + "\n\nDistro lain tidak dihapus. Layanan terminal harus berhenti sampai selesai.")
                .setNegativeButton("Batal", (d, w) -> discardRestore(plan))
                .setOnCancelListener(d -> discardRestore(plan))
                .setPositiveButton("Pulihkan", (d, w) -> {
                    pendingRestore = null;
                    busy(() -> {
                        try {
                            BackupManager.applyRestore(this, plan, this::append);
                            startSessionService();
                            append("-- selesai. Buka Terminal untuk masuk.");
                        } catch (Throwable t) {
                            append("!! GAGAL: " + t.getMessage());
                        } finally {
                            plan.close();
                        }
                    });
                }).show();
    }

    private void discardRestore(BackupManager.RestorePlan plan) {
        pendingRestore = null;
        busy(plan::close);
    }

    private void repairDistro() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Perbaiki identitas distro aktif?")
                .setMessage("Isi os-release akan diperiksa, lalu folder yang salah label dipindahkan tanpa install ulang atau menghapus isinya."
                        + "\n\nJika distro tujuan sudah ada, perbaikan dibatalkan agar tidak menimpa data.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Perbaiki", (d, w) -> busy(() -> {
                    try {
                        BackupManager.repairActiveIdentity(this, this::append);
                        append("-- selesai.");
                    } catch (Throwable t) {
                        append("!! GAGAL: " + t.getMessage());
                    }
                })).show();
    }

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
                        handedOff = true;
                        runOnUiThread(() -> askPassphraseRestore(tmp));
                        return;
                    }
                    handedOff = true;
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

    /** Izin penyimpanan diperlukan supaya /sdcard bisa di-bind + config luar terbaca. */
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
                    : "  izin penyimpanan ditolak — /sdcard & config luar tidak terbaca");
            if (granted) {
                Config.ensureExampleOnExternal(this);
                // Kalau izin datang setelah config internal dibuat, salin agar
                // file luar berisi nilai-nilai yang sudah diubah sebelumnya.
                File extFile = new File(android.os.Environment.getExternalStorageDirectory()
                        + "/linuxbox/alpine.conf");
                if (extFile.isFile()) append("  config luar: " + extFile.getAbsolutePath());
            }
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
            nativeBtn.setEnabled(enabled);
            toggleBtn.setEnabled(enabled);
            configBtn.setEnabled(enabled);
            backupBtn.setEnabled(enabled);
            restoreBtn.setEnabled(enabled);
            repairBtn.setEnabled(enabled);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            IntentFilter f = new IntentFilter(TermServerService.ACTION_STATE);
            registerReceiver(stateReceiver, f);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        BackupManager.RestorePlan plan = pendingRestore;
        pendingRestore = null;
        if (plan != null) new Thread(plan::close, "restore-cleanup").start();
        super.onDestroy();
    }

    /** Biarkan layanan sesi tetap jalan; minimize ke belakang. */
    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }
}