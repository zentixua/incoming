package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.particle.FxPool;
import ua.zentix.incoming.nuclear.Detonation;

/**
 * Ash, scraps of paper and small litter falling around the camera after the nuclear wave — like weather: in a disk around
 * and above the camera, only under open sky (over a roof — onto the roof), carried by the detonation wind. Amount follows the pall above
 * the location ({@link PallField#ashRate}): from settling dust — minutes after the wave; from smoky haze above the burning city — until
 * morning; paper — where the wave demolished houses, in the first minutes. Slots use their own {@link FxBudget#ASH} group.
 */
public final class NukeAsh {
    private static final RandomSource RANDOM = RandomSource.create();
    /** Radius of the disk around the camera, blocks. */
    static final double RADIUS = 20;
    /** Flakes per tick at the highest ash rate ("Particles: All" setting). */
    static final int MAX_PER_TICK = 14;

    private NukeAsh() {}

    /**
     * Tick: {@code rate} — ash above the camera 0..1; {@code sigma} — pall at the camera (small litter falls in dense
     * dust); {@code a} — the detonation whose pall covers the camera (wind, paper).
     */
    static void tick(ClientLevel level, Vec3 cam, double rate, double sigma, @Nullable ClientNuclear.Active a) {
        if (rate <= 0.01 || a == null || level.dimensionType().hasCeiling()) return;
        Detonation d = a.d;
        double ticks = a.ticks(0);
        double paper = a.pall.paperRate(cam.x - d.burst().x, cam.z - d.burst().z, ticks, NukePall.water(a));
        int n = Math.round((float) (rate * MAX_PER_TICK * Fx.density(cam)));
        // drift follows the detonation wind, weaker near the ground (as with dust)
        double wind = d.blocks(d.windSpeed()) / 20.0 * PallField.DRIFT;
        double wx = Math.cos(d.windDir()) * wind, wz = Math.sin(d.windDir()) * wind;
        for (int i = 0; i < n; i++) {
            double ang = RANDOM.nextDouble() * Mth.TWO_PI, r = RADIUS * Math.sqrt(RANDOM.nextDouble());
            // farther upwind: flakes drift into the disk
            double x = cam.x + Math.cos(ang) * r - wx * 40, z = cam.z + Math.sin(ang) * r - wz * 40;
            double y = cam.y + 4 + RANDOM.nextDouble() * 12;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null) continue;
            int roof = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 1;
            y = Math.max(y, roof + 1 + RANDOM.nextDouble() * 4);
            boolean isPaper = RANDOM.nextDouble() < paper * 0.25;
            boolean debris = !isPaper && sigma > PallField.SIGMA_DENSE * 0.3 / d.scale() && RANDOM.nextFloat() < 0.15f;
            Fx.Spec s = Fx.flake(isPaper);
            float drag = isPaper ? 0.88f : debris ? 0.94f : 0.9f;
            double ax = wx * (1 - drag) / drag, az = wz * (1 - drag) / drag;
            if (isPaper) {
                s.size(0.12f, 0.12f).color(0xE8E2D4, 0xBDB5A4).gravity(0.0025f).spin(0.18f).life(260 + RANDOM.nextInt(160));
            } else if (debris) {
                // small litter: dark, heavier, falls faster
                s.size(0.05f, 0.05f).color(0x2E2822, 0x2E2822).gravity(0.012f).spin(0.25f).life(120 + RANDOM.nextInt(60));
            } else {
                // ash: gray and whitish, darker from burned material
                int c = RANDOM.nextFloat() < 0.4f ? 0xC8C4BE : RANDOM.nextFloat() < 0.5f ? 0x8E8A84 : 0x5A5652;
                s.size(0.035f + 0.03f * RANDOM.nextFloat(), 0.035f + 0.03f * RANDOM.nextFloat()).color(c, c).life(220 + RANDOM.nextInt(160));
            }
            s.drag(drag).drift(ax, az).vel(wx, -0.02, wz).budget(FxBudget.ASH).spawn(level, x, y, z);
        }
    }

    static void reset() {
    }

    /** Number of living flakes (for the scenario line). */
    static int alive() {
        return FxPool.INSTANCE.live(FxBudget.ASH);
    }
}
