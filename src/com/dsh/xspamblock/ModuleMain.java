package com.dsh.xspamblock;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed entry point. Scoped to com.twitter.android only. */
public class ModuleMain implements IXposedHookLoadPackage {

    public static final String TAG = "XSBlock";
    public static final String TARGET = "com.twitter.android";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET.equals(lpparam.packageName)) return;
        if (!lpparam.packageName.equals(lpparam.processName)) return;

        XposedBridge.log(TAG + ": module loaded into " + lpparam.processName);

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Context ctx = (Context) param.args[0];
                LocalServer.start();
                MuteStore.init(ctx);
                AiJudge.init(ctx);
                ConfigBridge.install(ctx);
                ConfigBridge.request(ctx);
                try {
                    LinkHook.install(lpparam.classLoader);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": LinkHook install failed");
                    XposedBridge.log(t);
                }
                try {
                    NetHook.install(ctx, lpparam.classLoader);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": NetHook install failed");
                    XposedBridge.log(t);
                }
            }
        });
    }
}
