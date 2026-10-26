package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Stability;

import java.util.ArrayList;
import java.util.List;

/**
 * Coarse dust from torn turf: the front lifts it once, then it drifts and settles out of the mixed layer.
 * These are FrontLitter detachment details, rather than a second physical GroundDust layer (its mass and settling stay unchanged).
 * Detachment position and time use the same DustModel and ChemicalFront; contrast, billow size and accentuated lift are presentation.
 * Unlike integer-tick particle delays, the source checks fractional-tick arrival: the ring does not appear all at once.
 */
final class FrontDust implements SmokeGrid.Source {
    /**
     * Detachment patch: position, blast distance, front arrival and positive phase, grown billow horizontal σ,
     * area, detachment contrast, wind and stability; {@code until} — blast age after which its billow is invisible.
     */
    record Patch(double x, double y, double z, double slant, double arrival, double phase, double sigma,
                 double area, double contrast, double windX, double windZ, double stability, double until) {
        Patch(double x, double y, double z, double slant, double arrival, double phase, double sigma,
              double area, double contrast, double windX, double windZ, double stability) {
            this(x, y, z, slant, arrival, phase, sigma, area, contrast, windX, windZ, stability,
                    seenUntil(arrival, phase, fullTau(area, contrast, sigma)));
        }
    }

    /**
     * presentation: patch grid discretizes the ring, rather than counting soil particles. Same step along and across the ring,
     * {@link #STEP} m, wider for a large charge — at most {@link #PATCHES} patches (for a salvo — {@link #share}); a
     * grown patch billow has σ = {@link #SPREAD} steps: neighbours overlap, no beads in the layer. Cost: {@code SmokePuffs}
     * merges source billows pairwise every frame; the previous 24 × 64 grid (864 billows for Shahed, cross-ring step
     * 2.2 m versus 5.6 along) cost ~1 ms of frame time per blast, multiplied for a salvo.
     */
    static final double STEP = 5.6, SPREAD = 0.6;
    static final int PATCHES = 400;
    /**
     * presentation: at most {@link #ALL_PATCHES} visible patches across all blasts ({@link #share}): beyond that,
     * blasts are thinned (first those above an equal share, oldest among them, then farthest) — a group's patches merge
     * into one wider patch with the same mass, keeping the ring covered ({@link #thin}). Without a shared cap, eight
     * Shaheds at one site drew eight overlapping layers of ~360 billows — effects layer +12 ms per frame versus main.
     */
    static final int ALL_PATCHES = 800;
    /** Blasts with patches still visible (for the shared cap). */
    private static final List<FrontDust> LIVE = new ArrayList<>();
    /** Maximum thinning levels: each halves the count along arcs or across rings. */
    static final int LEVELS = 24;
    /** {@code SmokePuffs} does not draw a billow below this opacity (centre opacity 0.004): its patch is no longer needed. */
    static final double FAINTEST = 0.004;
    /**
     * presentation: mixed-layer height, vertical optical depth of lifted matter (stock per source area), and maximum optical depth
     * per billow. No new GroundDust mass. Depth 0.5 keeps the ring visible from 20–100 m without forming a wall near the camera
     * (0.08 made the ring invisible; 2.8 per billow in R2 was a full-screen brown wall). At the advancing edge the billow is still
     * compressed by the front (its 4σ is behind it): the full patch stock would be an opaque dot, so billow depth is
     * capped by grown depth and {@code EDGE_TAU}; the patch releases the rest as the billow grows.
     */
    static final double HEIGHT = 1.3, COLUMN_TAU = 0.5, EDGE_TAU = 0.8;
    /**
     * Screen colour of a thick grit layer — dry light soil dust: ω' ≈ 0.9 in red (mineral dust in visible light
     * 0.85–0.95, (est.) for turf grit), and albedo derived from it ({@link SmokeGrid#dustAlbedo}). A billow takes albedo, rather than
     * screen colour: screen colour used as albedo produced a grey thick layer, while dark dust (ω' ≈ 0.8) in a billow of 0.8 depth
     * under overhead sun darkened at the centre against its bright translucent rim (“doughnuts”).
     */
    static final float SHOWN_R = 0.72f, SHOWN_G = 0.65f, SHOWN_B = 0.54f;
    static final float ALBEDO_R = SmokeGrid.dustAlbedo(SHOWN_R), ALBEDO_G = SmokeGrid.dustAlbedo(SHOWN_G),
            ALBEDO_B = SmokeGrid.dustAlbedo(SHOWN_B);
    @Nullable
    private final ClientLevel level;
    private final long born;
    private final ChemicalFront front;
    /** Blast position. */
    private final double cx, cy, cz;
    /** Patches by ring from inside outward; each has its ring; rings have radius and site count. */
    private final Patch[] patches;
    private final int[] ring, spokes;
    private final double[] radius;
    private final double dr;
    /** Thinning level and its patches indexed by the original: first in a group — merged, others — null. */
    private int thinned;
    private Patch[] shown;
    private double end;
    /** Groups at each level (−1 — not computed yet). */
    private final int[] groupCount = new int[LEVELS + 2];

