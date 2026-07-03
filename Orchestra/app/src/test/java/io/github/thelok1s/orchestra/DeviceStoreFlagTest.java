package io.github.thelok1s.orchestra;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Light JVM test for the act_as_apple toggle (Task 4B): {@link DeviceStore#flag} must default to
 * false and round-trip through {@link DeviceStore#setFlag}.
 *
 * DeviceStore reads/writes through {@link App#context()}.getSharedPreferences(...); there is no
 * Robolectric/Android runtime wired into this project's unit-test source set (see the rest of
 * app/src/test — no test touches Context), so this test sets {@link App}'s private static Context
 * field (via reflection, restored in {@link #tearDown}) to a minimal in-memory
 * SharedPreferences-backed fake for its own duration only.
 */
public class DeviceStoreFlagTest {

    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> map = new HashMap<>();

        @Override public Map<String, ?> getAll() { return map; }
        @Override public String getString(String key, String def) {
            return map.containsKey(key) ? (String) map.get(key) : def;
        }
        @Override public Set<String> getStringSet(String key, Set<String> def) {
            //noinspection unchecked
            return map.containsKey(key) ? (Set<String>) map.get(key) : def;
        }
        @Override public int getInt(String key, int def) {
            return map.containsKey(key) ? (Integer) map.get(key) : def;
        }
        @Override public long getLong(String key, long def) {
            return map.containsKey(key) ? (Long) map.get(key) : def;
        }
        @Override public float getFloat(String key, float def) {
            return map.containsKey(key) ? (Float) map.get(key) : def;
        }
        @Override public boolean getBoolean(String key, boolean def) {
            return map.containsKey(key) ? (Boolean) map.get(key) : def;
        }
        @Override public boolean contains(String key) { return map.containsKey(key); }
        @Override public Editor edit() { return new FakeEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        final class FakeEditor implements Editor {
            final Map<String, Object> pending = new HashMap<>(map);
            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> values) { pending.put(key, values); return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { pending.remove(key); return this; }
            @Override public Editor clear() { pending.clear(); return this; }
            @Override public boolean commit() { map.clear(); map.putAll(pending); return true; }
            @Override public void apply() { commit(); }
        }
    }

    private static final class FakeContext extends ContextWrapper {
        final SharedPreferences prefs = new FakePrefs();
        FakeContext() { super(null); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    }

    private Context prevContext;

    @Before
    public void setUp() throws Exception {
        Field f = App.class.getDeclaredField("appContext");
        f.setAccessible(true);
        prevContext = (Context) f.get(null);
        f.set(null, new FakeContext());
    }

    @After
    public void tearDown() throws Exception {
        Field f = App.class.getDeclaredField("appContext");
        f.setAccessible(true);
        f.set(null, prevContext);
    }

    @Test
    public void actAsApple_defaultsFalse() {
        assertFalse(DeviceStore.flag("act_as_apple", false));
    }

    @Test
    public void actAsApple_roundTripsThroughSetFlag() {
        DeviceStore.setFlag("act_as_apple", true);
        assertTrue(DeviceStore.flag("act_as_apple", false));

        DeviceStore.setFlag("act_as_apple", false);
        assertFalse(DeviceStore.flag("act_as_apple", false));
    }
}
