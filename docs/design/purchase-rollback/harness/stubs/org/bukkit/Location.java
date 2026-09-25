package org.bukkit;

/**
 * Immutable holder of a world reference and coordinates.
 *
 * <p>Player.getLocation() must construct a NEW instance on every call (the
 * real Entity.getLocation() does the same), so a listener that teleports a
 * player mid-payout cannot corrupt a location a caller cached before the loop
 * started, and so the harness can prove per-drop re-reads (review #11).
 */
public final class Location
{
    private final World world;
    private final double x;
    private final double y;
    private final double z;

    public Location(World world, double x, double y, double z)
    {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public World getWorld()
    {
        return world;
    }

    public double getX()
    {
        return x;
    }

    public double getY()
    {
        return y;
    }

    public double getZ()
    {
        return z;
    }

    @Override
    public String toString()
    {
        return "Location{" + (world == null ? "null" : world.name()) + "," + x + "," + y + "," + z + "}";
    }
}
