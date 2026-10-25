package com.crescentkingdom.cksmpblackmarket;

import com.crescentkingdom.cksmpblackmarket.config.Lang;
import com.crescentkingdom.cksmpblackmarket.config.Settings;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled default files must parse and contain everything the code asks for. */
class ResourcesTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static YamlConfiguration load(String path) {
        var stream = ResourcesTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, path);
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test
    void configMatchesAxDarkAuctionsPacing() {
        Settings s = Settings.parse(load("config.yml"), LOG);

        assertEquals(120, s.intervalMinutes());
        assertEquals(30, s.offsetMinutes());
        assertEquals(60, s.announceSeconds());
        assertEquals(5, s.previewSeconds());
        assertEquals(15, s.roundSeconds());
        assertEquals(8, s.snipeWindowSeconds());
        assertEquals(8, s.snipeExtendSeconds());
        assertEquals(5, s.breakSeconds());
        assertTrue(s.broadcastBids());
        assertNotNull(s.sound("bidding-open"));
    }

    /** Structure only: MenuLayout.parse validates materials through a live server registry. */
    @Test
    void menuYamlHasBothPhasesAndTiers() {
        YamlConfiguration menu = load("Menus/bidding.yml");

        assertEquals(6, menu.getInt("menu.rows"));
        for (String phase : new String[]{"bidding", "preview"}) {
            assertNotNull(menu.getConfigurationSection("menu." + phase + ".items.reward"), phase + " reward");
            assertNotNull(menu.getConfigurationSection("menu." + phase + ".items.clock"), phase + " clock");
        }
        assertNotNull(menu.getConfigurationSection("menu.bidding.items.bid"));
        assertNotNull(menu.getConfigurationSection("menu.leading"));
        assertEquals(5, menu.getConfigurationSection("menu.bid-materials").getKeys(false).size());
    }

    @Test
    void langHasEveryKeyTheCodeUses() {
        Lang lang = Lang.parse(load("Lang/messages.yml"));

        for (String key : new String[]{
                "prefix", "money-format", "no-leader-name", "time.second", "time.minute", "time.hour", "time.day",
                "bid.no-round", "bid.not-open", "bid.already-leading", "bid.insufficient", "bid.withdraw-failed",
                "bid.outraced", "bid.error", "bid.placed", "bid.outbid", "bid.broadcast",
                "payout.refund", "payout.reward", "payout.stashed", "payout.reward-missing",
                "event.started", "event.finished", "event.cancelled-low-players", "event.cancelled-admin",
                "round.waiting", "round.showing", "round.bidding", "round.no-bids", "round.won", "round.you-won",
                "bossbar.preview", "bossbar.title", "status.active", "status.idle-next", "status.idle-none",
                "history.header", "history.line", "history.empty", "discord.event-start", "discord.round-won",
                "general.reloaded", "general.reload-failed", "menu.idle-name", "menu.idle-none",
                "admin.start-sent", "admin.stop-sent", "admin.skip-sent", "admin.no-leader",
                "editor.list.title", "editor.detail.title", "editor.rewards.title", "editor.msg.added"}) {
            assertFalse(lang.raw(key).isEmpty(), "missing lang key " + key);
        }

        for (String list : new String[]{"event.announce", "help.player", "help.admin", "menu.idle-lore"}) {
            assertFalse(lang.components(list).get(0).toString().contains("missing lang"), "missing lang list " + list);
        }
    }
}
