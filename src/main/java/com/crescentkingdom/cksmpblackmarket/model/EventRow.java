package com.crescentkingdom.cksmpblackmarket.model;

import java.util.List;

/** One black market event (a run of rounds). Mutated only by the leader shard. */
public record EventRow(long id, State state, long slotAt, List<String> poolIds, int roundIndex, long nextAt) {

    public enum State {
        /** Counting down to the first round. */
        ANNOUNCING,
        /** Rounds are being played; {@code nextAt} is when the next one opens. */
        RUNNING,
        FINISHED
    }

    public EventRow with(State state, int roundIndex, long nextAt) {
        return new EventRow(id, state, slotAt, poolIds, roundIndex, nextAt);
    }
}
