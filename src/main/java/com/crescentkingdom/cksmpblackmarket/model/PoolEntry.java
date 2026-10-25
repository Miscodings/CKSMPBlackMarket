package com.crescentkingdom.cksmpblackmarket.model;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** One auctionable prize. Stored in Postgres so the in-game editor's changes reach every shard. */
public record PoolEntry(
        String id,
        boolean enabled,
        int weight,
        double startBid,
        double minIncrement,
        double incrementPercent,
        Display display,
        List<ItemSpec> items,
        List<String> commands
) {

    /**
     * A reward item. Exactly one source is set: {@code stack} (a full serialized ItemStack, so custom
     * enchants, NBT and components survive exactly), a CKSMPItems id, or a vanilla material.
     */
    public record ItemSpec(String cksmpItem, Material material, int amount, String stack) {

        public static ItemSpec ofStack(String base64Stack) {
            return new ItemSpec(null, null, 0, base64Stack);
        }
    }

    /** Menu icon. Null fields fall back to the first reward item. */
    public record Display(String name, List<String> lore, ItemSpec icon) {

        public static Display none() {
            return new Display(null, List.of(), null);
        }
    }

    public static final double DEFAULT_START_BID = 1000;
    public static final double DEFAULT_MIN_INCREMENT = 100;
    public static final double DEFAULT_INCREMENT_PERCENT = 0.05;
    public static final int DEFAULT_WEIGHT = 10;

    public static PoolEntry created(String id, ItemSpec first) {
        return new PoolEntry(id, true, DEFAULT_WEIGHT, DEFAULT_START_BID, DEFAULT_MIN_INCREMENT,
                DEFAULT_INCREMENT_PERCENT, Display.none(), List.of(first), List.of());
    }

    public PoolEntry withEnabled(boolean value) {
        return new PoolEntry(id, value, weight, startBid, minIncrement, incrementPercent, display, items, commands);
    }

    public PoolEntry withWeight(int value) {
        return new PoolEntry(id, enabled, value, startBid, minIncrement, incrementPercent, display, items, commands);
    }

    public PoolEntry withStartBid(double value) {
        return new PoolEntry(id, enabled, weight, value, minIncrement, incrementPercent, display, items, commands);
    }

    public PoolEntry withMinIncrement(double value) {
        return new PoolEntry(id, enabled, weight, startBid, value, incrementPercent, display, items, commands);
    }

    public PoolEntry withIncrementPercent(double value) {
        return new PoolEntry(id, enabled, weight, startBid, minIncrement, value, display, items, commands);
    }

    public PoolEntry withItems(List<ItemSpec> value) {
        return new PoolEntry(id, enabled, weight, startBid, minIncrement, incrementPercent, display, List.copyOf(new ArrayList<>(value)), commands);
    }

    /** Exact amount the next bid must reach; mirrored by the SQL guard in BlackMarketDb#placeBid. */
    public double nextBid(RoundView round) {
        if (round.bidCount() == 0) return startBid;
        BigDecimal high = BigDecimal.valueOf(round.highBid());
        BigDecimal raise = high.multiply(BigDecimal.valueOf(incrementPercent)).max(BigDecimal.valueOf(minIncrement));
        return high.add(raise).setScale(2, RoundingMode.CEILING).doubleValue();
    }
}
