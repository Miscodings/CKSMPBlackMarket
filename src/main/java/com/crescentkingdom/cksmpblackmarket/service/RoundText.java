package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.function.Supplier;

/** Builds the placeholder set shared by chat, boss bar, menu and status output. */
public final class RoundText {

    private RoundText() {}

    /**
     * Money / time / name values are computed only if the template actually contains their tag: the menu and
     * boss bar use different subsets, and building components each second for unused tags is wasted work.
     * Player names go in as unparsed text, so a name can never inject MiniMessage tags.
     */
    public static TagResolver resolver(CKSMPBlackMarket plugin, Config cfg, RoundView v, long nowMs) {
        PoolEntry entry = cfg.pool().get(v.entryId());

        return TagResolver.resolver(
                lazy("item", () -> plugin.rewards().name(cfg, v.entryId(), entry)),
                Placeholder.unparsed("round", Integer.toString(v.roundNo())),
                Placeholder.unparsed("rounds", Integer.toString(v.roundTotal())),
                Placeholder.unparsed("bids", Integer.toString(v.bidCount())),
                lazy("current-bid", () -> Text.money(cfg.lang(), v.highBid())),
                lazy("next-bid", () -> entry == null ? Component.empty() : Text.money(cfg.lang(), entry.nextBid(v))),
                lazy("start-bid", () -> Text.money(cfg.lang(), v.startBid())),
                lazy("leader", () -> v.highBidder() == null
                        ? cfg.lang().format(cfg.lang().raw("no-leader-name"))
                        : Component.text(v.highBidderName())),
                lazy("time", () -> Text.time(cfg.lang(), v.active() ? Text.secondsUntil(v.endsAt(), nowMs) : 0)),
                // Plain numbers for sentences like "<seconds> seconds left", as AxDarkAuctions words them.
                Placeholder.unparsed("seconds", Long.toString(v.active() ? Text.secondsUntil(v.endsAt(), nowMs) : 0)),
                Placeholder.unparsed("opens-in", Long.toString(v.active() ? Text.secondsUntil(v.opensAt(), nowMs) : 0)),
                lazy("opens-in-time", () -> Text.time(cfg.lang(), v.active() ? Text.secondsUntil(v.opensAt(), nowMs) : 0))
        );
    }

    public static TagResolver lazy(String name, Supplier<Component> value) {
        return TagResolver.resolver(name, (args, context) -> Tag.selfClosingInserting(value.get()));
    }
}
