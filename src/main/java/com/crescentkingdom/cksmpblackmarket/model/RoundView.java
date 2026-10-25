package com.crescentkingdom.cksmpblackmarket.model;

import com.google.gson.JsonObject;

import java.util.UUID;

/** Immutable snapshot of one auction round; the unit that is cached per shard and sent over Redis. */
public record RoundView(
        long id,
        String entryId,
        int roundNo,
        int roundTotal,
        double startBid,
        double highBid,
        UUID highBidder,
        String highBidderName,
        int bidCount,
        long endsAt,
        boolean active
) {

    public RoundView withEndsAt(long newEndsAt) {
        return new RoundView(id, entryId, roundNo, roundTotal, startBid, highBid, highBidder, highBidderName, bidCount, newEndsAt, active);
    }

    public RoundView closed() {
        return new RoundView(id, entryId, roundNo, roundTotal, startBid, highBid, highBidder, highBidderName, bidCount, endsAt, false);
    }

    public boolean isActiveAt(long nowMs) {
        return active && endsAt > nowMs;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("entry", entryId);
        json.addProperty("no", roundNo);
        json.addProperty("total", roundTotal);
        json.addProperty("start", startBid);
        json.addProperty("high", highBid);
        if (highBidder != null) {
            json.addProperty("bidder", highBidder.toString());
            json.addProperty("bidderName", highBidderName);
        }
        json.addProperty("bids", bidCount);
        json.addProperty("endsAt", endsAt);
        json.addProperty("active", active);
        return json;
    }

    public static RoundView fromJson(JsonObject json) {
        UUID bidder = json.has("bidder") ? UUID.fromString(json.get("bidder").getAsString()) : null;
        return new RoundView(
                json.get("id").getAsLong(),
                json.get("entry").getAsString(),
                json.get("no").getAsInt(),
                json.get("total").getAsInt(),
                json.get("start").getAsDouble(),
                json.get("high").getAsDouble(),
                bidder,
                bidder == null ? null : json.get("bidderName").getAsString(),
                json.get("bids").getAsInt(),
                json.get("endsAt").getAsLong(),
                json.get("active").getAsBoolean()
        );
    }
}
