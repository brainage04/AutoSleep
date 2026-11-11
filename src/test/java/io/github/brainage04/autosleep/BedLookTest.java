package io.github.brainage04.autosleep;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BedLookTest {
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
    void oneLookCoversTheFullTurnInOneStep() {
        // The mod no longer walks the view round at so many degrees a tick: the first step is the whole turn.
        assertAll(
                () -> assertEquals(0.0F, BedLook.normalizeYaw(BedLook.yawTo(EYE, EYE.add(0.0D, 0.0D, 4.0D))), 1.0E-4F),
                () -> assertEquals(-170.0F, BedLook.normalizeYaw(190.0F), 1.0E-4F),
                () -> assertEquals(170.0F, BedLook.normalizeYaw(-190.0F), 1.0E-4F),
                () -> assertEquals(-180.0F, BedLook.normalizeYaw(180.0F), 1.0E-4F)
        );
    }
}
