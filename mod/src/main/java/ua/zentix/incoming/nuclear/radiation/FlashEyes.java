package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.EyeInjury;
import ua.zentix.incoming.registry.ModAttachments;

/**
 * Player eyes and flash ({@link EyeInjury}, G&amp;D §12.83–12.88). Client computes temporary whole-field bleaching itself
 * ({@code NukeFlash}); here, retinal burn: permanent blind spot where the ball was imaged, for someone looking toward it
 * (ball in field of view, within 60° of gaze) and seeing it. Dark-adapted player has a wider pupil, burns at longer range.
 */
public final class FlashEyes {
    /** Ball beyond this gaze angle (cosine of 60°) is outside the on-screen view. */
    private static final double FIELD_COS = 0.5;

    private FlashEyes() {}

    /** Eye dark adaptation at {@code eye}: 0 — day, 1 — night (skylight and block light at eye). */
    public static double darkness(Level level, Vec3 eye) {
        return EyeInjury.darkness(level.getMaxLocalRawBrightness(BlockPos.containing(eye), skyDarken(level)));
    }

    /**
     * How many levels (0..11) darker the sky currently is than day, as {@code Level.updateSkyBrightness}: vanilla client
     * computes it only on world entry, so client night would count as day.
     */
    static int skyDarken(Level level) {
        double rain = 1 - level.getRainLevel(1) * 5 / 16.0, thunder = 1 - level.getThunderLevel(1) * 5 / 16.0;
        double sun = 0.5 + 2 * Mth.clamp(Mth.cos(level.getTimeOfDay(1) * (float) (Math.PI * 2)), -0.25, 0.25);
        return (int) ((1 - sun * rain * thunder) * 11);
    }

    /** Maximum retinal burn range (blocks) for someone looking at the ball — darkest-adapted eye. */
    public static double reach(Detonation d) {
        return d.blocks(EyeInjury.retinaRange(d.yieldKt(), 1, d.visibility()));
    }

    /**
     * Player {@code p} seeing the ball: retinal burn if looking toward it and within burn range; sleeping eyes are closed.
     * A player whom launcher {@code owner} cannot damage under the rules (no PvP, same team) is unaffected, as with skin burns
     * ({@link FlashBurns}).
     */
    public static void flash(Level level, Detonation d, ServerPlayer p, @Nullable Entity owner) {
        if (p.isSleeping() || owner instanceof Player attacker && !p.canHarmPlayer(attacker)) return;
        RetinaScars.Scar scar = scar(d, p, darkness(level, p.getEyePosition()));
        if (scar == null) return;
        RetinaScars next = scars(p).with(scar);
        p.setData(ModAttachments.RETINA, next);
        PacketDistributor.sendToPlayer(p, new S2C.RetinaScars(next));
    }

    /** Retinal burn for entity {@code e} seeing the ball, with dark adaptation {@code dark}; null if no burn. */
    @Nullable
    public static RetinaScars.Scar scar(Detonation d, LivingEntity e, double dark) {
        Vec3 eye = e.getEyePosition(), f = e.getViewVector(1), to = d.burst().subtract(eye);
        double len = to.length();
        if (len < 1e-3) return null;
        double depth = to.dot(f);
        if (depth < FIELD_COS * len) return null;
        if (d.metres(len) > EyeInjury.retinaRange(d.yieldKt(), dark, d.visibility())) return null;
        // roll-free gaze basis — same as the on-screen afterimage
        double yaw = Math.toRadians(e.getViewYRot(1));
        Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)), up = right.cross(f);
        return new RetinaScars.Scar((float) (to.dot(right) / depth), (float) (to.dot(up) / depth), (float) (d.fireballRadius() / depth));
    }

    public static RetinaScars scars(LivingEntity e) {
        return e.getData(ModAttachments.RETINA);
    }

    /** Resend all scars: login, respawn, dimension change. */
    public static void sync(ServerPlayer p) {
        PacketDistributor.sendToPlayer(p, new S2C.RetinaScars(scars(p)));
    }
}
