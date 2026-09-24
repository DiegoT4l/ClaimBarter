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
 * <p>What that promise does not cover is worth stating plainly, because the
 * limits live in GriefPrevention 16.18.7 rather than here.
 *
 * <p><b>A write failure is invisible.</b> DataStore.savePlayerData only starts
 * a thread, and FlatFileDataStore.overrideSavePlayerData wraps the write in
 * catch (Exception). A full disk or a permissions fault is logged by
 * GriefPrevention and reported to this plugin as success.
 *
 * <p><b>A rollback may not reach a save already in flight.</b>
 * overrideSavePlayerData reads getBonusClaimBlocks() at serialization time on
 * its own thread; PlayerData.bonusClaimBlocks is neither volatile nor read
 * under a lock, and overrideSavePlayerData is not synchronized. So a
 * concurrent save can serialize a value this class has already corrected, and
 * re-saving does not help: a second writer truncates the same file with no
 * ordering against the first.
 *
 * <p>DataStore.savePlayerDataSync would close both, because it performs the
 * write on the calling thread. It is deliberately not used. That would put
 * file I/O on the main thread on every trade, and a stall there is felt by
 * everyone on the server rather than by the one player trading. The residual
 * risk is a narrow window on a path that only opens when something has already
 * gone wrong; the cost would be paid on every success.
 *
 * <p>So the guarantee here is narrower, and chosen rather than assumed: a
 * trade that cannot be completed is refused before anything moves, a trade
 * that fails partway is unwound as far as it can be, and nothing is reported
 * to the player as having worked when it did not.
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

        DataStore store = dataStore("a purchase", player);
        if (store == null)
        {
            return Result.fail("data-unavailable");
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

        // Boxed now, on the happy path. setBonusClaimBlocks takes an Integer,
        // so every call autoboxes, and Integer.valueOf allocates for anything
        // outside -128..127 - which is every realistic block total. The
        // rollback below may be running precisely because the heap is
        // exhausted, so it must not be the thing that allocates.
        Integer rollbackTo = bonusBefore;

        removeCurrency(inventory, items);

        Throwable failure = null;
        try
        {
            data.setBonusClaimBlocks((int) updated);
            // GriefPrevention does not persist on its own. DataStore.java:1031 is
            // explicit: "MUST be called after you're done making changes,
            // otherwise a reload will lose them."
            store.savePlayerData(playerId, data);
        }
        catch (Throwable thrown)
        {
            // Throwable, not RuntimeException. savePlayerData's whole body is
            // `new SavePlayerDataThread(...).start()`, and the realistic way
            // that fails on a loaded server is OutOfMemoryError from native
            // thread creation - an Error. The items are already gone by here,
            // so nothing may skip the unwind. Errors are rethrown once it has
            // run, rather than swallowed.
            failure = thrown;
        }

        if (failure == null)
        {
            return Result.ok("bought",
                    "blocks", blocks, "items", items, "item", settings.currencyName(items));
        }

        data.setBonusClaimBlocks(rollbackTo);
        // Refund before logging. The refund is the safety property and the log
        // is diagnostics; composing the log message allocates, and under the
        // OutOfMemoryError this path exists for, allocation is exactly what
        // fails. Ordering them the other way trades the player's items for a
        // console line.
        int returned = deliver(player, items, "refund for a failed purchase");
        logger.log(Level.SEVERE, "Purchase failed for " + player.getName()
                + "; bonus claim blocks restored to " + bonusBefore + " and "
                + returned + " of " + items + " item(s) returned", failure);

        if (failure instanceof Error error)
        {
            throw error;
        }
        return returned < items
                ? Result.fail("items-lost", "lost", items - returned)
                : Result.fail("transaction-failed");
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

        DataStore store = dataStore("a sale", player);
        if (store == null)
        {
            return Result.fail("data-unavailable");
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

        // Boxed ahead of the write, for the same reason as in buy().
        Integer rollbackTo = purchased;

        Throwable failure = null;
        try
        {
            data.setBonusClaimBlocks(purchased - blocks);
            store.savePlayerData(playerId, data);
        }
        catch (Throwable thrown)
        {
            failure = thrown;
        }

        if (failure != null)
        {
            data.setBonusClaimBlocks(rollbackTo);
            logger.log(Level.SEVERE, "Sale failed for " + player.getName()
                    + "; bonus claim blocks restored to " + purchased
                    + ", no items paid out", failure);
            if (failure instanceof Error error)
            {
                throw error;
            }
            return Result.fail("transaction-failed");
        }

        // The blocks are deducted and persisted by this point, so a payout that
        // only partly lands cannot be reported as a completed sale. Saying
        // "sold" while the items never arrived is the item-loss case
        // SECURITY.md treats as a vulnerability rather than a bug.
        int paid = deliver(player, items, "payout for a sale");
        if (paid < items)
        {
            return Result.fail("items-lost", "lost", items - paid);
        }

        return Result.ok("sold",
                "blocks", blocks, "items", items, "item", settings.currencyName(items));
    }

    Result info(Player player)
    {
        DataStore store = dataStore("a rate lookup", player);
        if (store == null)
        {
            return Result.fail("data-unavailable");
        }

        PlayerData data = store.getPlayerData(player.getUniqueId());
        return Result.ok("info",
                "item", settings.currencyName(),
                "blocks", settings.blocksPerItem(),
                "purchased", data.getBonusClaimBlocks(),
                "available", data.getRemainingClaimBlocks());
    }

    /**
     * GriefPrevention's data store, or null when it cannot be reached.
     *
     * <p>Both {@code instance} and {@code dataStore} are public mutable fields
     * that GriefPrevention clears as it unloads, so a dereference that was safe
     * one statement ago can fail in the next. Resolving them before anything is
     * taken from the player turns that into a refusal rather than a trade that
     * has to be unwound.
     *
     * <p>It is a narrowing, not a guarantee, and the difference is worth being
     * precise about. PlayerData loads lazily: getBonusClaimBlocks() calls
     * loadDataFromSecondaryStorage(), which reads
     * GriefPrevention.instance.dataStore again on its own. A player whose data
     * is not cached yet can still fail there, after this check has passed. What
     * the check does buy is that such a failure happens before any item or
     * block has moved.
     */
    private DataStore dataStore(String operation, Player player)
    {
        GriefPrevention plugin = GriefPrevention.instance;
        DataStore store = plugin == null ? null : plugin.dataStore;
        if (store == null)
        {
            logger.severe("Refused " + operation + " for " + player.getName()
                    + ": GriefPrevention's data store is unavailable");
        }
        return store;
    }

    /**
     * Hands items to a player, dropping at their feet whatever will not fit,
     * and returns how many actually arrived.
     *
     * <p>Dropping fires ItemSpawnEvent synchronously into other plugins'
     * listeners, so this can fail partway through a large payout. It is caught
     * here rather than allowed to escape: the caller is either unwinding a
     * failed trade or has already persisted the blocks, and in both cases an
     * escaping exception would replace the configured message with Bukkit's
     * generic internal error and lose the record of what was owed.
     *
     * <p>The count returned is what the caller reports. Reporting the amount
     * requested instead would send an operator to restore items the player had
     * already received, turning an item loss into item duplication.
     */
    private int deliver(Player player, int amount, String context)
    {
        int maxStack = settings.currency().getMaxStackSize();
        int delivered = 0;
        try
        {
            int remaining = amount;
            while (remaining > 0)
            {
                int size = Math.min(remaining, maxStack);
                remaining -= size;
                Map<Integer, ItemStack> leftover =
                        player.getInventory().addItem(new ItemStack(settings.currency(), size));
                int notStored = 0;
                for (ItemStack drop : leftover.values())
                {
                    notStored += drop.getAmount();
                }
                // Whatever addItem did not hand back is in the inventory.
                delivered += size - notStored;
                for (ItemStack drop : leftover.values())
                {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    delivered += drop.getAmount();
                }
            }
        }
        catch (RuntimeException failure)
        {
            logger.log(Level.SEVERE, "Handing " + amount + " item(s) to " + player.getName()
                    + " as a " + context + " stopped after " + delivered
                    + "; the remaining " + (amount - delivered)
                    + " are lost and must be restored by hand", failure);
        }
        return delivered;
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
}
