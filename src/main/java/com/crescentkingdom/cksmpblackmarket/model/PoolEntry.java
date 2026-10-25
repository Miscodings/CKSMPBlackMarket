package com.crescentkingdom.cksmpblackmarket.model;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** One auctionable prize, fully parsed from items.yml. */
public record PoolEntry(
        String id,
        int weight,
        double startBid,
        double minIncrement,
        double incrementPercent,
        Display display,
        List<ItemSpec> items,
        List<String> commands
) {

    /** A reward item: either a CKSMPItems id or a vanilla material. */
    public record ItemSpec(String cksmpItem, Material material, int amount) {}

    /** Menu icon. Null fields fall back to the first reward item / a generated name. */
    public record Display(String name, List<String> lore, ItemSpec icon) {}

    /** Exact amount the next bid must reach; mirrored by the SQL guard in BlackMarketDb#placeBid. */
    public double nextBid(RoundView round) {
        if (round.bidCount() == 0) return startBid;
        BigDecimal high = BigDecimal.valueOf(round.highBid());
        BigDecimal raise = high.multiply(BigDecimal.valueOf(incrementPercent)).max(BigDecimal.valueOf(minIncrement));
        return high.add(raise).setScale(2, RoundingMode.CEILING).doubleValue();
    }
}
