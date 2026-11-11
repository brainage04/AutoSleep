package io.github.brainage04.autosleep;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The view that puts the crosshair on a block, and the step the mod takes toward it each tick. A player turns to a bed
 * with the mouse before right-clicking it, so the mod turns the same way: a few degrees per tick, never a jump.
 */
final class BedLook {
    /** 400 degrees a second: a half turn takes under half a second and is still visible frame by frame. */
    static final float DEGREES_PER_TICK = 20.0F;

    private BedLook() {
    }

    /** Vanilla's yaw for looking from {@code eye} at {@code target}: 0 is south (+Z), -90 is east (+X). */
    static float yawTo(Vec3 eye, Vec3 target) {
        return (float) (Mth.atan2(target.z - eye.z, target.x - eye.x) * (180.0 / Math.PI)) - 90.0F;
    }

    /** Vanilla's pitch for looking from {@code eye} at {@code target}: negative is down. */
    static float pitchTo(Vec3 eye, Vec3 target) {
        double horizontal = Math.sqrt(Mth.square(target.x - eye.x) + Mth.square(target.z - eye.z));
        return (float) (-(Mth.atan2(target.y - eye.y, horizontal) * (180.0 / Math.PI)));
    }

    /** One turn step toward {@code target}, the short way round and at most {@link #DEGREES_PER_TICK}. */
    static float stepYaw(float yaw, float target) {
        float step = Mth.clamp(Mth.wrapDegrees(target - yaw), -DEGREES_PER_TICK, DEGREES_PER_TICK);
        return Mth.wrapDegrees(yaw + step);
    }

    /** One turn step toward {@code target}, never past it and never outside vanilla's pitch limits. */
    static float stepPitch(float pitch, float target) {
        float step = Mth.clamp(target - pitch, -DEGREES_PER_TICK, DEGREES_PER_TICK);
        return Mth.clamp(pitch + step, -90.0F, 90.0F);
    }
}
