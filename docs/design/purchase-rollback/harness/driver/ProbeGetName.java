package io.github.diegot4l.claimbarter;

import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.PlayerData;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ProbeGetName
{
    public static void main(String[] args)
    {
        BarterSettings settings = new BarterSettings(Material.IRON_INGOT, "iron ingots", 100, true, 0.5, 0);
        Logger logger = Logger.getLogger("probe");
        logger.setUseParentHandlers(false);
        RecordingHandler handler = new RecordingHandler();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);

        GriefPrevention gp = new GriefPrevention();
        DataStore store = new DataStore();
        gp.dataStore = store;
        GriefPrevention.instance = gp;

        PlayerData data = new PlayerData(500);
        store.setCachedPlayerData(data);

        PlayerInventory inv = new PlayerInventory();
        ItemStack stack = new ItemStack(settings.currency(), 64);
        inv.setSlot(0, stack);

        World world = new World("world");
        Player player = new Player("Steve", UUID.randomUUID(), world, inv);
        world.teleportTarget = player;
        player.getNameThrows = true;

        BarterService service = new BarterService(settings, logger);
        try
        {
            BarterService.Result r = service.buy(player, 2);
            System.out.println("RESULT: " + r);
        }
        catch (Throwable t)
        {
            System.out.println("THREW: " + t);
            t.printStackTrace();
        }
        for (RecordingHandler.Entry e : handler.entries)
        {
            System.out.println("[" + e.level + "] " + e.message);
        }
    }
}
