package io.github.brainage04.autosleep;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedLookTest {
    private static final float FACING_SOUTH = 0.0F;
    /** A player standing on the block under their feet at y=1, eye 1.62 above it. */
    private static final Vec3 EYE = new Vec3(0.5D, 2.62D, 0.5D);

    @Test
    void yawPointsAtTheTargetTheWayVanillaCountsDegrees() {
        assertAll(
                () -> assertEquals(0.0F, BedLook.yawTo(EYE, EYE.add(0.0D, 0.0D, 4.0D)), 1.0E-4F, "south"),
                () -> assertEquals(-90.0F, BedLook.yawTo(EYE, EYE.add(4.0D, 0.0D, 0.0D)), 1.0E-4F, "east"),
                () -> assertEquals(90.0F, BedLook.yawTo(EYE, EYE.add(-4.0D, 0.0D, 0.0D)), 1.0E-4F, "west"),
                () -> assertEquals(-180.0F, BedLook.yawTo(EYE, EYE.add(0.0D, 0.0D, -4.0D)), 1.0E-4F, "north")
        );
    }

    @Test
    void pitchPointsDownAtABlockBelowTheEye() {
        assertAll(
                // One block down over two blocks along: 26.565 degrees below the horizon, and vanilla's pitch is positive.
                () -> assertEquals(26.5651F, BedLook.pitchTo(EYE, new Vec3(0.5D, 1.62D, 2.5D)), 1.0E-3F),
                () -> assertEquals(0.0F, BedLook.pitchTo(EYE, new Vec3(0.5D, 2.62D, 4.5D)), 1.0E-4F)
        );
    }

    @Test
    void oneStepIsAtMostTwentyDegreesAndTakesTheShortWayRound() {
        assertAll(
                () -> assertEquals(20.0F, BedLook.stepYaw(0.0F, 90.0F), 1.0E-4F),
                () -> assertEquals(-20.0F, BedLook.stepYaw(0.0F, -90.0F), 1.0E-4F),
                // 20 degrees apart through north, not 340 through south.
                () -> assertEquals(-170.0F, BedLook.stepYaw(170.0F, -170.0F), 1.0E-4F),
                () -> assertEquals(150.0F, BedLook.stepYaw(170.0F, 0.0F), 1.0E-4F),
                // Never past the target.
                () -> assertEquals(90.0F, BedLook.stepYaw(80.0F, 90.0F), 1.0E-4F)
        );
    }

    @Test
    void aTurnTakesManyStepsAndStillArrivesExactly() {
        float yaw = FACING_SOUTH;
        int steps = 0;
        while (yaw != 179.0F) {
            float next = BedLook.stepYaw(yaw, 179.0F);
            float step = Math.abs(Mth.wrapDegrees(next - yaw));
            assertTrue(step <= BedLook.DEGREES_PER_TICK + 1.0E-4F, "a turn step jumped " + step + " degrees");
            yaw = next;
            steps++;
            assertTrue(steps <= 20, "a turn of 179 degrees did not arrive");
        }
        // A player never sees the view snap round: 179 degrees at 20 a tick is 9 ticks, under half a second.
        assertEquals(9, steps);
    }

    @Test
    void pitchStepsStopAtTheTargetAndAtVanillasLimits() {
        assertAll(
                () -> assertEquals(20.0F, BedLook.stepPitch(0.0F, 45.0F), 1.0E-4F),
                () -> assertEquals(-18.0F, BedLook.stepPitch(0.0F, -18.0F), 1.0E-4F),
                () -> assertEquals(-90.0F, BedLook.stepPitch(-89.0F, -170.0F), 1.0E-4F),
                () -> assertEquals(90.0F, BedLook.stepPitch(89.0F, 170.0F), 1.0E-4F)
        );
    }

}
