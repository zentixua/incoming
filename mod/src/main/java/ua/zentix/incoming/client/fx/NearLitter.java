package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.LocalLitter;
import ua.zentix.incoming.nuclear.model.ShockAtBody;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.Locale;

/**
 * Litter right by the camera: when a conventional blast front passes the player, all light matter around them (within {@link #REACH} m)
 * shudders at once and flies away from the blast in one breath — grass and turf, canopy leaves, sand, gravel and soil grit,
 * fine dust; then it hangs, settles and follows normal world wind. Whether it detaches and how it launches — the model ({@link LocalLitter}:
 * detachment by surface threshold, launch over the positive phase, air displacement u·t₊ — decimetres, settling); what to
 * show — actual nearby blocks (their texture and colour: {@link Fragments}); screen piece count — a sample
 * (<b>presentation:</b> {@link #PER_M2}, at most {@link #MAX} per blast). Area-wide turf detachment ring — {@link FrontLitter},
 * area-wide bare-ground dust — {@code GroundDust}; here only the camera foreground, where their samples are sparse.
 * <p>
 * Sites are upward-exposed surfaces in a sphere of {@link #REACH} around the eye ({@link #exposed}), with skylight (the front travels through
 * outdoor air: it does not touch a cave floor beneath the feet): grass at the feet below a canopy and the canopy itself, rather than a roof twenty
 * metres overhead; nearest first.
 * <p>
 * Site surface: loose soil ({@link DustModel.Surface#FIELD}: sand, gravel, dirt, snow), turf, grass and canopy
 * ({@link DustModel.Surface#COVERED}: holds up to 3 m/s), hard ({@link DustModel.Surface#HARD}: only deposited dust —
 * no pieces, fine dust only); water — nothing. Canopy and material above the blast see the airborne wave — full dynamic pressure, rather than
 * its ground-aligned fraction.
 */
public final class NearLitter {
    /** Reach from the camera, m: foreground (beyond it — {@link FrontLitter} and {@code GroundDust} samples). */
    static final double REACH = 16;
    /** Site sampling step, m. */
    static final double STEP = 1;
    /** Pieces per square metre at full detachment fraction (presentation: thousands of real blades and sand grains). */
    static final double PER_M2 = 1.2;
    /** Maximum pieces per blast (presentation; shared cap — {@link Fragments#share}). */
    static final int MAX = 400;
    /** Grass/foliage tuft, m (presentation: smaller is invisible from a few metres; mass per area sets the trajectory). */
    static final float CLUMP_MIN = 0.25f, CLUMP_MAX = 0.5f;
    /** Soil grit, m: gravel grain, soil clod (presentation — a sand grain is invisible). */
    static final float GRAIN_MIN = 0.06f, GRAIN_MAX = 0.12f;
    /**
     * Below this launch speed, a piece moves less than a centimetre per tick — it would simply appear in place (Grad at
     * 200 m has a loose-soil detachment fraction, but no launch): no pieces; dust follows its own mass. Presentation.
     */
    static final double MIN_KICK_MPS = 0.2;
    /** Launch elevation relative to ground, tangent (19–35°): presentation, speed magnitude from the model ({@link FrontLitter#launch}). */
    static final double LIFT_MIN = 0.35, LIFT_MAX = 0.7;
    /** Grit density, kg/m³ (dry soil clod). */
    static final double GRAIN_DENSITY = 1600;
    /**
     * Real loose soil grain, m (est.) (1–5 mm grit lies on top and is launched by the front): the law's launch and
     * settling belong to it; it is drawn larger ({@link #GRAIN_MIN}…{@link #GRAIN_MAX}, presentation): a 10 cm clod falls at
     * ~50 m/s, and the front barely affects it in milliseconds.
     */
    static final double CRUMB_M = 0.002;
    /** Fine dust: puff size per site, m, and opacity cap (presentation: thick dust forms a brown wall at the eyes). */
    static final float PUFF_MIN = 0.6f, PUFF_MAX = 1.2f, PUFF_ALPHA = 0.5f;
    /**
     * Fine dust settling, m/s: extinction-weighted, Σ k·fraction·v / Σ k·fraction over {@link DustModel} bins (3–400 μm, k ∝ 1/D) —
     * 0.027: its fine grains are visible and barely settle; the puff spreads and drifts instead of falling.
     */
    static final double DUST_SETTLE_MPS = 0.027;
    /** Puff area grows fourfold over its life, fades from midlife, disperses over 4–6 s (presentation) (est.). */
    static final float PUFF_GROWTH = 2f;
    static final int PUFF_LIFE = 80, PUFF_LIFE_SPREAD = 40;
    /** Last emission near the camera (client scenario line). */
    private static String last = "-";

