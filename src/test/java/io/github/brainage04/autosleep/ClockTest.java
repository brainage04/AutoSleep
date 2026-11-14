package io.github.brainage04.autosleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class ClockTest {
    record Tick(boolean paused, boolean playerCouldClick) {}

    @Provide
    Arbitrary<Tick> ticks() {
        return Combinators.combine(Arbitraries.of(true, false), Arbitraries.of(true, false))
                .as(Tick::new);
    }

    @Property
    void noClockCountsATickInWhichTheGameIsPaused(
            @ForAll Clock clock, @ForAll boolean playerCouldClick) {
        assertFalse(clock.counts(true, playerCouldClick));
    }

    @Property
    void theWorldClockCountsEveryTickInWhichTheGameRuns(@ForAll boolean playerCouldClick) {
        assertTrue(Clock.WORLD.counts(false, playerCouldClick));
    }

    @Property
    void thePlayerClockCountsARunningTickOnlyWhenThePlayerCouldClick(
            @ForAll boolean playerCouldClick) {
        assertEquals(playerCouldClick, Clock.PLAYER.counts(false, playerCouldClick));
    }

    @Property
    void everyPlayerTickIsAWorldTick(@ForAll("ticks") Tick tick) {
        if (Clock.PLAYER.counts(tick.paused(), tick.playerCouldClick())) {
            assertTrue(Clock.WORLD.counts(tick.paused(), tick.playerCouldClick()));
        }
    }

    /**
     * A counter that counts through its clock, as each of AutoSleeper's tick counters does, ends
     * with the same count when every paused tick is taken out of its ticks.
     */
    @Property
    void pausedTicksNeverAdvanceACounter(
            @ForAll Clock clock, @ForAll("tickLists") List<Tick> ticks) {
        List<Tick> running = ticks.stream().filter(tick -> !tick.paused()).toList();
        assertEquals(count(clock, running), count(clock, ticks));
    }

    @Property
    void aCounterNeverCountsMoreTicksThanTheGameRan(
            @ForAll Clock clock, @ForAll("tickLists") List<Tick> ticks) {
        long running = ticks.stream().filter(tick -> !tick.paused()).count();
        assertTrue(count(clock, ticks) <= running);
    }

    @Provide
    Arbitrary<List<Tick>> tickLists() {
        return ticks().list().ofMaxSize(200);
    }

    private static int count(Clock clock, List<Tick> ticks) {
        int counted = 0;
        for (Tick tick : ticks) {
            if (clock.counts(tick.paused(), tick.playerCouldClick())) {
                counted++;
            }
        }
        return counted;
    }
}
