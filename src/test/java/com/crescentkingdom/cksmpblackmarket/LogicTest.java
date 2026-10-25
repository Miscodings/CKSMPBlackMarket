package com.crescentkingdom.cksmpblackmarket;

import com.crescentkingdom.cksmpblackmarket.config.Pool;
import com.crescentkingdom.cksmpblackmarket.config.Settings;
import com.crescentkingdom.cksmpblackmarket.model.PoolEntry;
import com.crescentkingdom.cksmpblackmarket.model.RoundView;
import com.crescentkingdom.cksmpblackmarket.service.MarketState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicTest {

    private static final Logger LOG = Logger.getLogger("test");

    private static RoundView round(long id, int bids, double high, boolean active, long endsAt) {
        UUID bidder = bids == 0 ? null : UUID.randomUUID();
        return new RoundView(id, "x", 1, 3, 100, high, bidder, bidder == null ? null : "Bob", bids, 0, endsAt, active);
    }

    private static PoolEntry entry(double start, double minInc, double pct) {
        return new PoolEntry("x", true, 1, start, minInc, pct, PoolEntry.Display.none(), List.of(), List.of("say hi"));
    }

    @Test
    void nextBidIsStartThenRaisesByMaxOfFlatAndPercent() {
        PoolEntry e = entry(100, 10, 0.05);

        assertEquals(100.0, e.nextBid(round(1, 0, 0, true, 1)));
        assertEquals(110.0, e.nextBid(round(1, 1, 100, true, 1)));      // 5% = 5 < flat 10
        assertEquals(1050.0, e.nextBid(round(1, 2, 1000, true, 1)));     // 5% = 50 > flat 10
        assertEquals(1299.38, e.nextBid(round(1, 3, 1237.5, true, 1))); // 61.875 raise rounds up to the cent
    }

    @Test
    void marketStateOrdersUpdates() {
        MarketState state = new MarketState();

        assertTrue(state.apply(round(5, 1, 100, true, 10)));
        assertFalse(state.apply(round(5, 0, 100, true, 10)), "fewer bids is stale");
        assertTrue(state.apply(round(5, 2, 110, true, 10)));
        assertTrue(state.apply(round(5, 2, 110, false, 10)), "ended replaces active");
        assertFalse(state.apply(round(5, 9, 999, true, 10)), "ended is final");
        assertFalse(state.apply(round(4, 1, 1, true, 10)), "older round ignored");
        assertTrue(state.apply(round(6, 0, 0, true, 10)), "newer round wins");
    }

    @Test
    void slotSearchFindsNextTimeAndNeverScansFrom1970() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schedule.zone", "America/New_York");
        yaml.set("schedule.times", List.of("18:00", "21:30"));
        Settings s = Settings.parse(yaml, LOG);

        ZoneId zone = ZoneId.of("America/New_York");
        ZonedDateTime base = ZonedDateTime.of(2026, 10, 25, 19, 0, 0, 0, zone);

        long next = s.firstSlotAfter(base.toInstant().toEpochMilli());
        assertEquals(ZonedDateTime.of(2026, 10, 25, 21, 30, 0, 0, zone).toInstant().toEpochMilli(), next);

        long nextDay = s.firstSlotAfter(next);
        assertEquals(ZonedDateTime.of(2026, 10, 26, 18, 0, 0, 0, zone).toInstant().toEpochMilli(), nextDay);

        yaml.set("schedule.times", List.of());
        assertEquals(-1, Settings.parse(yaml, LOG).firstSlotAfter(0));
    }

    @Test
    void weekdayFilterIsHonoured() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schedule.zone", "UTC");
        yaml.set("schedule.times", List.of("12:00"));
        yaml.set("schedule.days", List.of("friday"));
        Settings s = Settings.parse(yaml, LOG);

        long monday = ZonedDateTime.of(2026, 10, 26, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli();
        ZonedDateTime slot = java.time.Instant.ofEpochMilli(s.firstSlotAfter(monday)).atZone(ZoneId.of("UTC"));
        assertEquals(java.time.DayOfWeek.FRIDAY, slot.getDayOfWeek());
    }

    @Test
    void poolDrawIsDistinctAndWeighted() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (String id : List.of("a", "b", "c")) {
            yaml.set("pool." + id + ".weight", id.equals("a") ? 98 : 1);
            yaml.set("pool." + id + ".start-bid", 10);
            yaml.set("pool." + id + ".reward.commands", List.of("say " + id));
        }
        Pool pool = Pool.of(Pool.parseYaml(yaml, LOG));
        assertNotNull(pool.get("a"));

        Random random = new Random(7);
        Map<String, Integer> firstPicks = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            List<String> draw = pool.draw(3, random);
            assertEquals(3, draw.size());
            assertEquals(3, Set3.of(draw).size(), "no repeats within one draw");
            firstPicks.merge(draw.getFirst(), 1, Integer::sum);
        }

        assertTrue(firstPicks.get("a") > 900, "heavy entry should lead almost always: " + firstPicks);
        assertEquals(3, pool.draw(10, random).size(), "never more than the pool holds");
    }

    /** Tiny helper so the test reads without another import. */
    private static final class Set3 {
        static java.util.Set<String> of(List<String> list) {
            return new java.util.HashSet<>(list);
        }
    }
}
