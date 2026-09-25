package org.bukkit;

import harness.support.CallLog;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Harness stand-in for org.bukkit.World, narrowed to dropItemNaturally.
 *
 * <p>Drop calls are numbered 1-based per world instance AND recorded into a
 * single static log shared by every World the harness constructs, because a
 * teleporting listener (review #11) makes later drops land on a DIFFERENT
 * World object than the first one, and an assertion that wants to see "the
 * first drop on the old world, the rest on the new one" has to read across
 * both instances in call order.
 */
public class World
{
    public static final class DropRecord
    {
        public final World world;
        public final Location location;
        public final int amountHandedOver;

        DropRecord(World world, Location location, int amountHandedOver)
        {
            this.world = world;
            this.location = location;
            this.amountHandedOver = amountHandedOver;
        }
    }

    public static final List<DropRecord> GLOBAL_DROP_LOG = new ArrayList<>();

    public static void resetGlobal()
    {
        GLOBAL_DROP_LOG.clear();
    }

    private final String name;
    private int dropCallCount = 0;

    // Knobs, all keyed by the 1-based drop-call number they apply to.
    public final Set<Integer> cancelDropNumbers = new HashSet<>();
    public final Map<Integer, RuntimeException> throwRuntimeOnDropNumber = new HashMap<>();
    public final Map<Integer, Error> throwErrorOnDropNumber = new HashMap<>();
    public final Map<Integer, Integer> shrinkDropNumberTo = new HashMap<>();
    public final Map<Integer, Integer> growDropNumberTo = new HashMap<>();
    public final Set<Integer> nullStackOnDropNumbers = new HashSet<>();
    public final Set<Integer> teleportPlayerOnDropNumbers = new HashSet<>();
    public final Set<Integer> kickPlayerOnDropNumbers = new HashSet<>();
    public final Set<Integer> returnNullOnDropNumbers = new HashSet<>();

    /** The player a teleport/kick knob acts on; wired by the harness after construction. */
    public Player teleportTarget;
    public World teleportToWorld;
    public Location teleportToLocation;

    public World(String name)
    {
        this.name = name;
    }

    public String name()
    {
        return name;
    }

    public int dropCallCount()
    {
        return dropCallCount;
    }

    public Item dropItemNaturally(Location location, ItemStack stack)
    {
        dropCallCount++;
        int n = dropCallCount;
        int handedOver = stack.getAmount();
        GLOBAL_DROP_LOG.add(new DropRecord(this, location, handedOver));
        CallLog.record("World(" + name + ").dropItemNaturally#" + n + "(amount=" + handedOver + ")");

        if (teleportPlayerOnDropNumbers.contains(n) && teleportTarget != null)
        {
            teleportTarget.teleport(teleportToWorld, teleportToLocation);
        }
        if (kickPlayerOnDropNumbers.contains(n) && teleportTarget != null)
        {
            teleportTarget.setConnected(false);
        }
        if (throwErrorOnDropNumber.containsKey(n))
        {
            throw throwErrorOnDropNumber.get(n);
        }
        if (throwRuntimeOnDropNumber.containsKey(n))
        {
            throw throwRuntimeOnDropNumber.get(n);
        }
        if (returnNullOnDropNumbers.contains(n))
        {
            return null;
        }

        boolean valid = !cancelDropNumbers.contains(n);
        int heldAmount = handedOver;
        if (shrinkDropNumberTo.containsKey(n))
        {
            heldAmount = shrinkDropNumberTo.get(n);
        }
        if (growDropNumberTo.containsKey(n))
        {
            heldAmount = growDropNumberTo.get(n);
        }
        // The dropped entity's own stack, not a plugin-driven allocation.
        Item entity = new Item(valid, new ItemStack(stack.getType(), heldAmount, false));
        if (nullStackOnDropNumbers.contains(n))
        {
            entity.setReturnNullStack(true);
        }
        return entity;
    }
}
