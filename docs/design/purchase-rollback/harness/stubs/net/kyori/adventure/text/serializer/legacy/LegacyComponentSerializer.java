package net.kyori.adventure.text.serializer.legacy;

import net.kyori.adventure.text.Component;

/**
 * Exists only so Messages compiles and so the harness can read back exactly
 * what the real serializer would have produced from an ampersand-coded
 * string: this stub wraps the string unchanged rather than parsing colour
 * codes, since the assertions the harness makes are about substrings and
 * placeholder substitution, not about rendered colour.
 */
public final class LegacyComponentSerializer
{
    private static final LegacyComponentSerializer INSTANCE = new LegacyComponentSerializer();

    private LegacyComponentSerializer()
    {
    }

    public static LegacyComponentSerializer legacyAmpersand()
    {
        return INSTANCE;
    }

    public Component deserialize(String input)
    {
        return new Component(input);
    }
}
