package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpblackmarket.config.Config;
import com.crescentkingdom.cksmpblackmarket.config.Settings;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One shared boss bar per shard, shown while a round is live to players standing in the market area.
 * The once-a-second update also adds players who walk in and removes those who leave. Global-region thread only.
 */
public final class BossBarService {

    private final CKSMPBlackMarket plugin;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    private final Set<UUID> viewers = new HashSet<>();

    public BossBarService(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    public void update() {
        Config cfg = plugin.config();
        Settings s = cfg.settings();
        RoundView round = plugin.state().current();
        long now = System.currentTimeMillis();

        boolean live = s.bossBarEnabled() && s.participate() && round != null && round.isActiveAt(now);
        if (!live) {
            hide();
            return;
        }

        boolean preview = round.isPreviewAt(now);
        bar.name(cfg.lang().component(preview ? "bossbar.preview" : "bossbar.title", TagResolver.resolver(
                RoundText.resolver(plugin, cfg, round, now),
                Placeholder.component("amount", Text.money(cfg.lang(), round.highBid())))));

        double span = preview ? s.previewSeconds() * 1000.0 : s.roundSeconds() * 1000.0;
        long until = preview ? round.opensAt() : round.endsAt();
        bar.progress((float) Math.max(0.0, Math.min(1.0, (until - now) / Math.max(1.0, span))));
        bar.color(s.bossBarColor());
        bar.overlay(s.bossBarOverlay());

        syncViewers();
    }

    /** Shows the bar to eligible players who do not have it and takes it from those who left the area. */
    private void syncViewers() {
        Set<UUID> online = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            online.add(player.getUniqueId());

            if (plugin.audiences().eligible(player)) {
                if (viewers.add(player.getUniqueId())) player.showBossBar(bar);
            } else if (viewers.remove(player.getUniqueId())) {
                player.hideBossBar(bar);
            }
        }

        viewers.retainAll(online);
    }

    public void hide() {
        if (viewers.isEmpty()) return;

        Bukkit.getServer().hideBossBar(bar);
        viewers.clear();
    }
}
