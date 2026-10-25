package com.crescentkingdom.cksmpblackmarket.config;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.sound.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Everything reloadable, bundled so a reload is one volatile swap. */
public record Config(Settings settings, Lang lang, Pool pool, MenuLayout bidding) {

    /** Plays a configured sound to the audience; silently ignores disabled / unknown keys. */
    public void play(Audience audience, String soundKey) {
        Sound sound = settings.sound(soundKey);
        if (sound != null) audience.playSound(sound, Sound.Emitter.self());
    }

    /** Prizes live in Postgres (see PrizeService), so a file reload carries the current pool over unchanged. */
    public static Config load(CKSMPBlackMarket plugin, Pool pool) {
        FileConfiguration config = yaml(plugin, "config.yml", true);
        FileConfiguration lang = yaml(plugin, "Lang/messages.yml", true);
        FileConfiguration menu = yaml(plugin, "Menus/bidding.yml", false);

        return new Config(
                Settings.parse(config, plugin.getLogger()),
                Lang.parse(lang),
                pool,
                MenuLayout.parse(menu, plugin.getLogger())
        );
    }

    public Config withPool(Pool newPool) {
        return new Config(settings, lang, newPool, bidding);
    }

    /**
     * Loads the on-disk file. With {@code mergeDefaults} the bundled copy backs it, so keys added in a
     * newer build never go missing (not used for items.yml or the menu: entries an admin deleted must stay deleted).
     */
    public static FileConfiguration yaml(CKSMPBlackMarket plugin, String path, boolean mergeDefaults) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.exists()) plugin.saveResource(path, false);

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (!mergeDefaults) return yaml;

        try (InputStream bundled = plugin.getResource(path)) {
            if (bundled != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
                yaml.options().copyDefaults(true);
            }
        } catch (java.io.IOException err) {
            plugin.getLogger().warning("Could not read bundled " + path + ": " + err.getMessage());
        }

        return yaml;
    }
}
