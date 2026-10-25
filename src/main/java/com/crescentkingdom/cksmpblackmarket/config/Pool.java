package com.crescentkingdom.cksmpblackmarket.config;

import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry.Display;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry.ItemSpec;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/** The prize pool as loaded from Postgres. Immutable; every edit produces a new instance that is swapped in. */
public final class Pool {

    private static final Pool EMPTY = new Pool(Map.of(), List.of());

    private final Map<String, PoolEntry> byId;
    private final List<PoolEntry> sorted;

    private Pool(Map<String, PoolEntry> byId, List<PoolEntry> sorted) {
        this.byId = byId;
        this.sorted = sorted;
    }

    public static Pool empty() {
        return EMPTY;
    }

    public static Pool of(Collection<PoolEntry> entries) {
        Map<String, PoolEntry> map = new HashMap<>();
        for (PoolEntry entry : entries) map.put(entry.id(), entry);

        List<PoolEntry> sorted = new ArrayList<>(map.values());
        sorted.sort(Comparator.comparing(PoolEntry::id));
        return new Pool(Map.copyOf(map), List.copyOf(sorted));
    }

    /** Disabled prizes are still resolvable (a live round may be using one), just never drawn. */
    public PoolEntry get(String id) {
        return byId.get(id);
    }

    public List<PoolEntry> all() {
        return sorted;
    }

    public Pool with(PoolEntry entry) {
        Map<String, PoolEntry> map = new HashMap<>(byId);
        map.put(entry.id(), entry);
        return of(map.values());
    }

    public Pool without(String id) {
        Map<String, PoolEntry> map = new HashMap<>(byId);
        map.remove(id);
        return of(map.values());
    }

    /** Weighted draw of up to {@code count} distinct enabled entry ids. */
    public List<String> draw(int count, Random random) {
        List<PoolEntry> remaining = new ArrayList<>();
        for (PoolEntry entry : sorted) if (entry.enabled()) remaining.add(entry);

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

    /** Parses the legacy items.yml format; used only to seed an empty database. */
    public static List<PoolEntry> parseYaml(FileConfiguration config, Logger log) {
        List<PoolEntry> entries = new ArrayList<>();
        ConfigurationSection root = config.getConfigurationSection("pool");
        if (root == null) return entries;

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) continue;

            List<ItemSpec> items = parseItems(section.getMapList("reward.items"), id, log);
            List<String> commands = section.getStringList("reward.commands");
            if (items.isEmpty() && commands.isEmpty()) {
                log.warning("Pool entry '" + id + "' has no reward.items or reward.commands, skipping.");
                continue;
            }

            int weight = section.getInt("weight", PoolEntry.DEFAULT_WEIGHT);
            double startBid = section.getDouble("start-bid", 0);
            if (weight < 1 || startBid <= 0) {
                log.warning("Pool entry '" + id + "' needs weight >= 1 and start-bid > 0, skipping.");
                continue;
            }

            entries.add(new PoolEntry(
                    id, true, weight, startBid,
                    Math.max(0.01, section.getDouble("min-increment", 1)),
                    Math.max(0, section.getDouble("increment-percent", 0)),
                    parseDisplay(section.getConfigurationSection("display"), id, log),
                    List.copyOf(items), List.copyOf(commands)
            ));
        }

        return entries;
    }

    private static Display parseDisplay(ConfigurationSection section, String id, Logger log) {
        if (section == null) return Display.none();

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
        if (cksmpItem != null) return new ItemSpec(cksmpItem.toLowerCase(), null, amount, null);

        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            log.warning("Pool entry '" + id + "': unknown item material '" + materialName + "'.");
            return null;
        }

        return new ItemSpec(null, material, amount, null);
    }
}
