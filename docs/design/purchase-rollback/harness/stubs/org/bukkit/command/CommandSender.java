package org.bukkit.command;

import net.kyori.adventure.text.Component;

/** Narrowed to the one method BarterCommand/Messages actually calls. */
public interface CommandSender
{
    void sendMessage(Component message);
}
