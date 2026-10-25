package com.crescentkingdom.cksmpblackmarket.gui.editor;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/** Builds editor menu items and tags them so CKSMPCore's click listener treats them as inert menu furniture. */
final class MenuItems {

    private MenuItems() {}

    static ItemStack plain(CKSMPBlackMarket plugin, Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.ITEM_NAME, name);
        if (!lore.isEmpty()) item.setData(DataComponentTypes.LORE, ItemLore.lore().addLines(lore).build());
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS));
        mark(plugin, item);
        return item;
    }

    /** Keeps the prize's own name and lore (custom enchant lines included) and appends editor lines. */
    static ItemStack decorate(CKSMPBlackMarket plugin, ItemStack icon, List<Component> extraLore) {
        List<Component> lore = new ArrayList<>();
        ItemLore existing = icon.getData(DataComponentTypes.LORE);
        if (existing != null) lore.addAll(existing.lines());
        lore.addAll(extraLore);

        icon.setData(DataComponentTypes.LORE, ItemLore.lore().addLines(lore).build());
        mark(plugin, icon);
        return icon;
    }

    static boolean isAir(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static void mark(CKSMPBlackMarket plugin, ItemStack item) {
        NamespacedKey menuKey = plugin.core().getGUIManager().getItemMenuKey();
        item.editPersistentDataContainer(pdc -> pdc.set(menuKey, PersistentDataType.BOOLEAN, true));
    }
}
