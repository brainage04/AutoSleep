package io.github.brainage04.autosleep;

import java.util.HashMap;
import java.util.Map;

/** Failed tries per bed; a bed with {@code limit} of them is given up. */
final class FailedTries<B> {
    private final int limit;

    private final Map<B, Integer> tries = new HashMap<>();

    FailedTries(int limit) {
        this.limit = limit;
    }

    /**
     * Counts {@code count} more failed tries on the bed, up to the limit. Returns true only for the
     * call that gives the bed up, so the player is told once.
     */
    boolean add(B bed, int count) {
        int before = tries.getOrDefault(bed, 0);
        int after = Math.min(limit, before + count);
        tries.put(bed, after);
        return before < limit && after == limit;
    }

    boolean givenUp(B bed) {
        return tries.getOrDefault(bed, 0) >= limit;
    }

    void clear() {
        tries.clear();
    }
}
