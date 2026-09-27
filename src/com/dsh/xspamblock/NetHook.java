package com.dsh.xspamblock;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Intercepts the finished OkHttp response for X's GraphQL calls.
 *
 * OkHttp keeps its real class names inside the X APK (the library ships consumer
 * ProGuard rules), so this entry point stays stable across X builds even though the
 * rest of the app is fully obfuscated.
 *
 * GraphQL responses are read in full and the Response is rebuilt from the rewritten
 * text. Non-GraphQL traffic is never touched, so images and video streams keep their
 * original streaming behaviour.
 */
public final class NetHook {

    private static final int MAX_BODY_CHARS = 16 * 1024 * 1024;
    private static final int MAX_URL_LOG = 0;
    private static final int STATS_EVERY = 60;

    private static volatile boolean sInstalled;
    private static Context sContext;
    private static OkHttpBridge sBridge;
    private static final AtomicInteger sGraphQl = new AtomicInteger();
    private static final AtomicInteger sDropped = new AtomicInteger();
    private static final AtomicInteger sFlagged = new AtomicInteger();
    private static final Map<String, AtomicInteger> sOps = new ConcurrentHashMap<String, AtomicInteger>();
    private static final Set<String> sLoggedUrls = Collections.synchronizedSet(new LinkedHashSet<String>());

    /** An authenticated request captured from X's own traffic, plus the client that owns it. */
    private static volatile Object sClient;
    private static volatile Object sTemplateRequest;
    private static volatile boolean sTemplateIsLegacy;

    private NetHook() {}

    static void install(Context ctx, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        sContext = ctx;

        try {
            sBridge = new OkHttpBridge(cl);
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": okhttp bridge unavailable: " + t);
            return;
        }

        Method target = null;
        try {
            Class<?> realCall = XposedHelpers.findClass("okhttp3.internal.connection.RealCall", cl);
            target = realCall.getDeclaredMethod("getResponseWithInterceptorChain$okhttp");
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": RealCall target missing: " + t);
        }
        if (target == null) return;

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    onResponse(param);
                } catch (Throwable t) {
                    XposedBridge.log(ModuleMain.TAG + ": onResponse error: " + t);
                }
            }
        });
        XposedBridge.log(ModuleMain.TAG + ": hook installed on RealCall#getResponseWithInterceptorChain");
    }

    private static void onResponse(XC_MethodHook.MethodHookParam param) {
        Object resp = param.getResult();
        if (resp == null) return;

        Object req = Reflect.call(resp, "request");
        Object urlObj = req == null ? null : Reflect.call(req, "url");
        if (urlObj == null) return;
        String url = String.valueOf(urlObj);

        captureTemplate(param, req, url);

        if (url.indexOf("/graphql/") < 0) return;

        final int gql = sGraphQl.incrementAndGet();
        String op = operationOf(url);

        Object body = Reflect.call(resp, "body");
        if (body == null) return;
        Object contentType = sBridge.contentType(body);
        String text = sBridge.readBody(body);
        if (text == null) {
            XposedBridge.log(ModuleMain.TAG + ": body read failed for " + op + " (" + Reflect.lastError + ")");
            return;
        }
        if (text.length() > MAX_BODY_CHARS) {
            Object same = rebuild(resp, contentType, text, op);
            if (same != null) param.setResult(same);
            return;
        }

        reportOnce(op, text);

        String out = text;
        try {
            out = Transformer.transform(url, op, text);
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": transform failed for " + op + ": " + t);
            out = text;
        }

        Object rebuilt = rebuild(resp, contentType, out, op);
        if (rebuilt == null) {
            XposedBridge.log(ModuleMain.TAG + ": REBUILD FAILED for " + op + " (" + Reflect.lastError + ")");
            return;
        }
        param.setResult(rebuilt);

        if (gql % STATS_EVERY == 0) {
            XposedBridge.log(ModuleMain.TAG + ": stats gql=" + gql + " flagged=" + sFlagged.get()
                    + " dropped=" + sDropped.get() + " mutedKeywords=" + MuteStore.size());
        }
    }

    /**
     * Keeps an authenticated POST around so MuteAction can replay it. A legacy /1.1/
     * request is preferred: its headers match the muted-keywords endpoint far better than
     * a GraphQL call's do.
     */
    private static void captureTemplate(XC_MethodHook.MethodHookParam param, Object req, String url) {
        if (url == null || !url.contains("api.x.com")) return;
        Object method = Reflect.call(req, "method");
        if (!"POST".equals(method)) return;
        Object auth = Reflect.call(req, "header", new Class<?>[]{String.class}, "authorization");
        if (auth == null) return;

        boolean legacy = url.contains("/1.1/") && url.indexOf("/graphql/") < 0;
        if (sTemplateRequest != null && !(legacy && !sTemplateIsLegacy)) return;

        Object client = null;
        try {
            java.lang.reflect.Field f = param.thisObject.getClass().getDeclaredField("client");
            f.setAccessible(true);
            client = f.get(param.thisObject);
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": cannot read RealCall.client: " + t);
        }
        if (client == null) return;

        sClient = client;
        sTemplateRequest = req;
        sTemplateIsLegacy = legacy;
        XposedBridge.log(ModuleMain.TAG + ": captured authenticated request template (legacy=" + legacy + ") " + url);
    }

    /** Handle used by MuteAction to replay requests with X's own client. */
    static final class RequestTemplate {
        private final Object client = sClient;
        private final Object request = sTemplateRequest;

        int postForm(String url, String formBody) throws Exception {
            return sBridge.postForm(client, request, url, formBody);
        }

        int postJson(String url, String jsonBody) throws Exception {
            return sBridge.postJson(client, request, url, jsonBody);
        }
    }

    static RequestTemplate template() {
        if (sClient == null || sTemplateRequest == null || sBridge == null) return null;
        return new RequestTemplate();
    }

    private static Object rebuild(Object resp, Object contentType, String text, String op) {
        try {
            Object newBody = sBridge.createBody(contentType, text);
            if (newBody == null) {
                XposedBridge.log(ModuleMain.TAG + ": createBody null for " + op + " (" + Reflect.lastError + ")");
                return null;
            }
            return sBridge.rebuild(resp, newBody);
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": rebuild exception for " + op + ": " + t);
            return null;
        }
    }

    /** Writes a body to the app's external files dir; used while diagnosing rendering. */
    static void dumpDebug(String name, String body) {
        try {
            if (sContext == null) return;
            File dir = sContext.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "xsb_" + name + ".json");
            Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            try {
                w.write(body);
            } finally {
                w.close();
            }
            XposedBridge.log(ModuleMain.TAG + ": dumped " + f.getAbsolutePath());
        } catch (Throwable t) {
            XposedBridge.log(ModuleMain.TAG + ": dump failed: " + t);
        }
    }

    static void countFlagged() {
        sFlagged.incrementAndGet();
    }

    static void countDropped(int n) {
        sDropped.addAndGet(n);
    }

    private static void reportOnce(String op, String text) {
        AtomicInteger n = sOps.get(op);
        if (n == null) {
            n = new AtomicInteger();
            sOps.put(op, n);
        }
        if (n.incrementAndGet() != 1) return;
        if (MAX_URL_LOG == 0) return;
        XposedBridge.log(ModuleMain.TAG + ": OP " + op + " len=" + text.length());
    }

    private static String operationOf(String url) {
        int i = url.indexOf("/graphql/");
        if (i < 0) return "?";
        String rest = url.substring(i + "/graphql/".length());
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        String[] parts = rest.split("/");
        return parts.length >= 2 ? parts[1] : parts[0];
    }
}
