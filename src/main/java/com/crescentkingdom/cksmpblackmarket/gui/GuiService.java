package com.crescentkingdom.cksmpblackmarket.gui;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Tracks open bidding menus, refreshes them, and heals a shard whose cache fell behind. */
public final class GuiService {

    private static final long RESYNC_AFTER_MS = 10_000;
    private static final long IDLE_RESYNC_AFTER_MS = 30_000;

    private final CKSMPBlackMarket plugin;
    private final Map<UUID, BiddingGui> viewers = new ConcurrentHashMap<>();
    private volatile boolean resyncing;

    public GuiService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        open(player, true);
    }

    private void open(Player player, boolean withSound) {
        Config cfg = plugin.config();

        if (!cfg.settings().participate()) {
            cfg.lang().send(player, "general.not-participating");
            return;
        }

        player.getScheduler().run(plugin, task -> {
            BiddingGui gui = new BiddingGui(plugin, player, cfg);

            // Opening replaces any menu the player had, whose close handler would otherwise drop this entry.
            plugin.core().getGUIManager().setGUI(player, gui);
            viewers.put(player.getUniqueId(), gui);
            gui.render();
            if (withSound) cfg.play(player, "menu-open");
        }, null);

        resync(true);
    }

    public void remove(UUID uuid) {
        viewers.remove(uuid);
    }

    /** Close handler of a specific menu: only forgets the player if that menu is still the tracked one. */
    public void remove(UUID uuid, BiddingGui gui) {
        viewers.remove(uuid, gui);
    }

    /** Re-renders every open menu on its owner's thread. Cheap when nothing visible changed. */
    public void refreshAll() {
        if (viewers.isEmpty()) return;

        for (Map.Entry<UUID, BiddingGui> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                viewers.remove(entry.getKey());
                continue;
            }

            BiddingGui gui = entry.getValue();
            player.getScheduler().run(plugin, task -> {
                // Preview -> bidding changes the layout, so the inventory is rebuilt (same size, no flicker to a closed state).
                if (!gui.render()) open(player, false);
            }, () -> viewers.remove(entry.getKey()));
        }
    }

    /** Once-a-second tick: countdown refresh, plus a database check if no packet has arrived for a while. */
    public void tick() {
        refreshAll();
        resync(false);
    }

    /**
     * Reconciles the cached round with the database. A shard that missed a packet (Redis blip, restart
     * mid-round) otherwise shows stale numbers until the next event; the bid itself is always checked in
     * SQL, so staleness only ever costs a refused click.
     */
    public void resync(boolean force) {
        if (resyncing) return;
        long staleAfter = viewers.isEmpty() ? IDLE_RESYNC_AFTER_MS : RESYNC_AFTER_MS;
        if (!force && System.currentTimeMillis() - plugin.state().lastSyncMs() < staleAfter) return;

        resyncing = true;
        plugin.db().async(this::authoritativeRound).whenComplete((fresh, err) -> {
            resyncing = false;

            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Round resync failed", err);
                return;
            }

            // Only ever apply what the database returned; MarketState discards it if it is older than what we hold.
            if (fresh != null) plugin.state().apply(fresh);
            plugin.state().touch();
        });
    }

    /** The live round, or, when none is live, the database's current row for the round we have cached. */
    private RoundView authoritativeRound() throws java.sql.SQLException {
        RoundView active = plugin.db().loadActiveRound();
        if (active != null) return active;

        RoundView cached = plugin.state().current();
        return cached != null && cached.active() ? plugin.db().loadRound(cached.id()) : null;
    }
}
