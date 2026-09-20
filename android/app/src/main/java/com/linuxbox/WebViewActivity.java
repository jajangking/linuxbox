package com.linuxbox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.json.JSONException;
import org.json.JSONObject;

public class WebViewActivity extends Activity {
    private static final String READ_CLIPBOARD = "linuxbox:clipboard-read";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setUserAgentString(
                web.getSettings().getUserAgentString() + " LinuxBoxWebView/1");
        setContentView(web);
        String url = getIntent().getStringExtra("url");
        if (url == null) url = "http://127.0.0.1:8770/";
        // Activity tidak diekspor; URL awal berasal dari service LinuxBox sendiri.
        final Uri terminal = Uri.parse(url);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsPrompt(WebView view, String sourceUrl, String message,
                                      String defaultValue, JsPromptResult result) {
                if (!READ_CLIPBOARD.equals(message)) {
                    return super.onJsPrompt(view, sourceUrl, message, defaultValue, result);
                }
                // Tidak memakai addJavascriptInterface: interface itu tersedia bagi
                // semua iframe tanpa identitas origin pemanggil. Prompt membawa URL
                // sumber, jadi halaman/iframe eksternal tidak dapat membaca clipboard.
                if (!hasWindowFocus() || !view.isShown()
                        || !isTerminalPage(terminal, sourceUrl)
                        || !isTerminalPage(terminal, view.getUrl())) {
                    result.confirm("{\"status\":\"denied\"}");
                } else {
                    result.confirm(readClipboard());
                }
                return true;
            }
        });
        web.loadUrl(url);
    }

    private static boolean isTerminalPage(Uri terminal, String url) {
        if (url == null) return false;
        Uri source = Uri.parse(url);
        String scheme = terminal.getScheme();
        String path = source.getPath();
        return ("http".equals(scheme) || "https".equals(scheme))
                && scheme.equals(source.getScheme())
                && terminal.getHost() != null
                && terminal.getHost().equals(source.getHost())
                && terminal.getPort() == source.getPort()
                && ("/".equals(path) || "/index.html".equals(path));
    }

    private String readClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence text = clip == null || clip.getItemCount() == 0
                    ? null : clip.getItemAt(0).getText();
            // Hanya teks; jangan membuka URI/file dari clipboard melalui coerceToText.
            if (text == null || text.length() == 0) return "{\"status\":\"empty\"}";
            return new JSONObject().put("status", "ok").put("text", text.toString()).toString();
        } catch (SecurityException | JSONException e) {
            return "{\"status\":\"unavailable\"}";
        }
    }
}
