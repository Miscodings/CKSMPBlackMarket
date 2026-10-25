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

    private final CKSMPBlackMarket plugin;
    private final Map<UUID, BiddingGui> viewers = new ConcurrentHashMap<>();
    private volatile boolean resyncing;

    public GuiService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        Config cfg = plugin.config();

        if (!cfg.settings().participate()) {
            cfg.lang().send(player, "general.not-participating");
            return;
        }

        player.getScheduler().run(plugin, task -> {
            BiddingGui gui = new BiddingGui(plugin, player, cfg);
            viewers.put(player.getUniqueId(), gui);

            plugin.core().getGUIManager().setGUI(player, gui);
            gui.render();
            cfg.play(player, "menu-open");
        }, null);

        resync(true);
    }

    public void remove(UUID uuid) {
        viewers.remove(uuid);
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
            player.getScheduler().run(plugin, task -> gui.render(), () -> viewers.remove(entry.getKey()));
        }
    }

    /** Once-a-second tick: countdown refresh, plus a database check if no packet has arrived for a while. */
    public void tick() {
        if (viewers.isEmpty()) return;

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
        if (!force && System.currentTimeMillis() - plugin.state().lastSyncMs() < RESYNC_AFTER_MS) return;

        resyncing = true;
        plugin.db().async(() -> plugin.db().loadActiveRound()).whenComplete((fresh, err) -> {
            resyncing = false;

            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Round resync failed", err);
                return;
            }

            if (fresh != null) {
                plugin.state().apply(fresh);
            } else {
                RoundView cached = plugin.state().current();
                if (cached != null && cached.active()) {
                    plugin.state().apply(new RoundView(cached.id(), cached.entryId(), cached.roundNo(), cached.roundTotal(),
                            cached.startBid(), cached.highBid(), cached.highBidder(), cached.highBidderName(),
                            cached.bidCount(), cached.endsAt(), false));
                }
            }

            plugin.state().touch();
        });
    }
}
