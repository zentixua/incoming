package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;

/**
 * Conventional blast front on grass: on arrival it tears off and lifts grass blades, turf and canopy leaves — making the front
 * visible on the ground: a ring of rising tufts moves at front speed (complaint 23.10: “no ring at all”). They do not travel
 * far: air shifts only decimetres over a millisecond phase, and even a tuft launched at 30 m/s slows within one tick to
 * its terminal fall speed and lands half a metre away (laws below; tuft lift enhanced as presentation). Detachment sites —
 * dust detachment law ({@link BlastLitter#tearsCover}: u* behind the front above the turf threshold); launch speed —
 * foliage tuft drag under phase wind ({@link BlastLitter#kick}); timing — front arrival at the site
 * ({@link ChemicalFront}). Dust from bare soil, sand and snow belongs to the wave dust layer ({@code GroundDust}), not here.
 * <p>
 * Pieces are {@link Fragments} (leaf with block texture and colour: grass green, foliage from its tree), launching at the
 * imparted velocity into still air (phase lasts milliseconds, less than a tick), then gliding down. Count is a visual sample
 * (<b>presentation:</b> {@link #PER_M2} per square metre, at most {@link #MAX} per blast, tufts larger than real ones —
 * {@link #CLUMP_MIN}–{@link #CLUMP_MAX} m); detachment position and time follow the law; upward velocity fraction enhanced as presentation. Pieces have no world wind (⚠: only the launch carries them).
 */
public final class FrontLitter {
    /** Pieces per square metre of torn cover (presentation: thousands of real grass blades; this count shows a ring). */
    static final double PER_M2 = 0.8;
    /** Maximum pieces per blast (presentation; shared cap — {@link Fragments}). */
    static final int MAX = 900;
    /**
     * Tuft, m: larger than canopy foliage ({@code Fragments.SPRIG_MAX}) — presentation: smaller would be subpixel at 50 blocks.
     * Trajectory is independent of size: plate terminal speed depends on mass per area, rather than size.
     */
    static final float CLUMP_MIN = 0.6f, CLUMP_MAX = 1.0f;
    /** No pieces spawn beyond this camera distance in blocks: invisible there, and nearby pieces need the capped slots. */
    static final double REACH = 128;
    /** No detachment search beyond this distance in metres (for 450 kg it ends at ~110 m). */
    static final double TEAR_MAX = 300;

    private static final Fragments.Wind CALM = (x, y, z, into) -> {
        into[0] = 0;
        into[1] = 0;
        into[2] = 0;
    };

    private FrontLitter() {}

    /** World exit (and sandbox reset): remove blasts with visible dust from the shared patch cap ({@link FrontDust}). */
    public static void reset() {
        FrontDust.reset();
    }

    /**
     * Ground grass/leaf detachment ring {inner, outer radius}, m: where u* behind the front exceeds the turf threshold. Searched from
     * {@code from} (fireball edge: closer material burns and leaves with crater soil) to {@link #TEAR_MAX}; {@code hob}
     * is burst height above ground: below an airburst the wind points down, so the ring starts farther out. {0, 0} — nowhere.
     */
    static double[] tearRing(ChemicalFront front, double from, double hob) {
        double r = Math.max(from, 0.5), inner = 0, outer = 0;
        for (; r <= TEAR_MAX; r += 0.5) {
            if (!tears(front, r, hob)) continue;
            if (inner == 0) inner = r;
            outer = r;
        }
        return new double[]{inner, outer};
    }

    /** Outer detachment ring radius, m ({@link #tearRing}). */
    static double tearRange(ChemicalFront front, double from, double hob) {
        return tearRing(front, from, hob)[1];
    }

    private static boolean tears(ChemicalFront front, double ground, double hob) {
        double slant = Math.hypot(ground, hob);
        return BlastLitter.tearsCover(front.kpa(slant), along(ground, slant));
    }

    /** Dynamic-pressure fraction along the ground: above-ground bursts have an obliquely incident front (cos²). */
    static double along(double ground, double slant) {
        return slant <= 0 ? 1 : (ground / slant) * (ground / slant);
    }

