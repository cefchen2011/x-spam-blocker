package com.dsh.xspamblock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Module settings, styled with the Material 3 baseline scheme.
 *
 * The screen doubles as a health check: it asks X's process to report in and shows the
 * answer, so "module not loaded / scope not ticked" is distinguishable from "loaded but
 * nothing matched yet".
 */
public class SettingsActivity extends Activity {

    private static final String PROBE_TEXT =
            "@xiaomei520,\u54e5\u54e5\u7ea6\u5417\uff0c\u59b9\u59b9\u4e0a\u95e8\u670d\u52a1\u54e6";

    private View mStatusDot;
    private TextView mStatusTitle;
    private TextView mStatusDetail;
    private TextView mKeywordCount;
    private TextView mChipEmpty;
    private LinearLayout mChipFlow;
    private Switch mTestSwitch;
    private Switch mAiSwitch;
    private Switch mAiStrictSwitch;
    private EditText mAiKeyField;
    private EditText mAiModelField;
    private EditText mAiEndpointField;
    private TextView mAiResult;

    private BroadcastReceiver mStatusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mStatusDot = findViewById(R.id.statusDot);
        mStatusTitle = findViewById(R.id.statusTitle);
        mStatusDetail = findViewById(R.id.statusDetail);
        mKeywordCount = findViewById(R.id.keywordCount);
        mChipEmpty = findViewById(R.id.chipEmpty);
        mChipFlow = findViewById(R.id.chipFlow);
        mTestSwitch = findViewById(R.id.testSwitch);
        mAiSwitch = findViewById(R.id.aiSwitch);
        mAiStrictSwitch = findViewById(R.id.aiStrictSwitch);
        mAiKeyField = findViewById(R.id.aiKeyField);
        mAiModelField = findViewById(R.id.aiModelField);
        mAiEndpointField = findViewById(R.id.aiEndpointField);
        mAiResult = findViewById(R.id.aiResult);

        SharedPreferences prefs = getSharedPreferences(KeywordReceiver.PREFS, Context.MODE_PRIVATE);

        mTestSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                SharedPreferences sp = getSharedPreferences(KeywordReceiver.PREFS, Context.MODE_PRIVATE);
                sp.edit().putBoolean(KeywordReceiver.KEY_TEST_MODE, checked).commit();
                KeywordReceiver.publish(getApplicationContext(), sp);
            }
        });
        mAiSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                SharedPreferences sp = getSharedPreferences(KeywordReceiver.PREFS, Context.MODE_PRIVATE);
                sp.edit().putBoolean(KeywordReceiver.KEY_AI_ENABLED, checked).commit();
                KeywordReceiver.publish(getApplicationContext(), sp);
            }
        });
        mAiStrictSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                SharedPreferences sp = getSharedPreferences(KeywordReceiver.PREFS, Context.MODE_PRIVATE);
                sp.edit().putBoolean(KeywordReceiver.KEY_AI_STRICT, checked).commit();
                KeywordReceiver.publish(getApplicationContext(), sp);
            }
        });
        findViewById(R.id.aiSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndTest();
            }
        });

        mStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refresh();
            }
        };
        IntentFilter filter = new IntentFilter(ConfigBridge.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mStatusReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mStatusReceiver, filter);
        }

        loadAiFields(prefs);
    }

    private void loadAiFields(SharedPreferences p) {
        mAiSwitch.setChecked(p.getBoolean(KeywordReceiver.KEY_AI_ENABLED, true));
        mAiStrictSwitch.setChecked(p.getBoolean(KeywordReceiver.KEY_AI_STRICT, false));
        mAiKeyField.setText(p.getString(KeywordReceiver.KEY_AI_KEY, ""));
        mAiModelField.setText(p.getString(KeywordReceiver.KEY_AI_MODEL, AiJudge.MODEL_DEFAULT));
        mAiEndpointField.setText(p.getString(KeywordReceiver.KEY_AI_ENDPOINT, AiJudge.ENDPOINT_DEFAULT));
    }

    private void saveAndTest() {
        final String key = mAiKeyField.getText().toString().trim();
        final String model = mAiModelField.getText().toString().trim();
        final String endpoint = mAiEndpointField.getText().toString().trim();

        SharedPreferences sp = getSharedPreferences(KeywordReceiver.PREFS, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KeywordReceiver.KEY_AI_KEY, key)
                .putString(KeywordReceiver.KEY_AI_MODEL, model)
                .putString(KeywordReceiver.KEY_AI_ENDPOINT, endpoint)
                .commit();
        KeywordReceiver.publish(getApplicationContext(), sp);

        mAiResult.setText("\u6b63\u5728\u8c03\u7528 " + (model.isEmpty() ? AiJudge.MODEL_DEFAULT : model) + " \u2026");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = AiJudge.probe(endpoint, key, model, PROBE_TEXT);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mAiResult.setText("\u6d4b\u8bd5\u6837\u672c\uff1a" + PROBE_TEXT + "\n" + result);
                    }
                });
            }
        }, "XSBlock-ai-test").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences p = getSharedPreferences(KeywordReceiver.PREFS, Context.MODE_PRIVATE);
        KeywordReceiver.publish(getApplicationContext(), p);
        ConfigBridge.request(getApplicationContext());
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mStatusReceiver != null) {
            try {
                unregisterReceiver(mStatusReceiver);
            } catch (Throwable ignored) {
            }
        }
    }

    private void refresh() {
        SharedPreferences p = getSharedPreferences(KeywordReceiver.PREFS, Context.MODE_PRIVATE);

        long lastSeen = p.getLong(KeywordReceiver.KEY_LAST_SEEN, 0L);
        boolean alive = lastSeen > 0 && System.currentTimeMillis() - lastSeen < 60_000L;
        int pid = p.getInt(KeywordReceiver.KEY_PID, 0);

        tint(mStatusDot, getColor(alive ? R.color.md_success : R.color.md_error));
        if (alive) {
            mStatusTitle.setText("\u5df2\u8fde\u63a5");
            mStatusDetail.setText("X \u8fdb\u7a0b\u5df2\u56de\u62a5\uff08pid " + pid
                    + "\uff09\u3002\u6a21\u5757\u6b63\u5728\u62e6\u622a\u65f6\u95f4\u7ebf\u6570\u636e\u3002");
        } else {
            mStatusTitle.setText("\u672a\u8fde\u63a5");
            mStatusDetail.setText("\u8fd8\u6ca1\u6709\u6536\u5230 X \u8fdb\u7a0b\u7684\u56de\u62a5\u3002\u8bf7\u786e\u8ba4\u5df2\u5728 LSPosed \u4e2d\u542f\u7528\u672c\u6a21\u5757\u5e76\u52fe\u9009 X\uff0c"
                    + "\u7136\u540e\u5f3a\u884c\u505c\u6b62 X \u518d\u91cd\u65b0\u6253\u5f00\u3002");
        }

        mTestSwitch.setChecked(p.getBoolean(KeywordReceiver.KEY_TEST_MODE, false));
        mAiSwitch.setChecked(p.getBoolean(KeywordReceiver.KEY_AI_ENABLED, true));
        mAiStrictSwitch.setChecked(p.getBoolean(KeywordReceiver.KEY_AI_STRICT, false));

        String list = p.getString(KeywordReceiver.KEY_LIST, "");
        String[] words = (list == null || list.isEmpty()) ? new String[0] : list.split("\n");
        mKeywordCount.setText(words.length + " \u4e2a");
        renderChips(words);
    }

    private void renderChips(String[] words) {
        mChipFlow.removeAllViews();
        mChipEmpty.setVisibility(words.length == 0 ? View.VISIBLE : View.GONE);
        if (words.length == 0) return;

        float d = getResources().getDisplayMetrics().density;
        int available = getResources().getDisplayMetrics().widthPixels
                - (int) ((20 + 14) * 2 * d)
                - (int) (4 * d);

        LinearLayout row = null;
        int used = 0;
        int rowCount = 0;
        for (String word : words) {
            TextView chip = buildChip(word.trim());
            if (chip == null) continue;
            chip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int width = chip.getMeasuredWidth() + (int) (8 * d);
            if (row == null || used + width > available) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (rowCount > 0) rp.topMargin = (int) (6 * d);
                row.setLayoutParams(rp);
                mChipFlow.addView(row);
                rowCount++;
                used = 0;
            }
            row.addView(chip);
            used += width;
        }
    }

    private TextView buildChip(String word) {
        if (word.isEmpty()) return null;
        float d = getResources().getDisplayMetrics().density;
        TextView chip = new TextView(this);
        chip.setText(word);
        chip.setTextSize(13f);
        chip.setTextColor(getColor(R.color.md_on_secondary_container));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (8 * d);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void tint(View view, int color) {
        Drawable bg = view.getBackground();
        if (bg == null) return;
        bg = bg.mutate();
        bg.setTint(color);
        view.setBackground(bg);
    }
}
