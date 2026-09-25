package org.bukkit;

import java.util.Locale;

/**
 * Harness stand-in for org.bukkit.Material.
 *
 * <p>The real type is an enum; this one is deliberately a plain class so the
 * harness can construct arbitrary instances with knobs the enum cannot
 * express - a custom max stack size to exercise deliver()'s chunking at 1, 16
 * and 64, or an isItem/isAir combination BarterSettings must refuse. Reference
 * equality (used by BarterService's currency comparisons) still works because
 * every test reuses one shared instance per material rather than constructing
 * a fresh one per comparison.
 */
public class Material
{
    public static final Material IRON_INGOT = new Material("IRON_INGOT", 64, true, false);
    public static final Material DIAMOND = new Material("DIAMOND", 64, true, false);
    public static final Material SADDLE = new Material("SADDLE", 1, true, false);
    public static final Material AIR = new Material("AIR", 64, true, true);
    public static final Material BEDROCK = new Material("BEDROCK", 64, false, false);

    private final String name;
    private int maxStackSize;
    private boolean isItem;
    private boolean isAir;

    public Material(String name, int maxStackSize, boolean isItem, boolean isAir)
    {
        this.name = name;
        this.maxStackSize = maxStackSize;
        this.isItem = isItem;
        this.isAir = isAir;
    }

    /** Mirrors Material.matchMaterial(String), resolving only the names this harness ships. */
    public static Material matchMaterial(String name)
    {
        if (name == null)
        {
            return null;
        }
        switch (name.toUpperCase(Locale.ROOT))
        {
            case "IRON_INGOT":
                return IRON_INGOT;
            case "DIAMOND":
                return DIAMOND;
            case "SADDLE":
                return SADDLE;
            case "AIR":
                return AIR;
            case "BEDROCK":
                return BEDROCK;
            default:
                return null;
        }
    }

    public String name()
    {
        return name;
    }

    public boolean isAir()
    {
        return isAir;
    }

    public boolean isItem()
    {
        return isItem;
    }

    public int getMaxStackSize()
    {
        return maxStackSize;
    }

    // Knobs, mutated by the harness to exercise chunking and validation paths.
    public void setMaxStackSize(int value)
    {
        this.maxStackSize = value;
    }

    public void setIsItem(boolean value)
    {
        this.isItem = value;
    }

    public void setIsAir(boolean value)
    {
        this.isAir = value;
    }
}
