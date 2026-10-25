package com.crescentkingdom.cksmpblackmarket.gui.editor;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.Lang;
import com.crescentkingdom.cksmpblackmarket.config.Settings;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import com.crescentkingdom.cksmpcore.Managers.GUI.MenuInventoryHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Settings of one prize. Numbers are adjusted with clicks, so nothing needs typing. */
final class PrizeDetailMenu implements MenuInventoryHolder {

    private static final int SIZE = 27;
    private static final int SLOT_ICON = 4;
    private static final int SLOT_WEIGHT = 10;
    private static final int SLOT_START = 11;
    private static final int SLOT_INCREMENT = 12;
    private static final int SLOT_PERCENT = 13;
    private static final int SLOT_ENABLED = 14;
    private static final int SLOT_REWARDS = 15;
    private static final int SLOT_COMMANDS = 16;
    private static final int SLOT_BACK = 18;
    private static final int SLOT_GIVE = 22;
    private static final int SLOT_DELETE = 26;
    private static final long DELETE_CONFIRM_MS = 5_000;

    private static final double MAX_MONEY = 1_000_000_000_000.0;
    private static final int MAX_WEIGHT = 1000;

    private final CKSMPBlackMarket plugin;
    private final EditorService editor;
    private final Player player;
    private final String prizeId;
    private final Inventory inventory;

    private long deleteArmedAt;

    PrizeDetailMenu(CKSMPBlackMarket plugin, EditorService editor, Player player, String prizeId) {
        this.plugin = plugin;
        this.editor = editor;
        this.player = player;
        this.prizeId = prizeId;
        this.inventory = Bukkit.createInventory(this, SIZE, plugin.config().lang().component("editor.detail.title"));

        render();
    }

    private void render() {
        Config cfg = plugin.config();
        Lang lang = cfg.lang();
        Settings s = cfg.settings();
        PoolEntry entry = cfg.pool().get(prizeId);

        if (entry == null) {
            lang.send(player, "editor.msg.gone");
            editor.openList(player, 0);
            return;
        }

        inventory.clear();
        ItemStack filler = MenuItems.plain(plugin, Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, filler);

        inventory.setItem(SLOT_ICON, MenuItems.decorate(plugin, plugin.rewards().icon(entry), List.of()));

        inventory.setItem(SLOT_WEIGHT, adjuster(lang, Material.ANVIL, "weight", Placeholder.unparsed("value", Integer.toString(entry.weight())), 1, 5));
        inventory.setItem(SLOT_START, adjuster(lang, Material.GOLD_INGOT, "start", Placeholder.component("value", Text.money(lang, entry.startBid())), s.editorBidStep(), s.editorShiftMultiplier()));
        inventory.setItem(SLOT_INCREMENT, adjuster(lang, Material.IRON_INGOT, "increment", Placeholder.component("value", Text.money(lang, entry.minIncrement())), s.editorIncrementStep(), s.editorShiftMultiplier()));
        inventory.setItem(SLOT_PERCENT, adjuster(lang, Material.COMPARATOR, "percent", Placeholder.unparsed("value", Text.plainMoney(entry.incrementPercent() * 100)), s.editorPercentStep() * 100, s.editorShiftMultiplier()));

        Component status = lang.component(entry.enabled() ? "editor.list.status-on" : "editor.list.status-off");
        inventory.setItem(SLOT_ENABLED, MenuItems.plain(plugin, entry.enabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                lang.component("editor.detail.enabled-name", Placeholder.component("status", status)),
                lang.components("editor.detail.enabled-lore")));

        inventory.setItem(SLOT_REWARDS, MenuItems.plain(plugin, Material.CHEST, lang.component("editor.detail.rewards-name"),
                lang.components("editor.detail.rewards-lore", Placeholder.unparsed("count", Integer.toString(entry.items().size())))));
        inventory.setItem(SLOT_COMMANDS, MenuItems.plain(plugin, Material.COMMAND_BLOCK, lang.component("editor.detail.commands-name"),
                lang.components("editor.detail.commands-lore", Placeholder.unparsed("count", Integer.toString(entry.commands().size())))));

        inventory.setItem(SLOT_BACK, MenuItems.plain(plugin, Material.ARROW, lang.component("editor.detail.back"), List.of()));
        inventory.setItem(SLOT_GIVE, MenuItems.plain(plugin, Material.EMERALD, lang.component("editor.detail.give-name"), lang.components("editor.detail.give-lore")));

        boolean armed = System.currentTimeMillis() - deleteArmedAt < DELETE_CONFIRM_MS;
        inventory.setItem(SLOT_DELETE, MenuItems.plain(plugin, Material.TNT,
                lang.component(armed ? "editor.detail.delete-confirm-name" : "editor.detail.delete-name"),
                lang.components("editor.detail.delete-lore")));
    }

