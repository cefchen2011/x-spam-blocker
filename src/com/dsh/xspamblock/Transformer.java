package com.dsh.xspamblock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * Rewrites GraphQL timeline responses.
 *
 * Two jobs:
 *  1. drop every post whose text or author matches a blocked keyword;
 *  2. give the posts that look like sexual-solicitation spam a tappable "屏蔽" control.
 *
 * X returns timelines in several different shapes depending on the surface -
 * home_timeline_urt for the home feed, search_by_raw_query for search results, and so on -
 * and the shape changes between app versions. Rather than pattern-match one of them, the
 * whole response is walked looking for "instructions" arrays, which is what every timeline
 * shape has in common.
 *
 * The control is appended as a t.co-shaped URL plus a url entity that maps it to the
 * module's marker URL with "屏蔽" as the display label. That exact shape is what X
 * 12.25.0-prod.01 renders as a live link; see docs/verification.md.
 */
final class Transformer {

    /** Upper bound on model calls started from a single response. */
    private static final int MAX_AI_PER_RESPONSE = 8;

    /**
     * How long a response waits for the model before being handed back to X.
     *
     * Waiting here is what makes a spam button show up on the same render as the post.
     * Cached verdicts cost nothing, so this only bites the first time new posts appear.
     */
    private static final long AI_BATCH_WAIT_MS = 2000L;

    private static final Set<String> sLoggedOps =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private Transformer() {}

    /** One post pulled out of the response, kept so the decision pass can run later. */
    private static final class Row {
        final JSONObject result;
        final JSONObject details;
        final String text;
        final String author;

        Row(JSONObject result, JSONObject details, String text, String author) {
            this.result = result;
            this.details = details;
            this.text = text;
            this.author = author;
        }
    }

    /** Running totals for the response being processed. */
    private static final class Tally {
        int timelines;
        int tweets;
        int flagged;
        int dropped;
    }

