package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.Settings;
import com.crescentkingdom.cksmpblackmarket.model.EventRow;
import com.crescentkingdom.cksmpblackmarket.model.EventRow.State;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.storage.BlackMarketDb;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import com.crescentkingdom.cksmpcore.Utils.DiscordRelay;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.sql.SQLException;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Drives events. Exactly one shard holds the Postgres lease at a time and runs this state machine;
 * everything it needs to resume (event, active round) is in the database, so a failover continues
 * mid-event instead of restarting. Every write is also guarded in SQL (unique slot / round indexes,
 * conditional UPDATEs), so a stalled leader waking up after its lease expired cannot do damage.
 * <p>
 * All work happens on one dedicated thread; the tick itself is a handful of timestamp comparisons and
 * only touches the database when something actually changes (plus one lease renewal per lease/3).
 */
public final class EventScheduler {

    private final CKSMPBlackMarket plugin;
    private final String holder;
    private final Random random = new Random();
    private final Queue<String> controls = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "CKSMPBlackMarket-Scheduler");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean leader;
    private long lastRenewAttemptMs;
    private long lastRenewOkMs;
    private long lastSlotAt;
    private EventRow event;
    private int announceIdx;
    private long openedRoundId = -1;
    private boolean warnedEmptyPool;

    public EventScheduler(CKSMPBlackMarket plugin, String holder) {
        this.plugin = plugin;
        this.holder = holder;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::safeTick, 2, 1, TimeUnit.SECONDS);
    }

    public void stop() {
        executor.shutdownNow();

        if (leader) {
            leader = false;
            try {
                plugin.db().releaseLease(holder);
            } catch (SQLException err) {
                plugin.getLogger().log(Level.WARNING, "Could not release the scheduler lease; it will expire on its own", err);
            }
        }
    }

    public boolean isLeader() {
        return leader;
    }

    /** Admin request (START / STOP / SKIP); ignored by non-leaders. */
    public void enqueue(String action) {
        if (leader) controls.add(action);
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable err) {
            plugin.getLogger().log(Level.SEVERE, "Scheduler tick failed", err);
        }
    }

    private BlackMarketDb db() {
        return plugin.db();
    }

    private void tick() throws SQLException {
        Config cfg = plugin.config();
        Settings s = cfg.settings();
        long now = System.currentTimeMillis();
        long leaseMs = s.leaseSeconds() * 1000L;

        if (!s.leaderEligible()) {
            if (leader) stepDown("no longer eligible");
            return;
        }

        if (leader && now - lastRenewOkMs > leaseMs) {
            stepDown("lease could not be renewed in time");
        }

        if (now - lastRenewAttemptMs >= leaseMs / 3) {
            lastRenewAttemptMs = now;

            try {
                boolean owned = db().acquireLease(holder, leaseMs);
                if (owned) lastRenewOkMs = now;

                if (owned && !leader) becomeLeader(now);
                else if (!owned && leader) stepDown("lease taken by another shard");
            } catch (SQLException err) {
                plugin.getLogger().log(Level.WARNING, "Lease renewal failed", err);
            }
        }

        if (!leader) return;

        for (String action; (action = controls.poll()) != null; ) control(cfg, action, now);

        if (event == null) {
            if (!closeOrphanRound(now)) maybeBegin(cfg, now);
            return;
        }

        switch (event.state()) {
            case ANNOUNCING -> announcing(cfg, now);
            case RUNNING -> running(cfg, now);
            case FINISHED -> event = null;
        }
    }

    // ---------------------------------------------------------------- leadership

    private void becomeLeader(long now) throws SQLException {
        lastSlotAt = db().lastSlotAt();
        event = db().loadOpenEvent();

        RoundView active = db().loadActiveRound();
        if (active != null) {
            plugin.state().apply(active);
            // Already open before we took over: do not announce it a second time.
            if (now >= active.opensAt()) openedRoundId = active.id();
        }

        if (event != null && event.state() == State.ANNOUNCING) {
            int remaining = (int) Math.ceil((event.nextAt() - now) / 1000.0);
            announceIdx = (int) plugin.config().settings().announceAt().stream().filter(mark -> mark > remaining).count();
        }

        leader = true;
        plugin.getLogger().info("This shard now runs the black market scheduler" + (event != null ? " (resuming event " + event.id() + ")" : "") + ".");
    }

    private void stepDown(String reason) {
        leader = false;
        event = null;
        controls.clear();
        plugin.getLogger().info("This shard stopped running the black market scheduler: " + reason + ".");
    }

    // ---------------------------------------------------------------- admin controls

    private void control(Config cfg, String action, long now) throws SQLException {
        switch (action) {
            case "START" -> {
                if (event == null) createEvent(cfg, 0, now + cfg.settings().manualAnnounceSeconds() * 1000L, now);
            }
            case "STOP" -> {
                if (event != null) cancelEvent("admin", now);
            }
            case "SKIP" -> {
                RoundView current = plugin.state().current();
                if (current != null && current.active()) {
                    long newEnd = db().expireRoundNow(current.id());
                    // The leader's own cache drives endRound; without this it would wait out the old timer.
                    if (newEnd >= 0) plugin.state().apply(current.withEndsAt(newEnd));
                }
            }
            default -> plugin.getLogger().warning("Unknown scheduler control '" + action + "'.");
        }
    }

    // ---------------------------------------------------------------- state machine

    /**
     * A round left active with no open event (the previous leader died between closing the event and the
     * round) would block bidding forever. Close it through the normal path once its timer is up.
     */
    private boolean closeOrphanRound(long now) throws SQLException {
        RoundView current = plugin.state().current();
        if (current == null || !current.active()) return false;

        if (now >= current.endsAt()) {
            RoundView ended = db().endRound(current.id());
            if (ended != null) {
                plugin.state().apply(ended);
                plugin.sync().publishRoundEnd(ended);
                discordWinner(ended);
            } else {
                RoundView fresh = db().loadRound(current.id());
                if (fresh != null) plugin.state().apply(fresh);
            }
        }

        return true;
    }

    private void maybeBegin(Config cfg, long now) throws SQLException {
        Settings s = cfg.settings();
        // Slots older than the grace window are skipped by construction (and the search never starts at 1970).
        long after = Math.max(lastSlotAt, now - s.lateGraceSeconds() * 1000L - 1);
        long slot = s.firstSlotAfter(after);
        if (slot < 0) return;

        if (now >= slot - s.announceSeconds() * 1000L) createEvent(cfg, slot, slot, now);
    }

    private void createEvent(Config cfg, long slotAt, long startAt, long now) throws SQLException {
        List<String> ids = cfg.pool().draw(cfg.settings().rounds(), random);

        if (ids.isEmpty()) {
            if (!warnedEmptyPool) plugin.getLogger().warning("items.yml has no valid pool entries; no event can run.");
            warnedEmptyPool = true;
            if (slotAt > 0) lastSlotAt = Math.max(lastSlotAt, slotAt);
            return;
        }
        warnedEmptyPool = false;

        EventRow created = db().insertEvent(slotAt, ids, startAt, now);
        if (slotAt > 0) lastSlotAt = Math.max(lastSlotAt, slotAt);

        if (created == null) {
            event = db().loadOpenEvent();
            return;
        }

        event = created;
        announceIdx = 0;

        long untilStart = Math.max(1, (startAt - now) / 1000);
        discord("discord.event-start",
                Placeholder.unparsed("seconds", Long.toString(untilStart)),
                Placeholder.unparsed("rounds", Integer.toString(ids.size())));
    }

    private void announcing(Config cfg, long now) throws SQLException {
        List<Integer> marks = cfg.settings().announceAt();
        int remaining = (int) Math.ceil((event.nextAt() - now) / 1000.0);

        boolean fire = false;
        while (announceIdx < marks.size() && remaining <= marks.get(announceIdx)) {
            announceIdx++;
            fire = true;
        }
        if (fire && remaining > 0) plugin.sync().publishAnnounce(remaining, event.poolIds().size());

        if (now < event.nextAt()) return;

        if (!enoughPlayers(cfg.settings())) {
            cancelEvent("low-players", now);
            return;
        }

        beginRound(cfg, 0, now);
    }

    private void running(Config cfg, long now) throws SQLException {
        RoundView current = plugin.state().current();

        if (current != null && current.active()) {
            if (openedRoundId != current.id() && now >= current.opensAt()) {
                openedRoundId = current.id();
                plugin.sync().publishBiddingOpen(current);
            }

            if (now >= current.endsAt()) endRound(cfg, current, now);
            return;
        }

        if (now < event.nextAt()) return;

        int next = event.roundIndex() + 1;
        if (next >= event.poolIds().size()) finishEvent(now);
        else beginRound(cfg, next, now);
    }

    private void beginRound(Config cfg, int index, long now) throws SQLException {
        List<String> ids = event.poolIds();

        // A prize removed from items.yml since the draw is skipped rather than aborting the event.
        PoolEntry entry = null;
        int idx = index;
        while (idx < ids.size() && (entry = cfg.pool().get(ids.get(idx))) == null) idx++;

        if (entry == null) {
            finishEvent(now);
            return;
        }

        RoundView round = db().insertRound(event.id(), entry, idx + 1, ids.size(),
                cfg.settings().previewSeconds() * 1000L, cfg.settings().roundSeconds() * 1000L);
        if (round == null) {
            event = db().loadOpenEvent();
            return;
        }

        event = event.with(State.RUNNING, idx, round.endsAt());
        db().updateEvent(event);

        plugin.state().apply(round);
        plugin.sync().publishRoundStart(round);
    }

    private void endRound(Config cfg, RoundView current, long now) throws SQLException {
        RoundView ended = db().endRound(current.id());

        if (ended == null) {
            // Extended by a bid we have not heard about yet, or closed elsewhere: take the database's word.
            RoundView fresh = db().loadRound(current.id());
            if (fresh != null) plugin.state().apply(fresh);
            return;
        }

        plugin.state().apply(ended);
        plugin.sync().publishRoundEnd(ended);
        discordWinner(ended);

        if (event.roundIndex() + 1 >= event.poolIds().size()) {
            finishEvent(now);
            return;
        }

        event = event.with(State.RUNNING, event.roundIndex(), now + cfg.settings().breakSeconds() * 1000L);
        db().updateEvent(event);
    }

    private void finishEvent(long now) throws SQLException {
        db().updateEvent(event.with(State.FINISHED, event.roundIndex(), now));
        event = null;
        plugin.sync().publishEventEnd();
    }

    /** Closes the event without prizes: the leading bid, if any, is refunded. */
    private void cancelEvent(String reason, long now) throws SQLException {
        RoundView closed = null;
        RoundView current = plugin.state().current();

        if (current != null && current.active()) {
            closed = db().cancelRound(current.id());
            if (closed != null) plugin.state().apply(closed);
        }

        db().updateEvent(event.with(State.FINISHED, event.roundIndex(), now));
        event = null;
        plugin.sync().publishCancel(reason, closed);
    }

    private void discordWinner(RoundView ended) {
        if (ended.highBidder() == null) return;

        Config cfg = plugin.config();
        discord("discord.round-won",
                Placeholder.unparsed("player", String.valueOf(ended.highBidderName())),
                Placeholder.component("item", plugin.rewards().name(cfg, ended.entryId(), cfg.pool().get(ended.entryId()))),
                Placeholder.component("amount", Text.money(cfg.lang(), ended.highBid())));
    }

    /** Posts to the Discord chat relay through CKSMPCore. Leader only, so each message goes out once. */
    private void discord(String key, TagResolver... tags) {
        Config cfg = plugin.config();
        if (!cfg.settings().discordEnabled()) return;

        DiscordRelay.sendSystem(plugin.core().getPluginInstance(), cfg.settings().discordSender(), cfg.lang().plain(key, tags));
    }

    private boolean enoughPlayers(Settings s) {
        if (s.minOnlinePlayers() == 0) return true;

        try {
            return plugin.core().getRedisManager().getRedisClient().hlen("minecraft:smp:players") >= s.minOnlinePlayers();
        } catch (Exception err) {
            plugin.getLogger().warning("Could not read the network player count (" + err.getMessage() + "); starting anyway.");
            return true;
        }
    }
}
