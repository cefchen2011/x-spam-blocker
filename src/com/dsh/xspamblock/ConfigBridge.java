package com.dsh.xspamblock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import de.robv.android.xposed.XposedBridge;

/**
 * Two-way configuration link between X's process and the module app.
 *
 * XSharedPreferences cannot be used here: this device's ROM redirects an app's
 * shared_prefs out of its data directory, so the path the framework hands out does not
 * exist. Instead the hooked process registers a receiver and asks the module app to
 * publish its settings; the app answers with a broadcast carrying the current values.
 * The app also pushes on every change, so an already-running X picks the switch up
 * immediately.
 */
final class ConfigBridge {

    static final String ACTION_CONFIG = "com.dsh.xspamblock.CONFIG";
    static final String ACTION_REQUEST = "com.dsh.xspamblock.REQUEST";
    static final String ACTION_STATUS = "com.dsh.xspamblock.STATUS";
    /** Sent by the app to remove one keyword, or all of them. */
    static final String ACTION_EDIT = "com.dsh.xspamblock.EDIT_KEYWORD";
    static final String EXTRA_OP = "op";
    static final String EXTRA_KEYWORD = "keyword";
    static final String OP_REMOVE = "remove";
    static final String OP_CLEAR = "clear";
    static final String EXTRA_TEST_MODE = "test_mode";
    static final String EXTRA_KEYWORDS = "keywords";
    static final String EXTRA_AI_ENABLED = "ai_enabled";
    static final String EXTRA_AI_KEY = "ai_key";
    static final String EXTRA_AI_ENDPOINT = "ai_endpoint";
    static final String EXTRA_AI_MODEL = "ai_model";

    private static volatile boolean sTestMode;
    private static volatile boolean sAnswered;

    private ConfigBridge() {}

    static boolean testMode() {
        return sTestMode;
    }

    static void install(final Context ctx) {
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;

                    if (ACTION_EDIT.equals(intent.getAction())) {
                        String op = intent.getStringExtra(EXTRA_OP);
                        if (OP_CLEAR.equals(op)) {
                            MuteStore.clear();
                        } else if (OP_REMOVE.equals(op)) {
                            MuteStore.remove(intent.getStringExtra(EXTRA_KEYWORD));
                        }
                        // MuteStore publishes the new list, which also refreshes the app UI.
                        return;
                    }

                    if (!ACTION_CONFIG.equals(intent.getAction())) return;
                    sTestMode = intent.getBooleanExtra(EXTRA_TEST_MODE, false);
                    AiJudge.configure(
                            intent.getBooleanExtra(EXTRA_AI_ENABLED, true),
                            intent.getStringExtra(EXTRA_AI_KEY),
                            intent.getStringExtra(EXTRA_AI_ENDPOINT),
                            intent.getStringExtra(EXTRA_AI_MODEL));
                    sAnswered = true;
                    XposedBridge.log(ModuleMain.TAG + ": AI config enabled=" + AiJudge.enabled()
                            + (AiJudge.enabled() ? "" : " (no API key configured)"));
                    XposedBridge.log(ModuleMain.TAG + ": config received (testMode=" + sTestMode + ")");
                    // Tell the app we are alive so its status card can say so.
                    try {
                        Intent status = new Intent(ACTION_STATUS)
                                .setPackage(MuteStore.MODULE_PACKAGE)
                                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                .putExtra(EXTRA_TEST_MODE, sTestMode)
                                .putExtra("pid", android.os.Process.myPid())
                                .putExtra("x_version", intent.getStringExtra("x_version"));
                        context.sendBroadcast(status);
                    } catch (Throwable ignored) {
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_CONFIG);
            filter.addAction(ACTION_EDIT);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            XposedBridge.log(ModuleMain.TAG + ": config bridge registered");
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": config bridge registration failed: " + t);
        }
    }

    /** Asks the module app to publish its current settings. */
    static void request(final Context ctx) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                for (int i = 0; i < 10 && !ok; i++) {
                    try {
                        Intent intent = new Intent(ACTION_REQUEST).setPackage(MuteStore.MODULE_PACKAGE);
                        // The module app is usually not running; without this flag the
                        // broadcast would be dropped because its process is stopped.
                        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        ctx.sendBroadcast(intent);
                        ok = true;
                    } catch (Throwable t) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                if (!ok) XposedBridge.log(ModuleMain.TAG + ": config request failed");
            }
        }, "XSBlock-config");
        t.setDaemon(true);
        t.start();
    }

    static boolean answered() {
        return sAnswered;
    }
}
