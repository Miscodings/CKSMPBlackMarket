package com.crescentkingdom.cksmpblackmarket.gui.editor;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Lang;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry.ItemSpec;
import com.crescentkingdom.cksmpblackmarket.storage.PrizeCodec;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The prize's items as an editable chest: drop items in, take them out. Whatever is in the top five rows when
 * the menu closes (or Save is clicked) becomes the prize, stored as exact copies of those stacks.
 */
final class RewardItemsMenu implements MenuInventoryHolder {

    static final int SIZE = 54;
    static final int EDITABLE_SLOTS = 45;
    static final int SLOT_SAVE = 49;
    static final int SLOT_INFO = 53;

    private final CKSMPBlackMarket plugin;
    private final EditorService editor;
    private final String prizeId;
    private final Inventory inventory;

    private boolean saved;

    RewardItemsMenu(CKSMPBlackMarket plugin, EditorService editor, Player player, PoolEntry entry) {
        this.plugin = plugin;
        this.editor = editor;
        this.prizeId = entry.id();

        Lang lang = plugin.config().lang();
        this.inventory = Bukkit.createInventory(this, SIZE, lang.component("editor.rewards.title"));

        List<ItemStack> items = plugin.rewards().buildItems(entry);
        for (int i = 0; i < Math.min(items.size(), EDITABLE_SLOTS); i++) inventory.setItem(i, items.get(i));
        if (items.size() > EDITABLE_SLOTS) {
            plugin.getLogger().warning("Prize '" + entry.id() + "' has more than " + EDITABLE_SLOTS + " item stacks; the editor shows the first " + EDITABLE_SLOTS + ".");
        }

        ItemStack filler = MenuItems.plain(plugin, Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = EDITABLE_SLOTS; slot < SIZE; slot++) inventory.setItem(slot, filler);

        inventory.setItem(SLOT_SAVE, MenuItems.plain(plugin, Material.LIME_CONCRETE, lang.component("editor.rewards.save-name"), List.of()));
        inventory.setItem(SLOT_INFO, MenuItems.plain(plugin, Material.BOOK, lang.component("editor.rewards.info-name"), lang.components("editor.rewards.info-lore")));
    }

    /** Writes the chest contents to the prize. Idempotent per menu instance. */
    private void save(Player player) {
        if (saved) return;
        saved = true;

        Lang lang = plugin.config().lang();
        PoolEntry entry = plugin.config().pool().get(prizeId);
        if (entry == null) {
            lang.send(player, "editor.msg.gone");
            return;
        }

        List<ItemSpec> specs = new ArrayList<>();
        for (int slot = 0; slot < EDITABLE_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!MenuItems.isAir(item)) specs.add(ItemSpec.ofStack(PrizeCodec.encodeStack(item)));
        }

        PoolEntry updated = entry.withItems(specs);

        // A prize that gives nothing must never be drawn.
        if (specs.isEmpty() && entry.commands().isEmpty()) {
            updated = updated.withEnabled(false);
            lang.send(player, "editor.msg.rewards-empty", Placeholder.unparsed("id", prizeId));
        } else {
            lang.send(player, "editor.msg.rewards-saved", Placeholder.unparsed("count", Integer.toString(specs.size())));
        }

        plugin.prizes().save(updated);
    }

    @Override
    public void handleClick(Player clicker, InventoryClickEvent event) {
        if (event.getClickedInventory() != inventory) return;

        if (event.getSlot() >= EDITABLE_SLOTS) event.setCancelled(true);

        if (event.getSlot() == SLOT_SAVE) {
            save(clicker);
            editor.openDetail(clicker, prizeId);
        }
    }

    /** Also fires when this menu is replaced or the player walks away, so edits are never lost. */
    @Override
    public void handleClose(Player closer, InventoryCloseEvent event) {
        save(closer);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    @Override
    public String getName() {
        return "blackmarket-editor-rewards";
    }

    @Override
    public int getSize() {
        return SIZE;
    }

    @Override
    public void handleDrag(Player dragger, InventoryDragEvent event) { }

    /** Items must move freely in both inventories; EditorListener protects the control row. */
    @Override
    public boolean isBottomInventoryClickable() {
        return true;
    }

    @Override
    public boolean cancelEvent() {
        return false;
    }
}
