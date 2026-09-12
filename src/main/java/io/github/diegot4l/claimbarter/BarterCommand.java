package io.github.diegot4l.claimbarter;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Handles /claimbarter.
 *
 * <p>One rule keeps the arguments predictable: the amount is always what the
 * player hands over. Buying is priced in items, selling in claim blocks.
 */
final class BarterCommand implements CommandExecutor, TabCompleter
{
    private final ClaimBarterPlugin plugin;

    BarterCommand(ClaimBarterPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        Messages messages = plugin.messages();

        if (args.length == 0)
        {
            messages.send(sender, "usage");
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);

        if (subcommand.equals("reload"))
        {
            if (!sender.hasPermission("claimbarter.reload"))
            {
                messages.send(sender, "no-permission");
                return true;
            }
            if (plugin.load())
            {
                // Re-read: load() replaced the Messages instance.
                plugin.messages().send(sender, "reloaded");
            }
            else
            {
                sender.sendMessage("ClaimBarter: configuration is not usable, see the console.");
            }
            return true;
        }

        if (!(sender instanceof Player player))
        {
            messages.send(sender, "players-only");
            return true;
        }

        switch (subcommand)
        {
            case "info" ->
            {
                report(player, plugin.service().info(player));
                return true;
            }
            case "buy" ->
            {
                if (!player.hasPermission("claimbarter.buy"))
                {
                    messages.send(player, "no-permission");
                    return true;
                }
                Integer amount = parseAmount(player, args);
                if (amount != null)
                {
                    report(player, plugin.service().buy(player, amount));
                }
                return true;
            }
            case "sell" ->
            {
                if (!player.hasPermission("claimbarter.sell"))
                {
                    messages.send(player, "no-permission");
                    return true;
                }
                Integer amount = parseAmount(player, args);
                if (amount != null)
                {
                    report(player, plugin.service().sell(player, amount));
                }
                return true;
            }
            default ->
            {
                messages.send(player, "usage");
                return true;
            }
        }
    }

    /** Returns null and messages the player when the argument is unusable. */
    private Integer parseAmount(Player player, String[] args)
    {
        if (args.length < 2)
        {
            plugin.messages().send(player, "usage");
            return null;
        }
        try
        {
            int amount = Integer.parseInt(args[1]);
            if (amount <= 0)
            {
                plugin.messages().send(player, "invalid-amount");
                return null;
            }
            return amount;
        }
        catch (NumberFormatException notANumber)
        {
            plugin.messages().send(player, "invalid-amount");
            return null;
        }
    }

    private void report(Player player, BarterService.Result result)
    {
        plugin.messages().send(player, result.messageKey(), result.placeholders());
    }

    /**
     * Offers only the subcommands the sender may actually run.
     *
     * <p>Completing one that {@link #onCommand} goes on to refuse advertises a
     * command and then denies it, which reads as a bug on any server that
     * restricts trading to a rank.
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args)
    {
        if (args.length == 1)
        {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("claimbarter.buy"))
            {
                options.add("buy");
            }
            if (sender.hasPermission("claimbarter.sell"))
            {
                options.add("sell");
            }
            options.add("info");
            if (sender.hasPermission("claimbarter.reload"))
            {
                options.add("reload");
            }
            return options.stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }
}
