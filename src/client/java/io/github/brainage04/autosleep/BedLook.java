package io.github.brainage04.autosleep;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The view that puts the crosshair on a block. A player turns with the mouse in one motion and then
 * right-clicks, so the mod does the same thing without spending ticks on it: one look, then the use
 * on that same tick.
 */
final class BedLook {
    private BedLook() {}

    /**
     * Vanilla's yaw for looking from {@code eye} at {@code target}: 0 is south (+Z), -90 is east
     * (+X).
     */
    static float yawTo(Vec3 eye, Vec3 target) {
        return (float) (Mth.atan2(target.z - eye.z, target.x - eye.x) * (180.0 / Math.PI)) - 90.0F;
    }

    /**
     * Vanilla's pitch for looking from {@code eye} at {@code target}: positive is down, as on the
     * player.
     */
    static float pitchTo(Vec3 eye, Vec3 target) {
        double horizontal = Math.sqrt(Mth.square(target.x - eye.x) + Mth.square(target.z - eye.z));
        return (float) (-(Mth.atan2(target.y - eye.y, horizontal) * (180.0 / Math.PI)));
    }

    /** A yaw as vanilla keeps it on a player: the short way round, inside -180..180. */
    static float normalizeYaw(float yaw) {
        return Mth.wrapDegrees(yaw);
    }
}