    private FrontDust(@Nullable ClientLevel level, ChemicalFront front, Vec3 pos, long born, List<Patch> patches, List<Integer> rings,
                      int[] spokes, double[] radius, double dr) {
        this.front = front;
        this.level = level;
        this.born = born;
        cx = pos.x;
        cy = pos.y;
        cz = pos.z;
        this.patches = patches.toArray(Patch[]::new);
        ring = rings.stream().mapToInt(Integer::intValue).toArray();
        this.spokes = spokes;
        this.radius = radius;
        this.dr = dr;
        java.util.Arrays.fill(groupCount, -1);
        show(this.patches);
    }

    /** Site cover: top height and whether it is foliage. */
    record Cover(int top, boolean leaves) {}

    /** Torn cover in column (bx, bz); null — nothing to tear (bare soil, water, stone, sand) or no chunk. */
    interface Ground {
        @Nullable
        Cover at(int bx, int bz);
    }

    static void spawn(ClientLevel level, Vec3 pos, ChemicalFront front, double inner, double outer, double hob) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // over the grit lifetime of seconds — air at detachment (constant-wind estimate)
        FrontDust dust = layout(level, pos, front, inner, outer, hob, ClientAir.now(), (bx, bz) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null) return null;
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
            var cover = chunk.getBlockState(m.set(bx, top, bz));
            // only torn cover; bare soil, water, stone and sand remain with GroundDust
            boolean leaves = cover.is(BlockTags.LEAVES);
            return leaves || cover.is(Blocks.GRASS_BLOCK) ? new Cover(top, leaves) : null;
        }, BlastTime.ticks());
        if (dust == null) return;
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        LIVE.removeIf(FrontDust::ended);
        share(LIVE, dust, BlastTime.ticks(), eye);
        SmokeGrid.add(dust);
    }

    /** Detachment patches around {@code pos} in a ring from {@code inner} to {@code outer} m; null — no detachment anywhere. */
    @Nullable
    static FrontDust layout(@Nullable ClientLevel level, Vec3 pos, ChemicalFront front, double inner, double outer, double hob, Air air,
                            Ground ground, long born) {
        List<Patch> patches = new ArrayList<>();
        List<Integer> places = new ArrayList<>();
        int rings = rings(inner, outer);
        double dr = (outer - inner) / rings, arc = arc(inner, outer);
        int[] counts = new int[rings];
        double[] radii = new double[rings];
        for (int j = 0; j < rings; j++) {
            double r = inner + dr * (j + 0.5);
            int spokes = spokes(r, arc);
            counts[j] = spokes;
            radii[j] = r;
            for (int i = 0; i < spokes; i++) {
                double a = 2 * Math.PI * (i + 0.5 * (j % 2)) / spokes;
                double x = pos.x + r * Math.cos(a), z = pos.z + r * Math.sin(a);
                Cover cover = ground.at(Mth.floor(x), Mth.floor(z));
                if (cover == null) continue;
                double y = cover.top() + 1.05, slant = Math.sqrt(r * r + (y - pos.y) * (y - pos.y));
                double along = cover.leaves() || y > pos.y - Math.max(0, hob) + 2 ? 1 : FrontLitter.along(r, Math.hypot(r, hob));
                double friction = BlastLitter.friction(front.kpa(slant), along);
                if (!BlastLitter.tearsCover(front.kpa(slant), along)) continue;
                double contrast = Math.min(1, friction / DustModel.Surface.COVERED.threshold() - 1);
                patches.add(new Patch(x, y, z, slant, front.arrivalSeconds(slant),
                        BlastModel.chemicalPhaseSeconds(slant, front.tntKg, front.surface), SPREAD * Math.max(dr, 2 * Math.PI * r / spokes),
                        2 * Math.PI * r * dr / spokes, contrast, air.windX(y + HEIGHT / 2), air.windZ(y + HEIGHT / 2), air.stability()));
                places.add(j);
            }
        }
        return patches.isEmpty() ? null : new FrontDust(level, front, pos, born, patches, places, counts, radii, dr);
    }

    /**
     * Lift over the dynamic-pressure impulse, then settling of the coarse bin: dm/dt = −v_settle m / h, as in DustModel.
     * Constant mixed-layer height is an approximation over seconds. Brightness disappears only with the suspension;
     * the end of the positive phase stops emission but does not remove lifted dust.
     */
    static double envelope(double age, double phase) {
        if (age <= 0 || phase <= 0) return 0;
        return DustModel.lifted(age / phase) * Math.exp(-BlastLitter.COVER_DUST_SETTLE * Math.max(0, age - phase) / HEIGHT);
    }

    /** Patch rings within the detachment ring from {@code inner} to {@code outer} m: cross-ring step {@link #STEP}, wider for a large ring. */
    static int rings(double inner, double outer) {
        double step = Math.max(STEP, Math.sqrt(Math.PI * (outer * outer - inner * inner) / PATCHES));
        return Math.max(1, (int) Math.ceil((outer - inner) / step));
    }

    /**
     * Patch step along a ring, m: {@link #STEP}, or for a large ring wide enough that after rounding per ring patch count is at
     * most {@link #PATCHES}: Σ⌈2πr/step⌉ ≤ π(R² − r²)/(dr·step) + rings (ring midpoints: Σ r·dr = (R² − r²)/2).
     */
    static double arc(double inner, double outer) {
        int rings = rings(inner, outer);
        double dr = (outer - inner) / rings;
        return Math.max(STEP, Math.PI * (outer * outer - inner * inner) / (dr * Math.max(1, PATCHES - rings)));
    }

    /**
     * Shared cap ({@link #ALL_PATCHES}): {@code fresh} joins {@code live}; while visible patches across all blasts at
     * {@code now} (ticks) exceed the cap, thin a blast above an equal share (if none, any still eligible),
     * oldest first, ties resolved by distance from the eye. None eligible — the new blast draws no dust: previous ones already fit
     * (visible count only decreases over time).
     */
    static void share(List<FrontDust> live, FrontDust fresh, long now, Vec3 eye) {
        live.add(fresh);
        while (true) {
            int total = 0;
            for (FrontDust d : live) total += d.visible(now);
            if (total <= ALL_PATCHES) return;
            double fair = (double) ALL_PATCHES / live.size();
            FrontDust pick = null;
            boolean pickAbove = false;
            for (FrontDust d : live) {
                if (!d.canThin()) continue;
                boolean above = d.visible(now) > fair;
                if (pick == null || above && !pickAbove || above == pickAbove && (d.born < pick.born
                        || d.born == pick.born && d.distanceSqr(eye) > pick.distanceSqr(eye))) {
                    pick = d;
                    pickAbove = above;
                }
            }
            if (pick == null) {
                fresh.show(new Patch[fresh.patches.length]);
                return;
            }
            pick.thin();
        }
    }

    /** Visible patches at {@code now} (ticks). */
    int visible(long now) {
        double elapsed = BlastTime.seconds(now, born, 0);
        int n = 0;
        for (Patch p : shown) if (p != null && p.until > elapsed) n++;
        return n;
    }

    /** Patches drawn now (first in each group — merged; others — null). */
    Patch[] shown() {
        return shown;
    }

    private double distanceSqr(Vec3 eye) {
        return (cx - eye.x) * (cx - eye.x) + (cy - eye.y) * (cy - eye.y) + (cz - eye.z) * (cz - eye.z);
    }

    /** Next level has fewer groups. */
    boolean canThin() {
        return thinned < LEVELS && groups(thinned + 1) < groups(thinned);
    }

    /** Halve density: next thinning level. */
    void thin() {
        thinned++;
        show(aggregate(thinned));
    }

    private void show(Patch[] shown) {
        this.shown = shown;
        double last = 0;
        for (Patch p : shown) if (p != null) last = Math.max(last, p.until);
        end = last;
    }

    /** Level {@code thinned} step along the arc (a) and across rings (b): alternate doubling. */
    private static int arcStride(int thinned) {
        return 1 << ((thinned + 1) / 2);
    }

    private static int ringStride(int thinned) {
        return 1 << (thinned / 2);
    }

    /** Largest arc step for a ring of {@code spokes} patches: at least three remain on a ring. */
    private static int arcMost(int spokes) {
        return Integer.highestOneBit(Math.max(1, spokes / 3));
    }

    /**
     * Group of patch {@code i} at level {@code thinned}: rings in blocks of b from the inner ring; along the arc — site index on
     * the block's first ring at this angle divided by the arc step (at least three groups per ring).
     */
    private long group(int i, int thinned) {
        int b = ringStride(thinned), j0 = ring[i] / b * b, s0 = spokes[j0], a = Math.min(arcStride(thinned), arcMost(s0));
        double turn = Math.atan2(patches[i].z - cz, patches[i].x - cx) / (2 * Math.PI);
        int k = Math.floorMod((int) Math.floor((turn - Math.floor(turn)) * s0 - 0.5 * (j0 % 2) + 1e-9), s0);
        return (long) j0 << 32 | (k / a);
    }

    private int groups(int thinned) {
        if (groupCount[thinned] < 0) {
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (int i = 0; i < patches.length; i++) seen.add(group(i, thinned));
            groupCount[thinned] = seen.size();
        }
        return groupCount[thinned];
    }

    /**
     * Patches at level {@code thinned}: one patch per group at the first index, with the same mass (sum of area ×
     * contrast), at the group's centre of mass, σ — group step × {@link #SPREAD}; arrival and phase from its position. The first
     * billow in the group retains its seed: thinning merges billows rather than respawning them.
     */
    private Patch[] aggregate(int thinned) {
        Patch[] out = new Patch[patches.length];
        java.util.Map<Long, List<Integer>> members = new java.util.LinkedHashMap<>();
        for (int i = 0; i < patches.length; i++) members.computeIfAbsent(group(i, thinned), g -> new ArrayList<>()).add(i);
        int b = ringStride(thinned);
        for (List<Integer> g : members.values()) {
            int first = g.getFirst(), j0 = ring[first] / b * b, s0 = spokes[j0], a = Math.min(arcStride(thinned), arcMost(s0));
            double area = 0, mass = 0, x = 0, y = 0, z = 0;
            for (int i : g) {
                Patch p = patches[i];
                area += p.area;
                mass += p.area * p.contrast;
                x += p.area * p.x;
                y += p.area * p.y;
                z += p.area * p.z;
            }
            x /= area;
            y /= area;
            z /= area;
            double slant = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz));
            Patch head = patches[first];
            double sigma = SPREAD * Math.max(Math.min(b, spokes.length - j0) * dr, a * 2 * Math.PI * radius[j0] / s0);
            out[first] = new Patch(x, y, z, slant, front.arrivalSeconds(slant), BlastModel.chemicalPhaseSeconds(slant, front.tntKg, front.surface),
                    sigma, area, mass / area, head.windX, head.windZ, head.stability);
        }
        return out;
    }

    /** World exit. */
    static void reset() {
        LIVE.clear();
    }

    /** Patches on a ring of radius {@code r} with arc step {@code arc}. */
    static int spokes(double r, double arc) {
        return Math.max(6, (int) Math.ceil(2 * Math.PI * r / arc));
    }

    /** Grown patch billow optical depth across its centre (before settling). */
    static double fullTau(double area, double contrast, double sigma) {
        return COLUMN_TAU * area * contrast / (2 * Math.PI * sigma * Math.min(HEIGHT / 2, sigma));
    }

    /**
     * Blast age at which settling makes the patch billow fainter than {@link #FAINTEST}: its optical depth is at most
     * max({@code tau}, {@link #EDGE_TAU}), then fades by settling (spreading only dilutes it).
     */
    static double seenUntil(double arrival, double phase, double tau) {
        double most = Math.max(tau, EDGE_TAU);
        return arrival + phase + HEIGHT / BlastLitter.COVER_DUST_SETTLE * Math.log(Math.max(1, most / FAINTEST));
    }

    /**
     * One finite stock per source area: billow expansion creates no new optical mass; at the edge — no thicker than the
     * grown billow and no less than {@link #EDGE_TAU}. An invisible patch ({@link Patch#until}) returns an empty billow: its
     * source slot (billow seed) remains, but merging and drawing skip it.
     */
    static void emitPatch(Patch p, double elapsed, double radius, SmokeGrid.Sink out) {
        if (elapsed >= p.until) {
            out.blob(p.x, p.y, p.z, 0, 0, 0, 0, 0, ALBEDO_R, ALBEDO_G, ALBEDO_B, CloudModel.T_AIR_K, 0);
            return;
        }
        double age = elapsed - p.arrival, left = envelope(age, p.phase);
        double path = Math.max(1, Math.hypot(p.windX, p.windZ)) * Math.max(0, age);
        double spread = Stability.sigmaY(p.stability, path);
        double dx = p.windX * Math.max(0, age), dz = p.windZ * Math.max(0, age);
        // billow stands on the ground and grows upward with itself: centre at its vertical σ (torn material
        // starts at ground level), rather than hovering halfway up the layer while still centimetres wide. Entire billow (centre, lift and 4σ) stays behind
        // the front.
        double room = Math.max(0, radius - p.slant - Math.hypot(dx, dz));
        double s = Math.min(Math.hypot(p.sigma, spread), room / 5);
        double v = Math.min(HEIGHT / 2, s), lift = v;
        double k = SmokeGrid.dustK(ALBEDO_R, ALBEDO_G, ALBEDO_B), edge = Math.max(EDGE_TAU, fullTau(p.area, p.contrast, p.sigma));
        double dust = s > 0 && v > 0 ? Math.min(COLUMN_TAU * p.area * p.contrast * left, edge * 2 * Math.PI * s * v) / k : 0;
        out.blob(p.x + dx, p.y + lift, p.z + dz,
                s, v, 0, dust, 0, ALBEDO_R, ALBEDO_G, ALBEDO_B, CloudModel.T_AIR_K, 0);
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        double elapsed = BlastTime.seconds(BlastTime.ticks(), born, 20 * (seconds - SmokeGrid.seconds(0)));
        if (ended() || elapsed >= end) return false;
        double radius = front.radiusAt(elapsed);
        for (Patch p : shown) {
            // undrawn member of a thinned group — empty billow: source billow indices (seeds) stay unchanged
            if (p == null) out.blob(cx, cy, cz, 0, 0, 0, 0, 0, ALBEDO_R, ALBEDO_G, ALBEDO_B, CloudModel.T_AIR_K, 0);
            else emitPatch(p, elapsed, radius, out);
        }
        return true;
    }

    @Override
    public boolean ended() {
        return level != Minecraft.getInstance().level || BlastTime.seconds(BlastTime.ticks(), born, 0) >= end;
    }
}
