package org.bukkit.inventory;

import harness.support.CallLog;
import org.bukkit.Material;

import java.util.HashMap;

/**
 * Harness stand-in for org.bukkit.inventory.PlayerInventory, backed by a
 * 36-element live array.
 *
 * <p>mirrorSemantics is the one knob every injection has to run under both
 * values of, because paper-api does not say which way CraftBukkit's own
 * PlayerInventory.getStorageContents() behaves and this repo's own docs admit
 * it (final_spec.json residual_risks: "whether getStorageContents returns
 * copies or write-through mirrors"). When true, getStorageContents() returns
 * the SAME array (and the SAME ItemStack objects) the inventory itself holds,
 * so a caller's in-place edits land immediately and setStorageContents(...) is
 * effectively a self-copy; when false, it returns a fresh array of cloned
 * ItemStacks, so nothing changes until setStorageContents(...) writes it back
 * - which is what makes throwInSetStorageContentsAfterSlotK produce a
 * genuinely partial write only in the copy case, and a fully-applied-before-
 * the-throw mutation in the mirror case. Both are real possibilities the
 * BarterService under test has to survive identically.
 */
public class PlayerInventory
{
    public static final int SIZE = 36;

    private final ItemStack[] live = new ItemStack[SIZE];

    public boolean mirrorSemantics = true;

    /** -1 = unlimited (real 36-slot occupancy); N = hard cap on NEW slots addItem may consume. */
    public int freeSlotOverride = -1;
    private int freeSlotOverrideConsumed = 0;

    /** -1 disabled; otherwise setStorageContents writes only the first K slots, then throws an Error. */
    public int throwInSetStorageContentsAfterSlotK = -1;
    /** When true, arms a one-shot "the very next getStorageContents() call also throws" the instant
     * throwInSetStorageContentsAfterSlotK fires - models the measuring recount itself failing
     * (heap exhaustion), independent of exactly which call number that recount happens to be. */
    public boolean alsoFailNextReadAfterSetStorageContentsThrow = false;
    /** One-shot: arms "the very next getStorageContents() call throws" only when a setStorageContents
     * call COMPLETES without throwing - models the B9 verification recount failing on its own after a
     * removeCurrency that returned normally, independent of that recount's exact call number. */
    public boolean failNextReadAfterSetStorageContents = false;
    private boolean nextGetStorageContentsThrows = false;

    /** -1 disabled; otherwise the Nth (1-based) getStorageContents() call throws an Error. */
    public int throwOnGetStorageContentsCallN = -1;
    private int getStorageContentsCallCount = 0;
    public int setStorageContentsCallCount = 0;

    /** -1 disabled; otherwise addItem throws an Error after placing exactly K items total. */
    public int throwInAddItemAfterPlacingK = -1;
    private int addItemPlacedSoFar = 0;
    public int addItemCallCount = 0;

    public void setSlot(int index, ItemStack stack)
    {
        live[index] = stack;
    }

    public ItemStack rawSlot(int index)
    {
        return live[index];
    }

    /** Lets a knob elsewhere (a failed setStorageContents) arm the next read to fail too. */
    public void armThrowOnNextGetStorageContentsCall()
    {
        nextGetStorageContentsThrows = true;
    }

