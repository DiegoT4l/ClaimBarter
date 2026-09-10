package io.github.diegot4l.claimbarter;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * An immutable snapshot of config.yml, rebuilt on every reload.
 *
 * <p>Every value is validated here rather than at the point of use, so a bad
 * configuration fails loudly at load instead of halfway through a trade.
 */
record BarterSettings(
        Material currency,
        int blocksPerItem,
        boolean sellingEnabled,
        double refundRatio,
        int maxPurchasedBlocks)
{
    static BarterSettings from(FileConfiguration config) throws InvalidSettingException
    {
        String itemName = config.getString("currency.item", "IRON_INGOT");
        Material currency = Material.matchMaterial(itemName);
        if (currency == null)
        {
            throw new InvalidSettingException("currency.item", "no such material: " + itemName);
        }
        // Material.AIR reports isItem() == true, so it has to be excluded by
        // name. Configured as currency it would be uncharged forever: empty
        // inventory slots are null rather than stacks of air.
        if (currency.isAir())
        {
            throw new InvalidSettingException("currency.item", "air cannot be used as currency");
        }
        if (!currency.isItem())
        {
            throw new InvalidSettingException("currency.item", itemName + " is not an obtainable item");
        }

        int blocksPerItem = config.getInt("currency.blocks-per-item", 100);
        if (blocksPerItem <= 0)
        {
            throw new InvalidSettingException("currency.blocks-per-item", "must be greater than zero");
        }

        double refundRatio = config.getDouble("selling.refund-ratio", 0.5D);
        if (refundRatio < 0.0D || refundRatio > 1.0D)
        {
            throw new InvalidSettingException("selling.refund-ratio", "must be between 0.0 and 1.0");
        }

        int maxPurchasedBlocks = config.getInt("limits.max-purchased-blocks", 0);
        if (maxPurchasedBlocks < 0)
        {
            throw new InvalidSettingException("limits.max-purchased-blocks", "must not be negative");
        }

        return new BarterSettings(
                currency,
                blocksPerItem,
                config.getBoolean("selling.enabled", true),
                refundRatio,
                maxPurchasedBlocks);
    }

    /** The configured item's name, lower-cased and spaced, for use in messages. */
    String currencyName()
    {
        return currency.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
