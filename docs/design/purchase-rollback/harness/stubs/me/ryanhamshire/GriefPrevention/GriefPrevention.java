package me.ryanhamshire.GriefPrevention;

/**
 * Harness stand-in for the two public mutable fields BarterService actually
 * reads. Setting instance or dataStore to null drives the data-unavailable
 * rows without needing a real plugin lifecycle.
 */
public class GriefPrevention
{
    public static GriefPrevention instance;

    public DataStore dataStore;
}