    public ItemStack[] getStorageContents()
    {
        getStorageContentsCallCount++;
        int n = getStorageContentsCallCount;
        CallLog.record("PlayerInventory.getStorageContents#" + n + (mirrorSemantics ? "[mirror]" : "[copy]"));
        if (nextGetStorageContentsThrows)
        {
            nextGetStorageContentsThrows = false;
            throw new Error("harness: getStorageContents call #" + n
                    + " throws (armed by a prior setStorageContents)");
        }
        if (throwOnGetStorageContentsCallN >= 0 && n == throwOnGetStorageContentsCallN)
        {
            throw new Error("harness: getStorageContents call #" + n + " throws (throwOnGetStorageContentsCallN)");
        }
        if (mirrorSemantics)
        {
            // Invariant #5 anchor: the buy grant's preceding read is the B9 recount.
            CallLog.markAlloc();
            return live;
        }
        ItemStack[] copy = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++)
        {
            copy[i] = live[i] == null ? null : live[i].clone();
        }
        // After the copy is built: CraftBukkit's defensive copy is not the plugin's allocation.
        CallLog.markAlloc();
        return copy;
    }

    public void setStorageContents(ItemStack[] contents)
    {
        setStorageContentsCallCount++;
        CallLog.record("PlayerInventory.setStorageContents#" + setStorageContentsCallCount);
        int limit = throwInSetStorageContentsAfterSlotK >= 0
                ? Math.min(throwInSetStorageContentsAfterSlotK, SIZE)
                : SIZE;
        for (int i = 0; i < limit; i++)
        {
            live[i] = contents[i];
        }
        if (throwInSetStorageContentsAfterSlotK >= 0)
        {
            if (alsoFailNextReadAfterSetStorageContentsThrow)
            {
                nextGetStorageContentsThrows = true;
            }
            throw new Error("harness: setStorageContents throws after slot " + throwInSetStorageContentsAfterSlotK);
        }
        // Reached only when the write completed.
        if (failNextReadAfterSetStorageContents)
        {
            failNextReadAfterSetStorageContents = false;
            nextGetStorageContentsThrows = true;
        }
    }

    public int getMaxStackSize()
    {
        return 64;
    }

    public HashMap<Integer, ItemStack> addItem(ItemStack... newItems)
    {
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        for (int idx = 0; idx < newItems.length; idx++)
        {
            addItemCallCount++;
            ItemStack incoming = newItems[idx];
            int amount = incoming.getAmount();
            int placedThisStack = 0;
            for (int unit = 0; unit < amount; unit++)
            {
                if (throwInAddItemAfterPlacingK >= 0 && addItemPlacedSoFar >= throwInAddItemAfterPlacingK)
                {
                    CallLog.record("PlayerInventory.addItem#" + addItemCallCount
                            + " throws after placing " + addItemPlacedSoFar + " total");
                    throw new Error("harness: addItem throws after placing " + throwInAddItemAfterPlacingK);
                }
                int slot = findPlacementSlot(incoming.getType());
                if (slot < 0)
                {
                    break;
                }
                placeOneUnit(slot, incoming.getType());
                placedThisStack++;
                addItemPlacedSoFar++;
            }
            CallLog.record("PlayerInventory.addItem#" + addItemCallCount
                    + "(amount=" + amount + ") placed=" + placedThisStack);
            int leftoverAmount = amount - placedThisStack;
            if (leftoverAmount > 0)
            {
                // Bukkit's own leftover object, not a plugin-driven allocation.
                leftover.put(idx, new ItemStack(incoming.getType(), leftoverAmount, false));
            }
        }
        return leftover;
    }

    private int findPlacementSlot(Material type)
    {
        int maxStack = type.getMaxStackSize();
        for (int i = 0; i < SIZE; i++)
        {
            ItemStack existing = live[i];
            if (existing != null && existing.getType() == type && !existing.hasItemMeta()
                    && existing.getAmount() < maxStack)
            {
                return i;
            }
        }
        if (freeSlotOverride >= 0 && freeSlotOverrideConsumed >= freeSlotOverride)
        {
            return -1;
        }
        for (int i = 0; i < SIZE; i++)
        {
            if (live[i] == null)
            {
                if (freeSlotOverride >= 0)
                {
                    freeSlotOverrideConsumed++;
                }
                return i;
            }
        }
        return -1;
    }

    private void placeOneUnit(int slot, Material type)
    {
        if (live[slot] == null)
        {
            // The inventory's own new slot object, not a plugin-driven allocation.
            live[slot] = new ItemStack(type, 1, false);
        }
        else
        {
            live[slot].setAmount(live[slot].getAmount() + 1);
        }
    }
}
