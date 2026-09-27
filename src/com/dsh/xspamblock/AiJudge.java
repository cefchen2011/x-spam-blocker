package com.dsh.xspamblock;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;

/**
 * Asks deepseek-flash whether a reply is sexual-solicitation spam.
 *
 * The local regex is what makes the button appear instantly; the model is the referee.
 * Verdicts are cached by text hash and persisted, so a post is classified once and the
 * answer is reused on every later render. Calls run on a small background pool and never
 * block the network thread that is producing the timeline.
 */
final class AiJudge {

    static final String ENDPOINT_DEFAULT = "https://api.deepseek.com";
    static final String MODEL_DEFAULT = "deepseek-flash";

    /**
     * No key is shipped. Until one is entered on the settings screen the model stays
     * switched off and the module runs on the local rule set alone.
     */
    static final String DEFAULT_API_KEY = "";
    private static final long CONNECT_TIMEOUT_MS = 6000L;
    private static final long READ_TIMEOUT_MS = 25000L;
    private static final int MAX_CANDIDATES_PER_RESPONSE = 6;

    private static final String SYSTEM_PROMPT =
            "\u4f60\u662f\u793e\u4ea4\u5e73\u53f0\u7684\u5185\u5bb9\u5b89\u5168\u5224\u5b9a\u5668\u3002"
            + "\u5224\u65ad\u7ed9\u51fa\u7684\u63a8\u6587\u56de\u590d\u662f\u5426\u5c5e\u4e8e\u201c\u6027\u6697\u793a\u5783\u573e\u5f15\u6d41\u201d\uff1a"
            + "\u7279\u5f81\u662f\u5f00\u5934 @\u67d0\u4eba \u52a0\u9017\u53f7\uff0c\u540e\u9762\u8ddf\u62db\u5ad6\u3001\u7ea6\u70ae\u3001\u5356\u8d44\u6e90\u3001\u966a\u804a\u7b49\u6027\u6697\u793a\u63fd\u5ba2\u5185\u5bb9\u3002"
            + "\u666e\u901a\u4ea4\u6d41\u3001\u8c03\u4f83\u3001\u670b\u53cb\u95f4\u6253\u8da3\u4e00\u5f8b\u4e0d\u7b97\u3002"
            + "\u53ea\u8f93\u51fa JSON\uff0c\u4e0d\u8981\u591a\u4f59\u6587\u5b57\uff0c\u683c\u5f0f\uff1a"
            + "{\"spam\": true \u6216 false, \"handle\": \"\u88ab@\u7684\u7528\u6237\u540d\uff08\u4e0d\u542b@\uff09\uff0c\u6ca1\u6709\u5219\u7a7a\u5b57\u7b26\u4e32\", "
            + "\"confidence\": 0.0 \u5230 1.0, \"reason\": \"\u7b80\u77ed\u4e2d\u6587\u7406\u7531\"}";

    /** A verdict: spam or not, plus the handle the model identified. */
    static final class Verdict {
        final boolean spam;
        final String handle;
        final double confidence;

        Verdict(boolean spam, String handle, double confidence) {
            this.spam = spam;
            this.handle = handle;
            this.confidence = confidence;
        }
    }

    private static final Map<String, Verdict> CACHE = new ConcurrentHashMap<String, Verdict>();
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<String, Boolean>();
    private static final AtomicInteger sCalls = new AtomicInteger();
    private static final AtomicInteger sErrors = new AtomicInteger();

    private static volatile boolean sEnabled;
    private static volatile boolean sStrict;
    private static volatile String sApiKey = "";
    private static volatile String sEndpoint = ENDPOINT_DEFAULT;
    private static volatile String sModel = MODEL_DEFAULT;

    private static File sCacheFile;
    private static ThreadPoolExecutor sPool;

    private AiJudge() {}

    static void configure(boolean enabled, boolean strict, String apiKey, String endpoint, String model) {
        sEnabled = enabled;
        sStrict = strict;
        sApiKey = apiKey == null ? "" : apiKey.trim();
        sEndpoint = (endpoint == null || endpoint.trim().isEmpty()) ? ENDPOINT_DEFAULT : endpoint.trim();
        while (sEndpoint.endsWith("/")) sEndpoint = sEndpoint.substring(0, sEndpoint.length() - 1);
        sModel = (model == null || model.trim().isEmpty()) ? MODEL_DEFAULT : model.trim();
    }

    static boolean enabled() {
        return sEnabled && !sApiKey.isEmpty();
    }

    static boolean strict() {
        return sStrict;
    }

    static int callCount() {
        return sCalls.get();
    }

    static int errorCount() {
        return sErrors.get();
    }

    static int cacheSize() {
        return CACHE.size();
    }

    static void init(Context ctx) {
        // Starts switched off; the settings screen pushes the key, endpoint and model over
        // ConfigBridge. Endpoint and model already have sensible public defaults.
        configure(true, false, DEFAULT_API_KEY, ENDPOINT_DEFAULT, MODEL_DEFAULT);
        sCacheFile = new File(ctx.getFilesDir(), "xsb_ai_cache.json");
        sPool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(64),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        load();
        XposedBridge.log(ModuleMain.TAG + ": AI judge ready (enabled=" + enabled()
                + " model=" + sModel + " endpoint=" + sEndpoint + " cached=" + CACHE.size() + ")");
    }

