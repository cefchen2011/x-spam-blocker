package com.dsh.xspamblock;

import android.content.Intent;
import android.net.Uri;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Catches the tap on the injected "屏蔽" control.
 *
 * The control is a url entity, so X opens it exactly like any other external link
 * (a Custom Tab via an ACTION_VIEW intent). Every activity start in the X process
 * passes through one of the methods hooked here; when the URI is our marker we run
 * the mute and swallow the intent, so no browser appears.
 */
final class LinkHook {

    static final String SCHEME = "https";
    static final String HOST = "xspamblock.example.com";
    private static final String PATH = "/mute";

    /**
     * Hosts whose taps belong to us.
     *
     * The control normally opens the loopback service, which records the block itself;
     * swallowing that intent here just makes the tap feel instant (no browser flash). The
     * marker host is the fallback for when the service is not up yet.
     */
    private static boolean isOurs(String host) {
        if (host == null) return false;
        return HOST.equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private LinkHook() {}

    /** The URL the injected control opens; also the key the handler matches on. */
    static String markerUrl(String handle) {
        return SCHEME + "://" + HOST + PATH + "?h=" + handle;
    }

    static void install(ClassLoader cl) {
        int hooked = 0;
        hooked += hookIntentMethods("android.app.Activity", cl,
                "startActivity", "startActivityForResult", "startActivityIfNeeded");
        hooked += hookIntentMethods("android.app.ContextImpl", cl,
                "startActivity", "startActivityAsUser");
        hooked += hookIntentMethods("android.content.ContextWrapper", cl, "startActivity");
        XposedBridge.log(ModuleMain.TAG + ": link hook installed on " + hooked + " method(s)");
    }

    private static int hookIntentMethods(String className, ClassLoader cl, String... names) {
        Class<?> cls = XposedHelpers.findClassIfExists(className, cl);
        if (cls == null) return 0;
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            if (!matches(m.getName(), names)) continue;
            if (!hasIntentParam(m)) continue;
            try {
                XposedBridge.hookMethod(m, HANDLER);
                count++;
            } catch (Throwable t) {
                XposedBridge.log(ModuleMain.TAG + ": cannot hook " + className + "#" + m.getName() + ": " + t);
            }
        }
        return count;
    }

    private static boolean matches(String name, String[] names) {
        for (String n : names) {
            if (n.equals(name)) return true;
        }
        return false;
    }

    private static boolean hasIntentParam(Method m) {
        for (Class<?> p : m.getParameterTypes()) {
            if (Intent.class.isAssignableFrom(p)) return true;
        }
        return false;
    }

    private static final XC_MethodHook HANDLER = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Intent intent = null;
                for (Object arg : param.args) {
                    if (arg instanceof Intent) {
                        intent = (Intent) arg;
                        break;
                    }
                }
                if (intent == null) return;
                Uri data = intent.getData();
                if (data == null) return;
                if (!isOurs(data.getHost())) return;

                String handle = data.getQueryParameter("h");
                XposedBridge.log(ModuleMain.TAG + ": 屏蔽 tapped -> @" + handle);
                param.setResult(returnValueFor(param));
                MuteAction.perform(handle);
            } catch (Throwable t) {
                XposedBridge.log(ModuleMain.TAG + ": link handler error: " + t);
            }
        }

        private Object returnValueFor(XC_MethodHook.MethodHookParam param) {
            Class<?> ret = param.method instanceof Method ? ((Method) param.method).getReturnType() : null;
            return ret == int.class ? Integer.valueOf(0) : null;
        }
    };
}
