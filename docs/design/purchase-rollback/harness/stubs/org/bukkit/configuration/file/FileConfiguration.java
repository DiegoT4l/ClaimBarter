package org.bukkit.configuration.file;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Just enough of org.bukkit.configuration.file.FileConfiguration for
 * BarterSettings.from(FileConfiguration) to compile and, if the harness
 * chooses to exercise it, run against a Map-backed root section loaded from
 * the shipped config.yml.
 */
public class FileConfiguration extends ConfigurationSection
{
}