    /**
     * One-off call used by the settings screen's test button. Runs in the app process,
     * which has its own copy of these statics, so it cannot disturb the hooked process.
     */
    static String probe(String endpoint, String key, String model, String text) {
        configure(true, false, key, endpoint, model);
        Verdict v = classify(text, "probe");
        if (v == null) {
            return "\u8c03\u7528\u5931\u8d25\uff08\u8bf7\u68c0\u67e5 Key / \u7f51\u7edc / \u63a5\u53e3\u5730\u5740\uff09";
        }
        return "\u5224\u5b9a\uff1a" + (v.spam ? "\u5783\u573e\u5f15\u6d41" : "\u6b63\u5e38")
                + "\uff0c\u7528\u6237\u540d @" + v.handle
                + "\uff0c\u7f6e\u4fe1\u5ea6 " + String.format("%.2f", v.confidence);
    }

    /** Cached verdict, or null when this text has not been classified yet. */
    static Verdict cached(String text) {
        return CACHE.get(key(text));
    }

    /** Queues a classification when one is not already cached or running. */
    static void requestAsync(final String text, final String fallbackHandle) {
        if (!enabled() || sPool == null) return;
        final String k = key(text);
        if (CACHE.containsKey(k) || IN_FLIGHT.putIfAbsent(k, Boolean.TRUE) != null) return;
        try {
            sPool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Verdict v = classify(text, fallbackHandle);
                        if (v != null) {
                            CACHE.put(k, v);
                            save();
                            if (v.spam) {
                                XposedBridge.log(ModuleMain.TAG + ": AI verdict spam @" + v.handle
                                        + " (" + String.format("%.2f", v.confidence) + ") :: " + shorten(text));
                            }
                        }
                    } finally {
                        IN_FLIGHT.remove(k);
                    }
                }
            });
        } catch (Throwable t) {
            IN_FLIGHT.remove(k);
        }
    }

    /** Synchronous classification with a short budget, for strict mode. */
    static Verdict classifyNow(String text, String fallbackHandle) {
        if (!enabled()) return null;
        String k = key(text);
        Verdict v = CACHE.get(k);
        if (v != null) return v;
        v = classify(text, fallbackHandle);
        if (v != null) {
            CACHE.put(k, v);
            save();
        }
        return v;
    }

    private static Verdict classify(String text, String fallbackHandle) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("model", sModel);
            payload.put("temperature", 0);
            payload.put("max_tokens", 800);
            JSONObject fmt = new JSONObject();
            fmt.put("type", "json_object");
            payload.put("response_format", fmt);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
            messages.put(new JSONObject().put("role", "user").put("content", text));
            payload.put("messages", messages);

            String body = post(sEndpoint + "/chat/completions", payload.toString());
            if (body == null) return null;

            sCalls.incrementAndGet();
            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) return null;
            String content = message.optString("content", "");
            if (content.isEmpty()) return null;

            JSONObject verdict = new JSONObject(content);
            boolean spam = verdict.optBoolean("spam", false);
            String handle = verdict.optString("handle", "");
            double confidence = verdict.optDouble("confidence", 0.0);
            if (handle == null || handle.trim().isEmpty()) handle = fallbackHandle;
            handle = handle.replace("@", "").trim();
            if (spam && (handle == null || handle.isEmpty())) return null;
            return new Verdict(spam, handle, confidence);
        } catch (Throwable t) {
            sErrors.incrementAndGet();
            XposedBridge.log(ModuleMain.TAG + ": AI judge failed: " + t);
            return null;
        }
    }

    private static String post(String url, String payload) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
            conn.setReadTimeout((int) READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + sApiKey);
            byte[] bytes = payload.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            if (code < 200 || code >= 300) {
                sErrors.incrementAndGet();
                String flat = sb.toString().replace('\n', ' ');
                XposedBridge.log(ModuleMain.TAG + ": AI judge HTTP " + code + " "
                        + (flat.length() > 200 ? flat.substring(0, 200) : flat));
                return null;
            }
            return sb.toString();
        } catch (Throwable t) {
            sErrors.incrementAndGet();
            XposedBridge.log(ModuleMain.TAG + ": AI judge transport: " + t);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String key(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static String shorten(String s) {
        String t = s.replace('\n', ' ');
        return t.length() <= 60 ? t : t.substring(0, 60);
    }

    private static synchronized void load() {
        if (sCacheFile == null || !sCacheFile.exists()) return;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(sCacheFile), "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                JSONObject root = new JSONObject(sb.toString());
                JSONArray keys = root.names();
                if (keys == null) return;
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.getString(i);
                    JSONObject v = root.optJSONObject(k);
                    if (v == null) continue;
                    CACHE.put(k, new Verdict(v.optBoolean("s", false), v.optString("h", ""), v.optDouble("c", 0)));
                }
                XposedBridge.log(ModuleMain.TAG + ": AI cache loaded (" + CACHE.size() + " verdicts)");
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": AI cache load failed: " + t);
        }
    }

    private static synchronized void save() {
        if (sCacheFile == null) return;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Verdict> e : CACHE.entrySet()) {
                Verdict v = e.getValue();
                root.put(e.getKey(), new JSONObject()
                        .put("s", v.spam).put("h", v.handle).put("c", v.confidence));
            }
            Writer w = new OutputStreamWriter(new FileOutputStream(sCacheFile), "UTF-8");
            try {
                w.write(root.toString());
            } finally {
                w.close();
            }
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": AI cache save failed: " + t);
        }
    }
}
