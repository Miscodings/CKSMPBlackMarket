package com.crescentkingdom.cksmpblackmarket.sync;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.service.RoundText;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Redis fan-out. Every state change is published once by whichever shard caused it and applied by every
 * shard (the publisher included), so there is exactly one code path that touches chat and caches.
 * Handlers run on the Redis listener thread: cache updates happen inline, anything touching Bukkit
 * is hopped onto the global region scheduler.
 */
public final class SyncService {

    private static final String ROUND_START = "round_start";
    private static final String BID = "bid";
    private static final String ROUND_END = "round_end";
    private static final String ANNOUNCE = "announce";
    private static final String EVENT_END = "event_end";
    private static final String CANCEL = "cancel";
    private static final String CONTROL = "control";

    private final CKSMPBlackMarket plugin;

    public SyncService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    public void register() {
        plugin.core().getRedisManager().registerPackets(List.of(new BmPacket(plugin)));
    }

    // ---------------------------------------------------------------- publishing

    public void publishRoundStart(RoundView round) {
        JsonObject json = message(ROUND_START);
        json.add("round", round.toJson());
        publish(json);
    }

    public void publishBid(RoundView round, UUID previousBidder, double previousBid) {
        JsonObject json = message(BID);
        json.add("round", round.toJson());
        if (previousBidder != null) {
            json.addProperty("prev", previousBidder.toString());
            json.addProperty("prevBid", previousBid);
        }
        publish(json);
    }

    public void publishRoundEnd(RoundView round) {
        JsonObject json = message(ROUND_END);
        json.add("round", round.toJson());
        publish(json);
    }

    public void publishAnnounce(int secondsLeft, int rounds) {
        JsonObject json = message(ANNOUNCE);
        json.addProperty("seconds", secondsLeft);
        json.addProperty("rounds", rounds);
        publish(json);
    }

    public void publishEventEnd() {
        publish(message(EVENT_END));
    }

    /**
     * {@code reason} completes the lang key event.cancelled-*, e.g. "low-players" or "admin".
     * {@code closedRound} is the round that was refunded and closed, or null when none was running.
     */
    public void publishCancel(String reason, RoundView closedRound) {
        JsonObject json = message(CANCEL);
        json.addProperty("reason", reason);
        if (closedRound != null) json.add("round", closedRound.toJson());
        publish(json);
    }

    /** Admin request, acted on by the leader only: START, STOP or SKIP. */
    public void publishControl(String action) {
        JsonObject json = message(CONTROL);
        json.addProperty("action", action);
        publish(json);
    }

