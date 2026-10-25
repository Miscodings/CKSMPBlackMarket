package com.crescentkingdom.cksmpblackmarket.config;

import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry.Display;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry.ItemSpec;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/** The parsed item pool. Immutable; swapped wholesale on reload. */
public final class Pool {

    private final Map<String, PoolEntry> entries;

    private Pool(Map<String, PoolEntry> entries) {
        this.entries = entries;
    }

    public PoolEntry get(String id) {
        return entries.get(id);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Weighted draw of up to {@code count} distinct entry ids. */
    public List<String> draw(int count, Random random) {
        List<PoolEntry> remaining = new ArrayList<>(entries.values());
        List<String> picked = new ArrayList<>(Math.min(count, remaining.size()));

        while (picked.size() < count && !remaining.isEmpty()) {
            int total = 0;
            for (PoolEntry entry : remaining) total += entry.weight();

            int roll = random.nextInt(total);
            for (int i = 0; i < remaining.size(); i++) {
                roll -= remaining.get(i).weight();
                if (roll < 0) {
                    picked.add(remaining.remove(i).id());
                    break;
                }
            }
        }

        return picked;
    }

    public static Pool parse(FileConfiguration config, Logger log) {
        Map<String, PoolEntry> entries = new LinkedHashMap<>();
        ConfigurationSection root = config.getConfigurationSection("pool");
        if (root == null) return new Pool(entries);

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;

            List<ItemSpec> items = parseItems(section.getMapList("reward.items"), id, log);
            List<String> commands = section.getStringList("reward.commands");
            if (items.isEmpty() && commands.isEmpty()) {
                log.warning("Pool entry '" + id + "' has no reward.items or reward.commands, skipping.");
                continue;
            }

            int weight = section.getInt("weight", 1);
            double startBid = section.getDouble("start-bid", 0);
            if (weight < 1 || startBid <= 0) {
                log.warning("Pool entry '" + id + "' needs weight >= 1 and start-bid > 0, skipping.");
                continue;
            }

            entries.put(id, new PoolEntry(
                    id, weight, startBid,
                    Math.max(0.01, section.getDouble("min-increment", 1)),
                    Math.max(0, section.getDouble("increment-percent", 0)),
                    parseDisplay(section.getConfigurationSection("display"), items, id, log),
                    List.copyOf(items), List.copyOf(commands)
            ));
        }

        return new Pool(Map.copyOf(entries));
    }

    private static Display parseDisplay(ConfigurationSection section, List<ItemSpec> items, String id, Logger log) {
        if (section == null) return new Display(null, List.of(), null);

        ItemSpec icon = null;
        if (section.contains("cksmp-item") || section.contains("material")) {
            icon = parseItem(section.getString("cksmp-item"), section.getString("material"), 1, id, log);
        }

        return new Display(section.getString("name"), List.copyOf(section.getStringList("lore")), icon);
    }

    private static List<ItemSpec> parseItems(List<Map<?, ?>> raw, String id, Logger log) {
        List<ItemSpec> items = new ArrayList<>(raw.size());

        for (Map<?, ?> map : raw) {
            Object cksmp = map.get("cksmp-item");
            Object material = map.get("material");
            Object amount = map.get("amount");

            ItemSpec spec = parseItem(
                    cksmp == null ? null : cksmp.toString(),
                    material == null ? null : material.toString(),
                    amount instanceof Number number ? Math.max(1, number.intValue()) : 1,
                    id, log
            );
            if (spec != null) items.add(spec);
        }

        return items;
    }

    private static ItemSpec parseItem(String cksmpItem, String materialName, int amount, String id, Logger log) {
        if (cksmpItem != null) return new ItemSpec(cksmpItem.toLowerCase(), null, amount);

        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            log.warning("Pool entry '" + id + "': unknown item material '" + materialName + "'.");
            return null;
        }

        return new ItemSpec(null, material, amount);
    }
}
