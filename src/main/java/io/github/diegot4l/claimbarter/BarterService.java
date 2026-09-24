package io.github.diegot4l.claimbarter;

import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every trade between items and GriefPrevention claim blocks.
 *
 * <p>The ordering inside each transaction is deliberate. On a purchase the
 * items are taken first and the blocks granted second, so a failure cannot
 * mint blocks for free; on a sale the blocks are removed and persisted first
 * and the items handed over second, so a failure cannot duplicate items.
 * Either way the loser of a crash is the plugin, never the server.
 *
 * <p>Two limits on that promise are worth stating, because both live in
 * GriefPrevention 16.18.7 and neither can be closed from out here.
 *
 * <p>A write failure is invisible. DataStore.savePlayerData only starts a
 * thread, and FlatFileDataStore.overrideSavePlayerData wraps the write in
 * catch (Exception), so a full disk or a permissions fault is logged by
 * GriefPrevention and reported to this plugin as success. Detecting it would
 * take a read-back after every save.
 *
 * <p>An in-memory rollback is not guaranteed to reach a save already in
 * flight. overrideSavePlayerData reads getBonusClaimBlocks() at serialization
 * time on its own thread, PlayerData.bonusClaimBlocks is neither volatile nor
 * read under a lock, and overrideSavePlayerData is not synchronized either.
 * So a concurrent save may serialize a value this class has already corrected.
 * Re-saving does not fix it: a second writer truncates the same file with no
 * ordering against the first. What does help is never starting a trade that
 * will have to be unwound, which is why the data store is resolved before any
 * item or block is touched.
 */
final class BarterService
{
    private final BarterSettings settings;
    private final Logger logger;

    BarterService(BarterSettings settings, Logger logger)
    {
        this.settings = settings;
        this.logger = logger;
    }

    /** The outcome of a trade, resolved into a message by the caller. */
    record Result(boolean ok, String messageKey, Object[] placeholders)
    {
        static Result fail(String key, Object... placeholders)
        {
            return new Result(false, key, placeholders);
        }

        static Result ok(String key, Object... placeholders)
        {
            return new Result(true, key, placeholders);
        }
    }

    Result buy(Player player, int items)
    {
        if (items <= 0)
        {
            return Result.fail("invalid-amount");
        }

        PlayerInventory inventory = player.getInventory();
        int held = countCurrency(inventory);
        if (held < items)
        {
            return Result.fail("not-enough-items",
                    "needed", items, "have", held, "item", settings.currencyName(items));
        }

        // Resolved before anything is taken from the player. The store is a
        // public mutable field that GriefPrevention clears as it unloads, and
        // it going away mid-tick is the only failure this method can actually
        // hit. Refusing here rather than unwinding later means a trade that
        // cannot complete is never started: no items taken, no blocks granted,
        // nothing left half-done.
        DataStore store = dataStore();
        if (store == null)
        {
            logger.severe("Purchase refused for " + player.getName()
                    + ": GriefPrevention's data store is unavailable");
            return Result.fail("transaction-failed");
        }

        // Widened to long so the ceiling checks below cannot themselves overflow.
        long blocks = (long) items * settings.blocksPerItem();
        UUID playerId = player.getUniqueId();
        PlayerData data = store.getPlayerData(playerId);
        int bonusBefore = data.getBonusClaimBlocks();
        long updated = (long) bonusBefore + blocks;

        if (updated > Integer.MAX_VALUE)
        {
            return Result.fail("overflow");
        }
        if (settings.maxPurchasedBlocks() > 0 && updated > settings.maxPurchasedBlocks())
        {
            return Result.fail("limit-reached", "limit", settings.maxPurchasedBlocks());
        }

        removeCurrency(inventory, items);
        boolean granted = false;
        try
        {
            data.setBonusClaimBlocks((int) updated);
            // GriefPrevention does not persist on its own. DataStore.java:1031 is
            // explicit: "MUST be called after you're done making changes,
            // otherwise a reload will lose them."
            store.savePlayerData(playerId, data);
            granted = true;
        }
        catch (RuntimeException failure)
        {
            logger.log(Level.SEVERE, "Purchase failed for " + player.getName(), failure);
        }
        finally
        {
            // The unwind lives in finally, not in the catch, so it also runs
            // when savePlayerData throws an Error. Its entire body in
            // GriefPrevention 16.18.7 is `new SavePlayerDataThread(...).start()`,
            // and the realistic way that fails on a loaded server is
            // OutOfMemoryError from native thread creation. Catching only
            // RuntimeException would let it escape with the items already taken,
            // which SECURITY.md classes as a vulnerability rather than a bug.
            //
            // setBonusClaimBlocks is a bare field write and cannot throw, so
            // the grant is undone first: left in place, a later save would
            // persist blocks nobody paid for.
            if (!granted)
            {
                data.setBonusClaimBlocks(bonusBefore);
                logger.severe("Rolled back bonus claim blocks for " + player.getName()
                        + " from " + updated + " to " + bonusBefore + "; refunding "
                        + items + " item(s)");
                deliver(player, items, "refund for a failed purchase");
            }
        }

        if (!granted)
        {
            return Result.fail("transaction-failed");
        }

        return Result.ok("bought",
                "blocks", blocks, "items", items, "item", settings.currencyName(items));
    }

