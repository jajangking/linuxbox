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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.linuxbox.distro.Bootstrap;
import com.linuxbox.distro.ProotSession;

import java.io.File;

public class MainActivity extends Activity {

    private static final int DEFAULT_PORT = 8770;

    private TextView log;
    private ScrollView scroll;
    private Button installBtn;
    private EditText portView;
    private CheckBox lanView;

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
        installBtn.setText("Install distro");
        Button startBtn = new Button(this);
        startBtn.setText("Start");
        row.addView(installBtn, lp(1));
        row.addView(startBtn, lp(1));
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        portView = new EditText(this);
        portView.setText(String.valueOf(DEFAULT_PORT));
        portView.setInputType(InputType.TYPE_CLASS_NUMBER);
        lanView = new CheckBox(this);
        lanView.setText("LAN (0.0.0.0)");
        row2.addView(portView, lp(1));
        row2.addView(lanView, lp(1));
        root.addView(row2);

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

        append("LinuxBox");
        append("  home   : " + getFilesDir().getAbsolutePath());
        append("  rootfs : " + ProotSession.rootfsDir(getFilesDir()).getAbsolutePath());
        append("Tap 'Install distro' sekali di awal.");
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

    private void install() {
        installBtn.setEnabled(false);
        append("-- bootstrap dimulai");
        new Thread(() -> {
            try {
                new Bootstrap(this, this::append).install();
                append("-- selesai");
            } catch (Throwable t) {
                append("!! GAGAL: " + t.getMessage());
                t.printStackTrace();
            } finally {
                runOnUiThread(() -> installBtn.setEnabled(true));
            }
        }, "bootstrap").start();
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

        File rootfs = ProotSession.rootfsDir(getFilesDir());
        File etc = new File(rootfs, "etc");
        if (!etc.isDirectory()) {
            append("!! Distro belum terinstall. Tap 'Install distro' dulu.");
            return;
        }
        if (!ProotSession.ptyBin(getFilesDir()).exists()) {
            append("!! ptylauncher belum ada di assets/bin. Jalankan 'Install distro'.");
        }
        Intent intent = new Intent(this, TermServerService.class)
                .putExtra("port", port)
                .putExtra("lan", lan);
        startForegroundService(intent);
        append("Memulai server web terminal...");
    }
}