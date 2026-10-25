package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.Pool;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * The prize pool lives in Postgres so an edit made in-game on one shard reaches all of them. Edits update
 * this shard's pool immediately (the editor menu re-renders from it), then persist and tell the other shards
 * to reload over Redis.
 */
public final class PrizeService {

    private final CKSMPBlackMarket plugin;

    public PrizeService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    /** Startup: if no prize exists yet, import items.yml once so a fresh install is not empty. */
    public void seedFromYamlIfEmpty() throws SQLException {
        if (plugin.db().prizeCount() > 0) return;

        List<PoolEntry> seed = Pool.parseYaml(Config.yaml(plugin, "items.yml", false), plugin.getLogger());
        for (PoolEntry entry : seed) plugin.db().insertPrizeIfAbsent(entry);

        if (!seed.isEmpty()) plugin.getLogger().info("Imported " + seed.size() + " prize(s) from items.yml into the database.");
    }

    /** Blocking load for startup. */
    public void loadNow() throws SQLException {
        apply(Pool.of(plugin.db().loadPrizes()));
    }

    /** Re-reads the table; used when another shard announces a change. */
    public void reloadAsync() {
        plugin.db().async(() -> plugin.db().loadPrizes()).whenComplete((prizes, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.WARNING, "Prize reload failed", err);
                return;
            }

            apply(Pool.of(prizes));
        });
    }

    public CompletableFuture<Void> save(PoolEntry prize) {
        plugin.swapConfig(cfg -> cfg.withPool(cfg.pool().with(prize)));
        plugin.rewards().invalidate();

        return persist(() -> plugin.db().upsertPrize(prize), "save prize '" + prize.id() + "'");
    }

    public CompletableFuture<Void> delete(String id) {
        plugin.swapConfig(cfg -> cfg.withPool(cfg.pool().without(id)));
        plugin.rewards().invalidate();

        return persist(() -> plugin.db().deletePrize(id), "delete prize '" + id + "'");
    }

    private CompletableFuture<Void> persist(Write write, String what) {
        return plugin.db().async(() -> {
            write.run();
            return (Void) null;
        }).whenComplete((ignored, err) -> {
            if (err != null) {
                // The local pool already changed; a reload on the next announcement (or restart) reverts it.
                plugin.getLogger().log(Level.SEVERE, "Could not " + what + " - the change is NOT saved", err);
                return;
            }

            plugin.sync().publishPrizesChanged();
        });
    }

    private void apply(Pool pool) {
        plugin.swapConfig(cfg -> cfg.withPool(pool));
        plugin.rewards().invalidate();
    }

    @FunctionalInterface
    private interface Write {
        void run() throws SQLException;
    }
}
