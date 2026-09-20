package com.linuxbox.web;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Token akses web terminal. Dipakai saat server dibuka ke LAN (0.0.0.0) supaya
 * siapa pun di jaringan yang sama tidak bisa membuka shell tanpa token.
 * Token disimpan permanen per-instal, jadi URL yang sudah dibookmark tetap
 * berlaku sampai dirotasi.
 */
public final class TokenStore {

    private static final String PREFS = "linuxbox";
    private static final String KEY = "web_token";

    private TokenStore() {}

    public static String getOrCreate(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = p.getString(KEY, null);
        if (t == null || t.isEmpty()) t = rotate(ctx);
        return t;
    }

    /** Buat token baru (mis. setelah terminal pernah dibuka tanpa auth). */
    public static String rotate(Context ctx) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        String t = sb.toString();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, t).apply();
        return t;
    }
}
