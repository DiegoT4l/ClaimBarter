package io.github.diegot4l.claimbarter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the messages block of config.yml and renders it.
 *
 * <p>Strings use legacy ampersand colour codes because that is what server
 * owners expect to edit in a config file.
 */
final class Messages
{
    private static final LegacyComponentSerializer SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

    /** Catches a template that still pluralises {@code {item}} by hand. */
    private static final Pattern HAND_PLURALISED = Pattern.compile("\\{item}(?=s\\b)");

    private final Map<String, String> values = new HashMap<>();
    private final String prefix;

    Messages(ConfigurationSection section, Logger logger)
    {
        if (section != null)
        {
            for (String key : section.getKeys(false))
            {
                values.put(key, section.getString(key, ""));
            }
        }
        prefix = values.getOrDefault("prefix", "");
        warnAboutHandPluralisation(logger);
    }

    /**
     * Warns about templates that were edited to work around the old singular.
     *
     * <p>{@code {item}} used to render the singular whatever the count, so a
     * server could fix "for 10 iron ingot" by writing "{item}s" in its own
     * config.yml. Now that the placeholder inflects, that same template renders
     * "ingotss", and {@code saveDefaultConfig()} never overwrites an existing
     * file, so nothing else would ever tell the operator. A warning is the most
     * this can do: the text is legal, it is only very unlikely to be wanted.
     */
    private void warnAboutHandPluralisation(Logger logger)
    {
        for (Map.Entry<String, String> entry : values.entrySet())
        {
            if (HAND_PLURALISED.matcher(entry.getValue()).find())
            {
                logger.warning("Message '" + entry.getKey() + "' contains \"{item}s\". "
                        + "{item} now matches the count beside it, so this renders a double "
                        + "plural. Drop the trailing s, and set currency.item-plural if the "
                        + "derived plural is wrong.");
            }
        }
    }

    void send(CommandSender recipient, String key, Object... placeholders)
    {
        recipient.sendMessage(render(key, placeholders));
    }

    Component render(String key, Object... placeholders)
    {
        String template = values.get(key);
        if (template == null)
        {
            // A missing key is usually a packaging bug, but it is also what an
            // upgraded server sees for a key added after its config.yml was
            // written, since saveDefaultConfig never overwrites one. The values
            // are appended so the message still carries its numbers: a player
            // told only "Missing message: items-lost" cannot tell an admin how
            // many items to restore.
            template = "&cMissing message: " + key + describe(placeholders);
        }
        return SERIALIZER.deserialize(prefix + fill(template, placeholders));
    }

    /** Renders key=value pairs for the missing-key fallback, or "" if none. */
    private static String describe(Object... placeholders)
    {
        if (placeholders.length < 2 || placeholders.length % 2 != 0)
        {
            return "";
        }
        StringBuilder detail = new StringBuilder(" (");
        for (int i = 0; i < placeholders.length; i += 2)
        {
            if (i > 0)
            {
                detail.append(", ");
            }
            detail.append(placeholders[i]).append('=').append(placeholders[i + 1]);
        }
        return detail.append(')').toString();
    }

    /**
     * Replaces {name} placeholders from alternating key/value arguments.
     *
     * <p>One pass over the template, so a substituted value is never rescanned
     * and no placeholder can eat the prefix of a longer one. Replacing them
     * one at a time made the argument order load-bearing: {@code {item}} is a
     * prefix of {@code {items}}, and info() passes "item" first, so an
     * {@code {items}} added to that template would have been rewritten to the
     * item name followed by a stray brace.
     *
     * <p>Values are quoted before insertion. currency.item-plural is operator
     * text and reaches this method, where a bare "$" would otherwise be read
     * as a capture-group reference.
     */
    private static String fill(String template, Object... placeholders)
    {
        if (placeholders.length % 2 != 0)
        {
            throw new IllegalArgumentException("placeholders must be key/value pairs");
        }
        if (placeholders.length == 0)
        {
            return template;
        }
        Map<String, String> substitutions = new HashMap<>();
        for (int i = 0; i < placeholders.length; i += 2)
        {
            substitutions.put(String.valueOf(placeholders[i]), String.valueOf(placeholders[i + 1]));
        }
        // An unknown placeholder is left exactly as written rather than blanked,
        // so a typo in a template is visible to the operator who made it.
        return PLACEHOLDER.matcher(template).replaceAll(match ->
        {
            String value = substitutions.get(match.group(1));
            return Matcher.quoteReplacement(value == null ? match.group() : value);
        });
    }
}
