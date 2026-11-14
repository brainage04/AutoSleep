package io.github.brainage04.autosleep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * A stay-up and a bed refused for monsters end once a later day starts, by {@link
 * AutoSleeper#dayOf(long)}. The day time is never negative: {@code /time set} and {@code /time add}
 * take no value below 0 (TimeCommand.java:24 and :35 use {@code TimeArgument.time()}, whose minimum
 * is 0 at TimeArgument.java:36-38 in 1.21.11), and the server only adds to it.
 */
class DayNumberTest {
    private static final long DAY = 24000L;

    private static final long LATEST = Long.MAX_VALUE - DAY;

    /** The morning after a night everyone slept through (ServerLevel.java:354-355 in 1.21.11). */
    private static long skipNight(long dayTime) {
        long l = dayTime + DAY;
        return l - l % DAY;
    }

    @Property
    void aNightSleptThroughEndsInTheNextDay(
            @ForAll @LongRange(min = 0, max = LATEST) long dayTime) {
        assertEquals(AutoSleeper.dayOf(dayTime) + 1, AutoSleeper.dayOf(skipNight(dayTime)));
    }

    @Property
    void aTickStartsTheNextDayOnlyAtTheEndOfADay(
            @ForAll @LongRange(min = 0, max = LATEST) long dayTime) {
        long next = dayTime + 1;
        assertEquals(
                AutoSleeper.dayOf(dayTime) + (next % DAY == 0 ? 1 : 0), AutoSleeper.dayOf(next));
    }

    @Property
    void aDayHoldsItsOwnTicks(@ForAll @LongRange(min = 0, max = LATEST) long dayTime) {
        long day = AutoSleeper.dayOf(dayTime);
        assertTrue(day * DAY <= dayTime && dayTime < (day + 1) * DAY, "day " + day);
    }

    @Example
    void daysStartAtMultiplesOf24000Ticks() {
        assertEquals(0L, AutoSleeper.dayOf(0L));
        assertEquals(0L, AutoSleeper.dayOf(13000L));
        assertEquals(0L, AutoSleeper.dayOf(23999L));
        assertEquals(1L, AutoSleeper.dayOf(24000L));
        assertEquals(1L, AutoSleeper.dayOf(skipNight(13000L)));
    }
}
