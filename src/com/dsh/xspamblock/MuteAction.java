package com.dsh.xspamblock;

import java.net.URLEncoder;

import de.robv.android.xposed.XposedBridge;

/**
 * Blocks a handle after the 屏蔽 control is tapped.
 *
 * The keyword is always recorded locally first (see MuteStore), which is what makes the
 * author's posts disappear immediately and survives restarts.
 *
 * It then tries to mirror the keyword into X's own server-side muted keywords through
 * /1.1/mutes/keywords/create.json, replayed on the app's own OkHttpClient. On X
 * 12.25.0-prod.01 that replay is rejected by X's edge with "cloudflare 400 Bad Request"
 * because the request must carry device attestation bound to the specific request, so
 * the local list is the effective mechanism. The attempt is kept and logged: if X
 * relaxes the check the server-side list will start filling in on its own.
 */
final class MuteAction {

    private MuteAction() {}

    static void perform(final String handle) {
        if (handle == null || handle.isEmpty()) return;

        MuteStore.add(handle);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                syncToX(handle);
            }
        }, "XSBlock-mute");
        t.setDaemon(true);
        t.start();
    }

    private static void syncToX(String handle) {
        NetHook.RequestTemplate template = NetHook.template();
        if (template == null) {
            XposedBridge.log(ModuleMain.TAG + ": no captured request template yet; keyword kept locally only");
            return;
        }
        String endpoint = "https://api.x.com/1.1/mutes/keywords/create.json";
        try {
            String form = "keyword=" + URLEncoder.encode(handle, "UTF-8");
            int code = template.postForm(endpoint, form);
            XposedBridge.log(ModuleMain.TAG + ": X muted-keyword create @" + handle + " -> HTTP " + code
                    + (code == 200 || code == 201 ? " (synced to X)" : " (kept locally)"));
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": X muted-keyword create failed, kept locally: " + t);
        }
    }
}
