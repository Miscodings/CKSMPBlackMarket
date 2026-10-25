package com.crescentkingdom.cksmpblackmarket.gui.editor;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The parts of the editor CKSMPCore's menu click handling cannot see: items dropped onto empty slots (Core
 * only forwards clicks on existing items) and keeping the control row of the editable chest intact.
 */
public final class EditorListener implements Listener {

    private final CKSMPBlackMarket plugin;

    public EditorListener(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();

        if (holder instanceof PrizeListMenu list) {
            handleList(event, player, top, list);
        } else if (holder instanceof RewardItemsMenu) {
            handleRewards(event);
        }
    }

    private void handleList(InventoryClickEvent event, Player player, Inventory top, PrizeListMenu list) {
        // Never let a stray double-click or shift-click shuffle real items into the editor chest.
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }

        boolean inTop = event.getClickedInventory() == top;
        ItemStack cursor = event.getCursor();
        ClickType click = event.getClick();

        // Drop: an item on the cursor placed onto an empty slot or the hopper.
        if (inTop && !MenuItems.isAir(cursor) && (click == ClickType.LEFT || click == ClickType.RIGHT)
                && (MenuItems.isAir(event.getCurrentItem()) || event.getSlot() == PrizeListMenu.SLOT_ADD)) {
            event.setCancelled(true);
            plugin.editor().addPrize(player, cursor);
            list.render();
            return;
        }

        // Shift-click from the player's own inventory.
        if (!inTop && click.isShiftClick()) {
            event.setCancelled(true);
            ItemStack current = event.getCurrentItem();
            if (!MenuItems.isAir(current)) {
                plugin.editor().addPrize(player, current);
                list.render();
            }
        }
    }

    private void handleRewards(InventoryClickEvent event) {
        int raw = event.getRawSlot();

        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR || (raw >= RewardItemsMenu.EDITABLE_SLOTS && raw < RewardItemsMenu.SIZE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RewardItemsMenu)) return;

        for (int raw : event.getRawSlots()) {
            if (raw >= RewardItemsMenu.EDITABLE_SLOTS && raw < RewardItemsMenu.SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
