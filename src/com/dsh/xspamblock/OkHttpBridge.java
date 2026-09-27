package com.dsh.xspamblock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XposedHelpers;

/**
 * Version-tolerant bridge to the okhttp classes X ships (okhttp 4.12.0 in X 12.25).
 *
 * Covers the two things this module needs: rebuilding a response body after reading it,
 * and issuing an authenticated request through the app's own client.
 */
final class OkHttpBridge {

    private final Class<?> responseClass;
    private final Class<?> responseBodyClass;
    private final Class<?> mediaTypeClass;
    private final Class<?> requestClass;
    private final Class<?> requestBodyClass;
    private final Method createBody;
    private final Method createRequestBody;

    OkHttpBridge(ClassLoader cl) throws Exception {
        responseClass = XposedHelpers.findClass("okhttp3.Response", cl);
        responseBodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
        mediaTypeClass = XposedHelpers.findClass("okhttp3.MediaType", cl);
        requestClass = XposedHelpers.findClass("okhttp3.Request", cl);
        requestBodyClass = XposedHelpers.findClass("okhttp3.RequestBody", cl);

        createBody = resolveCreate(responseBodyClass, mediaTypeClass);
        if (createBody == null) throw new NoSuchMethodException("ResponseBody.create(MediaType, String)");
        createRequestBody = resolveCreate(requestBodyClass, mediaTypeClass);
        if (createRequestBody == null) throw new NoSuchMethodException("RequestBody.create(MediaType, String)");

        responseClass.getMethod("newBuilder");
    }

    private static Method resolveCreate(Class<?> bodyClass, Class<?> mediaTypeClass) {
        try {
            Method m = bodyClass.getMethod("create", mediaTypeClass, String.class);
            if (Modifier.isStatic(m.getModifiers())) return m;
        } catch (Throwable ignored) {
        }
        try {
            Field companionField = bodyClass.getField("Companion");
            Object companion = companionField.get(null);
            if (companion != null) {
                Method m = companion.getClass().getMethod("create", mediaTypeClass, String.class);
                m.setAccessible(true);
                return m;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object invokeCreate(Method create, Class<?> owner, Object mediaType, String text) throws Exception {
        create.setAccessible(true);
        if (Modifier.isStatic(create.getModifiers())) return create.invoke(null, mediaType, text);
        Object companion = owner.getField("Companion").get(null);
        return create.invoke(companion, mediaType, text);
    }

    Object createBody(Object mediaType, String text) throws Exception {
        return invokeCreate(createBody, responseBodyClass, mediaType, text);
    }

    Object contentType(Object body) {
        return Reflect.call(body, "contentType");
    }

    String readBody(Object body) {
        Object s = Reflect.call(body, "string");
        return s instanceof String ? (String) s : null;
    }

    Object rebuild(Object response, Object newBody) {
        Object builder = Reflect.call(response, "newBuilder");
        if (builder == null) return null;
        Object withBody = Reflect.call(builder, "body", new Class<?>[]{responseBodyClass}, newBody);
        if (withBody == null) return null;
        return Reflect.call(withBody, "build");
    }

    Object mediaType(String mime) {
        Object mt = Reflect.callStatic(mediaTypeClass, "get", new Class<?>[]{String.class}, mime);
        if (mt == null) mt = Reflect.callStatic(mediaTypeClass, "parse", new Class<?>[]{String.class}, mime);
        return mt;
    }

    /** Clones the captured request, points it at url, and POSTs a form body through the app's client. */
    int postForm(Object client, Object templateRequest, String url, String formBody) throws Exception {
        return post(client, templateRequest, url, "application/x-www-form-urlencoded", formBody);
    }

    int postJson(Object client, Object templateRequest, String url, String jsonBody) throws Exception {
        return post(client, templateRequest, url, "application/json", jsonBody);
    }

    int post(Object client, Object templateRequest, String url, String mime, String payload) throws Exception {
        Object mediaType = mediaTypeOrPlain(mime);
        Object body = invokeCreate(createRequestBody, requestBodyClass, mediaType, payload);

        Object builder = requestClass.getMethod("newBuilder").invoke(templateRequest);
        Class<?> builderClass = builder.getClass();

        // The captured template is a GraphQL POST whose body is gzipped. Carrying its
        // body-related headers over to a plain-text form body makes Cloudflare answer
        // 400 Bad Request, so drop them and let okhttp recompute them from the new body.
        for (String header : new String[]{"Content-Encoding", "Content-Length", "Content-Type",
                "x-client-transaction-id", "x-twitter-client-transaction-id",
                // Attestation is bound to the request it was minted for. Dropping it lets
                // X's own interceptor re-sign our request on the way out.
                "X-Attest-Signature", "X-Attest-Token", "X-Attest-Signature-Version"}) {
            try {
                builderClass.getMethod("removeHeader", String.class).invoke(builder, header);
            } catch (Throwable ignored) {
            }
        }

        builderClass.getMethod("url", String.class).invoke(builder, url);
        builderClass.getMethod("method", String.class, requestBodyClass).invoke(builder, "POST", body);
        Object request = builderClass.getMethod("build").invoke(builder);


        Object call = client.getClass().getMethod("newCall", requestClass).invoke(client, request);
        Object response = call.getClass().getMethod("execute").invoke(call);
        Object code = response.getClass().getMethod("code").invoke(response);
        try {
            Object respBody = response.getClass().getMethod("body").invoke(response);
            Object text = respBody.getClass().getMethod("string").invoke(respBody);
            if (text instanceof String) {
                String flat = ((String) text).replace('\n', ' ').replace('\r', ' ').trim();
                if (flat.length() > 500) flat = flat.substring(0, 500);
                de.robv.android.xposed.XposedBridge.log(ModuleMain.TAG + ": mute response body: " + flat);
            }
        } catch (Throwable ignored) {
        }
        try {
            response.getClass().getMethod("close").invoke(response);
        } catch (Throwable ignored) {
        }
        return code instanceof Integer ? (Integer) code : -1;
    }

    private static void logHeaders(String label, Object request) {
        try {
            Object headers = request.getClass().getMethod("headers").invoke(request);
            Object names = headers.getClass().getMethod("names").invoke(headers);
            java.util.Collection<?> list = (java.util.Collection<?>) names;
            StringBuilder sb = new StringBuilder(label).append(" method=");
            sb.append(request.getClass().getMethod("method").invoke(request));
            sb.append(" url=").append(request.getClass().getMethod("url").invoke(request));
            for (Object n : list) {
                String name = String.valueOf(n);
                Object value = headers.getClass().getMethod("get", String.class).invoke(headers, name);
                String v = String.valueOf(value);
                if ("authorization".equalsIgnoreCase(name) || "cookie".equalsIgnoreCase(name)) {
                    v = "<" + v.length() + " chars>";
                }
                sb.append(" | ").append(name).append('=').append(v.length() > 80 ? v.substring(0, 80) + "..." : v);
            }
            de.robv.android.xposed.XposedBridge.log(ModuleMain.TAG + ": " + sb);
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log(ModuleMain.TAG + ": header log failed: " + t);
        }
    }

    private Object mediaTypeOrPlain(String mime) {
        Object mt = mediaType(mime);
        if (mt != null) return mt;
        return Reflect.callStatic(mediaTypeClass, "get", new Class<?>[]{String.class}, "text/plain");
    }

    Class<?> requestClass() {
        return requestClass;
    }
}
