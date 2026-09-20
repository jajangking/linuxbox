package com.linuxbox;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.linuxbox.distro.BackupManager;
import com.linuxbox.distro.Bootstrap;
import com.linuxbox.distro.DistroCatalog;
import com.linuxbox.distro.ProotSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 8770;
    private static final int REQ_RESTORE = 4242;

    private TextView log;
    private ScrollView scroll;
    private Button installBtn;
    private Button startBtn;
    private Button stopBtn;
    private Button backupBtn;
    private Button restoreBtn;
    private EditText portView;
    private CheckBox lanView;
    private CheckBox authView;
    private Spinner distroView;

    private final BroadcastReceiver urlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String url = intent.getStringExtra("url");
            if (url == null) return;
            append("Terminal: " + url);
            startActivity(new Intent(MainActivity.this, WebViewActivity.class).putExtra("url", url));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("LinuxBox");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        installBtn = new Button(this);
        installBtn.setText("Pasang distro");
        startBtn = new Button(this);
        startBtn.setText("Start");
        row.addView(installBtn, lp(1));
        row.addView(startBtn, lp(1));
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        stopBtn = new Button(this);
        stopBtn.setText("Stop server");
        backupBtn = new Button(this);
        backupBtn.setText("Backup");
        restoreBtn = new Button(this);
        restoreBtn.setText("Restore...");
        row2.addView(stopBtn, lp(1));
        row2.addView(backupBtn, lp(1));
        row2.addView(restoreBtn, lp(1));
        root.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        portView = new EditText(this);
        portView.setText(String.valueOf(DEFAULT_PORT));
        portView.setInputType(InputType.TYPE_CLASS_NUMBER);
        lanView = new CheckBox(this);
        lanView.setText("LAN");
        authView = new CheckBox(this);
        authView.setText("Token");
        row3.addView(portView, lp(1));
        row3.addView(lanView, lp(1));
        row3.addView(authView, lp(1));
        root.addView(row3);

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
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setPadding(0, dp(12), 0, 0);
        log.setTextColor(0xFFC8C8C8);
        root.addView(log);

        scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        installBtn.setOnClickListener(v -> install());
        startBtn.setOnClickListener(v -> startServer());
        stopBtn.setOnClickListener(v -> stopServer());
        backupBtn.setOnClickListener(v -> backup());
        restoreBtn.setOnClickListener(v -> pickBackup());

        append("LinuxBox");
        append("  home   : " + getFilesDir().getAbsolutePath());
        append("  distro : " + DistroCatalog.activeId(this));
        append("  rootfs : " + ProotSession.activeRootfsDir(this).getAbsolutePath());
        append("  shell  : " + ProotSession.detectShell(ProotSession.activeRootfsDir(this)));
        append("Pilih distro lalu 'Pasang distro'. Setelah itu 'Start'.");
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(urlReceiver, new IntentFilter("com.linuxbox.URL"));
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(urlReceiver);
        } catch (Exception ignored) {
        }
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            android.net.Uri uri = data.getData();
            append("-- restore dari " + uri.getLastPathSegment());
            busy(new Runnable() {
                @Override
                public void run() {
                    File tmp = new File(getCacheDir(), "restore-" + System.currentTimeMillis() + ".tar.gz");
                    try {
                        InputStream in = getContentResolver().openInputStream(uri);
                        if (in == null) throw new java.io.IOException("berkas tidak bisa dibuka");
                        FileOutputStream out = new FileOutputStream(tmp);
                        byte[] buf = new byte[1 << 16];
                        int n;
                        long total = 0;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            total += n;
                        }
                        out.close();
                        in.close();
                        append("  disalin " + (total / (1024 * 1024)) + " MB");
                        BackupManager.importRootfs(MainActivity.this, tmp, MainActivity.this::append);
                        append("-- selesai. Start ulang server untuk memakai rootfs baru.");
                    } catch (Throwable t) {
                        append("!! GAGAL: " + t.getMessage());
                        t.printStackTrace();
                    } finally {
                        tmp.delete();
                    }
                }
            });
        }
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

    /** Jalankan tugas berat di thread latar sambil menonaktifkan tombol. */
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
            stopBtn.setEnabled(enabled);
            backupBtn.setEnabled(enabled);
            restoreBtn.setEnabled(enabled);
        });
    }

    private DistroCatalog.Distro selectedDistro() {
        Object o = distroView.getSelectedItem();
        return o instanceof DistroCatalog.Distro ? (DistroCatalog.Distro) o : DistroCatalog.current(this);
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

    private void backup() {
        append("-- backup rootfs");
        busy(() -> {
            try {
                File out = BackupManager.exportRootfs(this, this::append);
                append("-- backup selesai: " + out.getName());
            } catch (Throwable t) {
                append("!! GAGAL: " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    private void pickBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/gzip");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(i, "Pilih berkas backup"), REQ_RESTORE);
        } catch (Exception e) {
            append("!! tidak bisa membuka pemilih berkas: " + e.getMessage());
        }
    }

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(portView.getText().toString());
            if (port < 1024 || port > 65535) port = DEFAULT_PORT;
        } catch (NumberFormatException e) {
            port = DEFAULT_PORT;
        }
        boolean lan = lanView.isChecked();
        boolean auth = authView.isChecked();

        String distro = DistroCatalog.activeId(this);
        if (!DistroCatalog.isInstalled(this, distro)) {
            append("!! Distro " + distro + " belum terpasang. Tap 'Pasang distro' dulu.");
            return;
        }
        if (!ProotSession.ptyBin(getFilesDir()).exists()) {
            append("!! ptylauncher belum ada. Jalankan 'Pasang distro'.");
            return;
        }
        Intent intent = new Intent(this, TermServerService.class)
                .putExtra("port", port)
                .putExtra("lan", lan)
                .putExtra("auth", auth);
        startForegroundService(intent);
        append("Memulai server web terminal..."
                + (lan ? " (LAN: wajib token)" : (auth ? " (token aktif)" : "")));
    }

    private void stopServer() {
        try {
            startService(new Intent(this, TermServerService.class).putExtra("stop", true));
        } catch (Exception ignored) {
        }
    }

    private void refreshInfo() {
        runOnUiThread(() -> {
            append("  distro : " + DistroCatalog.activeId(this));
            append("  rootfs : " + ProotSession.activeRootfsDir(this).getAbsolutePath());
            append("  shell  : " + ProotSession.detectShell(ProotSession.activeRootfsDir(this)));
        });
    }
}
