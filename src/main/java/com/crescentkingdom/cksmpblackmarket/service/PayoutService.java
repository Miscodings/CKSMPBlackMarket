package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.storage.BlackMarketDb.Payout;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Delivers refunds and prizes from the payout ledger. The ledger is the only path money or prizes take
 * to an offline or remote player, and {@code DELETE ... RETURNING} makes the claim atomic across shards:
 * whichever shard hosts the player claims, any other shard gets nothing.
 */
public final class PayoutService {

    private final CKSMPBlackMarket plugin;
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();

    public PayoutService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    /** Safe from any thread. No-ops quickly when the player is not on this shard. */
    public void claim(UUID uuid) {
        if (Bukkit.getPlayer(uuid) == null || !claiming.add(uuid)) return;

        plugin.db().async(() -> plugin.db().takePayouts(uuid)).whenComplete((rows, err) -> {
            claiming.remove(uuid);

            if (err != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to claim payouts for " + uuid, err);
                return;
            }
            if (rows.isEmpty()) return;

            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                restore(uuid, rows);
                return;
            }

            // Exactly one of apply / restore runs, including when the scheduler refuses (returns null) for a removed player.
            AtomicBoolean handled = new AtomicBoolean();
            Runnable toLedger = () -> {
                if (handled.compareAndSet(false, true)) restore(uuid, rows);
            };

            var scheduled = player.getScheduler().run(plugin, task -> {
                if (handled.compareAndSet(false, true)) apply(player, rows);
            }, toLedger);

            if (scheduled == null) toLedger.run();
        });
    }

    private void apply(Player player, List<Payout> rows) {
        Config cfg = plugin.config();
        double refund = 0;
        List<Payout> unapplied = new ArrayList<>();

        for (Payout row : rows) {
            if (row.kind().equals("MONEY")) {
                refund += row.amount();
                continue;
            }

            PoolEntry entry = cfg.pool().get(row.payload());
            if (entry == null) {
                cfg.lang().send(player, "payout.reward-missing", Placeholder.unparsed("id", String.valueOf(row.payload())));
                unapplied.add(row);
                continue;
            }

            plugin.rewards().give(player, entry);
        }

        if (refund > 0) {
            if (plugin.economy().depositPlayer(player, refund).transactionSuccess()) {
                cfg.lang().send(player, "payout.refund", Placeholder.component("amount", Text.money(cfg.lang(), refund)));
            } else {
                unapplied.add(new Payout("MONEY", refund, null));
            }
        }

        if (!unapplied.isEmpty()) restore(player.getUniqueId(), unapplied);
    }

    private void restore(UUID uuid, List<Payout> rows) {
        plugin.db().async(() -> {
            plugin.db().restorePayouts(uuid, rows);
            return null;
        }).exceptionally(err -> {
            plugin.getLogger().log(Level.SEVERE, "Could not restore " + rows.size() + " payout(s) for " + uuid + ": " + rows, err);
            return null;
        });
    }
}
