package com.dsh.xspamblock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Rewrites GraphQL timeline responses.
 *
 * Two jobs:
 *  1. drop every post that matches a blocked keyword, so the effect of tapping 屏蔽 is
 *     immediate even before X's own server list catches up;
 *  2. append a tappable "屏蔽" control to posts that look like the target spam.
 *
 * The control is appended as a t.co-shaped URL plus a url entity that maps it to the
 * module's marker URL with "屏蔽" as the display label. That exact shape is what X
 * 12.25.0-prod.01 renders as a live link; see docs/verification.md for the experiments
 * that established it.
 */
final class Transformer {

    private Transformer() {}

    static String transform(String url, String operation, String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return body;
            JSONObject timelineResponse = data.optJSONObject("timeline_response");
            if (timelineResponse == null) return body;
            JSONObject timeline = timelineResponse.optJSONObject("timeline");
            if (timeline == null) return body;
            JSONArray instructions = timeline.optJSONArray("instructions");
            if (instructions == null) return body;

            int flagged = 0;
            int dropped = 0;
            int testLeft = ConfigBridge.testMode() ? 3 : 0;

            for (int i = 0; i < instructions.length(); i++) {
                JSONObject instruction = instructions.optJSONObject(i);
                JSONArray entries = instruction == null ? null : instruction.optJSONArray("entries");
                if (entries == null) continue;

                List<Integer> remove = new ArrayList<Integer>();
                for (int j = 0; j < entries.length(); j++) {
                    JSONObject result = findTweetResult(entries.optJSONObject(j), 0);
                    if (result == null) continue;
                    JSONObject details = result.optJSONObject("details");
                    if (details == null) continue;
                    String text = details.optString("full_text", "");
                    String handle = authorOf(result);

                    if (MuteStore.isBlocked(text, handle)) {
                        remove.add(Integer.valueOf(j));
                        continue;
                    }
                    String blockHandle = decide(text);
                    if (blockHandle != null) {
                        addButton(result, details, text, blockHandle);
                        flagged++;
                    } else if (testLeft > 0 && handle != null && !handle.isEmpty()) {
                        addButton(result, details, text, handle);
                        testLeft--;
                        flagged++;
                    }
                }

                for (int k = remove.size() - 1; k >= 0; k--) {
                    entries.remove(((Integer) remove.get(k)).intValue());
                    dropped++;
                }
            }

            if (flagged > 0) {
                XposedBridge.log(ModuleMain.TAG + ": flagged " + flagged + " spam post(s) with a 屏蔽 control");
            }
            if (dropped > 0) {
                XposedBridge.log(ModuleMain.TAG + ": hid " + dropped + " post(s) from blocked authors");
            }
            if (flagged == 0 && dropped == 0) return body;
            return root.toString();
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": Transformer error: " + t);
            return body;
        }
    }

    /**
     * Decides whether this reply deserves a 屏蔽 control, and for which handle.
     *
     * With the model switched off this is the local regex plus keyword list. With it on,
     * the regex only picks candidates and the model has the final say. A given text is
     * sent to the model at most once because verdicts are cached by text hash.
     */
    private static String decide(String text) {
        String candidate = SpamDetector.candidateHandle(text);
        if (candidate == null) return null;
        if (!AiJudge.enabled()) return SpamDetector.detect(text);

        AiJudge.Verdict cached = AiJudge.cached(text);
        if (cached != null) {
            if (!cached.spam) return null;
            return (cached.handle == null || cached.handle.isEmpty()) ? candidate : cached.handle;
        }

        // Unknown text: queue a classification. The local detector still decides this
        // render, unless strict mode is on - then the button waits for the cached verdict
        // and shows up on the next render of the same post.
        AiJudge.requestAsync(text, candidate);
        if (AiJudge.strict()) return null;
        return SpamDetector.detect(text);
    }

    /**
     * Appends the 屏蔽 control to the post text.
     *
     * X hides a trailing media/link URL behind a short display_text_range. Leaving that
     * URL in full_text makes X ignore our entity, so the hidden tail is dropped first.
     */
    private static void addButton(JSONObject result, JSONObject details, String original, String handle) {
        JSONArray origRange = details.optJSONArray("display_text_range");
        String visible = original;
        if (origRange != null) {
            int e = origRange.optInt(1, original.length());
            if (e > 0 && e < original.length()) visible = original.substring(0, e);
        }

        // Prefer the loopback service's real URL: X resolves and opens it without help.
        // If the service is not up yet, fall back to a marker URL that LinkHook swallows.
        String shortUrl = LocalServer.urlFor(handle);
        if (shortUrl == null) shortUrl = LinkHook.markerUrl(handle);
        String updated = visible + "  " + shortUrl;
        int start = visible.length() + 2;
        int end = updated.length();

        details.remove("full_text");
        try {
            details.put("full_text", updated);
        } catch (Throwable ignored) {
        }
        if (origRange != null) {
            try {
                origRange.put(0, 0);
                origRange.put(1, end);
            } catch (Throwable ignored) {
            }
        }

        JSONArray entities = result.optJSONArray("url_entities");
        if (entities == null) {
            entities = new JSONArray();
            try {
                result.put("url_entities", entities);
            } catch (Throwable ignored) {
            }
        }
        try {
            JSONObject e = new JSONObject();
            e.put("url", shortUrl);
            e.put("expanded_url", shortUrl);
            e.put("display_url", "\u5c4f\u853d");
            JSONArray idx = new JSONArray();
            idx.put(start);
            idx.put(end);
            e.put("indices", idx);
            entities.put(e);
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": button entity failed: " + t);
        }
    }

    private static String authorOf(JSONObject result) {
        JSONObject core = result.optJSONObject("core");
        JSONObject userResults = core == null ? null : core.optJSONObject("user_results");
        JSONObject user = userResults == null ? null : userResults.optJSONObject("result");
        JSONObject userCore = user == null ? null : user.optJSONObject("core");
        return userCore == null ? null : userCore.optString("screen_name", null);
    }

    private static JSONObject findTweetResult(JSONObject node, int depth) {
        if (node == null || depth > 6) return null;
        JSONObject direct = node.optJSONObject("tweet_results");
        if (direct != null) {
            JSONObject result = direct.optJSONObject("result");
            if (result != null && result.optJSONObject("details") != null) return result;
        }
        JSONObject content = node.optJSONObject("content");
        if (content != null && content != node) {
            JSONObject found = findTweetResult(content, depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
