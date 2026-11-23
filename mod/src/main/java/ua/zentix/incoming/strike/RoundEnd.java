package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.StrikeRoundEvent.Outcome;

import java.util.UUID;

/**
 * How one round ended ({@link ua.zentix.incoming.api.StrikeRoundEvent.Ended}).
 *
 * @param position         where: the burst, the kill, the break-up; null for a round that never left
 * @param chargeKg         {@link Outcome#IMPACT}: TNT-equivalent charge, kg
 * @param interceptor      SAM block of a SAM kill
 * @param interceptorOwner SAM owner, or the entity whose damage brought the round down
 * @param released         the round had left (a Released was posted)
 * @param refunded         its munitions are back with the payer or never left
 */
public record RoundEnd(Outcome outcome, @Nullable Vec3 position, double chargeKg, @Nullable BlockPos interceptor, @Nullable UUID interceptorOwner,
                       boolean released, boolean refunded) {
    /** Warhead went off at {@code at}. */
    public static RoundEnd impact(Vec3 at, double chargeKg) {
        return new RoundEnd(Outcome.IMPACT, at, chargeKg, null, null, true, false);
    }

    /** Nuclear burst of {@code yieldKt} at {@code at}: 1 kt is 10⁶ kg TNT by definition. */
    public static RoundEnd nuclear(Vec3 at, double yieldKt) {
        return impact(at, yieldKt * 1e6);
    }

    public static RoundEnd intercepted(Vec3 at, @Nullable BlockPos sam, @Nullable UUID by) {
        return new RoundEnd(Outcome.INTERCEPTED, at, 0, sam, by, true, false);
    }

    /** A released round that never went off at {@code at}. */
    public static RoundEnd failed(@Nullable Vec3 at) {
        return new RoundEnd(Outcome.FAILED, at, 0, null, null, true, false);
    }

    /** A released round stood down at {@code at}: launched munitions are not refunded. */
    public static RoundEnd recalled(@Nullable Vec3 at) {
        return new RoundEnd(Outcome.RECALLED, at, 0, null, null, true, false);
    }

    /** A round that never left: failed release, cancelled before release. */
    public static RoundEnd unreleased(Outcome outcome, boolean refunded) {
        return new RoundEnd(outcome, null, 0, null, null, false, refunded);
    }
}
