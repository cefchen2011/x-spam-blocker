package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-time stub. */
public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) { return null; }
}
