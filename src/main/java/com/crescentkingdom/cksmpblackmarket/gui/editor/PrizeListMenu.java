package com.crescentkingdom.cksmpblackmarket.gui.editor;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.Lang;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import com.crescentkingdom.cksmpcore.Managers.GUI.MenuInventoryHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * The prize pool as a chest. Each prize shows as the item itself. Drop an item on an empty slot (or
 * shift-click one from your inventory) to add a prize; see EditorListener for that part.
 */
final class PrizeListMenu implements MenuInventoryHolder {

    static final int SIZE = 54;
    static final int PAGE_SIZE = 45;
    static final int SLOT_PREV = 45;
    static final int SLOT_HELP = 47;
    static final int SLOT_ADD = 49;
    static final int SLOT_PAGE = 50;
    static final int SLOT_CLOSE = 52;
    static final int SLOT_NEXT = 53;

    private final CKSMPBlackMarket plugin;
    private final EditorService editor;
    private final Player player;
    private final Inventory inventory;
    private final String[] idBySlot = new String[PAGE_SIZE];

    private int page;

    PrizeListMenu(CKSMPBlackMarket plugin, EditorService editor, Player player, int page) {
        this.plugin = plugin;
        this.editor = editor;
        this.player = player;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, SIZE, plugin.config().lang().component("editor.list.title"));

        render();
    }

    void render() {
        Config cfg = plugin.config();
        Lang lang = cfg.lang();
        List<PoolEntry> all = cfg.pool().all();

        int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));

        inventory.clear();
        Arrays.fill(idBySlot, null);

        for (int i = 0; i < PAGE_SIZE; i++) {
            int index = page * PAGE_SIZE + i;
            if (index >= all.size()) break;

            PoolEntry entry = all.get(index);
            idBySlot[i] = entry.id();

            List<Component> lore = lang.components("editor.list.prize-lore",
                    Placeholder.unparsed("weight", Integer.toString(entry.weight())),
                    Placeholder.component("start-bid", Text.money(lang, entry.startBid())),
                    Placeholder.component("status", lang.component(entry.enabled() ? "editor.list.status-on" : "editor.list.status-off")));

            inventory.setItem(i, MenuItems.decorate(plugin, plugin.rewards().icon(entry), lore));
        }

        ItemStack filler = MenuItems.plain(plugin, Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = PAGE_SIZE; slot < SIZE; slot++) inventory.setItem(slot, filler);

        if (page > 0) inventory.setItem(SLOT_PREV, MenuItems.plain(plugin, Material.ARROW, lang.component("editor.list.prev"), List.of()));
        if (page < pages - 1) inventory.setItem(SLOT_NEXT, MenuItems.plain(plugin, Material.ARROW, lang.component("editor.list.next"), List.of()));

        inventory.setItem(SLOT_HELP, MenuItems.plain(plugin, Material.BOOK, lang.component("editor.list.help-name"), lang.components("editor.list.help-lore")));
        inventory.setItem(SLOT_ADD, MenuItems.plain(plugin, Material.HOPPER, lang.component("editor.list.add-name"), lang.components("editor.list.add-lore")));
        inventory.setItem(SLOT_PAGE, MenuItems.plain(plugin, Material.PAPER, lang.component("editor.list.page",
                Placeholder.unparsed("page", Integer.toString(page + 1)),
                Placeholder.unparsed("pages", Integer.toString(pages)),
                Placeholder.unparsed("count", Integer.toString(all.size()))), List.of()));
        inventory.setItem(SLOT_CLOSE, MenuItems.plain(plugin, Material.BARRIER, lang.component("editor.list.close"), List.of()));
    }

    @Override
    public void handleClick(Player clicker, InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getClickedInventory() != inventory) return;

        int slot = event.getSlot();

        if (slot >= 0 && slot < PAGE_SIZE) {
            String id = idBySlot[slot];
            if (id == null) return;

            switch (event.getClick()) {
                case LEFT, SHIFT_LEFT -> editor.openDetail(clicker, id);
                case RIGHT, SHIFT_RIGHT -> toggle(id);
                default -> { }
            }
            return;
        }

        switch (slot) {
            case SLOT_PREV -> {
                page--;
                render();
            }
            case SLOT_NEXT -> {
                page++;
                render();
            }
            case SLOT_CLOSE -> clicker.closeInventory();
            default -> { }
        }
    }

    private void toggle(String id) {
        PoolEntry entry = plugin.config().pool().get(id);
        if (entry == null) return;

        plugin.prizes().save(entry.withEnabled(!entry.enabled()));
        render();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    @Override
    public String getName() {
        return "blackmarket-editor-list";
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

    /** True so the player can use their own inventory (shift-click to add); EditorListener guards what that may do. */
    @Override
    public boolean isBottomInventoryClickable() {
        return true;
    }

    @Override
    public boolean cancelEvent() {
        return true;
    }
}
