package com.dsh.xspamblock;

import java.lang.reflect.Method;

/** Small reflection helpers that record the last failure instead of throwing. */
final class Reflect {

    /** Last error seen by call(), for diagnostics. */
    static volatile String lastError;

    private Reflect() {}

    static Object call(Object target, String name) {
        return call(target, name, new Class<?>[0]);
    }

    static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            lastError = name + ": null target";
            return null;
        }
        try {
            Method m = target.getClass().getMethod(name, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            lastError = name + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            return null;
        }
    }

    static Object callStatic(Class<?> cls, String name, Class<?>[] types, Object... args) {
        if (cls == null) {
            lastError = name + ": null class";
            return null;
        }
        try {
            Method m = cls.getMethod(name, types);
            m.setAccessible(true);
            return m.invoke(null, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            lastError = name + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            return null;
        }
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
