package io.github.brainage04.autosleep;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The view that puts the crosshair on a block in one step, as a player does with the mouse, so the use can follow on
 * the same tick instead of after a turn spread over ticks.
 */
final class BedLook {
    private BedLook() {
    }

    /** Vanilla's yaw for looking from {@code eye} at {@code target}: 0 is south (+Z), -90 is east (+X)
     * ({@code Direction#fromYRot}). */
    static float yawTo(Vec3 eye, Vec3 target) {
        return (float) (Mth.atan2(target.z - eye.z, target.x - eye.x) * (180.0 / Math.PI)) - 90.0F;
    }

    /** Vanilla's pitch for looking from {@code eye} at {@code target}: positive is down, as on the player
     * ({@code Entity#calculateViewVector}). */
    static float pitchTo(Vec3 eye, Vec3 target) {
        double horizontal = Math.sqrt(Mth.square(target.x - eye.x) + Mth.square(target.z - eye.z));
        return (float) (-(Mth.atan2(target.y - eye.y, horizontal) * (180.0 / Math.PI)));
    }

    static float normalizeYaw(float yaw) {
        return Mth.wrapDegrees(yaw);
    }
}
