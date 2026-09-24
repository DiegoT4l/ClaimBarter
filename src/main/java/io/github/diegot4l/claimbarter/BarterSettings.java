package io.github.diegot4l.claimbarter;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * An immutable snapshot of config.yml, rebuilt on every reload.
 *
 * <p>Every value is validated here rather than at the point of use, so a bad
 * configuration fails loudly at load instead of halfway through a trade.
 */
record BarterSettings(
        Material currency,
        String currencyPlural,
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

        // Resolved here rather than defaulted in config.yml so that a server
        // which changes currency.item cannot be left describing a different
        // item than the one it charges.
        String configuredPlural = config.getString("currency.item-plural", "");
        String currencyPlural = configuredPlural == null || configuredPlural.isBlank()
                ? derivedPlural(currency)
                : configuredPlural.trim();

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
                currencyPlural,
                blocksPerItem,
                config.getBoolean("selling.enabled", true),
                refundRatio,
                maxPurchasedBlocks);
    }

    /** The configured item's name, lower-cased and spaced, for use in messages. */
    String currencyName()
    {
        return displayName(currency);
    }

    /**
     * The currency name inflected for the count it will be printed next to, so
     * a message reads "for 10 iron ingots" rather than "for 10 iron ingot".
     *
     * <p>Use this where the plugin supplies the number, and the no-argument
     * {@link #currencyName()} where the template does: "a single {item}" and
     * "1 {item}" are the message's own singular, not a count this class knows
     * about.
     */
    String currencyName(int count)
    {
        return count == 1 ? currencyName() : currencyPlural;
    }

    private static String displayName(Material material)
    {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * Appends an "s" unless the name already ends in one.
     *
     * <p>That is a convenience, not a rule, and it is wrong in both directions.
     * Minecraft materials are full of mass nouns (redstone, gunpowder, sand)
     * where appending anything reads wrong, and of singulars that already end
     * in "s" and still take a plural — ten of COMPASS are "compasses", not
     * "compass".
     *
     * <p>No rule over the characters can separate those two cases: "glass" and
     * "compass" end identically and differ only in the dictionary, which this
     * plugin has no business shipping. {@code currency.item-plural} is the
     * escape hatch for either, and the derivation is only ever a default for
     * the server that has not set one.
     */
    private static String derivedPlural(Material material)
    {
        String name = displayName(material);
        return name.endsWith("s") ? name : name + "s";
    }
}
