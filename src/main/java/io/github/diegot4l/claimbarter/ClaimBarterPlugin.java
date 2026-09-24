package io.github.diegot4l.claimbarter;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Trades a configured item for GriefPrevention claim blocks.
 *
 * <p>There is no balance and no Vault provider. The item leaves the player's
 * inventory and the claim blocks land in GriefPrevention's bonus pool, which
 * is the only pool an outside plugin should write to.
 */
public final class ClaimBarterPlugin extends JavaPlugin
{
    private BarterSettings settings;
    private Messages messages;
    private BarterService service;

    @Override
    public void onEnable()
    {
        saveDefaultConfig();

        if (GriefPrevention.instance == null)
        {
            getLogger().severe("GriefPrevention is not available. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!load())
        {
            getLogger().severe("Refusing to start with an unusable configuration.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand command = getCommand("claimbarter");
        if (command == null)
        {
            getLogger().severe("The claimbarter command is missing from plugin.yml. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        BarterCommand handler = new BarterCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);

        getLogger().info(String.format(
                "Trading %d claim blocks per %s.", settings.blocksPerItem(), settings.currencyName()));
    }

    /** Rereads config.yml. Returns false when the file cannot be used. */
    boolean load()
    {
        reloadConfig();
        try
        {
            settings = BarterSettings.from(getConfig());
        }
        catch (InvalidSettingException badValue)
        {
            getLogger().severe(badValue.getMessage());
            return false;
        }
        messages = new Messages(getConfig().getConfigurationSection("messages"), getLogger());
        service = new BarterService(settings, getLogger());
        return true;
    }

    Messages messages()
    {
        return messages;
    }

    BarterService service()
    {
        return service;
    }
}