    Result sell(Player player, int blocks)
    {
        if (!settings.sellingEnabled())
        {
            return Result.fail("selling-disabled");
        }
        if (blocks <= 0)
        {
            return Result.fail("invalid-amount");
        }

        DataStore store = dataStore();
        if (store == null)
        {
            logger.severe("Sale refused for " + player.getName()
                    + ": GriefPrevention's data store is unavailable");
            return Result.fail("transaction-failed");
        }

        UUID playerId = player.getUniqueId();
        PlayerData data = store.getPlayerData(playerId);

        int purchased = data.getBonusClaimBlocks();
        if (purchased < blocks)
        {
            // Only the bonus pool is sellable. Accrued blocks are earned by
            // playing, and letting those be cashed out would turn idle time
            // into an infinite item faucet.
            return Result.fail("not-enough-blocks", "have", purchased);
        }

        int available = data.getRemainingClaimBlocks();
        if (available < blocks)
        {
            return Result.fail("blocks-in-use", "available", available);
        }

        // Decimal, not double: the ratio is written in config as a decimal
        // like 0.29, which no double holds exactly, and 10000 / 100 * 0.29
        // evaluates to 28.999999999999996 - one item short once floored.
        // BigDecimal.valueOf reads the ratio back as the shortest decimal that
        // round-trips, which is the value the operator typed.
        int items = BigDecimal.valueOf(blocks)
                .multiply(BigDecimal.valueOf(settings.refundRatio()))
                .divide(BigDecimal.valueOf(settings.blocksPerItem()), 0, RoundingMode.FLOOR)
                .intValueExact();
        if (items <= 0)
        {
            return Result.fail("amount-too-small", "item", settings.currencyName());
        }

        data.setBonusClaimBlocks(purchased - blocks);
        boolean removed = false;
        try
        {
            store.savePlayerData(playerId, data);
            removed = true;
        }
        catch (RuntimeException failure)
        {
            logger.log(Level.SEVERE, "Sale failed for " + player.getName(), failure);
        }
        finally
        {
            // In finally for the same reason as buy(): an Error out of
            // savePlayerData must not leave the deduction standing with nothing
            // paid out for it.
            if (!removed)
            {
                data.setBonusClaimBlocks(purchased);
                logger.severe("Rolled back bonus claim blocks for " + player.getName()
                        + " to " + purchased + "; no items paid out");
            }
        }

        if (!removed)
        {
            return Result.fail("transaction-failed");
        }

        // Guarded like the refund path. By this point the blocks are deducted
        // and persisted, so an exception escaping here would cost the player
        // both the blocks and the items, and would do it with no log line at
        // all because the command dispatcher only reports a generic error.
        deliver(player, items, "payout for a sale");
        return Result.ok("sold",
                "blocks", blocks, "items", items, "item", settings.currencyName(items));
    }

