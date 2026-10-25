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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Map<UUID, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBidMs = new ConcurrentHashMap<>();

    public BidService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    public void forget(UUID uuid) {
        lastBidMs.remove(uuid);
    }

    /**
     * Registered as a CKSMPCore save hook: a player's save (and any cross-shard transfer gated on it) waits
     * for their pending bid to settle, so a withdrawn wallet is never persisted while its bid or refund is
     * still in flight.
     */
    public CompletableFuture<Void> awaitInFlight(UUID uuid) {
        CompletableFuture<Void> pending = inFlight.get(uuid);
        if (pending == null) return CompletableFuture.completedFuture(null);

        // Time out a copy: timing out the shared future itself would drop the in-flight entry while the bid is still running.
        return pending.copy().orTimeout(10, TimeUnit.SECONDS).exceptionally(err -> null);
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

        if (!round.isBiddableAt(now)) {
            fail(player, cfg, "bid.not-open");
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
            fail(player, cfg, "bid.insufficient", amountTag,
                    Placeholder.component("balance", Text.money(cfg.lang(), plugin.economy().getBalance(player))));
            return;
        }

        // Claimed before the wallet is touched, and completed only once the bid AND any refund have finished,
        // so the save hook covers the whole charge -> settle -> refund window.
        CompletableFuture<Void> settled = new CompletableFuture<>();
        if (inFlight.putIfAbsent(uuid, settled) != null) return;
        lastBidMs.put(uuid, now);

        // A throwing economy must not leave the slot claimed, or this player could never bid again.
        boolean charged;
        try {
            charged = plugin.economy().withdrawPlayer(player, amount).transactionSuccess();
        } catch (RuntimeException err) {
            plugin.getLogger().log(Level.SEVERE, "Economy failed while charging " + player.getName(), err);
            charged = false;
        }

        if (!charged) {
            release(uuid, settled);
            fail(player, cfg, "bid.withdraw-failed");
            return;
        }

        String name = player.getName();
        plugin.db().async(() -> plugin.db().placeBid(
                round.id(), uuid, name, BigDecimal.valueOf(amount), entry,
                settings.snipeWindowSeconds() * 1000L, settings.snipeExtendSeconds() * 1000L
        )).handle((outcome, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.SEVERE, "Bid failed for " + name, err);
                return refund(uuid, amount, "bid.error");
            }
            if (!outcome.accepted()) return refund(uuid, amount, "bid.outraced");

            accepted(player, outcome, amount, entry);
            return CompletableFuture.<Void>completedFuture(null);
        }).thenCompose(refunded -> refunded).whenComplete((ignored, err) -> release(uuid, settled));
    }

    private void release(UUID uuid, CompletableFuture<Void> settled) {
        settled.complete(null);
        inFlight.remove(uuid, settled);
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
    private CompletableFuture<Void> refund(UUID uuid, double amount, String messageKey) {
        Config cfg = plugin.config();
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return viaLedger(uuid, amount);

        // Completes only after the deposit or the ledger insert has actually finished.
        CompletableFuture<Void> done = new CompletableFuture<>();

        // Exactly one path runs: the task, the retired callback, or the fallback for a null (already-removed) player.
        AtomicBoolean handled = new AtomicBoolean();
        Runnable toLedger = () -> {
            if (handled.compareAndSet(false, true)) viaLedger(uuid, amount).whenComplete((ignored, err) -> done.complete(null));
        };

        var scheduled = player.getScheduler().run(plugin, task -> {
            if (!handled.compareAndSet(false, true)) return;

            if (!plugin.economy().depositPlayer(player, amount).transactionSuccess()) {
                viaLedger(uuid, amount).whenComplete((ignored, err) -> done.complete(null));
                return;
            }

            cfg.lang().send(player, messageKey, Placeholder.component("amount", Text.money(cfg.lang(), amount)));
            cfg.play(player, "bid-fail");
            done.complete(null);
        }, toLedger);

        if (scheduled == null) toLedger.run();

        return done;
    }

    private CompletableFuture<Void> viaLedger(UUID uuid, double amount) {
        return plugin.db().async(() -> {
            plugin.db().insertPayout(uuid, "MONEY", amount, null);
            return (Void) null;
        }).exceptionally(err -> {
            plugin.getLogger().log(Level.SEVERE, "COULD NOT REFUND " + amount + " to " + uuid, err);
            return null;
        });
    }

    private void fail(Player player, Config cfg, String key, TagResolver... tags) {
        cfg.lang().send(player, key, tags);
        cfg.play(player, "bid-fail");
    }
}
