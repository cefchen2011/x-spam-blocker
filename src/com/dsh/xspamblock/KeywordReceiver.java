package com.dsh.xspamblock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Settings bridge for the hooked process.
 *
 * Stores the blocked-keyword snapshot that X's process reports, and answers its config
 * requests so the hook side sees the current test-mode switch.
 */
public class KeywordReceiver extends BroadcastReceiver {

    public static final String PREFS = "xspamblock";
    public static final String KEY_LIST = "keyword_list";
    public static final String KEY_COUNT = "keyword_count";
    public static final String KEY_TEST_MODE = "test_mode";
    public static final String KEY_LAST_SEEN = "last_seen";
    public static final String KEY_PID = "last_pid";
    public static final String KEY_AI_ENABLED = "ai_enabled";
    public static final String KEY_AI_KEY = "ai_key";
    public static final String KEY_AI_ENDPOINT = "ai_endpoint";
    public static final String KEY_AI_MODEL = "ai_model";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (MuteStore.ACTION.equals(action)) {
            p.edit()
                    .putString(KEY_LIST, intent.getStringExtra("keywords"))
                    .putInt(KEY_COUNT, intent.getIntExtra("count", 0))
                    .putLong("updated_at", System.currentTimeMillis())
                    .apply();
            return;
        }

        if (ConfigBridge.ACTION_REQUEST.equals(action)) {
            publish(context, p);
            return;
        }

        if (ConfigBridge.ACTION_STATUS.equals(action)) {
            p.edit()
                    .putLong(KEY_LAST_SEEN, System.currentTimeMillis())
                    .putInt(KEY_PID, intent.getIntExtra("pid", 0))
                    .apply();
        }
    }

    /** Asks the hooked process to drop one keyword (op = OP_REMOVE) or all of them. */
    public static void edit(Context context, String op, String keyword) {
        Intent i = new Intent(ConfigBridge.ACTION_EDIT)
                .setPackage("com.twitter.android")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ConfigBridge.EXTRA_OP, op)
                .putExtra(ConfigBridge.EXTRA_KEYWORD, keyword);
        context.sendBroadcast(i);
    }

    /** Pushes the current settings back to the hooked process. */
    public static void publish(Context context, SharedPreferences p) {
        Intent reply = new Intent(ConfigBridge.ACTION_CONFIG)
                .setPackage("com.twitter.android")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ConfigBridge.EXTRA_TEST_MODE, p.getBoolean(KEY_TEST_MODE, false))
                .putExtra(ConfigBridge.EXTRA_KEYWORDS, p.getString(KEY_LIST, ""))
                .putExtra(ConfigBridge.EXTRA_AI_ENABLED, p.getBoolean(KEY_AI_ENABLED, true))
                .putExtra(ConfigBridge.EXTRA_AI_KEY, p.getString(KEY_AI_KEY, ""))
                .putExtra(ConfigBridge.EXTRA_AI_ENDPOINT,
                        p.getString(KEY_AI_ENDPOINT, AiJudge.ENDPOINT_DEFAULT))
                .putExtra(ConfigBridge.EXTRA_AI_MODEL,
                        p.getString(KEY_AI_MODEL, AiJudge.MODEL_DEFAULT));
        context.sendBroadcast(reply);
    }
}
