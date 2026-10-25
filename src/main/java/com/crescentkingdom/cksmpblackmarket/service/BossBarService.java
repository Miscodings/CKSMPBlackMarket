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

/** One shared boss bar per shard, shown to everyone while a round is live. Global-region thread only. */
public final class BossBarService {

    private final CKSMPBlackMarket plugin;
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
    private volatile boolean shown;

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

        bar.name(cfg.lang().component("bossbar.title", TagResolver.resolver(
                RoundText.resolver(plugin, cfg, round, now),
                Placeholder.component("amount", Text.money(cfg.lang(), round.highBid())))));
        bar.progress((float) Math.max(0.0, Math.min(1.0, (round.endsAt() - now) / (s.roundSeconds() * 1000.0))));
        bar.color(s.bossBarColor());
        bar.overlay(s.bossBarOverlay());

        if (!shown) {
            Bukkit.getServer().showBossBar(bar);
            shown = true;
        }
    }

    /** Players who connect mid-round still get the bar. Safe from any thread. */
    public void onJoin(Player player) {
        if (shown) player.showBossBar(bar);
    }

    public void hide() {
        if (!shown) return;

        Bukkit.getServer().hideBossBar(bar);
        shown = false;
    }
}
