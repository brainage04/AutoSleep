package io.github.brainage04.autosleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class FailedTriesTest {
    private static final int BEDS = 4;

    /** {@code count} more failed tries on bed number {@code bed}. */
    record Failure(int bed, int count) {}

    @Provide
    Arbitrary<List<Failure>> failures() {
        Arbitrary<Failure> failure =
                Combinators.combine(
                                Arbitraries.integers().between(0, BEDS - 1),
                                Arbitraries.integers().between(1, AutoSleeper.TRIES_PER_BED))
                        .as(Failure::new);
        return failure.list().ofMaxSize(40);
    }

    @Property
    void aBedIsGivenUpExactlyWhenItsTriesReachTheLimitAndStaysGivenUp(
            @ForAll("failures") List<Failure> failures,
            @ForAll @IntRange(min = 1, max = 6) int limit) {
        FailedTries<Integer> tries = new FailedTries<>(limit);
        Map<Integer, Integer> sums = new HashMap<>();
        for (Failure failure : failures) {
            tries.add(failure.bed(), failure.count());
            sums.merge(failure.bed(), failure.count(), Integer::sum);
            for (int bed = 0; bed < BEDS; bed++) {
                assertEquals(
                        sums.getOrDefault(bed, 0) >= limit,
                        tries.givenUp(bed),
                        "bed " + bed + " after " + sums);
            }
        }
    }

    @Property
    void thePlayerIsToldOnceABedIsGivenUpAndNeverAgain(
            @ForAll("failures") List<Failure> failures,
            @ForAll @IntRange(min = 1, max = 6) int limit) {
        FailedTries<Integer> tries = new FailedTries<>(limit);
        Map<Integer, Integer> sums = new HashMap<>();
        for (Failure failure : failures) {
            int before = sums.getOrDefault(failure.bed(), 0);
            int after = before + failure.count();
            sums.put(failure.bed(), after);
            assertEquals(
                    before < limit && after >= limit,
                    tries.add(failure.bed(), failure.count()),
                    failure + " after " + before + " tries");
        }
    }

    @Property
    void aNewNightForgetsEveryBed(
            @ForAll("failures") List<Failure> failures,
            @ForAll @IntRange(min = 1, max = 6) int limit) {
        FailedTries<Integer> tries = new FailedTries<>(limit);
        failures.forEach(failure -> tries.add(failure.bed(), failure.count()));
        tries.clear();
        for (int bed = 0; bed < BEDS; bed++) {
            assertFalse(tries.givenUp(bed), "bed " + bed);
            assertTrue(tries.add(bed, limit), "a new give-up of bed " + bed + " is told");
        }
    }

    @Example
    void aMissedLookAtTheLimitIsToldAndAWalkThatFailsAfterwardsIsNot() {
        FailedTries<String> tries = new FailedTries<>(AutoSleeper.TRIES_PER_BED);
        for (int i = 1; i < AutoSleeper.TRIES_PER_BED; i++) {
            assertFalse(tries.add("bed", 1), "try " + i);
            assertFalse(tries.givenUp("bed"), "try " + i);
        }
        assertTrue(tries.add("bed", 1));
        assertTrue(tries.givenUp("bed"));
        assertFalse(tries.add("bed", AutoSleeper.TRIES_PER_BED));
        assertTrue(tries.givenUp("bed"));
    }

    @Example
    void aWalkThatFailsGivesTheBedUpAtOnceAfterAnyMissedLooks() {
        FailedTries<String> tries = new FailedTries<>(AutoSleeper.TRIES_PER_BED);
        assertTrue(tries.add("fresh", AutoSleeper.TRIES_PER_BED));
        assertFalse(tries.add("missed once", 1));
        assertTrue(tries.add("missed once", AutoSleeper.TRIES_PER_BED));
        assertTrue(tries.givenUp("fresh"));
        assertTrue(tries.givenUp("missed once"));
        assertFalse(tries.givenUp("never tried"));
    }
}
