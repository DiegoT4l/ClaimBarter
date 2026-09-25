package org.bukkit.entity;

import org.bukkit.inventory.ItemStack;

/**
 * Harness stand-in for the dropped-item entity World.dropItemNaturally
 * returns. isValid() is what tells a cancelled ItemSpawnEvent (the stack was
 * destroyed silently) apart from a genuine drop; heldStackAmount is settable
 * independently of what was handed over so a listener that shrinks or merges
 * the stack can be modelled without touching the caller's own ItemStack.
 */
public class Item
{
    private boolean valid;
    private ItemStack itemStack;
    private boolean returnNullStack;

    public Item(boolean valid, ItemStack itemStack)
    {
        this.valid = valid;
        this.itemStack = itemStack;
    }

    public boolean isValid()
    {
        return valid;
    }

    public ItemStack getItemStack()
    {
        return returnNullStack ? null : itemStack;
    }

    public void setValid(boolean valid)
    {
        this.valid = valid;
    }

    public void setItemStack(ItemStack itemStack)
    {
        this.itemStack = itemStack;
    }

    public void setReturnNullStack(boolean returnNullStack)
    {
        this.returnNullStack = returnNullStack;
    }
}
