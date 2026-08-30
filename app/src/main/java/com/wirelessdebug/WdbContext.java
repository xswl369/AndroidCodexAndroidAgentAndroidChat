package com.wirelessdebug;

import android.content.Context;

/**
 * 无线调试库的应用上下文持有者。
 * 宿主 App（或 Demo）在 Application.onCreate() 中调用 {@link #init(Context)} 一次即可。
 */
public final class WdbContext {
    private static volatile Context app;

    private WdbContext() {}

    public static void init(Context c) {
        app = c != null ? c.getApplicationContext() : null;
    }

    public static Context get() {
        return app;
    }
}
