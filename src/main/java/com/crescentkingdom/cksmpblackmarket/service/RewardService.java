package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry.ItemSpec;
import com.crescentkingdom.cksmpblackmarket.storage.PrizeCodec;
import com.crescentkingdom.cksmpcore.Managers.PlayerData.Data.PlayerData;
import com.crescentkingdom.cksmpcore.Managers.PlayerData.Stash.StashManager;
import com.crescentkingdom.cksmpitems.API.CKSMPItemsAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Turns pool entries into ItemStacks / names and hands prizes to winners. */
public final class RewardService {

    private final CKSMPBlackMarket plugin;
    private final Map<String, Component> names = new ConcurrentHashMap<>();
    private final Map<String, ItemStack> icons = new ConcurrentHashMap<>();
    private StashManager stashManager;

    public RewardService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    /** Drops cached names; call on reload and when CKSMPItems finishes loading its catalogue. */
    public void invalidate() {
        names.clear();
        icons.clear();
    }

    /** Display name of the prize as a component, cached per entry. */
    public Component name(Config cfg, String entryId, PoolEntry entry) {
        if (entry == null) return Component.text(entryId);

        return names.computeIfAbsent(entryId, id -> {
            if (entry.display().name() != null) return cfg.lang().format(entry.display().name());

            ItemSpec spec = entry.display().icon() != null ? entry.display().icon() : entry.items().isEmpty() ? null : entry.items().getFirst();
            return spec == null ? Component.text(id) : build(spec).effectiveName();
        });
    }

    /** Fresh icon stack for the menu (display icon, else the first reward item, else a barrier). */
    public ItemStack icon(PoolEntry entry) {
        // Decoding a serialized stack is the expensive part; menus ask for icons every refresh.
        return icons.computeIfAbsent(entry.id(), id -> {
            ItemSpec spec = entry.display().icon() != null ? entry.display().icon() : entry.items().isEmpty() ? null : entry.items().getFirst();
            ItemStack icon = spec == null ? ItemStack.of(Material.BARRIER) : build(spec);
            icon.setAmount(1);
            return icon;
        }).clone();
    }

    /** Fresh reward stacks, split to legal stack sizes. */
    public List<ItemStack> buildItems(PoolEntry entry) {
        List<ItemStack> out = new ArrayList<>(entry.items().size());

        for (ItemSpec spec : entry.items()) {
            ItemStack stack = build(spec);

            // A serialized stack already carries its own amount; never re-split what the admin set up.
            if (spec.stack() != null) {
                out.add(stack);
                continue;
            }

            int max = Math.max(1, stack.getMaxStackSize());
            int remaining = spec.amount();

            while (remaining > 0) {
                ItemStack part = stack.clone();
                part.setAmount(Math.min(max, remaining));
                out.add(part);
                remaining -= part.getAmount();
            }
        }

        return out;
    }

    /** Gives the prize. Must run on the player's thread. */
    public void give(Player player, PoolEntry entry) {
        Config cfg = plugin.config();

        if (!entry.items().isEmpty()) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(buildItems(entry).toArray(ItemStack[]::new));

            if (!leftover.isEmpty()) {
                stash(player, new ArrayList<>(leftover.values()));
                cfg.lang().send(player, "payout.stashed");
            }
        }

        for (String command : entry.commands()) {
            String resolved = command.replace("<player>", player.getName());
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved));
        }

        cfg.lang().send(player, "payout.reward", Placeholder.component("item", name(cfg, entry.id(), entry)));
    }

    private void stash(Player player, ArrayList<ItemStack> items) {
        PlayerData profile = plugin.core().getPlayerAPI().getProfile(player.getUniqueId());

        if (profile == null) {
            // Core has not loaded this player (should not happen after PlayerDataLoadEvent); never lose the prize.
            for (ItemStack item : items) player.getWorld().dropItemNaturally(player.getLocation(), item);
            return;
        }

        if (stashManager == null) stashManager = new StashManager(plugin.core().getPluginInstance());
        stashManager.createStash(player, profile, items);
    }

    private ItemStack build(ItemSpec spec) {
        if (spec.stack() != null) return PrizeCodec.decodeStack(spec.stack());
        if (spec.material() != null) return ItemStack.of(spec.material(), spec.amount());

        ItemStack item = CKSMPItemsAPI.getInstance().getItemManager().getItem(spec.cksmpItem());
        if (item == null) {
            plugin.getLogger().warning("CKSMPItems has no item '" + spec.cksmpItem() + "'; using a barrier placeholder.");
            return ItemStack.of(Material.BARRIER, spec.amount());
        }

        ItemStack copy = item.clone();
        copy.setAmount(spec.amount());
        return copy;
    }
}
