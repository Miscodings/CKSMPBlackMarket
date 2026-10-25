package com.crescentkingdom.cksmpblackmarket.service;

import com.crescentkingdom.cksmpblackmarket.model.RoundView;

/**
 * This shard's view of the current round. Written by Redis packets and DB reads, read lock-free by
 * the menu, boss bar and placeholders. Stale or reordered updates are discarded, so duplicate packets
 * (including a leader hearing its own broadcast) are harmless.
 */
public final class MarketState {

    private volatile RoundView current;
    private volatile long lastSyncMs;

    public RoundView current() {
        return current;
    }

    public long lastSyncMs() {
        return lastSyncMs;
    }

    /** True when {@code incoming} replaced the cached view. */
    public synchronized boolean apply(RoundView incoming) {
        RoundView cur = current;
        lastSyncMs = System.currentTimeMillis();

        if (cur != null) {
            if (incoming.id() < cur.id()) return false;

            if (incoming.id() == cur.id()) {
                if (!cur.active()) return false;
                if (incoming.active() && incoming.bidCount() < cur.bidCount()) return false;
            }
        }

        current = incoming;
        return true;
    }

    /** Marks the cache as verified against the database without changing it. */
    public void touch() {
        lastSyncMs = System.currentTimeMillis();
    }
}
