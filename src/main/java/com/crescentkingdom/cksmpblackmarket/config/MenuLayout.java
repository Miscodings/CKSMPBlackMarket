package com.crescentkingdom.cksmpblackmarket.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Menus/bidding.yml parsed into fixed layouts. The menu has two phases sharing one inventory size: a
 * {@code preview} (item shown, bidding not open yet) and {@code bidding}.
 */
public record MenuLayout(int size, Button fill, Phase bidding, Phase preview, NavigableMap<Double, Material> bidMaterials, Button leading) {

    /** {@code function} is one of reward / clock / bid / close; {@code material} may be null for reward. */
    public record Button(String function, int slot, Material material, String itemModel, String name, List<String> lore) {}

    public record Phase(String title, List<Button> buttons) {}

    /** Material of the bid button for a given next-bid amount: the highest tier at or below it. */
    public Material bidMaterial(double nextBid, Material fallback) {
        var entry = bidMaterials.floorEntry(nextBid);
        return entry == null ? fallback : entry.getValue();
    }

    public static MenuLayout parse(FileConfiguration yaml, Logger log) {
        ConfigurationSection menu = yaml.getConfigurationSection("menu");
        if (menu == null) throw new IllegalStateException("Menus/bidding.yml is missing the 'menu' section");

        int size = Math.max(1, Math.min(6, menu.getInt("rows", 6))) * 9;

        ConfigurationSection fillSection = menu.getConfigurationSection("fill");
        Button fill = fillSection == null ? null : button(fillSection, "fill", -1, log);

        ConfigurationSection leadingSection = menu.getConfigurationSection("leading");
        Button leading = leadingSection == null ? null : button(leadingSection, "leading", -1, log);

        NavigableMap<Double, Material> materials = new TreeMap<>();
        ConfigurationSection tiers = menu.getConfigurationSection("bid-materials");
        if (tiers != null) {
            for (String key : tiers.getKeys(false)) {
                Material material = Material.matchMaterial(String.valueOf(tiers.getString(key)));
                try {
                    if (material == null) throw new IllegalArgumentException("unknown material");
                    materials.put(Double.parseDouble(key), material);
                } catch (IllegalArgumentException err) {
                    log.warning("Ignoring bid-materials entry '" + key + "': " + err.getMessage());
                }
            }
        }

        return new MenuLayout(size, fill,
                phase(menu.getConfigurationSection("bidding"), "Black Market", size, log),
                phase(menu.getConfigurationSection("preview"), "Black Market", size, log),
                materials, leading);
    }

    private static Phase phase(ConfigurationSection section, String fallbackTitle, int size, Logger log) {
        if (section == null) return new Phase(fallbackTitle, List.of());

        List<Button> buttons = new ArrayList<>();
        ConfigurationSection items = section.getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(key);
                if (item == null) continue;

                int slot = item.getInt("slot", -1);
                if (slot < 0 || slot >= size) {
                    log.warning("Menu item '" + key + "' has slot " + slot + " outside 0-" + (size - 1) + ", skipping.");
                    continue;
                }

                Button button = button(item, item.getString("function", key), slot, log);
                if (button != null) buttons.add(button);
            }
        }

        return new Phase(section.getString("title", fallbackTitle), List.copyOf(buttons));
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
        } else if (!function.equals("reward") && !function.equals("bid")) {
            log.warning("Menu item '" + function + "' needs a material, skipping.");
            return null;
        }

        return new Button(function, slot, material, s.getString("item-model"), s.getString("name"), List.copyOf(s.getStringList("lore")));
    }
}
