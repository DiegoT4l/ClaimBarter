package org.bukkit.inventory;

import harness.support.CallLog;
import org.bukkit.Material;

/**
 * Harness stand-in for org.bukkit.inventory.ItemStack.
 *
 * <p>throwOnConstructAfterN and throwOnSetAmountAfterN are static and counted
 * across the whole trade (not per instance), because that is what a genuine
 * heap-exhaustion failure looks like: the Nth allocation anywhere fails, not
 * the Nth call on one particular object. The harness resets both counters
 * with resetKnobs() before every injection run.
 */
public class ItemStack
{
    public static int newInstanceCount = 0;
    public static int throwOnConstructAfterN = -1;
    public static int throwOnSetAmountAfterN = -1;
    private static int setAmountCalls = 0;

    public static void resetKnobs()
    {
        newInstanceCount = 0;
        throwOnConstructAfterN = -1;
        throwOnSetAmountAfterN = -1;
        setAmountCalls = 0;
    }

    private final Material type;
    private int amount;
    private boolean hasItemMeta;

    public ItemStack(Material type, int amount)
    {
        this(type, amount, true);
    }

    /**
     * Harness-internal: used by stub plumbing that models what CraftBukkit
     * itself allocates (a defensive copy under copy semantics, addItem's own
     * leftover object, a synthetic dropped entity's stack) rather than an
     * allocation the PLUGIN's own code performs. Still counted against
     * throwOnConstructAfterN - a JVM does not care who asked - but not written
     * to CallLog, so invariant #6 (NO ALLOCATION-BEARING CALL BETWEEN THE TAKE
     * AND THE GRANT, which is about calls the plugin's OWN code makes) is not
     * tripped by Bukkit's own internal bookkeeping, such as the recount
     * getStorageContents() call B9 itself requires.
     */
    public ItemStack(Material type, int amount, boolean logAsPluginCall)
    {
        newInstanceCount++;
        if (throwOnConstructAfterN >= 0 && newInstanceCount > throwOnConstructAfterN)
        {
            throw new OutOfMemoryError("harness: ItemStack construction #" + newInstanceCount
                    + " exceeds throwOnConstructAfterN=" + throwOnConstructAfterN);
        }
        this.type = type;
        this.amount = amount;
        if (logAsPluginCall)
        {
            CallLog.record("ItemStack.new(" + (type == null ? "null" : type.name()) + "," + amount + ")");
        }
    }

    public Material getType()
    {
        return type;
    }

    public int getAmount()
    {
        return amount;
    }

    public void setAmount(int amount)
    {
        setAmountCalls++;
        if (throwOnSetAmountAfterN >= 0 && setAmountCalls > throwOnSetAmountAfterN)
        {
            throw new OutOfMemoryError("harness: ItemStack.setAmount call #" + setAmountCalls
                    + " exceeds throwOnSetAmountAfterN=" + throwOnSetAmountAfterN);
        }
        this.amount = amount;
    }

    public boolean hasItemMeta()
    {
        return hasItemMeta;
    }

    public void setHasItemMeta(boolean hasItemMeta)
    {
        this.hasItemMeta = hasItemMeta;
    }

    public ItemStack clone()
    {
        ItemStack copy = new ItemStack(type, amount, false);
        copy.hasItemMeta = hasItemMeta;
        return copy;
    }
}
