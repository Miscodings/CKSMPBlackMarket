package com.crescentkingdom.cksmpblackmarket.storage;

import com.crescentkingdom.cksmpblackmarket.model.EventRow;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpcore.Managers.Database.DatabaseManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * All SQL for the plugin, on top of CKSMPCore's shared Postgres pool.
 * <p>
 * Every race between shards is settled by a conditional UPDATE/DELETE, never by application locks.
 * Times that decide outcomes (round end, lease expiry) use the database clock so shard clock skew
 * cannot let a late bid in or end a round early.
 */
public final class BlackMarketDb {

    private static final String NOW = "(extract(epoch from clock_timestamp())*1000)::bigint";
    private static final String ROUND_COLS =
            "id, entry_id, round_no, round_total, start_bid, high_bid, high_bidder, high_bidder_name, bid_count, bids_open_at, ends_at, state";
    private static final String UNIQUE_VIOLATION = "23505";

    private final DatabaseManager core;
    private final ExecutorService executor;

    public BlackMarketDb(DatabaseManager core, ExecutorService executor) {
        this.core = core;
        this.executor = executor;
    }

    @FunctionalInterface
    public interface SqlTask<T> {
        T run() throws SQLException;
    }

    public <T> CompletableFuture<T> async(SqlTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run();
            } catch (SQLException err) {
                throw new CompletionException(err);
            }
        }, executor);
    }

    // ---------------------------------------------------------------- schema

    public void createSchema() throws SQLException {
        try (Connection c = core.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS cksmpbm_event(id BIGSERIAL PRIMARY KEY, state VARCHAR(12) NOT NULL, slot_at BIGINT NOT NULL DEFAULT 0, pool_ids TEXT NOT NULL, round_index INTEGER NOT NULL DEFAULT -1, next_at BIGINT NOT NULL, created_at BIGINT NOT NULL)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS cksmpbm_event_slot_idx ON cksmpbm_event(slot_at) WHERE slot_at > 0");
            s.execute("CREATE TABLE IF NOT EXISTS cksmpbm_round(id BIGSERIAL PRIMARY KEY, event_id BIGINT NOT NULL, round_no INTEGER NOT NULL, round_total INTEGER NOT NULL, entry_id VARCHAR(64) NOT NULL, state VARCHAR(8) NOT NULL, start_bid DECIMAL(20,2) NOT NULL, high_bid DECIMAL(20,2) NOT NULL DEFAULT 0, high_bidder VARCHAR(36), high_bidder_name VARCHAR(32), bid_count INTEGER NOT NULL DEFAULT 0, cancelled BOOLEAN NOT NULL DEFAULT FALSE, started_at BIGINT NOT NULL, bids_open_at BIGINT NOT NULL DEFAULT 0, ends_at BIGINT NOT NULL, ended_at BIGINT)");
            s.execute("ALTER TABLE cksmpbm_round ADD COLUMN IF NOT EXISTS bids_open_at BIGINT NOT NULL DEFAULT 0");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS cksmpbm_round_event_idx ON cksmpbm_round(event_id, round_no)");
            s.execute("CREATE INDEX IF NOT EXISTS cksmpbm_round_active_idx ON cksmpbm_round(state) WHERE state = 'ACTIVE'");
            s.execute("CREATE INDEX IF NOT EXISTS cksmpbm_round_ended_idx ON cksmpbm_round(ended_at DESC) WHERE state = 'ENDED'");
            s.execute("CREATE TABLE IF NOT EXISTS cksmpbm_payout(id BIGSERIAL PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, kind VARCHAR(8) NOT NULL, amount DECIMAL(20,2) NOT NULL DEFAULT 0, payload TEXT, created_at BIGINT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS cksmpbm_payout_player_idx ON cksmpbm_payout(player_uuid)");
            s.execute("CREATE TABLE IF NOT EXISTS cksmpbm_prize(id VARCHAR(64) PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT TRUE, weight INTEGER NOT NULL, start_bid DECIMAL(20,2) NOT NULL, min_increment DECIMAL(20,2) NOT NULL, increment_percent DECIMAL(8,4) NOT NULL, display_name TEXT, display_lore TEXT NOT NULL DEFAULT '[]', icon TEXT, items TEXT NOT NULL DEFAULT '[]', commands TEXT NOT NULL DEFAULT '[]', updated_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS cksmpbm_lease(name VARCHAR(32) PRIMARY KEY, holder VARCHAR(64) NOT NULL, expires_at BIGINT NOT NULL)");
        }
    }

    // ---------------------------------------------------------------- leader lease

    /** Acquires or renews the scheduler lease. True when this holder now owns it. */
    public boolean acquireLease(String holder, long ttlMs) throws SQLException {
        String sql = "INSERT INTO cksmpbm_lease(name, holder, expires_at) VALUES ('scheduler', ?, " + NOW + " + ?) "
                + "ON CONFLICT (name) DO UPDATE SET holder = EXCLUDED.holder, expires_at = EXCLUDED.expires_at "
                + "WHERE cksmpbm_lease.holder = EXCLUDED.holder OR cksmpbm_lease.expires_at < " + NOW;

        try (Connection c = core.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, holder);
            ps.setLong(2, ttlMs);
            return ps.executeUpdate() == 1;
        }
    }

    /** Frees the lease on shutdown so another shard takes over immediately instead of after the TTL. */
    public void releaseLease(String holder) throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE cksmpbm_lease SET expires_at = 0 WHERE name = 'scheduler' AND holder = ?")) {
            ps.setString(1, holder);
            ps.executeUpdate();
        }
    }

    public String leaseHolder() throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT holder FROM cksmpbm_lease WHERE name = 'scheduler' AND expires_at >= " + NOW);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    // ---------------------------------------------------------------- events

    public EventRow loadOpenEvent() throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, state, slot_at, pool_ids, round_index, next_at FROM cksmpbm_event WHERE state <> 'FINISHED' ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;

            String ids = rs.getString("pool_ids");
            return new EventRow(rs.getLong("id"), EventRow.State.valueOf(rs.getString("state")), rs.getLong("slot_at"),
                    ids.isEmpty() ? List.of() : List.of(ids.split(",")), rs.getInt("round_index"), rs.getLong("next_at"));
        }
    }

    public long lastSlotAt() throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(slot_at), 0) FROM cksmpbm_event");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Inserts a new event, or returns null when another leader already created the same slot. */
    public EventRow insertEvent(long slotAt, List<String> poolIds, long nextAt, long nowMs) throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO cksmpbm_event(state, slot_at, pool_ids, round_index, next_at, created_at) VALUES ('ANNOUNCING', ?, ?, -1, ?, ?) RETURNING id")) {
            ps.setLong(1, slotAt);
            ps.setString(2, String.join(",", poolIds));
            ps.setLong(3, nextAt);
            ps.setLong(4, nowMs);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new EventRow(rs.getLong(1), EventRow.State.ANNOUNCING, slotAt, List.copyOf(poolIds), -1, nextAt);
            }
        } catch (SQLException err) {
            if (UNIQUE_VIOLATION.equals(err.getSQLState())) return null;
            throw err;
        }
    }

    public void updateEvent(EventRow event) throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE cksmpbm_event SET state = ?, round_index = ?, next_at = ? WHERE id = ?")) {
            ps.setString(1, event.state().name());
            ps.setInt(2, event.roundIndex());
            ps.setLong(3, event.nextAt());
            ps.setLong(4, event.id());
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- rounds

    /** Opens a round, or returns null when another leader already opened this round number. */
    public RoundView insertRound(long eventId, PoolEntry entry, int roundNo, int roundTotal, long previewMs, long durationMs) throws SQLException {
        String sql = "INSERT INTO cksmpbm_round(event_id, round_no, round_total, entry_id, state, start_bid, started_at, bids_open_at, ends_at) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE', ?, " + NOW + ", " + NOW + " + ?, " + NOW + " + ?) RETURNING " + ROUND_COLS;

        try (Connection c = core.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, eventId);
            ps.setInt(2, roundNo);
            ps.setInt(3, roundTotal);
            ps.setString(4, entry.id());
            ps.setBigDecimal(5, BigDecimal.valueOf(entry.startBid()));
            ps.setLong(6, previewMs);
            ps.setLong(7, previewMs + durationMs);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return mapRound(rs, "");
            }
        } catch (SQLException err) {
            if (UNIQUE_VIOLATION.equals(err.getSQLState())) return null;
            throw err;
        }
    }

    public RoundView loadActiveRound() throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + ROUND_COLS + " FROM cksmpbm_round WHERE state = 'ACTIVE' ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? mapRound(rs, "") : null;
        }
    }

    public RoundView loadRound(long id) throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + ROUND_COLS + " FROM cksmpbm_round WHERE id = ?")) {
            ps.setLong(1, id);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRound(rs, "") : null;
            }
        }
    }

    /** Result of a bid attempt. {@code view} is null when the compare-and-set lost. */
    public record BidOutcome(RoundView view, UUID previousBidder, double previousBid) {
        public boolean accepted() {
            return view != null;
        }
    }

    /**
     * Atomically takes the lead if, and only if, the round is still active, the bidder is not already
     * leading and the amount meets the current minimum. The outbid player's refund is queued in the
     * same transaction so money can never be lost between the two writes.
     */
    public BidOutcome placeBid(long roundId, UUID bidder, String bidderName, BigDecimal amount, PoolEntry entry,
                               long snipeWindowMs, long snipeExtendMs) throws SQLException {
        String sql = "WITH prev AS (SELECT id, high_bid, high_bidder, bid_count FROM cksmpbm_round WHERE id = ? AND state = 'ACTIVE' FOR UPDATE) "
                + "UPDATE cksmpbm_round r SET high_bid = ?, high_bidder = ?, high_bidder_name = ?, bid_count = prev.bid_count + 1, "
                + "ends_at = CASE WHEN r.ends_at - " + NOW + " < ? THEN GREATEST(r.ends_at, " + NOW + " + ?) ELSE r.ends_at END "
                + "FROM prev WHERE r.id = prev.id AND r.ends_at > " + NOW + " AND r.bids_open_at <= " + NOW + " "
                + "AND prev.high_bidder IS DISTINCT FROM ? "
                + "AND ? >= CASE WHEN prev.bid_count = 0 THEN r.start_bid ELSE prev.high_bid + GREATEST(?, prev.high_bid * ?) END "
                + "RETURNING prev.high_bid AS prev_bid, prev.high_bidder AS prev_bidder, "
                + "r.id, r.entry_id, r.round_no, r.round_total, r.start_bid, r.high_bid, r.high_bidder, r.high_bidder_name, r.bid_count, r.bids_open_at, r.ends_at, r.state";

        try (Connection c = core.getConnection()) {
            c.setAutoCommit(false);

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, roundId);
                ps.setBigDecimal(2, amount);
                ps.setString(3, bidder.toString());
                ps.setString(4, bidderName);
                ps.setLong(5, snipeWindowMs);
                ps.setLong(6, snipeExtendMs);
                ps.setString(7, bidder.toString());
                ps.setBigDecimal(8, amount);
                ps.setBigDecimal(9, BigDecimal.valueOf(entry.minIncrement()));
                ps.setBigDecimal(10, BigDecimal.valueOf(entry.incrementPercent()));

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        c.rollback();
                        return new BidOutcome(null, null, 0);
                    }

                    String prevBidderRaw = rs.getString("prev_bidder");
                    UUID prevBidder = prevBidderRaw == null ? null : UUID.fromString(prevBidderRaw);
                    double prevBid = rs.getDouble("prev_bid");
                    RoundView view = mapRound(rs, "");

                    if (prevBidder != null) insertPayout(c, prevBidder, "MONEY", BigDecimal.valueOf(prevBid), null);

                    c.commit();
                    return new BidOutcome(view, prevBidder, prevBid);
                }
            } catch (SQLException err) {
                c.rollback();
                throw err;
            }
        }
    }

    /**
     * Closes a round whose timer has genuinely run out (re-checked against the database clock, since a
     * bid on another shard may have extended it). Queues the prize for the winner atomically.
     * Returns null when the round was extended or already closed.
     */
    public RoundView endRound(long roundId) throws SQLException {
        return closeRound(roundId, "AND ends_at <= " + NOW, false);
    }

    /** Cancels a round regardless of its timer and refunds the leader. */
    public RoundView cancelRound(long roundId) throws SQLException {
        return closeRound(roundId, "", true);
    }

    private RoundView closeRound(long roundId, String guard, boolean cancel) throws SQLException {
        String sql = "UPDATE cksmpbm_round SET state = 'ENDED', cancelled = ?, ended_at = " + NOW
                + " WHERE id = ? AND state = 'ACTIVE' " + guard + " RETURNING " + ROUND_COLS;

        try (Connection c = core.getConnection()) {
            c.setAutoCommit(false);

            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setBoolean(1, cancel);
                ps.setLong(2, roundId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        c.rollback();
                        return null;
                    }

                    RoundView view = mapRound(rs, "");
                    if (view.highBidder() != null) {
                        if (cancel) insertPayout(c, view.highBidder(), "MONEY", BigDecimal.valueOf(view.highBid()), null);
                        else insertPayout(c, view.highBidder(), "REWARD", BigDecimal.valueOf(view.highBid()), view.entryId());
                    }

                    c.commit();
                    return view;
                }
            } catch (SQLException err) {
                c.rollback();
                throw err;
            }
        }
    }

    /** Pulls the round's end to now so the leader's next tick closes it. Returns the new end, or -1. */
    public long expireRoundNow(long roundId) throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE cksmpbm_round SET ends_at = " + NOW + " WHERE id = ? AND state = 'ACTIVE' AND ends_at > " + NOW + " RETURNING ends_at")) {
            ps.setLong(1, roundId);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        }
    }

    public record HistoryRow(String entryId, String winnerName, double price, long endedAt) {}

    public List<HistoryRow> recentWinners(int limit) throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT entry_id, high_bidder_name, high_bid, ended_at FROM cksmpbm_round WHERE state = 'ENDED' AND NOT cancelled AND high_bidder IS NOT NULL ORDER BY ended_at DESC LIMIT ?")) {
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                List<HistoryRow> rows = new ArrayList<>(limit);
                while (rs.next()) rows.add(new HistoryRow(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getLong(4)));
                return rows;
            }
        }
    }

    // ---------------------------------------------------------------- prizes

    private static final String PRIZE_COLS = "id, enabled, weight, start_bid, min_increment, increment_percent, display_name, display_lore, icon, items, commands";

    public List<PoolEntry> loadPrizes() throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT " + PRIZE_COLS + " FROM cksmpbm_prize");
             ResultSet rs = ps.executeQuery()) {
            List<PoolEntry> prizes = new ArrayList<>();

            while (rs.next()) {
                prizes.add(new PoolEntry(
                        rs.getString("id"), rs.getBoolean("enabled"), rs.getInt("weight"),
                        rs.getDouble("start_bid"), rs.getDouble("min_increment"), rs.getDouble("increment_percent"),
                        new PoolEntry.Display(rs.getString("display_name"), PrizeCodec.decodeStrings(rs.getString("display_lore")),
                                PrizeCodec.decodeSpecOrNull(rs.getString("icon"))),
                        PrizeCodec.decodeItems(rs.getString("items")),
                        PrizeCodec.decodeStrings(rs.getString("commands"))));
            }

            return prizes;
        }
    }

    public int prizeCount() throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM cksmpbm_prize");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Insert or overwrite (used by the editor). */
    public void upsertPrize(PoolEntry prize) throws SQLException {
        writePrize(prize, "ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, weight = EXCLUDED.weight, start_bid = EXCLUDED.start_bid, "
                + "min_increment = EXCLUDED.min_increment, increment_percent = EXCLUDED.increment_percent, display_name = EXCLUDED.display_name, "
                + "display_lore = EXCLUDED.display_lore, icon = EXCLUDED.icon, items = EXCLUDED.items, commands = EXCLUDED.commands, updated_at = EXCLUDED.updated_at");
    }

    /** Insert only when absent (used to seed from items.yml, safe to race across shards). */
    public void insertPrizeIfAbsent(PoolEntry prize) throws SQLException {
        writePrize(prize, "ON CONFLICT (id) DO NOTHING");
    }

    private void writePrize(PoolEntry p, String conflict) throws SQLException {
        String sql = "INSERT INTO cksmpbm_prize(" + PRIZE_COLS + ", updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ") " + conflict;

        try (Connection c = core.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, p.id());
            ps.setBoolean(2, p.enabled());
            ps.setInt(3, p.weight());
            ps.setBigDecimal(4, BigDecimal.valueOf(p.startBid()));
            ps.setBigDecimal(5, BigDecimal.valueOf(p.minIncrement()));
            ps.setBigDecimal(6, BigDecimal.valueOf(p.incrementPercent()));
            ps.setString(7, p.display().name());
            ps.setString(8, PrizeCodec.encodeStrings(p.display().lore()));
            ps.setString(9, PrizeCodec.encodeSpecOrNull(p.display().icon()));
            ps.setString(10, PrizeCodec.encodeItems(p.items()));
            ps.setString(11, PrizeCodec.encodeStrings(p.commands()));
            ps.executeUpdate();
        }
    }

    public void deletePrize(String id) throws SQLException {
        try (Connection c = core.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM cksmpbm_prize WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- payouts

    public record Payout(String kind, double amount, String payload) {}

    public void insertPayout(UUID player, String kind, double amount, String payload) throws SQLException {
        try (Connection c = core.getConnection()) {
            insertPayout(c, player, kind, BigDecimal.valueOf(amount), payload);
        }
    }

    private void insertPayout(Connection c, UUID player, String kind, BigDecimal amount, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO cksmpbm_payout(player_uuid, kind, amount, payload, created_at) VALUES (?, ?, ?, ?, " + NOW + ")")) {
            ps.setString(1, player.toString());
            ps.setString(2, kind);
            ps.setBigDecimal(3, amount);
            ps.setString(4, payload);
            ps.executeUpdate();
        }
    }

    /** Atomically removes and returns everything owed to the player; only one shard can ever win this. */
    public List<Payout> takePayouts(UUID player) throws SQLException {
        try (Connection c = core.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM cksmpbm_payout WHERE player_uuid = ? RETURNING kind, amount, payload")) {
            ps.setString(1, player.toString());

            try (ResultSet rs = ps.executeQuery()) {
                List<Payout> rows = new ArrayList<>(2);
                while (rs.next()) rows.add(new Payout(rs.getString(1), rs.getDouble(2), rs.getString(3)));
                return rows;
            }
        }
    }

    public void restorePayouts(UUID player, List<Payout> payouts) throws SQLException {
        try (Connection c = core.getConnection()) {
            for (Payout p : payouts) insertPayout(c, player, p.kind(), BigDecimal.valueOf(p.amount()), p.payload());
        }
    }

    // ---------------------------------------------------------------- mapping

    private static RoundView mapRound(ResultSet rs, String prefix) throws SQLException {
        String bidder = rs.getString(prefix + "high_bidder");

        return new RoundView(
                rs.getLong(prefix + "id"),
                rs.getString(prefix + "entry_id"),
                rs.getInt(prefix + "round_no"),
                rs.getInt(prefix + "round_total"),
                rs.getDouble(prefix + "start_bid"),
                rs.getDouble(prefix + "high_bid"),
                bidder == null ? null : UUID.fromString(bidder),
                rs.getString(prefix + "high_bidder_name"),
                rs.getInt(prefix + "bid_count"),
                rs.getLong(prefix + "bids_open_at"),
                rs.getLong(prefix + "ends_at"),
                "ACTIVE".equals(rs.getString(prefix + "state"))
        );
    }
}
