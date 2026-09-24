package io.github.diegot4l.claimbarter;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * An immutable snapshot of config.yml, rebuilt on every reload.
 *
 * <p>Every value is validated here rather than at the point of use, so a bad
 * configuration fails loudly at load instead of halfway through a trade. The
 * two currency names are resolved here for the same reason: they are derived
 * once from a validated material, not recomputed per message.
 */
record BarterSettings(
        Material currency,
        String currencySingular,
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

        // getString with a non-null default never returns null: it falls back
        // to the default both when the key is absent and when it is YAML null.
        String configuredPlural = config.getString("currency.item-plural", "").strip();
        if (configuredPlural.indexOf('&') >= 0 || configuredPlural.indexOf('§') >= 0)
        {
            // Every other setting rejects a value it cannot honour, and this one
            // is spliced into a message that is then read by the legacy colour
            // serializer. An ampersand there would be eaten along with the
            // character after it, silently truncating the item name mid-word and
            // bleeding a colour into the rest of the line.
            throw new InvalidSettingException("currency.item-plural", "must not contain colour codes");
        }
        // Resolved here rather than defaulted in config.yml so that a server
        // which changes currency.item cannot be left describing a different
        // item than the one it charges.
        String currencyPlural = configuredPlural.isEmpty()
                ? derivedPlural(currency)
                : configuredPlural;

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
                displayName(currency),
                currencyPlural,
                blocksPerItem,
                config.getBoolean("selling.enabled", true),
                refundRatio,
                maxPurchasedBlocks);
    }

    /** The configured item's name, lower-cased and spaced, for use in messages. */
    String currencyName()
    {
        return currencySingular;
    }

    /**
     * The currency name inflected for the count it will be printed next to, so
     * a message reads "for 10 iron ingots" rather than "for 10 iron ingot".
     *
     * <p>Use this where the plugin supplies the number, and the no-argument
     * {@link #currencyName()} where the template does: "a single {item}" and
     * "1 {item}" are the message's own singular, not a count this class knows
     * about.
     *
     * <p>A template carrying two live counts can only agree with one of them.
     * {@code not-enough-items} is the only such template, and {@code {item}}
     * sits next to {@code {needed}}, which is the count passed here.
     */
    String currencyName(int count)
    {
        return count == 1 ? currencySingular : currencyPlural;
    }

    private static String displayName(Material material)
    {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * Guesses the plural of a material name, as a default for the server that
     * has not set {@code currency.item-plural}.
     *
     * <p>Two of the three rules here are mechanical and safe. A name ending in
     * a sibilant takes "-es" (torch becomes torches, brush becomes brushes,
     * shulker box becomes boxes), and anything else takes a plain "-s".
     *
     * <p>The third case cannot be decided from the characters. A name already
     * ending in "s" may be a plural already, a mass noun, or a singular that
     * still inflects: "glass" and "compass" end identically and differ only in
     * a dictionary this plugin has no business shipping. Those are left
     * untouched, which is right for glass and wrong for compass.
     *
     * <p>Two smaller classes are also left alone deliberately, because their
     * rules have common exceptions: names ending in "o" (potato wants
     * potatoes, bamboo does not want bambooes) and in "f" (bookshelf wants
     * bookshelves, but roof wants roofs). Mass nouns such as redstone and
     * gunpowder are wrong under any suffix rule at all.
     *
     * <p>{@code currency.item-plural} is the escape hatch for every one of
     * those, and a server using such an item as currency should set it.
     */
    private static String derivedPlural(Material material)
    {
        String name = displayName(material);
        if (name.endsWith("ch") || name.endsWith("sh") || name.endsWith("x") || name.endsWith("z"))
        {
            return name + "es";
        }
        if (name.endsWith("s"))
        {
            return name;
        }
        return name + "s";
    }
}
