package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Builds the placeholder set shared by chat, boss bar, menu and status output. */
public final class RoundText {

    private RoundText() {}

    /** Player names go in as unparsed text, so a name can never inject MiniMessage tags. */
    public static TagResolver resolver(CKSMPBlackMarket plugin, Config cfg, RoundView v, long nowMs) {
        PoolEntry entry = cfg.pool().get(v.entryId());
        long seconds = v.active() ? Text.secondsUntil(v.endsAt(), nowMs) : 0;

        Component leader = v.highBidder() == null
                ? cfg.lang().format(cfg.lang().raw("no-leader-name"))
                : Component.text(v.highBidderName());

        return TagResolver.resolver(
                Placeholder.component("item", plugin.rewards().name(cfg, v.entryId(), entry)),
                Placeholder.unparsed("round", Integer.toString(v.roundNo())),
                Placeholder.unparsed("rounds", Integer.toString(v.roundTotal())),
                Placeholder.component("current-bid", Text.money(cfg.lang(), v.highBid())),
                Placeholder.component("next-bid", entry == null ? Component.empty() : Text.money(cfg.lang(), entry.nextBid(v))),
                Placeholder.component("start-bid", Text.money(cfg.lang(), v.startBid())),
                Placeholder.component("leader", leader),
                Placeholder.unparsed("bids", Integer.toString(v.bidCount())),
                Placeholder.component("time", Text.time(cfg.lang(), seconds))
        );
    }
}
