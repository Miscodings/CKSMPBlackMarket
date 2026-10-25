package com.crescentkingdom.cksmpblackmarket.listener;

import com.crescentkingdom.cksmpblackmarket.CKSMPBlackMarket;
import com.crescentkingdom.cksmpcore.Events.Player.PlayerDataLoadEvent;
import com.crescentkingdom.cksmpitems.Events.Items.CKSMPItemsLoadedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {

    private final CKSMPBlackMarket plugin;

    public PlayerListener(CKSMPBlackMarket plugin) {
        this.plugin = plugin;
    }

    /** Core has loaded the wallet by now, so refunds and prizes can safely be paid on this shard. */
    @EventHandler
    public void onDataLoad(PlayerDataLoadEvent event) {
        Player player = event.getPlayer();

        plugin.payouts().claim(player.getUniqueId());
        player.getScheduler().run(plugin, task -> plugin.bossBar().onJoin(player), null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.bids().forget(event.getPlayer().getUniqueId());
        plugin.gui().remove(event.getPlayer().getUniqueId());
    }

    /** CKSMPItems fills its catalogue after startup; names cached before that would be placeholders. */
    @EventHandler
    public void onItemsLoaded(CKSMPItemsLoadedEvent event) {
        plugin.rewards().invalidate();
    }
}
