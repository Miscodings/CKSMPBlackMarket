package com.crescentkingdom.cksmpblackmarket.gui;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.MenuLayout;
import com.crescentkingdom.cksmpblackmarket.config.MenuLayout.Button;
import com.crescentkingdom.cksmpblackmarket.config.MenuLayout.Phase;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.service.RoundText;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import com.crescentkingdom.cksmpcore.Managers.GUI.MenuInventoryHolder;
import com.crescentkingdom.cksmpcore.Utils.ItemUtils;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
 * stray clicks, drags and offhand swaps. It has two phases (preview, then bidding) that share one
 * inventory size; when the phase flips the service reopens it. Rendering is incremental: static buttons
 * once, the prize icon only when the round changes, the live buttons only when their text changes.
 */
public final class BiddingGui implements MenuInventoryHolder {

    public enum PhaseKind { PREVIEW, BIDDING }

    private final CKSMPBlackMarket plugin;
    private final Player player;
    private final MenuLayout layout;
    private final PhaseKind kind;
    private final Phase phase;
    private final Inventory inventory;
    private final String[] functionBySlot;
    private final ItemUtils itemUtils = new ItemUtils();

    private long renderedRound = Long.MIN_VALUE;
    private long renderedKey = Long.MIN_VALUE;

    public BiddingGui(CKSMPBlackMarket plugin, Player player, Config cfg) {
        this.plugin = plugin;
        this.player = player;
        this.layout = cfg.bidding();
        this.kind = currentKind();
        this.phase = kind == PhaseKind.PREVIEW ? layout.preview() : layout.bidding();
        this.functionBySlot = new String[layout.size()];
        this.inventory = Bukkit.createInventory(this, layout.size(), cfg.lang().format(phase.title()));

        if (layout.fill() != null) {
            ItemStack filler = staticItem(cfg, layout.fill());
            for (int slot = 0; slot < layout.size(); slot++) inventory.setItem(slot, filler);
        }

        for (Button button : phase.buttons()) functionBySlot[button.slot()] = button.function();
    }

    /** Which phase the cached round is in right now (a finished or absent round uses the bidding layout). */
    private PhaseKind currentKind() {
        RoundView round = plugin.state().current();
        return round != null && round.isPreviewAt(System.currentTimeMillis()) ? PhaseKind.PREVIEW : PhaseKind.BIDDING;
    }

    /**
     * Re-renders what changed. Must run on the player's thread. Returns false when the round moved into a
     * different phase, meaning this inventory has the wrong layout and should be reopened.
     */
    public boolean render() {
        Config cfg = plugin.config();
        RoundView round = plugin.state().current();
        long now = System.currentTimeMillis();
        boolean live = round != null && round.isActiveAt(now);

        if (live && currentKind() != kind) return false;

        // One long that changes whenever any visible text would: round, bids, whole seconds left, live flag.
        // Idle keys on the wall-clock second so the "next event" countdown keeps ticking.
        long key = live ? round.id() * 1_000_003L + round.bidCount() * 10_007L + Text.secondsUntil(round.endsAt(), now) : -(now / 1000) - 1;
        if (key == renderedKey) return true;
        renderedKey = key;

        PoolEntry entry = live ? cfg.pool().get(round.entryId()) : null;
        if (!live || entry == null) {
            renderIdle(cfg, now);
            return true;
        }

        TagResolver tags = TagResolver.resolver(
                RoundText.resolver(plugin, cfg, round, now),
                Placeholder.unparsed("player", String.valueOf(round.highBidderName())),
                RoundText.lazy("balance", () -> Text.money(cfg.lang(), plugin.economy().getBalance(player))));

        for (Button button : phase.buttons()) {
            switch (button.function()) {
                case "reward" -> {
                    if (renderedRound != round.id()) inventory.setItem(button.slot(), rewardIcon(cfg, entry, button, tags));
                }
                case "clock" -> inventory.setItem(button.slot(), render(cfg, button, tags, button.material()));
                case "bid" -> inventory.setItem(button.slot(), bidButton(cfg, round, entry, button, tags));
                default -> {
                    if (renderedRound == Long.MIN_VALUE) inventory.setItem(button.slot(), staticItem(cfg, button));
                }
            }
        }

        renderedRound = round.id();
        return true;
    }