    private static JsonObject message(String type) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        return json;
    }

    private void publish(JsonObject json) {
        plugin.core().getRedisManager().sendPacket(new BmPacket(plugin, json));
    }

    // ---------------------------------------------------------------- receiving

    public void handle(JsonObject data) {
        try {
            String type = data.get("type").getAsString();

            switch (type) {
                case ROUND_START -> onRoundStart(RoundView.fromJson(data.getAsJsonObject("round")));
                case BID -> onBid(data);
                case ROUND_END -> onRoundEnd(RoundView.fromJson(data.getAsJsonObject("round")));
                case ANNOUNCE -> onAnnounce(data.get("seconds").getAsInt(), data.get("rounds").getAsInt());
                case EVENT_END -> onEventEnd();
                case CANCEL -> onCancel(data);
                case CONTROL -> plugin.scheduler().enqueue(data.get("action").getAsString());
                default -> plugin.getLogger().warning("Unknown sync message type: " + type);
            }

            // Anything that changed the round must show up in open menus immediately, not at the next tick.
            if (type.equals(ROUND_START) || type.equals(BID) || type.equals(ROUND_END) || type.equals(CANCEL)) {
                onGlobal(plugin.gui()::refreshAll);
            }
        } catch (Exception err) {
            plugin.getLogger().log(Level.SEVERE, "Failed to handle sync message " + data, err);
        }
    }

    private void onRoundStart(RoundView round) {
        plugin.state().apply(round);
        if (!plugin.config().settings().participate()) return;

        onGlobal(() -> {
            Config cfg = plugin.config();
            cfg.lang().sendLines(plugin.audiences().announcements(), "round.start", RoundText.resolver(plugin, cfg, round, System.currentTimeMillis()));
            cfg.play(plugin.audiences().announcements(), "round-start");
            plugin.bossBar().update();
        });
    }

    private void onBid(JsonObject data) {
        RoundView round = RoundView.fromJson(data.getAsJsonObject("round"));
        boolean fresh = plugin.state().apply(round);
        if (!fresh || !plugin.config().settings().participate()) return;

        UUID previous = data.has("prev") ? UUID.fromString(data.get("prev").getAsString()) : null;
        double previousBid = data.has("prevBid") ? data.get("prevBid").getAsDouble() : 0;

        onGlobal(() -> {
            Config cfg = plugin.config();
            TagResolver tags = TagResolver.resolver(
                    RoundText.resolver(plugin, cfg, round, System.currentTimeMillis()),
                    Placeholder.component("amount", Text.money(cfg.lang(), round.highBid())),
                    Placeholder.unparsed("player", String.valueOf(round.highBidderName())));

            if (cfg.settings().broadcastBids()) cfg.lang().send(plugin.audiences().announcements(), "bid.broadcast", tags);

            if (previous != null) {
                Player outbid = Bukkit.getPlayer(previous);
                if (outbid != null) {
                    // First resolver wins on a name clash, so the refund amount must come before the shared tags.
                    TagResolver outbidTags = TagResolver.resolver(Placeholder.component("amount", Text.money(cfg.lang(), previousBid)), tags);
                    outbid.getScheduler().run(plugin, task -> {
                        cfg.lang().send(outbid, "bid.outbid", outbidTags);
                        cfg.play(outbid, "outbid");
                        plugin.payouts().claim(previous);
                    }, null);
                }
            }
        });
    }

    private void onRoundEnd(RoundView round) {
        plugin.state().apply(round);
        if (!plugin.config().settings().participate()) return;

        onGlobal(() -> {
            Config cfg = plugin.config();
            TagResolver tags = TagResolver.resolver(
                    RoundText.resolver(plugin, cfg, round, System.currentTimeMillis()),
                    Placeholder.component("amount", Text.money(cfg.lang(), round.highBid())),
                    Placeholder.unparsed("player", String.valueOf(round.highBidderName())));

            plugin.bossBar().update();

            if (round.highBidder() == null) {
                cfg.lang().send(plugin.audiences().announcements(), "round.no-bids", tags);
                return;
            }

            cfg.lang().send(plugin.audiences().announcements(), "round.won", tags);

            Player winner = Bukkit.getPlayer(round.highBidder());
            if (winner != null) {
                winner.getScheduler().run(plugin, task -> {
                    cfg.lang().send(winner, "round.you-won", tags);
                    cfg.play(winner, "win");
                    plugin.payouts().claim(round.highBidder());
                }, null);
            }
        });
    }

    private void onAnnounce(int seconds, int rounds) {
        if (!plugin.config().settings().participate()) return;

        onGlobal(() -> {
            Config cfg = plugin.config();
            cfg.lang().sendLines(plugin.audiences().announcements(), "event.announce",
                    Placeholder.component("time", Text.time(cfg.lang(), seconds)),
                    Placeholder.unparsed("rounds", Integer.toString(rounds)));
            cfg.play(plugin.audiences().announcements(), "event-announce");
        });
    }

    private void onEventEnd() {
        if (!plugin.config().settings().participate()) return;

        onGlobal(() -> {
            plugin.config().lang().send(plugin.audiences().announcements(), "event.finished");
            plugin.bossBar().update();
        });
    }

    private void onCancel(JsonObject data) {
        if (data.has("round")) {
            RoundView closed = RoundView.fromJson(data.getAsJsonObject("round"));
            plugin.state().apply(closed);
            // The leader's refund is in the ledger; the leading bidder's shard collects it.
            if (closed.highBidder() != null) plugin.payouts().claim(closed.highBidder());
        }

        if (!plugin.config().settings().participate()) return;

        String reason = data.get("reason").getAsString();
        onGlobal(() -> {
            plugin.config().lang().send(plugin.audiences().announcements(), "event.cancelled-" + reason);
            plugin.bossBar().update();
        });
    }

    private void onGlobal(Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }
}
