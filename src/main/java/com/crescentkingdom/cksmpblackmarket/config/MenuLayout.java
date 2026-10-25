package com.crescentkingdom.cksmpblackmarket.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.logging.Logger;

/** Menus/bidding.yml parsed into a fixed layout. */
public record MenuLayout(String title, int size, Button fill, List<Button> buttons) {

    /** {@code function} is one of reward / info / bid / close; {@code material} may be null for reward. */
    public record Button(String function, int slot, Material material, String itemModel, String name, List<String> lore) {}

    public static MenuLayout parse(FileConfiguration yaml, Logger log) {
        ConfigurationSection menu = yaml.getConfigurationSection("menu");
        if (menu == null) throw new IllegalStateException("Menus/bidding.yml is missing the 'menu' section");

        int rows = Math.max(1, Math.min(6, menu.getInt("size", 27) / 9));
        int size = rows * 9;

        Button fill = null;
        ConfigurationSection fillSection = menu.getConfigurationSection("fill");
        if (fillSection != null) fill = button(fillSection, "fill", -1, log);

        List<Button> buttons = new java.util.ArrayList<>();
        ConfigurationSection items = menu.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(key);
                if (section == null) continue;

                int slot = section.getInt("slot", -1);
                if (slot < 0 || slot >= size) {
                    log.warning("Menu item '" + key + "' has slot " + slot + " outside 0-" + (size - 1) + ", skipping.");
                    continue;
                }

                Button button = button(section, section.getString("function", key), slot, log);
                if (button != null) buttons.add(button);
            }
        }

        return new MenuLayout(menu.getString("title", "Black Market"), size, fill, List.copyOf(buttons));
    }

    private static Button button(ConfigurationSection s, String function, int slot, Logger log) {
        Material material = null;
        String raw = s.getString("material");
        if (raw != null) {
            material = Material.matchMaterial(raw);
            if (material == null || !material.isItem()) {
                log.warning("Menu item '" + function + "': unknown material '" + raw + "', skipping.");
                return null;
            }
        } else if (!function.equals("reward")) {
            log.warning("Menu item '" + function + "' needs a material, skipping.");
            return null;
        }

        return new Button(function, slot, material, s.getString("item-model"), s.getString("name"), List.copyOf(s.getStringList("lore")));
    }
}
