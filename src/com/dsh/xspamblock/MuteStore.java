package com.dsh.xspamblock;

import android.content.Context;
import android.content.Intent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import org.json.JSONArray;

/**
 * The module's "帖子屏蔽关键词" list.
 *
 * Keywords are stored without the leading "@" and matched case-insensitively against
 * both the post text and the author handle. The list is persisted inside X's own data
 * directory and mirrored to the module app by broadcast so the settings screen can show
 * it without needing root or a shared file.
 */
final class MuteStore {

    static final String ACTION = "com.dsh.xspamblock.KEYWORDS";
    static final String MODULE_PACKAGE = "com.dsh.xspamblock";

    private static File sFile;
    private static Context sContext;
    private static final Set<String> KEYWORDS = new LinkedHashSet<String>();

    private MuteStore() {}

    static synchronized void init(Context ctx) {
        sContext = ctx;
        sFile = new File(ctx.getFilesDir(), "xsb_muted_keywords.json");
        KEYWORDS.clear();
        if (sFile.exists()) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(sFile), "UTF-8"));
                try {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    JSONArray arr = new JSONArray(sb.toString());
                    for (int i = 0; i < arr.length(); i++) {
                        String k = normalize(arr.optString(i, ""));
                        if (!k.isEmpty()) KEYWORDS.add(k);
                    }
                } finally {
                    r.close();
                }
                XposedBridge.log(ModuleMain.TAG + ": loaded " + KEYWORDS.size() + " blocked keyword(s)");
            } catch (Throwable t) {
                XposedBridge.log(ModuleMain.TAG + ": keyword load failed: " + t);
            }
        }
        publish();
    }

    static synchronized boolean add(String rawKeyword) {
        String k = normalize(rawKeyword);
        if (k.isEmpty()) return false;
        boolean added = KEYWORDS.add(k);
        if (added) {
            save();
            publish();
            XposedBridge.log(ModuleMain.TAG + ": blocked keyword added -> @" + k
                    + " (total " + KEYWORDS.size() + ")");
        }
        return added;
    }

    /** Removes one keyword. Returns true when it was actually there. */
    static synchronized boolean remove(String rawKeyword) {
        String k = normalize(rawKeyword);
        if (k.isEmpty()) return false;
        boolean removed = KEYWORDS.remove(k);
        if (removed) {
            save();
            publish();
            XposedBridge.log(ModuleMain.TAG + ": blocked keyword removed -> @" + k
                    + " (total " + KEYWORDS.size() + ")");
        }
        return removed;
    }

    static synchronized void clear() {
        int before = KEYWORDS.size();
        KEYWORDS.clear();
        save();
        publish();
        XposedBridge.log(ModuleMain.TAG + ": blocked keywords cleared (" + before + " removed)");
    }

    static synchronized int size() {
        return KEYWORDS.size();
    }

    /** True when the keyword appears in the text or matches the author handle. */
    static synchronized boolean isBlocked(String text, String handle) {
        if (KEYWORDS.isEmpty()) return false;
        String t = text == null ? "" : text.toLowerCase();
        String h = handle == null ? "" : handle.toLowerCase();
        for (String k : KEYWORDS) {
            if (t.contains(k) || h.equals(k)) return true;
        }
        return false;
    }

    private static String normalize(String raw) {
        String k = raw == null ? "" : raw.trim().toLowerCase();
        while (k.startsWith("@")) k = k.substring(1);
        return k;
    }

    private static void save() {
        if (sFile == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (String k : KEYWORDS) arr.put(k);
            Writer w = new OutputStreamWriter(new FileOutputStream(sFile), "UTF-8");
            try {
                w.write(arr.toString());
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": keyword save failed: " + t);
        }
    }

    /** Mirrors the list to the module app so its settings screen can display it. */
    private static void publish() {
        if (sContext == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String k : KEYWORDS) {
                if (sb.length() > 0) sb.append('\n');
                sb.append('@').append(k);
            }
            Intent i = new Intent(ACTION).setPackage(MODULE_PACKAGE);
            i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            i.putExtra("keywords", sb.toString());
            i.putExtra("count", KEYWORDS.size());
            sContext.sendBroadcast(i);
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": keyword publish failed: " + t);
        }
    }

    static Set<String> snapshot() {
        synchronized (MuteStore.class) {
            return Collections.unmodifiableSet(new LinkedHashSet<String>(KEYWORDS));
        }
    }
}