    private void renderIdle(Config cfg, long now) {
        renderedRound = Long.MIN_VALUE;

        long next = cfg.settings().firstSlotAfter(now);
        Component time = next < 0 ? cfg.lang().component("menu.idle-none") : Text.time(cfg.lang(), (next - now) / 1000);
        TagResolver tags = Placeholder.component("time", time);

        for (Button button : phase.buttons()) {
            switch (button.function()) {
                case "reward" -> inventory.setItem(button.slot(), plain(Material.BARRIER,
                        cfg.lang().component("menu.idle-name"), cfg.lang().components("menu.idle-lore", tags)));
                case "clock", "bid" -> inventory.setItem(button.slot(), plain(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of()));
                default -> inventory.setItem(button.slot(), staticItem(cfg, button));
            }
        }
    }

    /** The bid button: tiered material by amount, or the "you lead" variant with the viewer's own head. */
    private ItemStack bidButton(Config cfg, RoundView round, PoolEntry entry, Button button, TagResolver tags) {
        if (player.getUniqueId().equals(round.highBidder()) && layout.leading() != null) {
            Button variant = layout.leading();
            ItemStack head = render(cfg, variant, tags, variant.material());
            head.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile().uuid(player.getUniqueId()).build());
            return head;
        }

        Material fallback = button.material() == null ? Material.GOLD_INGOT : button.material();
        return render(cfg, button, tags, layout.bidMaterial(entry.nextBid(round), fallback));
    }

    /** Placeholder-free buttons (filler, close) go through CKSMPCore's own menu item builder. */
    private ItemStack staticItem(Config cfg, Button button) {
        Component name = button.name() == null ? Component.empty() : cfg.lang().format(button.name());
        ItemStack item = itemUtils.menuItem(plugin.core().getPluginInstance(), button.material(), 1, name, button.lore(), false);
        if (button.itemModel() != null) item.setData(DataComponentTypes.ITEM_MODEL, Key.key(button.itemModel()));
        return item;
    }

    private ItemStack render(Config cfg, Button button, TagResolver tags, Material material) {
        Component name = button.name() == null ? Component.empty() : cfg.lang().format(button.name(), tags);
        List<Component> lore = new ArrayList<>(button.lore().size());
        for (String line : button.lore()) lore.add(cfg.lang().format(line, tags));

        ItemStack item = plain(material, name, lore);
        if (button.itemModel() != null) item.setData(DataComponentTypes.ITEM_MODEL, Key.key(button.itemModel()));
        return item;
    }

    private ItemStack rewardIcon(Config cfg, PoolEntry entry, Button button, TagResolver tags) {
        ItemStack icon = plugin.rewards().icon(entry);

        // Only an explicit display name overrides the item's own; custom-enchant lore is kept either way.
        if (entry.display().name() != null) icon.setData(DataComponentTypes.CUSTOM_NAME, plugin.rewards().name(cfg, entry.id(), entry));

        List<Component> lore = new ArrayList<>();
        if (!entry.display().lore().isEmpty()) {
            for (String line : entry.display().lore()) lore.add(cfg.lang().format(line));
        } else {
            ItemLore existing = icon.getData(DataComponentTypes.LORE);
            if (existing != null) lore.addAll(existing.lines());
        }
        for (String line : button.lore()) lore.add(cfg.lang().format(line, tags));

        if (!lore.isEmpty()) icon.setData(DataComponentTypes.LORE, ItemLore.lore().addLines(lore).build());
        mark(icon);
        return icon;
    }

    private ItemStack plain(Material material, Component name, List<Component> lore) {
        ItemStack item = ItemStack.of(material);
        item.setData(DataComponentTypes.ITEM_NAME, name);
        if (!lore.isEmpty()) item.setData(DataComponentTypes.LORE, ItemLore.lore().addLines(lore).build());
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
        plugin.gui().remove(closer.getUniqueId(), this);
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
