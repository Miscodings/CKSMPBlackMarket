package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.Settings;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Shows the prize that is up for bidding as a floating item with its name above it, at display.position in the
 * market warp's world. Only the shard that hosts that world spawns it. Driven by the once-a-second heartbeat;
 * the work itself runs on the region that owns the position (Folia safe), so the entity fields are only ever
 * touched from that one region thread. The entities are not persistent, so a crash or stop can never leave one behind.
 */
public final class ItemDisplayService {

    private final CKSMPBlackMarket plugin;

    private ItemDisplay item;
    private TextDisplay label;
    private String shownEntryId;
    private double shownScale;

    public ItemDisplayService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    /** Called from the global heartbeat; hands the real work to the region that owns the display position. */
    public void update() {
        Location at = plugin.area().displayLocation();
        if (at == null) {
            // Not this shard (or not configured): drop anything we still hold.
            if (item != null || label != null) removeEverything();
            return;
        }

        Bukkit.getRegionScheduler().run(plugin, at, task -> sync(at));
    }

    private void sync(Location at) {
        RoundView round = plugin.state().current();
        boolean live = plugin.config().settings().participate() && round != null && round.isActiveAt(System.currentTimeMillis());
        if (!live) {
            removeEverything();
            return;
        }

        Config cfg = plugin.config();
        PoolEntry entry = cfg.pool().get(round.entryId());
        if (entry == null) {
            removeEverything();
            return;
        }

        Settings settings = cfg.settings();
        boolean intact = item != null && item.isValid() && label != null && label.isValid();
        if (intact && round.entryId().equals(shownEntryId) && settings.displayScale() == shownScale) return;

        // The chunk must already be loaded (players in the room keep it loaded); never force-load it from here.
        if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;

        removeEverything();

        ItemStack stack = plugin.rewards().icon(entry);
        float scale = (float) settings.displayScale();

        item = at.getWorld().spawn(at, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setItemStack(stack);
            display.setBillboard(Display.Billboard.VERTICAL);
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
        });

        Location above = at.clone().add(0, settings.displayTextOffset() + scale / 2.0, 0);
        label = at.getWorld().spawn(above, TextDisplay.class, display -> {
            display.setPersistent(false);
            display.text(plugin.rewards().name(cfg, entry.id(), entry));
            display.setBillboard(Display.Billboard.CENTER);
        });

        shownEntryId = round.entryId();
        shownScale = settings.displayScale();
    }

    private void removeEverything() {
        remove(item);
        remove(label);
        item = null;
        label = null;
        shownEntryId = null;
    }

    private void remove(Entity entity) {
        if (entity == null) return;

        try {
            if (entity.isValid()) entity.remove();
        } catch (Exception ignored) {
            // Off its region thread (shutdown) the call may be refused; the entity is not persistent anyway.
        }
    }

    /** Plugin disable. */
    public void clear() {
        removeEverything();
    }
}