    private ItemStack adjuster(Lang lang, Material material, String key, TagResolver value, double step, int multiplier) {
        List<Component> lore = new ArrayList<>(lang.components("editor.detail." + key + "-lore"));
        lore.addAll(lang.components("editor.detail.adjust-hint",
                Placeholder.unparsed("step", Text.plainMoney(step)),
                Placeholder.unparsed("multiplier", Integer.toString(multiplier))));

        return MenuItems.plain(plugin, material, lang.component("editor.detail." + key + "-name", value), lore);
    }

    @Override
    public void handleClick(Player clicker, InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) return;

        PoolEntry entry = plugin.config().pool().get(prizeId);
        if (entry == null) {
            render();
            return;
        }

        Settings s = plugin.config().settings();
        ClickType click = event.getClick();
        int direction = click == ClickType.LEFT || click == ClickType.SHIFT_LEFT ? 1 : click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT ? -1 : 0;
        int factor = click.isShiftClick() ? s.editorShiftMultiplier() : 1;

        switch (event.getSlot()) {
            case SLOT_WEIGHT -> {
                int weight = clamp(entry.weight() + direction * (click.isShiftClick() ? 5 : 1), 1, MAX_WEIGHT);
                update(entry, entry.withWeight(weight));
            }
            case SLOT_START -> update(entry, entry.withStartBid(money(entry.startBid() + direction * s.editorBidStep() * factor, 0.01)));
            case SLOT_INCREMENT -> update(entry, entry.withMinIncrement(money(entry.minIncrement() + direction * s.editorIncrementStep() * factor, 0.01)));
            case SLOT_PERCENT -> update(entry, entry.withIncrementPercent(percent(entry.incrementPercent() + direction * s.editorPercentStep() * factor)));
            case SLOT_ENABLED -> update(entry, entry.withEnabled(!entry.enabled()));
            case SLOT_REWARDS -> editor.openRewards(clicker, prizeId);
            case SLOT_BACK -> editor.show(clicker, new PrizeListMenu(plugin, editor, clicker, 0));
            case SLOT_GIVE -> give(clicker, entry);
            case SLOT_DELETE -> delete(clicker, entry);
            default -> { }
        }
    }

    private void update(PoolEntry before, PoolEntry after) {
        if (after.equals(before)) return;

        plugin.prizes().save(after);
        render();
    }

    private void give(Player clicker, PoolEntry entry) {
        for (ItemStack item : plugin.rewards().buildItems(entry)) {
            clicker.getInventory().addItem(item).values().forEach(left -> clicker.getWorld().dropItemNaturally(clicker.getLocation(), left));
        }

        plugin.config().lang().send(clicker, "editor.msg.given", Placeholder.unparsed("id", entry.id()));
    }

    /** Two clicks within a few seconds, so a stray click cannot remove a prize. */
    private void delete(Player clicker, PoolEntry entry) {
        Lang lang = plugin.config().lang();
        long now = System.currentTimeMillis();

        if (now - deleteArmedAt >= DELETE_CONFIRM_MS) {
            deleteArmedAt = now;
            lang.send(clicker, "editor.msg.delete-armed", Placeholder.unparsed("id", entry.id()));
            render();
            return;
        }

        plugin.prizes().delete(entry.id());
        lang.send(clicker, "editor.msg.deleted", Placeholder.unparsed("id", entry.id()));
        editor.show(clicker, new PrizeListMenu(plugin, editor, clicker, 0));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double money(double value, double min) {
        double bounded = Math.max(min, Math.min(MAX_MONEY, value));
        return BigDecimal.valueOf(bounded).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double percent(double value) {
        double bounded = Math.max(0, Math.min(1, value));
        return BigDecimal.valueOf(bounded).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    @Override
    public String getName() {
        return "blackmarket-editor-detail";
    }

    @Override
    public int getSize() {
        return SIZE;
    }

    @Override
    public void handleDrag(Player dragger, InventoryDragEvent event) {
        event.setCancelled(true);
    }

    @Override
    public void handleClose(Player closer, InventoryCloseEvent event) { }

    @Override
    public boolean isBottomInventoryClickable() {
        return false;
    }

    @Override
    public boolean cancelEvent() {
        return true;
    }
}
