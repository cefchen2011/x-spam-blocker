package de.robv.android.xposed;

import java.lang.reflect.Member;

/** Compile-time stub. */
public abstract class XC_MethodHook {
    public XC_MethodHook() {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable getThrowable() { return null; }
        public boolean hasThrowable() { return false; }
        public void setThrowable(Throwable t) {}
        public Object getObjectExtra(String key) { return null; }
        public void setObjectExtra(String key, Object o) {}
    }

    public static class Unhook {
        public void unhook() {}
    }
}
