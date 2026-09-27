package de.robv.android.xposed.callbacks;

/** Compile-time stub. */
public class XC_LoadPackage {
    public static class LoadPackageParam extends XCallbackParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public android.content.pm.ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }

    public static class XCallbackParam {
        public Object[] args;
    }
}
