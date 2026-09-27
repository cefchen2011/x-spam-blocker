package de.robv.android.xposed;

import java.io.File;
import java.util.Map;
import java.util.Set;

/** Compile-time stub. The real implementation is provided by the framework at runtime. */
public class XSharedPreferences implements android.content.SharedPreferences {
    public XSharedPreferences(String packageName) {}
    public XSharedPreferences(String packageName, String prefFileName) {}
    public XSharedPreferences(File file) {}

    public void reload() {}
    public boolean makeWorldReadable() { return false; }
    public File getFile() { return null; }

    @Override public Map<String, ?> getAll() { return null; }
    @Override public String getString(String key, String defValue) { return defValue; }
    @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
    @Override public int getInt(String key, int defValue) { return defValue; }
    @Override public long getLong(String key, long defValue) { return defValue; }
    @Override public float getFloat(String key, float defValue) { return defValue; }
    @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
    @Override public boolean contains(String key) { return false; }
    @Override public Editor edit() { return null; }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
}