    /** Normal world wind at a piece ({@link ClientAir}): litter launched by the front then settles and follows it. */
    private static final Fragments.Wind AIR = (x, y, z, into) -> {
        into[0] = ClientAir.windX(y);
        into[2] = ClientAir.windZ(y);
        into[1] = Math.hypot(into[0], into[2]);
    };

    private NearLitter() {}

    public static String describe() {
        return last;
    }

    /** Material at the site: law surface and piece material; null — nothing (water, air). */
    record Cover(DustModel.Surface surface, Fragments.Kind kind, BlockState torn, boolean pieces) {}

    /**
     * Site surface from top block {@code cover} and plant above it {@code plant}: grass and flowers — grass tuft;
     * canopy — foliage tuft; turf — grass tuft; loose soil — its block grit; hard — dust only.
     */
    static Cover cover(BlockState cover, BlockState plant) {
        if (!cover.getFluidState().isEmpty()) return null;
        boolean grows = !plant.isAir() && plant.canBeReplaced() && plant.getFluidState().isEmpty();
        if (grows) return new Cover(DustModel.Surface.COVERED, Fragments.Kind.LEAF, plant, true);
        if (cover.is(BlockTags.LEAVES)) return new Cover(DustModel.Surface.COVERED, Fragments.Kind.LEAF, cover, true);
        if (cover.is(Blocks.GRASS_BLOCK)) return new Cover(DustModel.Surface.COVERED, Fragments.Kind.LEAF, Blocks.SHORT_GRASS.defaultBlockState(), true);
        if (FarBlasts.loose(cover)) return new Cover(DustModel.Surface.FIELD, Fragments.Kind.CHUNK, cover, true);
        if (FarBlasts.sod(cover)) return new Cover(DustModel.Surface.COVERED, Fragments.Kind.LEAF, Blocks.SHORT_GRASS.defaultBlockState(), true);
        if (cover.isAir()) return null;
        return new Cover(DustModel.Surface.HARD, Fragments.Kind.CHUNK, cover, false);
    }