    /** Presentation redirects the kick upward; it must not add speed or kinetic energy to the drag integral. */
    static double[] launch(double speed, double lift) {
        double horizontal = speed / Math.sqrt(1 + lift * lift);
        return new double[]{horizontal, lift * horizontal};
    }

    /**
     * Tear grass and leaves around the blast at {@code pos}: front {@code front}, fireball radius {@code ball} m,
     * burst height above ground {@code hob} m. A piece waits for front arrival at its site ({@link Fragments#DELAY_MAX} —
     * maximum delay; beyond 2 s no detaching front remains).
     */
    static void spawn(ClientLevel level, Vec3 pos, @Nullable ChemicalFront front, double ball, double hob, RandomSource rnd) {
        if (front == null) return;
        double[] ring = tearRing(front, Math.max(ball, 1), hob);
        double inner = ring[0], outer = ring[1];
        if (!(outer > inner)) return;
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (Math.hypot(eye.x - pos.x, eye.z - pos.z) > outer + REACH) return;
        int n = Math.min(Fragments.share(MAX), (int) Math.round(PER_M2 * Math.PI * (outer * outer - inner * inner)));
        FrontDust.spawn(level, pos, front, inner, outer, hob);
        double terminal = Fragments.terminal(Fragments.Kind.LEAF, 0, 0);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        double groundY = pos.y - Math.max(0, hob);
        for (int i = 0; i < n; i++) {
            double r = Math.sqrt(inner * inner + rnd.nextDouble() * (outer * outer - inner * inner)), a = rnd.nextDouble() * Mth.TWO_PI;
            double cos = Math.cos(a), sin = Math.sin(a), x = pos.x + cos * r, z = pos.z + sin * r;
            if (Math.hypot(eye.x - x, eye.z - z) > REACH) continue;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null) continue;
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
            BlockState plant = chunk.getBlockState(m.set(bx, top + 1, bz)), cover = chunk.getBlockState(m.set(bx, top, bz));
            boolean grows = !plant.isAir() && plant.canBeReplaced() && plant.getFluidState().isEmpty();
            boolean leaves = cover.is(BlockTags.LEAVES);
            if (!grows && !leaves && !cover.is(BlockTags.DIRT)) continue;
            // turf as grass (grass-block particles are dirt: orange cubes instead of blades); colour from this site's grass
            BlockState torn = grows ? plant : cover.is(Blocks.GRASS_BLOCK) ? Blocks.SHORT_GRASS.defaultBlockState() : cover;
            m.set(bx, grows ? top + 1 : top, bz);
            // canopy and material above the blast see the airborne wave, rather than its ground-aligned component
            double y = top + (grows ? 1.2 : 1.05), dy = y - pos.y, slant = Math.sqrt(r * r + dy * dy);
            double share = leaves || y > groundY + 2 ? 1 : along(r, Math.hypot(r, Math.max(0, hob)));
            double kpa = front.kpa(slant);
            if (!BlastLitter.tearsCover(kpa, share)) continue;
            double v = BlastLitter.kick(BlastModel.windSpeed(kpa) * Math.sqrt(share), BlastModel.chemicalPhaseSeconds(slant, front.tntKg, front.surface),
                    terminal) / 20;
            // presentation: tuft lift at 19–35° to the surface
            // rotation preserves launch speed magnitude from the drag integral
            double lift = 0.35 + 0.35 * rnd.nextDouble();
            double[] kick = launch(v, lift);
            int delay = (int) Math.ceil(20 * front.arrivalSeconds(slant));
            if (delay > Fragments.DELAY_MAX) continue;
            float size = CLUMP_MIN + (CLUMP_MAX - CLUMP_MIN) * rnd.nextFloat();
            Fragments.torn(level, torn, m.immutable(), Fragments.Kind.LEAF, x, y, z, cos * kick[0], kick[1], sin * kick[0], size, CALM, delay, false);
        }
    }
}
