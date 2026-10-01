package io.github.diegot4l.claimbarter;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

/**
 * An immutable snapshot of config.yml, rebuilt on every reload.
 *
 * <p>Every value is validated here rather than at the point of use, so a bad
 * configuration fails loudly at load instead of halfway through a trade.
 *
 * <p>{@code currencyPlural} is the one component that is not simply a config
 * value: it falls back to a guess derived from {@code currency}. The singular
 * is deliberately NOT stored beside it. It is fully determined by
 * {@code currency}, and a second field holding a name nothing ties back to the
 * material would let the two disagree, which is the failure this design exists
 * to prevent.
 *
 * <p>{@code currency} is a one-item template. For a bare material it carries
 * no components at all, and only plain stacks match it; for an item written
 * with components, such as {@code minecraft:trial_key[minecraft:custom_data=
 * {coin:"iron_penny"}]}, a stack matches only when its components are exactly
 * the template's. Either way the comparison is {@link ItemStack#isSimilar},
 * so a renamed or enchanted copy is never spent by accident.
 */
record BarterSettings(
        ItemStack currency,
        String currencyPlural,
        int blocksPerItem,
        boolean sellingEnabled,
        double refundRatio,
        int maxPurchasedBlocks)
{
    static BarterSettings from(FileConfiguration config) throws InvalidSettingException
    {
        String itemName = config.getString("currency.item", "IRON_INGOT").strip();
        ItemStack currency = parseCurrency(itemName);

        // getString with a non-null default never returns null: it falls back
        // to the default both when the key is absent and when it is YAML null.
        String configuredPlural = config.getString("currency.item-plural", "").strip();
        if (configuredPlural.indexOf('&') >= 0 || configuredPlural.indexOf('\u00A7') >= 0)
        {
            // Every other setting rejects a value it cannot honour, and this one
            // is spliced into a message that is then read by the legacy colour
            // serializer. An ampersand there would be eaten along with the
            // character after it, silently truncating the item name mid-word and
            // bleeding a colour into the rest of the line.
            throw new InvalidSettingException("currency.item-plural", "must not contain colour codes");
        }
        if (configuredPlural.indexOf('{') >= 0 || configuredPlural.indexOf('}') >= 0)
        {
            // This is the only operator-supplied free text that becomes part of
            // a message rather than the whole of one. Braces are refused so it
            // cannot smuggle in a placeholder of its own.
            throw new InvalidSettingException("currency.item-plural", "must not contain braces");
        }
        // Resolved here rather than defaulted in config.yml so that a server
        // which changes currency.item cannot be left describing a different
        // item than the one it charges.
        String currencyPlural = configuredPlural.isEmpty()
                ? derivedPlural(displayName(currency))
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
     *
     * <p>A template carrying two live counts can only agree with one of them.
     * {@code not-enough-items} is the only such template, and {@code {item}}
     * sits next to {@code {needed}}, which is the count passed here.
     */
    String currencyName(int count)
    {
        return count == 1 ? displayName(currency) : currencyPlural;
    }

    /**
     * Reads {@code currency.item}: either a bare material name, which keeps the
     * original meaning of "a plain stack of this", or a full item string with
     * components in the same syntax as {@code /give}.
     */
    private static ItemStack parseCurrency(String itemName) throws InvalidSettingException
    {
        ItemStack currency;
        if (itemName.indexOf('[') >= 0)
        {
            try
            {
                currency = Bukkit.getItemFactory().createItemStack(itemName);
            }
            catch (IllegalArgumentException e)
            {
                throw new InvalidSettingException("currency.item", "not a valid item: " + e.getMessage());
            }
            // An item string whose components all turned out to be defaults
            // would silently mean "any plain stack", which is not what someone
            // who wrote components asked for.
            if (!currency.hasItemMeta())
            {
                throw new InvalidSettingException("currency.item",
                        itemName + " carries no components; write the bare material instead");
            }
        }
        else
        {
            Material material = Material.matchMaterial(itemName);
            if (material == null)
            {
                throw new InvalidSettingException("currency.item", "no such material: " + itemName);
            }
            currency = material.isAir() ? null : new ItemStack(material);
        }
        // Material.AIR reports isItem() == true, so it has to be excluded by
        // name. Configured as currency it would be uncharged forever: empty
        // inventory slots are null rather than stacks of air.
        if (currency == null || currency.getType().isAir())
        {
            throw new InvalidSettingException("currency.item", "air cannot be used as currency");
        }
        if (!currency.getType().isItem())
        {
            throw new InvalidSettingException("currency.item", itemName + " is not an obtainable item");
        }
        currency.setAmount(1);
        return currency;
    }

    /**
     * The name a player sees: the item's own {@code item_name} when the
     * template sets one, so a coin built on another item is not described as
     * that item; otherwise the material, lower-cased and spaced.
     */
    private static String displayName(ItemStack currency)
    {
        ItemMeta meta = currency.getItemMeta();
        if (meta != null && meta.hasItemName())
        {
            return PlainTextComponentSerializer.plainText()
                    .serialize(meta.itemName())
                    .toLowerCase(Locale.ROOT);
        }
        return currency.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * Guesses the plural of a material name, as a default for the server that
     * has not set {@code currency.item-plural}.
     *
     * <p>Three rules here are mechanical and exception-free. A name ending in
     * a sibilant takes "-es" ("torch" becomes "torches", "shulker box" becomes
     * "shulker boxes"); a consonant before a final "y" turns it into "-ies"
     * ("poppy" becomes "poppies"), while a vowel before it does not ("clay"
     * stays "clays"); anything else takes a plain "-s".
     *
     * <p>What cannot be decided from the characters is a name already ending
     * in "s". It may be a plural already, a mass noun, or a singular that still
     * inflects: "glass" and "compass" end identically and differ only in a
     * dictionary this plugin has no business shipping. Those are left
     * untouched, which is right for glass and wrong for compass.
     *
     * <p>Two more classes are left alone deliberately, because their rules have
     * common exceptions: names ending in "o" ("potato" wants "potatoes",
     * "bamboo" does not want "bambooes") and in "f" ("bookshelf" wants
     * "bookshelves", but "roof" wants "roofs"). Mass nouns such as "redstone"
     * and "gunpowder" are wrong under any suffix rule at all.
     *
     * <p>A multi-word name is inflected on its last word, which is wrong
     * whenever the head noun is not the last one: LILY_OF_THE_VALLEY comes out
     * as "lily of the valleys" where English wants "lilies of the valley".
     * Finding the head noun needs a grammar, not a suffix.
     *
     * <p>{@code currency.item-plural} is the escape hatch for every one of
     * those, and a server using such an item as currency should set it.
     *
     * @param name a display name from {@link #displayName}, not a Material, so
     *             the caller's already-resolved string is reused
     */
    private static String derivedPlural(String name)
    {
        if (name.endsWith("ch") || name.endsWith("sh") || name.endsWith("x") || name.endsWith("z"))
        {
            return name + "es";
        }
        if (name.endsWith("s"))
        {
            return name;
        }
        if (name.length() >= 2 && name.endsWith("y") && !isVowel(name.charAt(name.length() - 2)))
        {
            return name.substring(0, name.length() - 1) + "ies";
        }
        return name + "s";
    }

    private static boolean isVowel(char letter)
    {
        return "aeiou".indexOf(letter) >= 0;
    }
}
