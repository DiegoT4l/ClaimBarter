package io.github.diegot4l.claimbarter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

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

    private final Map<String, String> values = new HashMap<>();
    private final String prefix;

    Messages(ConfigurationSection section)
    {
        if (section != null)
        {
            for (String key : section.getKeys(false))
            {
                values.put(key, section.getString(key, ""));
            }
        }
        prefix = values.getOrDefault("prefix", "");
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
            // A missing key is a packaging bug, not a player-facing condition.
            template = "&cMissing message: " + key;
        }
        return SERIALIZER.deserialize(prefix + fill(template, placeholders));
    }

    /** Replaces {name} placeholders from alternating key/value arguments. */
    private static String fill(String template, Object... placeholders)
    {
        if (placeholders.length % 2 != 0)
        {
            throw new IllegalArgumentException("placeholders must be key/value pairs");
        }
        String result = template;
        for (int i = 0; i < placeholders.length; i += 2)
        {
            result = result.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return result;
    }
}
