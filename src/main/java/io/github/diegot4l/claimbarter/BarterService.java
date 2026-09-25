package io.github.diegot4l.claimbarter;

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

        // Widened to long so the ceiling checks below cannot themselves overflow.
        long blocks = (long) items * settings.blocksPerItem();
        UUID playerId = player.getUniqueId();
        PlayerData data = GriefPrevention.instance.dataStore.getPlayerData(playerId);
        long updated = (long) data.getBonusClaimBlocks() + blocks;

        if (updated > Integer.MAX_VALUE)
        {
            return Result.fail("overflow");
        }
        if (settings.maxPurchasedBlocks() > 0 && updated > settings.maxPurchasedBlocks())
        {
            return Result.fail("limit-reached", "limit", settings.maxPurchasedBlocks());
        }

        removeCurrency(inventory, items);
        try
        {
            data.setBonusClaimBlocks((int) updated);
            // GriefPrevention does not persist on its own. DataStore.java:1031 is
            // explicit: "MUST be called after you're done making changes,
            // otherwise a reload will lose them."
            GriefPrevention.instance.dataStore.savePlayerData(playerId, data);
        }
        catch (RuntimeException failure)
        {
            giveCurrency(player, items);
            logger.log(Level.SEVERE, "Purchase failed for " + player.getName() + "; items refunded", failure);
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

        UUID playerId = player.getUniqueId();
        PlayerData data = GriefPrevention.instance.dataStore.getPlayerData(playerId);

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
        try
        {
            GriefPrevention.instance.dataStore.savePlayerData(playerId, data);
        }
        catch (RuntimeException failure)
        {
            data.setBonusClaimBlocks(purchased);
            logger.log(Level.SEVERE, "Sale failed for " + player.getName() + "; no items paid out", failure);
            return Result.fail("transaction-failed");
        }

        giveCurrency(player, items);
        return Result.ok("sold",
                "blocks", blocks, "items", items, "item", settings.currencyName(items));
    }

    Result info(Player player)
    {
        PlayerData data = GriefPrevention.instance.dataStore.getPlayerData(player.getUniqueId());
        return Result.ok("info",
                "item", settings.currencyName(),
                "blocks", settings.blocksPerItem(),
                "purchased", data.getBonusClaimBlocks(),
                "available", data.getRemainingClaimBlocks());
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
