package com.wirelessdebug;

import android.content.Context;

/**
 * 无线调试配对状态（SharedPreferences 持久化）。
 * 配对成功/校验连接成功时写入，供 shell 通道与 UI 共享。
 */
public final class PairState {
    private static final String PREF = "wireless_debug";
    private static final String KEY_PAIRED = "paired";

    private PairState() {}

    public static boolean isPaired(Context ctx) {
        try {
            return ctx != null && ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean(KEY_PAIRED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void markPaired(Context ctx) {
        try {
            if (ctx != null) {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_PAIRED, true).apply();
            }
        } catch (Throwable ignored) {}
    }

    public static void clear(Context ctx) {
        try {
            if (ctx != null) {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .edit().remove(KEY_PAIRED).apply();
            }
        } catch (Throwable ignored) {}
    }
}
