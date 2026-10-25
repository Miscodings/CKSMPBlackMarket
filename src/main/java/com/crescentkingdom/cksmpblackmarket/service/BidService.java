package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.Settings;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.storage.BlackMarketDb.BidOutcome;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Places bids. The wallet is charged first on the player's own thread (Core's economy is shard-local),
 * then the database settles who actually leads with one compare-and-set. If the CAS loses or fails the
 * charge is returned, either straight to the wallet or through the payout ledger.
 */
public final class BidService {

    private final CKSMPBlackMarket plugin;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastBidMs = new ConcurrentHashMap<>();

    public BidService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    public void forget(UUID uuid) {
        inFlight.remove(uuid);
        lastBidMs.remove(uuid);
    }

    /** Call on the player's thread (menu click handler). */
    public void bid(Player player) {
        Config cfg = plugin.config();
        Settings settings = cfg.settings();
        long now = System.currentTimeMillis();

        RoundView round = plugin.state().current();
        PoolEntry entry = round == null ? null : cfg.pool().get(round.entryId());
        if (round == null || entry == null || !round.isActiveAt(now)) {
            fail(player, cfg, "bid.no-round");
            return;
        }

        UUID uuid = player.getUniqueId();
        if (uuid.equals(round.highBidder())) {
            fail(player, cfg, "bid.already-leading");
            return;
        }

        Long last = lastBidMs.get(uuid);
        if (last != null && now - last < settings.bidCooldownMs()) return;

        double amount = entry.nextBid(round);
        TagResolver amountTag = Placeholder.component("amount", Text.money(cfg.lang(), amount));
        if (!plugin.economy().has(player, amount)) {
            fail(player, cfg, "bid.insufficient", amountTag);
            return;
        }

        if (!inFlight.add(uuid)) return;
        lastBidMs.put(uuid, now);

        EconomyResponse charge = plugin.economy().withdrawPlayer(player, amount);
        if (!charge.transactionSuccess()) {
            inFlight.remove(uuid);
            fail(player, cfg, "bid.withdraw-failed");
            return;
        }

        String name = player.getName();
        plugin.db().async(() -> plugin.db().placeBid(
                round.id(), uuid, name, BigDecimal.valueOf(amount), entry,
                settings.snipeWindowSeconds() * 1000L, settings.snipeExtendSeconds() * 1000L
        )).whenComplete((outcome, err) -> {
            inFlight.remove(uuid);

            if (err != null) {
                plugin.getLogger().log(Level.SEVERE, "Bid failed for " + name, err);
                refund(uuid, amount, "bid.error");
            } else if (!outcome.accepted()) {
                refund(uuid, amount, "bid.outraced");
            } else {
                accepted(player, outcome, amount, entry);
            }
        });
    }

    private void accepted(Player player, BidOutcome outcome, double amount, PoolEntry entry) {
        Config cfg = plugin.config();
        plugin.state().apply(outcome.view());
        plugin.sync().publishBid(outcome.view(), outcome.previousBidder(), outcome.previousBid());

        TagResolver tags = TagResolver.resolver(
                Placeholder.component("amount", Text.money(cfg.lang(), amount)),
                Placeholder.component("item", plugin.rewards().name(cfg, entry.id(), entry)));

        player.getScheduler().run(plugin, task -> {
            cfg.lang().send(player, "bid.placed", tags);
            cfg.play(player, "bid-success");
        }, null);
    }

    /** Returns a charged amount: straight to the wallet if the player is still here, else via the ledger. */
    private void refund(UUID uuid, double amount, String messageKey) {
        Config cfg = plugin.config();
        Player player = Bukkit.getPlayer(uuid);

        Runnable viaLedger = () -> plugin.db().async(() -> {
            plugin.db().insertPayout(uuid, "MONEY", amount, null);
            return null;
        }).exceptionally(err -> {
            plugin.getLogger().log(Level.SEVERE, "COULD NOT REFUND " + amount + " to " + uuid, err);
            return null;
        });

        if (player == null) {
            viaLedger.run();
            return;
        }

        player.getScheduler().run(plugin, task -> {
            if (!plugin.economy().depositPlayer(player, amount).transactionSuccess()) {
                viaLedger.run();
                return;
            }

            cfg.lang().send(player, messageKey, Placeholder.component("amount", Text.money(cfg.lang(), amount)));
            cfg.play(player, "bid-fail");
        }, viaLedger);
    }

    private void fail(Player player, Config cfg, String key, TagResolver... tags) {
        cfg.lang().send(player, key, tags);
        cfg.play(player, "bid-fail");
    }
}
