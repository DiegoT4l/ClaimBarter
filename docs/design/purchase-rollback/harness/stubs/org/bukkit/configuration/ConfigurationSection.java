package org.bukkit.configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Map-backed stand-in for org.bukkit.configuration.ConfigurationSection.
 *
 * <p>getString(path, def) reproduces verified MemorySection behaviour: it
 * returns the default both when the key is absent from the map AND when the
 * stored value is null (a YAML {@code key:} with nothing after it). Real
 * MemorySection.get(path, def) does NOT make that distinction itself - a
 * present-but-null SectionPathData is returned as null, not as def - so the
 * null-collapsing has to happen in getString itself, exactly as javap of
 * paper-api 26.2's MemorySection.getString(String,String) shows: the result of
 * get(path, def) is null-checked and only then does def come back.
 */
public class ConfigurationSection
{
    protected final Map<String, Object> map = new LinkedHashMap<>();

    public void set(String path, Object value)
    {
        map.put(path, value);
    }

    public Set<String> getKeys(boolean deep)
    {
        return map.keySet();
    }

    public String getString(String path, String def)
    {
        Object value = map.get(path);
        return value == null ? def : String.valueOf(value);
    }

    public int getInt(String path, int def)
    {
        Object value = map.get(path);
        return value instanceof Number number ? number.intValue() : def;
    }

    public double getDouble(String path, double def)
    {
        Object value = map.get(path);
        return value instanceof Number number ? number.doubleValue() : def;
    }

    public boolean getBoolean(String path, boolean def)
    {
        Object value = map.get(path);
        return value instanceof Boolean bool ? bool : def;
    }

    public ConfigurationSection getConfigurationSection(String path)
    {
        Object value = map.get(path);
        return value instanceof ConfigurationSection section ? section : null;
    }
}
