package com.crescentkingdom.cksmpblackmarket.gui;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.MenuLayout;
import com.crescentkingdom.cksmpblackmarket.config.MenuLayout.Button;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.service.RoundText;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import com.crescentkingdom.cksmpcore.Managers.GUI.MenuInventoryHolder;
import com.crescentkingdom.cksmpcore.Utils.ItemUtils;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The bidding menu, hosted through CKSMPCore's GUIManager so Core's click listener already cancels
 * stray clicks, drags and offhand swaps. Rendering is incremental: static buttons are placed once,
 * the prize icon only when the round changes, and the two live buttons only when their text changes.
 */
public final class BiddingGui implements MenuInventoryHolder {

    private final CKSMPBlackMarket plugin;
    private final Player player;
    private final MenuLayout layout;
    private final Inventory inventory;
    private final String[] functionBySlot;
    private final ItemUtils itemUtils = new ItemUtils();

    private long renderedRound = Long.MIN_VALUE;
    private long renderedKey = Long.MIN_VALUE;

    public BiddingGui(CKSMPBlackMarket plugin, Player player, Config cfg) {
        this.plugin = plugin;
        this.player = player;
        this.layout = cfg.bidding();
        this.functionBySlot = new String[layout.size()];
        this.inventory = Bukkit.createInventory(this, layout.size(), cfg.lang().format(layout.title()));

        if (layout.fill() != null) {
            ItemStack filler = staticItem(cfg, layout.fill());
            for (int slot = 0; slot < layout.size(); slot++) inventory.setItem(slot, filler);
        }

        for (Button button : layout.buttons()) functionBySlot[button.slot()] = button.function();
    }

    /** Re-renders what changed. Must run on the player's thread. */
    public void render() {
        Config cfg = plugin.config();
        RoundView round = plugin.state().current();
        long now = System.currentTimeMillis();
        boolean live = round != null && round.isActiveAt(now);

        // One long that changes whenever any visible text would: round, bids, whole seconds left, live flag.
        // Idle keys on the wall-clock second so the "next event" countdown keeps ticking.
        long key = live ? round.id() * 1_000_003L + round.bidCount() * 10_007L + Text.secondsUntil(round.endsAt(), now) : -(now / 1000) - 1;
        if (key == renderedKey) return;
        renderedKey = key;

        if (!live) {
            renderIdle(cfg, now);
            return;
        }

        PoolEntry entry = cfg.pool().get(round.entryId());
        if (entry == null) {
            renderIdle(cfg, now);
            return;
        }

        TagResolver tags = TagResolver.resolver(
                RoundText.resolver(plugin, cfg, round, now),
                RoundText.lazy("balance", () -> Text.money(cfg.lang(), plugin.economy().getBalance(player))));

        for (Button button : layout.buttons()) {
            switch (button.function()) {
                case "reward" -> {
                    if (renderedRound != round.id()) inventory.setItem(button.slot(), rewardIcon(cfg, entry, button, tags));
                }
                case "info", "bid" -> inventory.setItem(button.slot(), render(cfg, button, tags));
                default -> {
                    if (renderedRound == Long.MIN_VALUE) inventory.setItem(button.slot(), staticItem(cfg, button));
                }
            }
        }

        renderedRound = round.id();
    }

    private void renderIdle(Config cfg, long now) {
        renderedRound = Long.MIN_VALUE;

        long next = cfg.settings().firstSlotAfter(now);
        Component time = next < 0 ? cfg.lang().component("menu.idle-none") : Text.time(cfg.lang(), (next - now) / 1000);
        TagResolver tags = Placeholder.component("time", time);

        for (Button button : layout.buttons()) {
            switch (button.function()) {
                case "reward" -> inventory.setItem(button.slot(), item(Material.BARRIER, null,
                        cfg.lang().component("menu.idle-name"), cfg.lang().components("menu.idle-lore", tags)));
                case "info", "bid" -> inventory.setItem(button.slot(), item(Material.GRAY_STAINED_GLASS_PANE, null, Component.text(" "), List.of()));
                default -> inventory.setItem(button.slot(), staticItem(cfg, button));
            }
        }
    }

    /** Placeholder-free buttons (filler, close) go through CKSMPCore's own menu item builder. */
    private ItemStack staticItem(Config cfg, Button button) {
        Component name = button.name() == null ? Component.empty() : cfg.lang().format(button.name());
        ItemStack item = itemUtils.menuItem(plugin.core().getPluginInstance(), button.material(), 1, name, button.lore(), false);
        if (button.itemModel() != null) item.setData(DataComponentTypes.ITEM_MODEL, Key.key(button.itemModel()));
        return item;
    }

    private ItemStack render(Config cfg, Button button, TagResolver tags) {
        Component name = button.name() == null ? Component.empty() : cfg.lang().format(button.name(), tags);
        List<Component> lore = new ArrayList<>(button.lore().size());
        for (String line : button.lore()) lore.add(cfg.lang().format(line, tags));

        return item(button.material(), button.itemModel(), name, lore);
    }

    private ItemStack rewardIcon(Config cfg, PoolEntry entry, Button button, TagResolver tags) {
        ItemStack icon = plugin.rewards().icon(entry);

        Component name = plugin.rewards().name(cfg, entry.id(), entry);
        icon.setData(DataComponentTypes.CUSTOM_NAME, name);

        List<Component> lore = new ArrayList<>();
        if (!entry.display().lore().isEmpty()) {
            for (String line : entry.display().lore()) lore.add(cfg.lang().format(line));
        } else {
            ItemLore existing = icon.getData(DataComponentTypes.LORE);
            if (existing != null) lore.addAll(existing.lines());
        }
        for (String line : button.lore()) lore.add(cfg.lang().format(line, tags));

        icon.setData(DataComponentTypes.LORE, ItemLore.lore().addLines(lore).build());
        mark(icon);
        return icon;
    }

    private ItemStack item(Material material, String itemModel, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.ITEM_NAME, name);
        if (!lore.isEmpty()) item.setData(DataComponentTypes.LORE, ItemLore.lore().addLines(lore).build());
        if (itemModel != null) item.setData(DataComponentTypes.ITEM_MODEL, Key.key(itemModel));
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS));
        mark(item);
        return item;
    }

    /** Tags the stack so CKSMPCore's click listener treats it as inert menu furniture. */
    private void mark(ItemStack item) {
        NamespacedKey menuKey = plugin.core().getGUIManager().getItemMenuKey();
        item.editPersistentDataContainer(pdc -> pdc.set(menuKey, PersistentDataType.BOOLEAN, true));
    }

    // ---------------------------------------------------------------- MenuInventoryHolder

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    @Override
    public String getName() {
        return "blackmarket-bidding";
    }

    @Override
    public int getSize() {
        return layout.size();
    }

    @Override
    public void handleClick(Player clicker, InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) return;

        int slot = event.getSlot();
        String function = slot >= 0 && slot < functionBySlot.length ? functionBySlot[slot] : null;
        if (function == null) return;

        switch (function) {
            case "bid" -> plugin.bids().bid(clicker);
            case "close" -> clicker.closeInventory();
            default -> { }
        }
    }

    @Override
    public void handleDrag(Player dragger, InventoryDragEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void handleClose(Player closer, InventoryCloseEvent event) {
        plugin.gui().remove(closer.getUniqueId());
    }

    @Override
    public boolean isBottomInventoryClickable() {
        return false;
    }

    @Override
    public boolean cancelEvent() {
        return true;
    }
}
