package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.CraterGround;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.warhead.GroundMaterial;
import ua.zentix.incoming.warhead.MaterialPath;
import ua.zentix.incoming.warhead.SurfaceBlocks;
import ua.zentix.incoming.warhead.SurfaceDamage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Surface-burst crater (DESIGN-nuke §1.7, §3.5): paraboloid with irregular edge, ejecta rim to two radii, trinitite on
 * floor and walls. Depth and rim are measured from the ground under the crater ({@link CraterGround}): first a survey
 * reads the surface of every chunk around the bowl and fits the ground plane through their low parts; then each column
 * standing above that plane is checked for a building ({@link CraterGround#base}), whose ground is the ground under it.
 * Buildings and roof gardens are cut away with the bowl instead of moving it up; hills, knolls and lake beds keep their
 * own surface, and the rim lies on the same ground the bowl is cut from.
 * <p>
 * Visits chunks outward from center, one column per step under the shared time budget (the survey one chunk per
 * step). Chunks load in the background via tickets ({@link NuclearTickets}) — current and several ahead; work waits
 * until ready. Chunk index and ground plane are world-saved, so after restart digging resumes at the same position
 * with the same ground (operations repeatable).
 */
public final class CraterJob {
    /** How many chunks ahead to request loading. */
    private static final int LOOKAHEAD = 4;
    /** Survey reach beyond the bowl, ×radius: the widest edge ({@link CraterGround#edge}) and a margin for the fit. */
    private static final double SURVEY_REACH = 1.15;
    /** How many blocks down to search for natural ground under buildings. */
    private static final int MAX_DEPTH = 96;
    /** Same vanilla blast-proof boundary as the charge/breach rays (obsidian). */
    private static final float BLAST_PROOF = 1200;

    private final Detonation d;
    private final BlockPos gz;
    private final int outer;
    private final List<ChunkPos> chunks = new ArrayList<>();
    private int next;
    private int column;
    private int samples;
    @Nullable
    private CraterModel.Soil soil;
    private List<BlockState> rim = List.of();
    /** Ground plane relative to the epicentre ({@link CraterGround#fit}); null — survey not finished. */
    @Nullable
    private double[] ground;
    /** Bowl chunks for the survey (after the soil is known) and how many are read. */
    @Nullable
    private List<ChunkPos> survey;
    private int surveyed;
    private double[] sx = new double[0], sz = new double[0], sh = new double[0];
    /** The survey has just finished and its plane is not saved yet ({@link #surveyedGround}). */
    private boolean unsaved;

    /** @param startChunk chunk to resume from (0 — new crater) */
    public CraterJob(Detonation d, int startChunk) {
        this(d, startChunk, null);
    }

    /**
     * @param startChunk chunk to resume from (0 — new crater)
     * @param ground     saved ground plane ({@link #ground()}); null — survey it, or for a crater half dug by a version
     *                   without the survey, keep measuring from each column's natural ground
     */
    public CraterJob(Detonation d, int startChunk, @Nullable double[] ground) {
        this.d = d;
        this.gz = BlockPos.containing(d.burst().x, d.groundY(), d.burst().z);
        // largest soil multiplier is wet (×1.3): rim reaches two radii
        this.outer = Mth.ceil(d.blocks(CraterModel.radius(d.yieldKt(), CraterModel.Soil.WET)) * 2) + 1;
        ChunkPos c = new ChunkPos(gz);
        int rc = (outer >> 4) + 1;
        for (int cx = c.x - rc; cx <= c.x + rc; cx++) {
            for (int cz = c.z - rc; cz <= c.z + rc; cz++) {
                ChunkPos p = new ChunkPos(cx, cz);
                if (nearest(p) <= outer) chunks.add(p);
            }
        }
        chunks.sort(Comparator.comparingDouble(this::nearest));
        this.next = Mth.clamp(startChunk, 0, chunks.size());
        // a crater half dug before the survey existed: surveying now would read its own pit as the ground
        this.ground = ground != null ? ground.clone() : next > 0 ? new double[] {0, 0, Double.POSITIVE_INFINITY} : null;
    }

    /** Ground plane {@code {a, b, c}} relative to the epicentre; null — survey not finished. */
    @Nullable
    public double[] ground() {
        return ground == null ? null : ground.clone();
    }

    /** The plane once, right after the survey fits it (to save with the progress); null otherwise. */
    @Nullable
    public double[] surveyedGround() {
        if (!unsaved) return null;
        unsaved = false;
        return ground();
    }

    public Detonation detonation() {
        return d;
    }

    /** Chunks already dug (for persistence). */
    public int progress() {
        return next;
    }

    /** Soil under the epicenter used for the profile (null — epicenter chunk not loaded yet). */
    @Nullable
    public CraterModel.Soil soil() {
        return soil;
    }

    private double nearest(ChunkPos p) {
        double x = Mth.clamp(gz.getX(), p.getMinBlockX(), p.getMaxBlockX()) - gz.getX();
        double z = Mth.clamp(gz.getZ(), p.getMinBlockZ(), p.getMaxBlockZ()) - gz.getZ();
        return Math.sqrt(x * x + z * z);
    }

    public enum Step { PROGRESS, WAIT, DONE }

    /** One column. WAIT — required chunk still loading, resume next tick. */
    public Step step(ServerLevel level, RandomSource random) {
        if (next >= chunks.size()) {
            release(level);
            return Step.DONE;
        }
        if (soil == null && !sampleSoil(level)) return Step.WAIT;
        if (ground == null) return survey(level);
        for (int i = next; i < Math.min(chunks.size(), next + LOOKAHEAD); i++) NuclearTickets.hold(level, chunks.get(i), true);
        ChunkPos cp = chunks.get(next);
        if (!NuclearTickets.neighbourhoodLoaded(level, cp)) return Step.WAIT;
        dig(level, cp.getMinBlockX() + (column & 15), cp.getMinBlockZ() + (column >> 4), random);
        if (++column >= 256) {
            column = 0;
            NuclearTickets.hold(level, cp, false);
            next++;
        }
        return Step.PROGRESS;
    }

    /** Epicenter soil: determines crater radius, depth and rim material. */
    private boolean sampleSoil(ServerLevel level) {
        ChunkPos c = new ChunkPos(gz);
        NuclearTickets.hold(level, c, true);
        if (!Terrain.ready(level, c.x, c.z)) return false;
        GroundMaterial mat = GroundMaterial.sample(level, gz);
        soil = mat.soil();
        rim = mat.debris();
        // resume after restart: epicenter chunk already dug, no reason to hold it
        if (next > 0) NuclearTickets.hold(level, c, false);
        return true;
    }

    /** Survey radius, blocks: the bowl of the sampled soil with its widest edge, where the crater cuts. */
    private double reach() {
        return Math.max(1, d.blocks(CraterModel.radius(d.yieldKt(), soil)) * SURVEY_REACH);
    }

    /**
     * One chunk of the survey: its low surface ({@link CraterGround#lowColumn} of its columns within reach) at that
     * column. After the last chunk, the ground plane.
     */
    private Step survey(ServerLevel level) {
        double bowl = reach();
        if (survey == null) {
            survey = new ArrayList<>();
            for (ChunkPos p : chunks) if (nearest(p) <= bowl) survey.add(p);
            sx = new double[survey.size()];
            sz = new double[survey.size()];
            sh = new double[survey.size()];
        }
        if (surveyed >= survey.size()) {
            // no ground anywhere in the bowl (void): each column keeps its own, as before the survey
            ground = samples == 0 ? new double[] {0, 0, Double.POSITIVE_INFINITY} : CraterGround.fit(sx, sz, sh, samples);
            unsaved = true;
            return Step.PROGRESS;
        }
        for (int i = surveyed; i < Math.min(survey.size(), surveyed + LOOKAHEAD); i++) NuclearTickets.hold(level, survey.get(i), true);
        ChunkPos cp = survey.get(surveyed);
        if (!Terrain.ready(level, cp.x, cp.z)) return Step.WAIT;
        int[] heights = new int[256], at = new int[256];
        int n = 0;
        for (int i = 0; i < 256; i++) {
            int x = cp.getMinBlockX() + (i & 15), z = cp.getMinBlockZ() + (i >> 4);
            double dx = x + 0.5 - d.burst().x, dz = z + 0.5 - d.burst().z;
            if (dx * dx + dz * dz > bowl * bowl) continue;
            int h = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            // an empty column (void) is no ground: a plane at the world bottom would dig the bowl down to it
            if (h <= level.getMinBuildHeight()) continue;
            heights[n] = h;
            at[n++] = i;
        }
        if (n > 0) {
            int k = CraterGround.lowColumn(heights, n), i = at[k];
            sx[samples] = cp.getMinBlockX() + (i & 15) + 0.5 - d.burst().x;
            sz[samples] = cp.getMinBlockZ() + (i >> 4) + 0.5 - d.burst().z;
            sh[samples] = heights[k];
            samples++;
        }
        // the epicentre chunk stays held: digging starts there
        if (surveyed > 0) NuclearTickets.hold(level, cp, false);
        surveyed++;
        return Step.PROGRESS;
    }

    private void dig(ServerLevel level, int x, int z, RandomSource random) {
        double[] plane = ground;
        Profile p = new Profile() {
            @Override
            public double depth(double r) {
                return d.blocks(CraterModel.profileDepth(d.metres(r), d.yieldKt(), soil));
            }

            @Override
            public double rim(double r) {
                return d.blocks(CraterModel.rimHeight(d.metres(r), d.yieldKt(), soil));
            }

            @Override
            public double ground(int x, int z) {
                return plane == null ? Double.POSITIVE_INFINITY : CraterGround.at(plane, x + 0.5 - d.burst().x, z + 0.5 - d.burst().z);
            }
        };
        digColumn(level, x, z, d.burst(), outer, d.seed() % 13, p, rim, ModBlocks.TRINITITE.get().defaultBlockState(), Mode.VAPORIZE, random);
    }

    /** How a crater column is applied to blocks. */
    public enum Mode {
        /** Nuclear: vaporizes everything above soil within the bowl too; depth rounded down to whole blocks. */
        VAPORIZE,
        /**
         * Conventional charge: removes soil only (blast breaks buildings over the crater); depth and rim use whole blocks
         * with random rounding by fractional part: a sub-block bowl has the law's volume on average.
         */
        DIG
    }

    /** Crater profile: bowl depth and rim height above natural ground, blocks, at radius r blocks from center. */
    public interface Profile {
        double depth(double r);

        double rim(double r);

        /**
         * Ground plane under the crater at column ({@code x}, {@code z}) as a heightmap height (first air above): a
         * column standing above it is checked for a building ({@link CraterGround#base}). Infinite — each column's own
         * natural ground (conventional craters, whose bowl is smaller than a building).
         */
        default double ground(int x, int z) {
            return Double.POSITIVE_INFINITY;
        }
    }

    /**
     * One crater column (nuclear and conventional share shape law, {@link CraterModel#bowl}, {@link CraterModel#rim}):
     * irregular edge ({@link CraterGround#edge}), depth and rim from the column's ground (its natural ground, or the
     * ground under a building standing on it, {@link Profile#ground}); remove everything above the bowl profile
     * (underwater pit fills with water); ejecta soil {@code rimStates} forms the rim. Blocks change via
     * {@link ColumnScar#replace}. Column chunk and neighbors must be ready.
     *
     * @param outer  horizontal distance beyond which the column is untouched, blocks
     * @param phase  irregular-edge phase offset
     * @param glaze  floor glazing material (nuclear — trinitite), null — none
     * @param mode   nuclear or conventional crater ({@link Mode})
     */
    public static void digColumn(ServerLevel level, int x, int z, net.minecraft.world.phys.Vec3 centre, double outer, double phase, Profile profile,
                                 List<BlockState> rimStates, @Nullable BlockState glaze, Mode mode, RandomSource random) {
        double dx = x + 0.5 - centre.x, dz = z + 0.5 - centre.z;
        if (dx * dx + dz * dz > outer * outer) return;
        double r = Math.hypot(dx, dz) / CraterGround.edge(Math.atan2(dz, dx), phase);
        double depth = profile.depth(r);
        double lift = profile.rim(r);
        if (depth <= 0 && (mode == Mode.VAPORIZE ? lift < 1 : lift <= 0)) return;

        // heights as heightmaps: first air above a block
        int top = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int ground = naturalSurface(level, x, z, top);
        double plane = profile.ground(x, z);
        if (Double.isFinite(plane)) {
            BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos(x, 0, z);
            ground = CraterGround.base(y -> layer(level.getBlockState(at.setY(y))), ground, plane, level.getMinBuildHeight());
        }
        if (mode == Mode.DIG) ground = SurfaceBlocks.excavationTop(level, x, z, ground, top, centre.y);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, 0, z);
        // resulting surface = natural ground + rim − bowl (overlap near edge)
        double relief = lift - depth;
        BlockPos damagedPos = null;
        double damagedDepth = 0;
        // Context for resistance overrides: the excavation footprint, without executing a vanilla explosion.
        // VAPORIZE does not query resistance and needs no context.
        Explosion excavation = mode == Mode.DIG
                ? new Explosion(level, null, centre.x, centre.y, centre.z, (float) outer, false, Explosion.BlockInteraction.DESTROY)
                : null;
        if (mode == Mode.DIG) {
            BlockState surface = level.getBlockState(m.setY(ground - 1));
            if (surface.getBlock().defaultDestroyTime() < 0 || surface.getExplosionResistance(level, m, excavation) >= BLAST_PROOF) return;
            if (relief < 0) {
                // Consume represented material depth layer by layer. Geometry and the saved remainder stay
                // in block units, including a thin element buried under loose ground. A loose top consumes
                // the same one random draw and uses the same rounding direction as the original soil law.
                double left = -relief;
                double rounding = SurfaceBlocks.cohesive(surface) ? Double.NaN : random.nextDouble();
                int full = 0;
                for (int y = ground - 1; y >= level.getMinBuildHeight() && left > 1e-9; y--) {
                    BlockState layer = level.getBlockState(m.setY(y));
                    if (layer.isAir() || !layer.getFluidState().isEmpty()
                            || layer.getBlock().defaultDestroyTime() < 0
                            || layer.getExplosionResistance(level, m, excavation) >= BLAST_PROOF) break;
                    boolean cohesive = SurfaceBlocks.cohesive(layer);
                    // Loose soil is bulk column volume; cohesive shapes contribute their actual chord.
                    double occupied = cohesive ? MaterialPath.inside(layer.getCollisionShape(level, m), m,
                            new Vec3(x + 0.5, y + 1, z + 0.5), new Vec3(0, -1, 0), 1) : 1;
                    if (!(occupied > 0)) break;
                    double thickness = occupied * ModDataMaps.Penetrability.of(layer).thickness();
                    if (left + 1e-9 >= thickness) {
                        left -= thickness;
                        full++;
                    } else {
                        if (cohesive) {
                            damagedPos = m.immutable();
                            damagedDepth = left / thickness;
                        } else if ((Double.isNaN(rounding) ? random.nextDouble() : rounding) >= 1 - left / thickness) {
                            full++;
                        }
                        break;
                    }
                }
                relief = -full;
            } else if (relief > 0 && SurfaceBlocks.cohesive(surface)) {
                // A fractional ejecta rim is rubble, not a randomly added whole metre on intact paving.
                relief = Math.floor(relief);
            } else {
                double whole = Math.floor(relief);
                relief = whole + (random.nextDouble() < relief - whole ? 1 : 0);
            }
        }
        if (relief < 0) {
            // everything above bowl profile removed: buildings above soil and soil to profile; underwater pit fills with water
            int floor = Mth.floor(ground + relief);
            BlockState fill = level.getBlockState(m.setY(ground)).getFluidState().isSource()
                    ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
            for (int y = mode == Mode.VAPORIZE ? top - 1 : ground - 1; y >= floor; y--) {
                BlockState s = level.getBlockState(m.setY(y));
                if (y >= ground && !s.getFluidState().isEmpty()) continue; // water above the floor itself remains
                if (mode == Mode.DIG && (s.getBlock().defaultDestroyTime() < 0 || s.getExplosionResistance(level, m, excavation) >= BLAST_PROOF)) break;
                if (!s.isAir() && s.getBlock().defaultDestroyTime() >= 0) ColumnScar.replace(level, m, s, y < ground ? fill : Blocks.AIR.defaultBlockState());
            }
            BlockState bottom = level.getBlockState(m.setY(floor - 1));
            if (glaze != null && depth > lift && random.nextFloat() < 0.3f && !bottom.isAir() && bottom.getFluidState().isEmpty()
                    && bottom.getBlock().defaultDestroyTime() >= 0) {
                ColumnScar.replace(level, m, bottom, glaze);
            }
        } else if (relief >= 1 && !rimStates.isEmpty()) {
            // rim: ejected soil lies over natural ground
            int crest = Mth.floor(ground + relief);
            for (int y = ground; y < crest; y++) {
                BlockState was = level.getBlockState(m.setY(y));
                if (was.canBeReplaced()) ColumnScar.replace(level, m, was, rimStates.get(random.nextInt(rimStates.size())));
            }
        }
        // Full blocks above the residual surface have now gone; its chips must not be hidden by those old blocks.
        if (damagedPos != null) SurfaceDamage.mark(level, damagedPos, level.getBlockState(damagedPos), damagedDepth);
    }

    private static CraterGround.Layer layer(BlockState s) {
        if (BlockResponse.of(s).kind() == BlockResponse.Kind.GROUND) return CraterGround.Layer.GROUND;
        if (s.isAir()) return CraterGround.Layer.AIR;
        return s.getBlock() instanceof LiquidBlock ? CraterGround.Layer.LIQUID : CraterGround.Layer.BUILT;
    }

    /** First air above natural ground (below buildings, trees and other above-ground objects). */
    public static int naturalSurface(ServerLevel level, int x, int z, int top) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, 0, z);
        for (int y = top - 1; y >= Math.max(level.getMinBuildHeight(), top - MAX_DEPTH); y--) {
            if (BlockResponse.of(level.getBlockState(m.setY(y))).kind() == BlockResponse.Kind.GROUND) return y + 1;
        }
        return top;
    }


    public void release(ServerLevel level) {
        if (soil == null || ground == null) NuclearTickets.hold(level, new ChunkPos(gz), false);
        if (ground == null && survey != null) {
            for (int i = surveyed; i < Math.min(survey.size(), surveyed + LOOKAHEAD); i++) NuclearTickets.hold(level, survey.get(i), false);
        }
        for (int i = next; i < Math.min(chunks.size(), next + LOOKAHEAD); i++) NuclearTickets.hold(level, chunks.get(i), false);
    }
}