    /**
     * Blast front {@code s} from {@code blast} passed the body at {@code body} ({@code bodyArrival} s after blast): litter around the
     * camera if it is at the body (distant projectile camera has its own view, {@code FarBlasts}). An underground blast sends no wave into
     * the air.
     */
    static void front(BlastSource s, Vec3 blast, Vec3 body, double bodyArrival, long seed) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        if (level == null || s.underground() || eye.distanceTo(body) > CAMERA_AT_BODY) return;
        spawn(level, s.chargeKg(), s.surface(), blast, eye, bodyArrival, RandomSource.create(seed ^ 0x5EEDL));
    }

    /** Camera counts as at the body within this distance in blocks (third-person view — up to 4 blocks behind the head). */
    static final double CAMERA_AT_BODY = 6;

    /**
     * Sample sites: {@link #STEP} cells in a circle of {@link #REACH} around the camera, nearest first — piece budget goes
     * to matter at the feet first.
     */
    private static final int[][] COLUMNS = columns();

    private static int[][] columns() {
        int r = (int) Math.ceil(REACH / STEP);
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++) if ((i * i + j * j) * STEP * STEP <= REACH * REACH) out.add(new int[]{i, j});
        out.sort(java.util.Comparator.comparingInt(c -> c[0] * c[0] + c[1] * c[1]));
        return out.toArray(new int[0][]);
    }

    /** Open above the block: air or a shapeless plant (grass, flowers) — front passes, detached matter rises. */
    static boolean open(BlockState s) {
        return s.isAir() || s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    /**
     * Upward-exposed surfaces of column {@code at}(y), top-down from {@code from} to {@code to}: a block that can support
     * detached matter (neither air nor plant), with air or a plant above. Under a canopy — both the canopy and grass
     * below; under a roof — the roof and the floor of the open space below it.
     */
    static int[] exposed(java.util.function.IntFunction<BlockState> at, int from, int to) {
        int[] out = new int[8];
        int n = 0;
        BlockState above = at.apply(from + 1);
        for (int y = from; y >= to; y--) {
            BlockState s = at.apply(y);
            if (!open(s) && open(above)) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, 2 * n);
                out[n++] = y;
            }
            above = s;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /**
     * Real piece body terminal speed in still air, m/s: grass/foliage tuft — from mass per area (size
     * irrelevant); grit — soil grain {@link #CRUMB_M}. The law launches by this speed; flight and settling use the same value
     * ({@link Fragments#torn} with terminal speed): drawn larger, flies as the real body.
     */
    static double terminal(Fragments.Kind kind) {
        return kind == Fragments.Kind.LEAF ? Fragments.terminal(kind, 0, CLUMP_MIN) : Fragments.terminal(kind, GRAIN_DENSITY, CRUMB_M);
    }

    /**
     * Blast front of {@code charge} kg TNT ({@code surface} — on the ground) from {@code blast} passed the body at {@code eye}
     * ({@code bodyArrival} s after blast): surrounding litter on exposed surfaces in a sphere of {@link #REACH} around the eye (chunks
     * the client already has). Each site waits for front arrival there.
     */
    static void spawn(ClientLevel level, double charge, boolean surface, Vec3 blast, Vec3 eye, double bodyArrival, RandomSource rnd) {
        if (!(charge > 0) || !visible(charge, surface, Math.max(0.5, eye.distanceTo(blast) - REACH))) return;
        int budget = Fragments.share(MAX), born = 0, puffs = 0;
        java.util.Map<Integer, ShockAtBody> shocks = new java.util.HashMap<>();
        java.util.Map<Long, LocalLitter.Lift> lifts = new java.util.HashMap<>();
        double groundY = blast.y;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int low = Math.max(level.getMinBuildHeight(), Mth.floor(eye.y - REACH)), high = Mth.floor(eye.y + REACH);
        for (int[] col : COLUMNS) {
            if (born >= budget) break;
            double dx = col[0] * STEP + (rnd.nextDouble() - 0.5) * STEP, dz = col[1] * STEP + (rnd.nextDouble() - 0.5) * STEP;
            double x = eye.x + dx, z = eye.z + dz;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
            if (chunk == null) continue;
            // no exposed sites above the column's highest block (chunk getHeight already returns it)
            int from = Math.min(high, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15));
            for (int top : exposed(y -> chunk.getBlockState(m.set(bx, y, bz)), from, low)) {
                double y = top + 1.05;
                if (dx * dx + dz * dz + (y - eye.y) * (y - eye.y) > REACH * REACH) continue;
                // front travels through outdoor air: it does not affect sites without skylight (cave underfoot, enclosed room) —
                // same criterion as the nuclear pall (Minecraft skylight — site's connection to the sky, OpenAir)
                if (level.getBrightness(LightLayer.SKY, m.set(bx, top + 1, bz)) <= 0) continue;
                BlockState coverState = chunk.getBlockState(m.set(bx, top, bz)), plant = chunk.getBlockState(m.set(bx, top + 1, bz));
                Cover c = cover(coverState, plant);
                if (c == null) continue;
                boolean crown = c.torn == coverState && coverState.is(BlockTags.LEAVES);
                double ground = Math.hypot(x - blast.x, z - blast.z), slant = Math.hypot(ground, y - blast.y);
                // canopy and material above the blast see the airborne wave
                double along = crown || y > groundY + 2 ? 1 : FrontLitter.along(ground, slant);
                // law once per distance and dynamic-pressure fraction step (loose-soil entrainment integral ~6 μs per site, with up to
                // a thousand sites per blast): ShockAtBody depends only on distance, tuft and grit on material, rather than size
                int ri = (int) Math.round(slant / SLANT_STEP), ai = (int) Math.round(along / ALONG_STEP);
                long key = ((long) ri << 16) | ((long) ai << 8) | ((long) surfaceIndex(c.surface) << 1) | (c.kind == Fragments.Kind.LEAF ? 1 : 0);
                ShockAtBody shock = shocks.computeIfAbsent(ri, k -> ShockAtBody.chemical(charge, surface, k * SLANT_STEP));
                LocalLitter.Lift lift = lifts.computeIfAbsent(key, k -> LocalLitter.lift(shock, c.surface, ai * ALONG_STEP, terminal(c.kind)));
                int delay = Math.max(0, (int) Math.ceil(20 * (shock.arrivalSeconds() - bodyArrival)));
                if (delay > Fragments.DELAY_MAX) continue;
                double ox = ground > 1e-6 ? (x - blast.x) / ground : 0, oz = ground > 1e-6 ? (z - blast.z) / ground : 0;
                // pieces: detachment fraction × sample per site area (fractional part selected randomly)
                double want = lift.releaseShare() * PER_M2 * STEP * STEP;
                int n = (int) want + (rnd.nextDouble() < want - (int) want ? 1 : 0);
                if (c.pieces && pieces(lift)) {
                    // model launch (its upward fraction is near-ground turbulence, 0.1) rotated up to 19–35° from the ground, same speed
                    // magnitude: presentation (as for FrontLitter), “hopping” makes detachment visible nearby
                    double speed = Math.hypot(lift.horizontalMps(), lift.verticalMps()) / 20;
                    BlockPos from0 = new BlockPos(bx, c.torn == plant ? top + 1 : top, bz);
                    for (int k = 0; k < n && born < budget; k++) {
                        double[] kick = FrontLitter.launch(speed, LIFT_MIN + (LIFT_MAX - LIFT_MIN) * rnd.nextDouble());
                        float size = c.kind == Fragments.Kind.LEAF ? CLUMP_MIN + (CLUMP_MAX - CLUMP_MIN) * rnd.nextFloat()
                                : GRAIN_MIN + (GRAIN_MAX - GRAIN_MIN) * rnd.nextFloat();
                        // drawn larger, flies and settles as the law's real body
                        if (Fragments.torn(level, c.torn, from0, c.kind, x + (rnd.nextDouble() - 0.5) * STEP, y, z + (rnd.nextDouble() - 0.5) * STEP,
                                ox * kick[0], kick[1], oz * kick[0], size, lift.settlingMps(), AIR, delay, crown)) born++;
                    }
                }
                if (dust(level, lift, coverState, x, y, z, ox, oz, delay, rnd)) puffs++;
            }
        }
        last = String.format(Locale.ROOT, "near litter pieces %d puffs %d", born, puffs);
    }

    /**
     * Blast distance step, m, and ground-aligned dynamic-pressure fraction step for a single law calculation: over 0.25 m, front
     * pressure changes by ~5 % at 10 m and tenths of a percent at hundreds of metres, while piece sites already vary within a metre
     * cell. A salvo of 8 Shaheds over sand would compute the law for every column — ~7 ms per blast on the impact tick.
     */
    static final double SLANT_STEP = 0.25, ALONG_STEP = 0.05;

    /** Surface index for the law key. */
    private static int surfaceIndex(DustModel.Surface s) {
        return s == DustModel.Surface.FIELD ? 0 : s == DustModel.Surface.COVERED ? 1 : 2;
    }

    /**
     * Whether the front lifts any visible matter where strongest in the circle — {@code slant} m from the blast (on loose
     * soil and grass): if not, it lifts nothing farther away, so columns are not scanned (Grad at 200 m has detachment
     * fraction but no launch and no dust).
     */
    static boolean visible(double charge, boolean surface, double slant) {
        ShockAtBody shock = ShockAtBody.chemical(charge, surface, slant);
        LocalLitter.Lift loose = LocalLitter.lift(shock, DustModel.Surface.FIELD, 1, terminal(Fragments.Kind.CHUNK));
        LocalLitter.Lift grass = LocalLitter.lift(shock, DustModel.Surface.COVERED, 1, terminal(Fragments.Kind.LEAF));
        return pieces(loose) || pieces(grass) || dustAlpha(loose.massKgPerM2(), PUFF_MIN) >= PUFF_SEEN;
    }

    /** Fine dust puffs below this opacity are not drawn. */
    static final float PUFF_SEEN = 0.02f;

    /**
     * Opacity of a fine dust puff of size {@code size} m with lifted mass {@code kgPerM2} from the site cell:
     * 1 − e^(−τ), τ = {@link DustModel#K_MIX}·m / puff area, at most {@link #PUFF_ALPHA}.
     */
    static float dustAlpha(double kgPerM2, float size) {
        double tau = DustModel.K_MIX * Math.max(0, kgPerM2) * STEP * STEP / (Math.PI * size * size);
        return (float) Math.min(PUFF_ALPHA, 1 - Math.exp(-tau));
    }

    /** Pieces are visible only if launched ({@link #MIN_KICK_MPS}) and detached. */
    static boolean pieces(LocalLitter.Lift lift) {
        return lift.releaseShare() > 0 && Math.hypot(lift.horizontalMps(), lift.verticalMps()) >= MIN_KICK_MPS;
    }

    /**
     * Site fine dust: a puff shifted away from the blast by phase air over {@link LocalLitter.Lift#airDisplacementM}
     * (quickly, during the phase); then barely settling ({@link #DUST_SETTLE_MPS}), spreading and drifting. Opacity — optical depth of lifted mass
     * over puff area ({@link DustModel#K_MIX}), capped at {@link #PUFF_ALPHA} (presentation).
     */
    private static boolean dust(ClientLevel level, LocalLitter.Lift lift, BlockState cover, double x, double y, double z, double ox, double oz,
                                int delay, RandomSource rnd) {
        if (!(lift.massKgPerM2() > 0)) return false;
        float size = PUFF_MIN + (PUFF_MAX - PUFF_MIN) * rnd.nextFloat();
        float alpha = dustAlpha(lift.massKgPerM2(), size);
        if (alpha < PUFF_SEEN) return false;
        float[] c = FarBlasts.dustColour(GroundMaterial.of(cover));
        // drag halves velocity per tick: millisecond phase displacement is spread over the first ticks as v·(1 + ½ + ¼ …) = 2v, while
        // terminal speed is g·d/(1 − d) = g — settling at its own speed
        double shift = lift.airDisplacementM() / 2;
        Fx.smoke().vel(ox * shift, lift.verticalMps() / 40, oz * shift).size(size, size * PUFF_GROWTH).life(PUFF_LIFE + rnd.nextInt(PUFF_LIFE_SPREAD))
                .color(c, c).alpha(alpha).drag(0.5f).gravity((float) (DUST_SETTLE_MPS / 20)).wind(1).fadeIn(1, delay).fadeFrom(0.5f)
                .budget(FxBudget.GROUND).spawn(level, new Vec3(x, y - 0.6, z));
        return true;
    }
}