    Result info(Player player)
    {
        DataStore store = dataStore();
        if (store == null)
        {
            logger.severe("Cannot report rates to " + player.getName()
                    + ": GriefPrevention's data store is unavailable");
            return Result.fail("transaction-failed");
        }

        PlayerData data = store.getPlayerData(player.getUniqueId());
        return Result.ok("info",
                "item", settings.currencyName(),
                "blocks", settings.blocksPerItem(),
                "purchased", data.getBonusClaimBlocks(),
                "available", data.getRemainingClaimBlocks());
    }

    /**
     * GriefPrevention's data store, or null while it is unavailable.
     *
     * <p>Both {@code instance} and {@code dataStore} are public mutable fields
     * that GriefPrevention clears as it unloads, so a dereference that was safe
     * one statement ago can fail in the next. Resolving them once, up front,
     * turns that from a mid-transaction failure into a refusal.
     */
    private static DataStore dataStore()
    {
        GriefPrevention plugin = GriefPrevention.instance;
        return plugin == null ? null : plugin.dataStore;
    }

    /**
     * Hands items to a player as part of a trade that must not fail further.
     *
     * <p>Guarded because {@link #giveCurrency} can throw on the way out: it
     * drops whatever will not fit through the world, which fires ItemSpawnEvent
     * synchronously into other plugins' listeners. Letting that escape would
     * replace the configured message with Bukkit's generic internal error and
     * abandon the rest of the unwind, so the failure is logged loudly instead
     * and names the amount an operator has to restore by hand.
     */
    private void deliver(Player player, int items, String context)
    {
        try
        {
            giveCurrency(player, items);
        }
        catch (RuntimeException failure)
        {
            logger.log(Level.SEVERE, "Could not hand " + items + " item(s) to "
                    + player.getName() + " as a " + context
                    + "; they are lost and must be restored by hand", failure);
        }
    }

    /**
     * Counts currency in the main inventory only, and only plain stacks.
     *
     * <p>Anything carrying item metadata is skipped: a renamed or enchanted
     * ingot may be a keepsake or a quest item, and spending it because it
     * shares a material would be a bug the player pays for.
     */
    private int countCurrency(PlayerInventory inventory)
    {
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents())
        {
            if (isPlainCurrency(stack))
            {
                total += stack.getAmount();
                if (total < 0)
                {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return total;
    }

    private boolean isPlainCurrency(ItemStack stack)
    {
        return stack != null
                && stack.getType() == settings.currency()
                && !stack.hasItemMeta();
    }

    private void removeCurrency(PlayerInventory inventory, int amount)
    {
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++)
        {
            ItemStack stack = contents[slot];
            if (!isPlainCurrency(stack))
            {
                continue;
            }
            int taken = Math.min(stack.getAmount(), remaining);
            remaining -= taken;
            if (taken == stack.getAmount())
            {
                contents[slot] = null;
            }
            else
            {
                stack.setAmount(stack.getAmount() - taken);
            }
        }
        inventory.setStorageContents(contents);
    }

    /** Hands items back, dropping at the player's feet whatever will not fit. */
    private void giveCurrency(Player player, int amount)
    {
        int maxStack = settings.currency().getMaxStackSize();
        int remaining = amount;
        while (remaining > 0)
        {
            int size = Math.min(remaining, maxStack);
            remaining -= size;
            Map<Integer, ItemStack> leftover =
                    player.getInventory().addItem(new ItemStack(settings.currency(), size));
            for (ItemStack drop : leftover.values())
            {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
    }
}
