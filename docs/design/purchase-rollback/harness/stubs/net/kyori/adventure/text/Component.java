package net.kyori.adventure.text;

/**
 * Holds exactly the rendered String a real Component would eventually paint,
 * and exposes it back so the harness can assert on precisely what a player
 * would see - no ANSI, no MiniMessage tree, just the text.
 */
public final class Component
{
    private final String rendered;

    public Component(String rendered)
    {
        this.rendered = rendered;
    }

    public String rendered()
    {
        return rendered;
    }

    @Override
    public String toString()
    {
        return rendered;
    }
}
