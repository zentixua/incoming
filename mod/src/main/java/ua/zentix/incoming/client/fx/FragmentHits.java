package ua.zentix.incoming.client.fx;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.FragmentModel;
import ua.zentix.incoming.warhead.FragmentTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Where blast fragments hit ({@link FragmentModel}): first rays with the same seed and axis as the server, along the same block
 * path ({@link FragmentTrace}) — the client shows the hit where a wall chip or broken glass appears.
 * The fragment itself is invisible in flight: steel, ~500–700 K — below the Draper point, smaller than a centimetre and supersonic.
 * The hit is visible: a fast fragment strikes sparks from stone and metal (burning steel particles), throws a puff of
 * dust and splinters from soil and wood, glass shards from glass. Ray count follows particle share ({@link Fx#density}),
 * at most {@link #SHOWN} (fewer at distance), within {@link #RANGE} of the blast and at most {@link #TICK_BUDGET} per tick
 * across all blasts. Client rays run on the blast tick through blocks the main server blast has not yet destroyed: in the zone where
 * the wave breaks walls, hits also appear on walls about to collapse; beyond it — at the same places as server chips.
 */
final class FragmentHits {
    private FragmentHits() {}

    /** Maximum fragment hits the client shows per blast (out of thousands). */
    static final int SHOWN = 256;
    /** Maximum client fragment tracing distance, blocks: the puff and sparks are subpixel there, but rays would cost frame time. */
    static final double RANGE = 96;
    /** Beyond this camera-to-blast distance hits are invisible (half-block puff is subpixel), blocks. */
    static final double SEEN = 200;
    /** Maximum rays across all blasts per client tick (Grad salvo in one tick). */
    static final int TICK_BUDGET = 1024;
    private static long budgetTick = Long.MIN_VALUE;
    private static int budgetLeft;
    /**
     * Sparks on hard material start at this impact speed, m/s (estimate: steel sparks against stone even at tens of m/s; bright
     * impact flashes start at hundreds).
     */
    static final double SPARK_SPEED = 300;

    /** Fragment hit: {@code tick} ticks after the blast, at {@code point}. */
    record Hit(int tick, Vec3 point, BlockPos pos, BlockState state, @Nullable Direction face, double speed, double joules) {}

    /** First blast ray hits against current client blocks, by time. */
    static List<Hit> of(ClientLevel level, @Nullable WeaponType weapon, Vec3 pos, Vec3 axis, long seed) {
        List<Hit> out = new ArrayList<>();
        FragmentModel model = weapon == null ? null : FragmentModel.of(weapon.spec());
        if (model == null) return out;
        net.minecraft.client.Camera cam = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        double d = cam.getPosition().distanceTo(pos);
        if (d > SEEN) return out;
        long now = level.getGameTime();
        if (now != budgetTick) {
            budgetTick = now;
            budgetLeft = TICK_BUDGET;
        }
        // distant hits are smaller: fewer rays with distance (all SHOWN nearby)
        int n = Math.min(Math.min(model.traced(), budgetLeft), (int) (Fx.count(SHOWN, pos) * Math.min(1, RANGE / Math.max(d, 1))));
        budgetLeft -= n;
        for (int i = 0; i < n; i++) {
            FragmentModel.Fragment f = model.fragment(seed, i, axis);
            double range = Math.min(RANGE, f.range());
            if (range <= 0) continue;
            FragmentTrace.trace(level, pos, f.dir(), range, new FragmentTrace.Visitor() {
                @Override
                public void glass(BlockPos p, BlockState state, double s) {
                    if (f.energyAt(s) >= FragmentModel.GLASS_J) out.add(hit(f, s, pos.add(f.dir().scale(s)), p, state, null));
                }

                @Override
                public void stop(BlockPos p, BlockState state, double s, Vec3 point, Direction entry) {
                    out.add(hit(f, s, point, p, state, entry));
                }
            });
        }
        out.sort((a, b) -> Integer.compare(a.tick, b.tick));
        return out;
    }

    private static Hit hit(FragmentModel.Fragment f, double s, Vec3 point, BlockPos pos, BlockState state, @Nullable Direction face) {
        int tick = (int) Math.floor(20 * FragmentModel.timeTo(f.speed(), f.mass(), s));
        double v = f.speedAt(s);
        return new Hit(tick, point, pos, state, face, v, FragmentModel.energy(f.mass(), v));
    }

    /** Show hit {@code h}. */
    static void show(ClientLevel level, Hit h, RandomSource rnd) {
        Vec3 n = h.face == null ? Vec3.ZERO : Vec3.atLowerCornerOf(h.face.getNormal());
        Vec3 at = h.point.add(n.scale(0.05));
        BlockParticleOption dust = new BlockParticleOption(ParticleTypes.BLOCK, h.state);
        // block piece count by impact energy (estimate: a puff per ~200 J, from 1 to 6)
        int bits = Mth.clamp((int) (h.joules / 200), 1, 6);
        for (int i = 0; i < bits; i++) {
            Vec3 d = n.scale(0.05 + 0.1 * rnd.nextDouble()).add(rnd.nextGaussian() * 0.04, 0.03 + 0.06 * rnd.nextDouble(), rnd.nextGaussian() * 0.04);
            level.addParticle(dust, at.x, at.y, at.z, d.x, d.y, d.z);
        }
        if (h.face != null && h.speed >= SPARK_SPEED && hard(level, h)) {
            // sparks are burning steel particles chipped off on hard material: fan out from the face
            int sparks = 2 + rnd.nextInt(3);
            for (int i = 0; i < sparks; i++) {
                Vec3 d = n.scale(0.15 + 0.25 * rnd.nextDouble()).add(rnd.nextGaussian() * 0.2, rnd.nextGaussian() * 0.2, rnd.nextGaussian() * 0.2);
                Fx.spark().vel(d).size(0.05f, 0.025f).life(4 + rnd.nextInt(6)).gravity(0.04f).drag(0.9f).streak(1)
                        .color(0xFFE6A0, 0xFF7A20).spawn(level, at);
            }
        }
    }

    /** Stone, brick, metal: fragments strike sparks from them (not from soil, wood or leaves). */
    static boolean hard(ClientLevel level, Hit h) {
        SoundType s = h.state.getSoundType(level, h.pos, null);
        return s == SoundType.STONE || s == SoundType.DEEPSLATE || s == SoundType.DEEPSLATE_BRICKS || s == SoundType.DEEPSLATE_TILES
                || s == SoundType.POLISHED_DEEPSLATE || s == SoundType.METAL || s == SoundType.NETHER_BRICKS || s == SoundType.TUFF
                || s == SoundType.TUFF_BRICKS || s == SoundType.POLISHED_TUFF || s == SoundType.CALCITE || s == SoundType.BASALT
                || s == SoundType.COPPER || s == SoundType.ANVIL || s == SoundType.CHAIN || s == SoundType.LANTERN || s == SoundType.NETHERRACK;
    }
}