    static String transform(String url, String operation, String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject data = root.optJSONObject("data");
            if (data == null) return body;

            Tally tally = new Tally();
            int testLeft = ConfigBridge.testMode() ? 3 : 0;
            walk(data, tally, new int[]{testLeft}, 0);

            if (operation != null && tally.tweets > 0 && sLoggedOps.add(operation)) {
                XposedBridge.log(ModuleMain.TAG + ": " + operation + " timelines=" + tally.timelines
                        + " tweets=" + tally.tweets + " flagged=" + tally.flagged
                        + " dropped=" + tally.dropped);
            }
            if (tally.flagged > 0) {
                XposedBridge.log(ModuleMain.TAG + ": flagged " + tally.flagged + " post(s) with a 屏蔽 control");
            }
            if (tally.dropped > 0) {
                XposedBridge.log(ModuleMain.TAG + ": hid " + tally.dropped + " post(s) from blocked authors");
            }
            if (tally.flagged == 0 && tally.dropped == 0) return body;
            return root.toString();
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": Transformer error: " + t);
            return body;
        }
    }

    /** Finds every "instructions" array anywhere under the response and processes it. */
    private static void walk(Object node, Tally tally, int[] testLeft, int depth) {
        if (node == null || depth > 14) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONArray instructions = obj.optJSONArray("instructions");
            if (instructions != null) processTimeline(instructions, tally, testLeft);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("instructions".equals(key)) continue;
                walk(obj.opt(key), tally, testLeft, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                walk(arr.opt(i), tally, testLeft, depth + 1);
            }
        }
    }

    private static void processTimeline(JSONArray instructions, Tally tally, int[] testLeft) {
        tally.timelines++;
        for (int i = 0; i < instructions.length(); i++) {
            JSONObject instruction = instructions.optJSONObject(i);
            JSONArray entries = instruction == null ? null : instruction.optJSONArray("entries");
            if (entries == null) continue;

            List<Integer> remove = new ArrayList<Integer>();
            List<Row> rows = new ArrayList<Row>();

            for (int j = 0; j < entries.length(); j++) {
                JSONObject result = findTweetResult(entries.optJSONObject(j), 0);
                if (result == null) continue;
                JSONObject details = result.optJSONObject("details");
                if (details == null) continue;
                String text = details.optString("full_text", "");
                String author = authorOf(result);
                tally.tweets++;

                if (MuteStore.isBlocked(text, author)) {
                    remove.add(Integer.valueOf(j));
                    continue;
                }
                rows.add(new Row(result, details, text, author));
            }

            // Ask the model about everything the local rules did not already settle, in one
            // parallel round trip, before deciding what to render.
            warmAi(rows);

            for (int r = 0; r < rows.size(); r++) {
                Row row = rows.get(r);
                String handle = decide(row.text);
                if (handle != null) {
                    addButton(row.result, row.details, row.text, handle);
                    tally.flagged++;
                } else if (testLeft[0] > 0 && row.author != null && !row.author.isEmpty()) {
                    // Verification mode: the control mutes the post's own author, so the
                    // effect (their posts disappearing) can be observed directly.
                    addButton(row.result, row.details, row.text, row.author);
                    testLeft[0]--;
                    tally.flagged++;
                }
            }

            for (int k = remove.size() - 1; k >= 0; k--) {
                entries.remove(((Integer) remove.get(k)).intValue());
                tally.dropped++;
            }
        }
    }

    /** Queues the model calls this batch of rows needs, then waits (bounded) for answers. */
    private static void warmAi(List<Row> rows) {
        if (!AiJudge.enabled()) return;
        List<String> texts = new ArrayList<String>();
        List<String> handles = new ArrayList<String>();
        for (int i = 0; i < rows.size() && texts.size() < MAX_AI_PER_RESPONSE; i++) {
            Row row = rows.get(i);
            if (row.text.indexOf('@') < 0) continue;             // no @, nothing to judge
            if (SpamDetector.detect(row.text) != null) continue; // local rule already decides
            if (AiJudge.cached(row.text) != null) continue;      // already known
            texts.add(row.text);
            handles.add(SpamDetector.anyHandle(row.text));
        }
        if (texts.isEmpty()) return;
        AiJudge.warmBatch(texts, handles, AI_BATCH_WAIT_MS);
    }

    /**
     * The decision the user asked for:
     *
     *   if (contains "@") {
     *       if (matches the local rules)      -> show 屏蔽
     *       else if (the model says spam)     -> show 屏蔽
     *   }
     */
    private static String decide(String text) {
        if (text == null || text.indexOf('@') < 0) return null;

        String byRule = SpamDetector.detect(text);
        if (byRule != null) return byRule;

        if (!AiJudge.enabled()) return null;
        AiJudge.Verdict cached = AiJudge.cached(text);
        if (cached == null || !cached.spam) return null;
        if (cached.handle != null && !cached.handle.isEmpty()) return cached.handle;
        return SpamDetector.anyHandle(text);
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

        // Two things have to be true for X to render the control as a link reading "屏蔽":
        //   - the text slice must be a t.co-shaped link, which is the only shape X maps
        //     through display_url;
        //   - expanded_url must be https. An http target (the loopback service) makes X
        //     drop the entity entirely and fall back to printing the raw URL text.
        // So the target is the https marker, which LinkHook swallows on tap. The loopback
        // service stays up as the fallback path for anything that does reach 127.0.0.1.
        String tco = "https://t.co/XSB" + Math.abs(handle.hashCode() % 100000000);
        String target = LinkHook.markerUrl(handle);
        String updated = visible + "  " + tco;
        // X indexes entity ranges, and display_text_range itself, in Unicode code points.
        // Java strings are UTF-16, where an emoji costs two units, so posts containing an
        // emoji get indices shifted by the number of astral characters - which makes X drop
        // the entity and print the raw URL instead of "屏蔽". Convert before writing.
        int start = updated.codePointCount(0, visible.length() + 2);
        int end = updated.codePointCount(0, updated.length());

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
            e.put("url", tco);
            e.put("expanded_url", target);
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

    /** Finds the TweetResults node inside an entry, whichever nesting the surface uses. */
    private static JSONObject findTweetResult(Object node, int depth) {
        if (node == null || depth > 8) return null;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONObject tweetResults = obj.optJSONObject("tweet_results");
            if (tweetResults != null) {
                JSONObject result = tweetResults.optJSONObject("result");
                if (result != null && result.optJSONObject("details") != null) return result;
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("tweet_results".equals(key)) continue;
                JSONObject found = findTweetResult(obj.opt(key), depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject found = findTweetResult(arr.opt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }
}
