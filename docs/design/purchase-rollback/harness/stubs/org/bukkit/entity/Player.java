package org.bukkit.entity;

import harness.support.CallLog;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Harness stand-in for org.bukkit.entity.Player, narrowed to what
 * BarterService reads: inventory, identity, world/location (re-read per drop,
 * so getLocation() always returns a fresh object), connection state, and the
 * message channel.
 */
public class Player implements CommandSender
{
    private final String name;
    private final UUID uuid;
    private final PlayerInventory inventory;
    private World world;
    private double x;
    private double y;
    private double z;
    private boolean connected = true;

    public boolean getNameThrows = false;

    public final List<Component> messagesReceived = new ArrayList<>();

    public Player(String name, UUID uuid, World world, PlayerInventory inventory)
    {
        this.name = name;
        this.uuid = uuid;
        this.world = world;
        this.inventory = inventory;
    }

    public PlayerInventory getInventory()
    {
        return inventory;
    }

    public String getName()
    {
        if (getNameThrows)
        {
            throw new Error("harness: Player.getName throws");
        }
        return name;
    }

    public UUID getUniqueId()
    {
        return uuid;
    }

    public World getWorld()
    {
        return world;
    }

    public Location getLocation()
    {
        return new Location(world, x, y, z);
    }

    /** Harness-only: models a listener teleporting the player mid-payout. */
    public void teleport(World newWorld, Location newLocation)
    {
        this.world = newWorld;
        this.x = newLocation.getX();
        this.y = newLocation.getY();
        this.z = newLocation.getZ();
    }

    public boolean isConnected()
    {
        return connected;
    }

    public boolean isOnline()
    {
        return connected;
    }

    public void setConnected(boolean connected)
    {
        this.connected = connected;
    }

    @Override
    public void sendMessage(Component message)
    {
        messagesReceived.add(message);
        CallLog.record("Player.sendMessage(" + message.rendered() + ")");
    }
}
