package com.crescentkingdom.cksmpblackmarket.hook;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * %blackmarket_status% (active|idle), _item, _round, _rounds, _high_bid, _next_bid, _leader,
 * _time_left (seconds), _next_event (seconds until the next scheduled start, -1 if none).
 * Reads only the cached round, so it is safe to call every tick from scoreboards.
 */
public final class BlackMarketExpansion extends PlaceholderExpansion {

    private final CKSMPBlackMarket plugin;

    public BlackMarketExpansion(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "blackmarket";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Miscodings";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        Config cfg = plugin.config();
        long now = System.currentTimeMillis();
        RoundView round = plugin.state().current();
        boolean live = round != null && round.isActiveAt(now);

        return switch (params) {
            case "status" -> live ? "active" : "idle";
            case "next_event" -> Long.toString(Math.max(-1, cfg.settings().firstSlotAfter(now) < 0 ? -1 : (cfg.settings().firstSlotAfter(now) - now) / 1000));
            case "item" -> {
                if (!live) yield "";
                PoolEntry entry = cfg.pool().get(round.entryId());
                yield PlainTextComponentSerializer.plainText().serialize(plugin.rewards().name(cfg, round.entryId(), entry));
            }
            case "round" -> live ? Integer.toString(round.roundNo()) : "0";
            case "rounds" -> live ? Integer.toString(round.roundTotal()) : "0";
            case "high_bid" -> live ? Text.plainMoney(round.highBid()) : "0";
            case "next_bid" -> {
                PoolEntry entry = live ? cfg.pool().get(round.entryId()) : null;
                yield entry == null ? "0" : Text.plainMoney(entry.nextBid(round));
            }
            case "leader" -> live && round.highBidder() != null ? round.highBidderName() : "";
            case "time_left" -> live ? Long.toString(Text.secondsUntil(round.endsAt(), now)) : "0";
            default -> null;
        };
    }
}
