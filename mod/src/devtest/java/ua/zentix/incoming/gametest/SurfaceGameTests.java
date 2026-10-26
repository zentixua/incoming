package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.warhead.Penetration;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;

/**
 * The complete strike against published data: every weapon on every surface, checking what remains in the
 * world (crater, removed blocks, slab breach, nearby walls and glass), rather than each formula separately.
 * Complaint 23.10 ("the strikes are weak"): individual formulas were correct, but nobody compared the strike
 * result in the world against the data.
 * <p>
 * The strike uses the same path as a projectile in the game: Shahed, Lancet, missile and Grad use
 * {@link Warheads#detonate} with their weapon entity (as in {@code StrikeProjectile.impact}: point at the block
 * top, axis from the spec impact angle {@link Article.Impact}); the B-2 bomb is a real entity dropped as from
 * a B-2 (170 blocks above the target, 145 before it, at B-2 speed): ground impact, penetration path,
 * detonation, underground explosion.
 * <p>
 * Expectations are the sourced laws already used by the mod and their variation in the data:
 * <ul>
 * <li>Charge crater in soil: {@link CraterModel#chargeRadius} (EMRTC, Ambrosini et al. 2003, Kinney–Graham;
 * soil: TM 23-200): removed volume 0,6–1,45 of the law (as in {@code BlastGameTests.craterTest}, #417), plus
 * whole-block variation (crater columns remove whole blocks with a random addition for the fractional part:
 * 2σ of that addition);</li>
 * <li>Slab over a void: at least the FM 5-250 (1992) breach, table B-3: K = R³·M·C, M by R ranges (rock, good
 * masonry, concrete block), C = 1,8 (external untamped charge), ± FM rounding of 0,25 m; breached when R exceeds
 * the thickness, everything within radius R removed; otherwise FM predicts no removal. No larger than the
 * circle where the wave breaks masonry (a slab over a void is a panel, like a wall). A charge that cannot
 * breach one metre (Lancet) must not open the slab;</li>
 * <li>Concrete on soil (road pavement): soil behind the slab supports it, with no free face; neither a breach
 * nor a panel, but a crater in material no softer than rock (TM 23-200: rock ×0,7) and no larger than the same
 * charge crater in soil; a block fraction in cohesive material is damaged depth, not a through hole;</li>
 * <li>Nearby walls and glass: mod pressure thresholds ({@link BlockResponse}: masonry 12 psi, boards 4 psi,
 * glass 0,8 psi; DESIGN-nuke §3.3), from Jarrett and Kinney–Graham: everything closer than 0,8 of its threshold
 * radius breaks, beyond 1,25 stays intact;</li>
 * <li>Bomb: Young penetration (SAND97-2426, {@link Penetration}: ~15 % in soil, up to a factor of two in rock)
 * and an ejecta crater by scaled depth ({@link BuriedBlast}, TM 5-855-1; beyond λ = 1, a camouflet without a crater).</li>
 * </ul>
 * Report: {@code SURFACEBENCH} lines "weapon × surface → expected / actual" and the complete batch table
 * when all its checks finish ({@code grep SURFACEBENCH} in the shard log).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceGameTests {
    /** The top of the surface on the “range” site (template turf) and the square where it belongs. */
    private static final int TOP = 11, LO = 8, HI = 55, MID = 32;
    /** The bottom of your layers: layer 1 of the template - a stone above the air under the platform (sand would crumble in the air). */
    private static final int BASE = 2;

    private SurfaceGameTests() {}

    /** Surface: what lies in the column on {@code depth} blocks under the top (0 is the top block). */
    enum Surface {
        SOIL("soil: podzol on the ground"),
        SAND("sand"),
        GRAVEL("gravel (embankment)"),
        PAVED("asphalt: 1 m of black concrete on the ground"),
        ROCK("thicker stone"),
        DECK("flooring: 1 m stone over void 6 m"),
        ROOF("roof: 1 m concrete above the room 3 m");

        final String title;

        Surface(String title) {
            this.title = title;
        }

        BlockState layer(int depth) {
            return switch (this) {
                case SOIL -> depth == 0 ? Blocks.PODZOL.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                case SAND -> Blocks.SAND.defaultBlockState();
                case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
                case PAVED -> depth == 0 ? Blocks.BLACK_CONCRETE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                case ROCK -> Blocks.STONE.defaultBlockState();
                case DECK -> depth == 0 ? Blocks.STONE.defaultBlockState() : depth <= VOID ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState();
                case ROOF -> depth == 0 ? Blocks.GRAY_CONCRETE.defaultBlockState() : depth <= ROOM ? Blocks.AIR.defaultBlockState()
                        : depth == ROOM + 1 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            };
        }

        /** Slab above void (1 m). */
        boolean slab() {
            return this == DECK || this == ROOF;
        }

        /** The soil is a crater according to the law: there is no saturated soil here, the stone is a rock. */
        CraterModel.Soil soil() {
            return this == ROCK ? CraterModel.Soil.ROCK : CraterModel.Soil.DRY;
        }


    }

    /** The void under the flooring and the height of the room under the roof, blocks. */
    private static final int VOID = 6, ROOM = 3;

    /** Lay out the surface in the square {@code lo..hi} (site coordinates) from {@link #BASE} to {@code top}. */
    private static void build(GameTestHelper h, Surface s, int lo, int hi, int loZ, int hiZ, int top) {
        ServerLevel level = h.getLevel();
        for (int x = lo; x <= hi; x++) {
            for (int z = loZ; z <= hiZ; z++) {
                boolean edge = x == lo || x == hi || z == loZ || z == hiZ;
                for (int y = BASE; y <= top; y++) {
                    BlockState state = s.layer(top - y);
                    // walls of the room under the roof - masonry along the edge of the square
                    if (s == Surface.ROOF && edge && state.isAir()) state = Blocks.STONE_BRICKS.defaultBlockState();
                    level.setBlock(h.absolutePos(new BlockPos(x, y, z)), state, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // ---------------------------------------------------------------- report

    /** Report line: checked ({@code checked}) and passed. */
    private record Row(String weapon, String surface, String should, String got, boolean checked, boolean ok, String note) {
        String line() {
            String verdict = !checked ? "—  " + note : ok ? "ok" : "NO";
            return String.format(Locale.ROOT, "%-8s | %-38s | should %-46s | yes %-46s | %s", weapon, surface, should, got, verdict);
        }
    }

    /** Lines for batch: The batch table is printed when all its checks have been reported. */
    private static final Map<String, List<Row>> TABLES = new TreeMap<>();
    private static final Map<String, Integer> CALLS = new TreeMap<>();

    /** Checks in batch: surfaces of one weapon - one per surface, “next to” - one. */
    private static int tests(String batch) {
        return batch.endsWith("_nearby") ? 1 : Surface.values().length;
    }

    private static synchronized void report(String batch, List<Row> rows) {
        List<Row> table = TABLES.computeIfAbsent(batch, b -> new ArrayList<>());
        table.addAll(rows);
        for (Row r : rows) Incoming.LOG.info("SURFACEBENCH {}", r.line());
        int calls = CALLS.merge(batch, 1, Integer::sum);
        if (calls >= tests(batch)) {
            StringBuilder out = new StringBuilder("SURFACEBENCH table ").append(batch).append(" (weapon × surface → should/is):");
            for (Row r : table) out.append("\nSURFACEBENCH | ").append(r.line());
            Incoming.LOG.info(out.toString());
            TABLES.remove(batch);
            CALLS.remove(batch);
        }
    }

    private static String f(double x) {
        return String.format(Locale.ROOT, "%.1f", x);
    }

    /** Batch checks that keep the wreckage off, and what happened before the first one. */
    private static int debrisHolders;
    private static boolean debrisBefore;

    /**
     * The debris does not fall in blocks while the inspection is underway. The setting is for the entire server, and there are seven checks in the batch: the first one turns off
     * , the last one returns (each of its “before” would return someone else’s “turned off”, and {@code debrisLandsAsBlocks} in the same
     *  part CI would fall).
     */
    private static synchronized void noDebris(GameTestHelper h) {
        if (debrisHolders++ == 0) {
            debrisBefore = IncomingConfig.SERVER.debrisStay.get();
            IncomingConfig.SERVER.debrisStay.set(false);
        }
        StrikeGameTests.afterTest(h, () -> {
            synchronized (SurfaceGameTests.class) {
                if (--debrisHolders == 0) IncomingConfig.SERVER.debrisStay.set(debrisBefore);
            }
        });
    }

    // ---------------------------------------------------------------- projectile impact

    /** The entity of a weapon with an impact axis from the spec: angle to the horizon {@link Article.Impact#angle}, heading to +z. */
    private static Entity striker(ServerLevel level, WeaponType weapon) {
        EntityType<? extends Entity> type = switch (weapon) {
            case DRONE -> ModEntities.DRONE.get();
            case LOITER -> ModEntities.LOITER.get();
            case MISSILE -> ModEntities.CRUISE_MISSILE.get();
            case ROCKET -> ModEntities.ROCKET.get();
            default -> throw new IllegalArgumentException(weapon.name());
        };
        Entity e = type.create(level);
        Article.Impact impact = weapon.spec().striker().article().impact();
        double a = Math.toRadians(impact.angle());
        e.setDeltaMovement(new Vec3(0, -Math.sin(a), Math.cos(a)).scale(impact.speed() / 20));
        return e;
    }

    /**
     * Blocks removed by a model crater of radius {@code rc} and depth {@code depth} in one impact (at the column center),
     * and variance of the random whole-block addition ({@code CraterJob.digColumn}, DIG mode: fractional part is added
     * with its corresponding probability): {expectation, variance}.
     */
    static double[] lawBlocks(double rc, double depth) {
        double mean = 0, var = 0;
        int n = Mth.ceil(2 * rc + 1);
        for (int dx = -n; dx <= n; dx++) {
            for (int dz = -n; dz <= n; dz++) {
                double r = Math.hypot(dx, dz);
                double relief = CraterModel.rim(r, rc, depth) - CraterModel.bowl(r, rc, depth);
                if (relief >= 0) continue;
                double frac = relief - Math.floor(relief);
                mean += -relief;
                var += frac * (1 - frac);
            }
        }
        return new double[]{mean, var};
    }

    /**
     * Breakdown radius according to FM 5-250, m: K = R³·M·C, external charge without clogging; M - tab. B-3 by radius steps, group
     *  “rock, good masonry, concrete block” - flooring stone and roof concrete ({@link BreachModel}).
     */
    static double breachRadius(double tntKg) {
        return BreachModel.Material.ROCK.radius(tntKg, BreachModel.UNTAMPED);
    }

    /** FM rounds the breakdown radius of the external charge up to ½ foot (~0,25 m, 3-12b): legal tolerance in both directions, m. */
    static final double FM_ROUND = 0.25;

    /** Volume of a slab with thickness {@code t} within a radius of {@code r} from the charge on its face, m³. */
    static double slabVolume(double r, double t) {
        return r >= t ? Math.PI * (r * r * t - t * t * t / 3) : 2.0 / 3 * Math.PI * r * r * r;
    }

    /** The pitch of the impact grid: adjacent ones do not touch each other either with a crater or a wave on masonry and concrete. */
    private static int spacing(WeaponType weapon) {
        double tnt = weapon.spec().charge();
        double reach = Math.max(2 * CraterModel.chargeRadius(tnt, CraterModel.Soil.DRY) + 1,
                Math.max(breachRadius(tnt) + FM_ROUND, BlastDamage.breakRange(BlastModel.kpa(12), tnt)));
        return Math.max(8, Mth.ceil(2 * reach + 2));
    }

    /**
     * Weapon strikes with a grid over the entire surface of {@code s} (step - {@link #spacing}: for the rocket 3×3, for Shahed 5×5, for “Lancet”
     *  and “Grad” 6×6 - their crater share of the block, therefore the sum), measurement in the square of each blow: surface blocks removed,
     *  hole (top removed columns), depth; slabs have through holes.
     */
    private static void craterCell(GameTestHelper h, WeaponType weapon, Surface s) {
        ServerLevel level = h.getLevel();
        // during the inspection, debris does not lie in blocks: only those removed are considered
        noDebris(h);
        build(h, s, LO, HI, LO, HI, TOP);
        int step = spacing(weapon), n = (HI - LO + 1) / step, first = MID - (n - 1) * step / 2;
        h.assertTrue(n >= 3, weapon + ": less than three hits in a row, step " + step);
        List<BlockPos> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) for (int k = 0; k < n; k++) hits.add(new BlockPos(first + step * i, TOP, first + step * k));
        for (BlockPos hit : hits) {
            // Stable source seeds, independent of the random GameTest origin, other tests and deferred-work order.
            long seed = Mth.getSeed(hit.getX(), hit.getY(), hit.getZ());
            Warheads.testDetonate(level, weapon, Vec3.atBottomCenterOf(h.absolutePos(hit.above())), striker(level, weapon), seed);
        }
        Row[] row = {null};
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "the work of the blows is progressing");
            if (row[0] == null) row[0] = craterRow(h, weapon, s, hits, step);
            if (row[0].checked) h.assertTrue(row[0].ok, row[0].line());
        });
    }

    /** Measuring impacts of {@code hits} on the surface of {@code s} is against the law - report line (printed once). */
    private static Row craterRow(GameTestHelper h, WeaponType weapon, Surface s, List<BlockPos> hits, int step) {
        ServerLevel level = h.getLevel();
        int removed = 0, maxDepth = 0, holes = 0, through = 0;
        double area = 0, damaged = 0;
        int half = (step - 1) / 2;
        for (BlockPos hit : hits) {
            int opening = 0, holeCols = 0;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    int depth = 0;
                    for (int y = TOP; y >= BASE; y--) {
                        BlockPos rel = new BlockPos(hit.getX() + dx, y, hit.getZ() + dz);
                        if (s.layer(TOP - y).isAir()) continue;
                        BlockPos absolute = h.absolutePos(rel);
                        BlockState now = level.getBlockState(absolute);
                        damaged += ua.zentix.incoming.warhead.SurfaceDamage.depth(level, absolute);
                        if (now.isAir() || now.is(Blocks.FIRE) || !now.getFluidState().isEmpty()) {
                            removed++;
                            depth = Math.max(depth, TOP - y + 1);
                        }
                    }
                    BlockState cap = level.getBlockState(h.absolutePos(new BlockPos(hit.getX() + dx, TOP, hit.getZ() + dz)));
                    boolean open = cap.isAir() || cap.is(Blocks.FIRE);
                    if (open) opening++;
                    // the top of the stove has been removed - there is a hole right through it: there is emptiness underneath
                    if (s.slab() && open) holeCols++;
                    maxDepth = Math.max(maxDepth, depth);
                }
            }
            area += opening;
            if (holeCols > 0) through++;
            holes += holeCols;
        }
        double tnt = weapon.spec().charge(), count = hits.size();
        double dEq = 2 * Math.sqrt(area / hits.size() / Math.PI);
        String got = s.slab()
                ? String.format(Locale.ROOT, "%d bl. slabs for %d (%s/impact), breakdown %d, hole %s m", removed, hits.size(), f(removed / count), through,
                f(2 * Math.sqrt(holes / count / Math.PI)))
                : String.format(Locale.ROOT, "%d bl. for %d (%s/impact), Ø %s m, depth. %d", removed, hits.size(), f(removed / count), f(dEq), maxDepth);
        String should;
        boolean ok;
        if (s.slab()) {
            // FM 5-250: everything within the radius of the breakdown is demolished, the breakdown is a radius greater than the thickness (1 m); the slab above the void is also a
            //  panel - beyond the breakdown, the wave breaks it no further than the masonry (12 psi, Jarrett): upper limit
            //  radius along the steps of the table. B-3 ± rounding FM; not broken - there is nothing to demolish along FM (only the crater on top)
            double r = breachRadius(tnt), rLo = Math.max(0, r - FM_ROUND), rHi = r + FM_ROUND;
            double rPanel = BlastDamage.breakRange(BlastModel.kpa(BRICK_PSI), tnt);
            boolean breach = rLo > 1, none = rHi < 1;
            double vLo = breach ? count * slabVolume(rLo, 1) : 0, vHi = count * slabVolume(rHi, 1);
            // edge of the hole - in whole blocks: ±2√V blocks for edge noise
            int lo = (int) Math.max(0, Math.floor(0.6 * vLo - 2 * Math.sqrt(vLo))), hi = (int) Math.ceil(1.45 * vHi + 2 * Math.sqrt(vHi));
            should = String.format(Locale.ROOT, "%d–%d block, breakdown %s (FM R %s–%s m, masonry %s m)", lo, hi,
                    breach ? "everyone" : none ? "no" : "any", f(rLo), f(rHi), f(rPanel));
            ok = removed >= lo && removed <= hi && (!breach || through == hits.size()) && (!none || through == 0);
        } else {
            // crater in its environment; concrete roads - the environment is no softer than rock and no more than a crater in the ground
            CraterModel.Soil soil = s == Surface.PAVED ? CraterModel.Soil.ROCK : s.soil();
            double rc = CraterModel.chargeRadius(tnt, soil), depth = CraterModel.chargeDepth(tnt, soil);
            double[] one = lawBlocks(rc, depth);
            // data - 0,6–1,45 of the scope of the law; whole blocks - another 2σ random addition
            double mean = count * one[0], sigma = Math.sqrt(count * one[1]);
            double upper = s == Surface.PAVED ? count * lawBlocks(CraterModel.chargeRadius(tnt, CraterModel.Soil.DRY),
                    CraterModel.chargeDepth(tnt, CraterModel.Soil.DRY))[0] : mean;
            int lo = (int) Math.max(0, Math.floor(0.6 * mean - 2 * sigma)), hi = (int) Math.ceil(1.45 * upper + 2 * sigma);
            int deepest = Mth.ceil(1.3 * depth);
            should = String.format(Locale.ROOT, "%d–%d bl. (law %s), Ø %s m, depth. ≤ %d", lo, hi, f(mean), f(2 * rc), deepest);
            ok = removed + damaged >= lo && removed + damaged <= hi && maxDepth <= Math.max(1, deepest);
            got += ", damaged by lobes " + f(damaged) + " m³";
        }
        Row row = new Row(weapon.name(), s.title, should, got, true, ok, "");
        report("surface_" + weapon.name().toLowerCase(Locale.ROOT), List.of(row));
        return row;
    }

    // ---------------------------------------------------------------- next: walls and glass

    /** Masonry, boards, glass. */
    private static final double BRICK_PSI = 12, WOOD_PSI = 4;
    /** Fractions of the radius of the threshold of the glass: the near ones are knocked out, the distant ones are intact (edge of the model 0,9–1,11). */
    private static final double[] GLASS_AT = {0.7, 0.8, 1.25, 1.4};

    /**
     * Impact on the turf of the site: columns of masonry and boards on their beams (like {@code BlastGameTests.radiusTest}) and glass above the
     *  platform at fractions of the radius of its threshold (glass - according to Kinney-Graham pressure, without impulse; upward - outside the belt of
     *  fragments at the axis of the impact from above). Any closer 0,8 to its radius is broken, further 1,25 is intact.
     */
    private static void nearby(GameTestHelper h, WeaponType weapon) {
        ServerLevel level = h.getLevel();
        double tnt = weapon.spec().charge();
        BlockPos c = h.absolutePos(new BlockPos(MID, TOP + 1, MID));
        Vec3 at = Vec3.atBottomCenterOf(c);
        // masonry - to −z, boards - to +z: each material has its own half of the azimuths, and Shahed’s boards stand in small fractions
        List<BlastGameTests.Post> bricks = BlastGameTests.layPosts(c, at, BRICK_PSI, tnt, BlastDamage.breakRange(BlastModel.kpa(BRICK_PSI), tnt),
                List.of(), b -> b < 0);
        List<BlastGameTests.Post> planks = BlastGameTests.layPosts(c, at, WOOD_PSI, tnt, BlastDamage.breakRange(BlastModel.kpa(WOOD_PSI), tnt),
                bricks, b -> b > 0);
        for (BlastGameTests.Post p : bricks) level.setBlock(p.pos(), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (BlastGameTests.Post p : planks) level.setBlock(p.pos(), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
        // glasses: one at a time in the air above the site, the place is closest to a fraction of its radius
        List<double[]> glass = new ArrayList<>();
        List<BlockPos> panes = new ArrayList<>();
        int i = 0;
        for (double share : GLASS_AT) {
            BlockPos best = null;
            double bestShare = 0;
            int dx = 3 + 2 * i++;
            for (int y = c.getY() + 1; y < level.getMaxBuildHeight() - 1; y++) {
                BlockPos p = new BlockPos(c.getX() + dx, y, c.getZ());
                double own = BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI * BlockResponse.jitterAt(p)), tnt);
                double sh = Math.sqrt(at.distanceToSqr(Vec3.atCenterOf(p))) / own;
                if (best == null || Math.abs(sh - share) < Math.abs(bestShare - share)) {
                    best = p;
                    bestShare = sh;
                }
            }
            level.setBlock(best, Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS);
            panes.add(best);
            BlockPos pane = best;
            // glass above the wall of the site - away after inspection, like a bomb tower
            StrikeGameTests.afterTest(h, () -> level.setBlock(pane, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS));
            glass.add(new double[]{bestShare});
        }
        Warheads.detonate(level, weapon, at, striker(level, weapon), null);
        List<Row> rows = new ArrayList<>();
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "the impact is in progress");
            if (rows.isEmpty()) nearbyRows(level, weapon, tnt, bricks, planks, panes, glass, rows);
            for (Row row : rows) h.assertTrue(row.ok, row.line());
        });
    }

    /** Rows “nearby”: columns of masonry and boards, glass (printed once). */
    private static void nearbyRows(ServerLevel level, WeaponType weapon, double tnt, List<BlastGameTests.Post> bricks,
                                   List<BlastGameTests.Post> planks, List<BlockPos> panes, List<double[]> glass, List<Row> rows) {
        rows.add(posts(level, weapon, "walls: stone brick 12 psi", bricks, Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, tnt, BRICK_PSI));
        rows.add(posts(level, weapon, "walls: boards 4 psi", planks, Blocks.OAK_PLANKS, Blocks.OAK_PLANKS, tnt, WOOD_PSI));
        StringBuilder got = new StringBuilder();
        boolean ok = true;
        for (int k = 0; k < panes.size(); k++) {
            double sh = glass.get(k)[0];
            boolean gone = !level.getBlockState(panes.get(k)).is(Blocks.GLASS);
            got.append(String.format(Locale.ROOT, " %.2f%s", sh, gone ? "x" : "|"));
            if (sh <= 0.8 && !gone || sh >= 1.25 && gone) ok = false;
        }
        double r = BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI), tnt);
        rows.add(new Row(weapon.name(), "glass 0,8 psi (R " + f(r) + " m)", "≤0,8 R knocked out, ≥1,25 R intact", "shares R:" + got, true, ok, ""));
        report("surface_" + weapon.name().toLowerCase(Locale.ROOT) + "_nearby", rows);
    }

    /** A row of columns of one material: fractions of the Jarrett radius of its threshold, broken (x) or intact (|). */
    private static Row posts(ServerLevel level, WeaponType weapon, String what, List<BlastGameTests.Post> posts, Block material, Block cracked,
                             double tnt, double psi) {
        StringBuilder got = new StringBuilder();
        boolean ok = true;
        for (BlastGameTests.Post p : posts) {
            BlockState now = level.getBlockState(p.pos());
            // splinter pothole (cracked masonry) - worth
            boolean gone = !now.is(material) && !now.is(cracked);
            got.append(String.format(Locale.ROOT, " %.2f%s", p.share(), gone ? "x" : "|"));
            if (p.share() <= 0.8 && !gone || p.share() >= 1.25 && gone) ok = false;
        }
        String title = what + " (R " + f(BlastDamage.breakRange(BlastModel.kpa(psi), tnt)) + " m)";
        return new Row(weapon.name(), title, "≤0,8 R broken, ≥1,25 R intact", "shares R:" + got, true, ok, "");
    }

    // ---------------------------------------------------------------- bomb B-2

    /** The target of the bomb is on the strip (like {@code bunkerBusterDrillsAndDetonatesUnderground}) and the square of its surface. */
    private static final int BOMB_X = 16, BOMB_Z = 200, BOMB_HALF = 15;

    /** The top of the surface under the bomb: the thickness is deeper than its path along Yang and the crushing zone. */
    private static int bombTop(Surface s) {
        return switch (s) {
            case SOIL, PAVED -> 72;
            case SAND -> 54;
            case GRAVEL -> 42;
            case ROCK -> 30;
            case DECK, ROOF -> 80;
        };
    }

    /** Young path environment (data map {@code incoming:penetration}) for the surface layer at depth {@code depth}. */
    private static Penetration.Kind kind(BlockState s) {
        if (s.is(Blocks.STONE)) return Penetration.Kind.ROCK;
        if (s.is(Blocks.BLACK_CONCRETE) || s.is(Blocks.GRAY_CONCRETE) || s.is(Blocks.STONE_BRICKS)) return Penetration.Kind.CONCRETE;
        return Penetration.Kind.SOIL;
    }

    private static double youngS(BlockState s) {
        if (s.is(Blocks.SAND)) return 7.5;
        if (s.is(Blocks.GRAVEL)) return 5;
        if (s.is(Blocks.STONE) || s.is(Blocks.BLACK_CONCRETE) || s.is(Blocks.GRAY_CONCRETE)) return 0.84;
        if (s.is(Blocks.STONE_BRICKS)) return 1.0;
        return 12;
    }

    /**
     * Bomb B-2 on the surface: the real entity from the reset B-2. The path in the ground from the entrance to the explosion is according to Young for layers
     *  of the surface (according to the speed and angle of impact of the bomb itself); ejection crater - according to the given depth of the explosion; for slabs -
     *  the bomb went through (the explosion was deeper than the slab), the void sensor did not arm (before it - 4 m of solid).
     */
    private static void bomb(GameTestHelper h, Surface s) {
        ServerLevel level = h.getLevel();
        int top = bombTop(s);
        noDebris(h);
        int lo = BOMB_X - BOMB_HALF, hi = BOMB_X + BOMB_HALF, loZ = BOMB_Z - BOMB_HALF, hiZ = BOMB_Z + BOMB_HALF;
        StrikeGameTests.afterTest(h, () -> {
            // the tower is higher than the wall of the site - away: neighboring batches fly over the world of dough
            for (BlockPos p : BlockPos.betweenClosed(new BlockPos(lo - 4, 9, loZ - 4), new BlockPos(hi + 4, top + 12, hiZ + 4))) {
                BlockPos a = h.absolutePos(p);
                if (!level.getBlockState(a).isAir()) level.setBlock(a, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        });
        build(h, s, lo, hi, loZ, hiZ, top);
        Vec3 surface = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(BOMB_X, top + 1, BOMB_Z)));
        Set<BlockPos> fallen = new HashSet<>();
        Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> fall = event -> {
            if (event.getLevel() != level || !(event.getEntity() instanceof net.minecraft.world.entity.item.FallingBlockEntity block)
                    || block.time != 0) return;
            BlockPos p = block.getStartPos();
            if (Math.abs(p.getX() - surface.x) <= BOMB_HALF && Math.abs(p.getZ() - surface.z) <= BOMB_HALF
                    && p.getY() < surface.y && p.getY() >= surface.y - 3) fallen.add(p.immutable());
        };
        // Before CraterFalls can settle a block immediately by cancelling its join event.
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, fall);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(fall));
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        bomb.drop(surface.add(0, 170, -145), BomberEntity.levelVelocity(0), surface, null, null);
        level.addFreshEntity(bomb);
        Vec3[] last = {bomb.position(), null};
        double[] speed = {0};
        Vec3[] entry = {null}, charge = {null};
        h.onEachTick(() -> {
            if (bomb.isRemoved() || entry[0] != null) return;
            if (bomb.isDrilling()) {
                entry[0] = bomb.entry().subtract(0, 1, 0);
                return;
            }
            Vec3 now = bomb.position();
            last[1] = now.subtract(last[0]);
            speed[0] = last[1].length() * 20;
            last[0] = now;
        });
        Consumer<ExplosionEvent.Start> first = e -> {
            if (e.getLevel() == level && charge[0] == null && bomb.getUUID().equals(ua.zentix.incoming.stress.StressDirector.blastBy(e.getExplosion()))) {
                charge[0] = e.getExplosion().center();
            }
        };
        NeoForge.EVENT_BUS.addListener(first);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(first));
        Row[] row = {null};
        int[] settled = {0};
        h.succeedWhen(() -> {
            h.assertTrue(entry[0] != null, "the bomb did not penetrate the ground");
            h.assertTrue(bomb.isRemoved() && charge[0] != null, "the bomb didn't explode");
            boolean idle = StrikeWorld.get(level).idle() && level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,
                    new net.minecraft.world.phys.AABB(surface, surface).inflate(BOMB_HALF, top + 20, BOMB_HALF)).isEmpty();
            settled[0] = idle ? settled[0] + 1 : 0;
            // A timeline schedules chimney collapse after an empty-queue gap. Then allow two vanilla falling-block
            // update delays (2 ticks each), so a scheduled sand/gravel fall cannot hide between measurements.
            h.assertTrue(settled[0] >= 4, "detonation or collapse is still ongoing");
            if (row[0] == null) row[0] = bombRow(level, s, top, last[1], speed[0], entry[0], charge[0], fallen);
            h.assertTrue(row[0].ok, row[0].line());
        });
    }

    /** Bomb line: path vs Young, crater vs given depth (printed once). */
    private static Row bombRow(ServerLevel level, Surface s, int top, Vec3 step, double v, Vec3 entry, Vec3 charge, Set<BlockPos> fallen) {
        Vec3 dir = step.normalize();
        double sin = Math.max(0.05, -dir.y);
        // young's path through surface layers along the bomb axis
        Penetration.Run run = new Penetration.Run(BunkerBusterEntity.body(), v);
        double young = 0;
        for (int d = 0; d <= top - BASE && !run.stopped(); d++) {
            BlockState layer = s.layer(d);
            if (layer.isAir()) {
                run.gap();
                young += 1 / sin;
                continue;
            }
            young += run.through(kind(layer), youngS(layer), 1 / sin);
        }
        double path = charge.distanceTo(entry);
        boolean rocky = s == Surface.ROCK;
        double lo2 = rocky ? 0.5 : 0.8, hi2 = rocky ? 2 : 1.2;
        // ejection crater along the depth of the explosion under the top
        double depth = entry.y - charge.y;
        BuriedBlast law = new BuriedBlast(WeaponType.BUNKER.spec().warheadTnt(), depth);
        CraterModel.Soil soil = s == Surface.ROCK ? CraterModel.Soil.ROCK : CraterModel.Soil.DRY;
        int removedTop = 0, columns = 0, fallenTop = 0;
        boolean loose = s == Surface.SAND || s == Surface.GRAVEL;
        BlockPos ent = BlockPos.containing(entry);
        for (int dx = -BOMB_HALF + 1; dx < BOMB_HALF; dx++) {
            for (int dz = -BOMB_HALF + 1; dz < BOMB_HALF; dz++) {
                boolean col = false;
                for (int dy = 0; dy < 3; dy++) {
                    // entrance - top of the surface block: the block itself - below it
                    BlockPos a = new BlockPos(ent.getX() + dx, ent.getY() - 1 - dy, ent.getZ() + dz);
                    if (s.layer(dy).isAir()) continue;
                    BlockState now = level.getBlockState(a);
                    if (now.isAir() || now.is(Blocks.FIRE)) {
                        if (loose && !law.breaches() && fallen.contains(a)) {
                            fallenTop++;
                            continue;
                        }
                        removedTop++;
                        col = true;
                    }
                }
                if (col) columns++;
            }
        }
        // ejection crater - along the radius of the hole: law BuriedBlast - shape assessment (as stated in it), tolerance ±50 %; camouflage -
        // at the top only the bomb path (Ø 0,8 m, block along the axis): in the top three layers - no more than three columns
        double lawR = law.breaches() ? law.craterRadius(soil) : 0, gotR = Math.sqrt(columns / Math.PI);
        boolean pathOk = path >= lo2 * young && path <= hi2 * young;
        boolean craterOk = law.breaches() ? gotR >= 0.5 * lawR && gotR <= 1.5 * lawR
                : removedTop >= 1 && columns <= 3 && (!loose || fallenTop > 0);
        boolean throughSlab = !s.slab() || depth > 1 + (s == Surface.DECK ? VOID : ROOM);
        String should = String.format(Locale.ROOT, "path %s m (Yang, %s–%s), λ %s: %s", f(young), f(lo2 * young), f(hi2 * young),
                f(law.scaledDepth()), law.breaches() ? "crater R " + f(lawR) + " m (" + f(0.5 * lawR) + "–" + f(1.5 * lawR) + ")"
                        : "camouflage, only movement at the top (no shedding)")
                + (s.slab() ? ", explosion under the slab" : "");
        String got = String.format(Locale.ROOT, "impact %s m/s under %s°, path %s m, depth %s m, at the top %d bl. in %d columns (R %s m)",
                f(v), f(Math.toDegrees(Math.asin(sin))), f(path), f(depth), removedTop, columns, f(gotR))
                + (loose && !law.breaches() ? ", crumbled " + fallenTop + " blocks" : "");
        Row row = new Row("BOMB", s.title, should, got, true, pathOk && craterOk && throughSlab, "");
        report("surface_bomb", List.of(row));
        return row;
    }

    // ---------------------------------------------------------------- checks

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnSoil(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.SOIL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnSand(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.SAND);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnGravel(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.GRAVEL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnPaving(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.PAVED);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnRock(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.ROCK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnDeck(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.DECK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone", skyAccess = true)
    public static void droneOnRoof(GameTestHelper h) {
        craterCell(h, WeaponType.DRONE, Surface.ROOF);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_drone_nearby", skyAccess = true)
    public static void droneNearby(GameTestHelper h) {
        nearby(h, WeaponType.DRONE);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnSoil(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.SOIL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnSand(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.SAND);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnGravel(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.GRAVEL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnPaving(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.PAVED);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnRock(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.ROCK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnDeck(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.DECK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter", skyAccess = true)
    public static void loiterOnRoof(GameTestHelper h) {
        craterCell(h, WeaponType.LOITER, Surface.ROOF);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_loiter_nearby", skyAccess = true)
    public static void loiterNearby(GameTestHelper h) {
        nearby(h, WeaponType.LOITER);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnSoil(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.SOIL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnSand(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.SAND);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnGravel(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.GRAVEL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnPaving(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.PAVED);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnRock(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.ROCK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnDeck(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.DECK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile", skyAccess = true)
    public static void missileOnRoof(GameTestHelper h) {
        craterCell(h, WeaponType.MISSILE, Surface.ROOF);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_missile_nearby", skyAccess = true)
    public static void missileNearby(GameTestHelper h) {
        nearby(h, WeaponType.MISSILE);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnSoil(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.SOIL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnSand(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.SAND);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnGravel(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.GRAVEL);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnPaving(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.PAVED);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnRock(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.ROCK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnDeck(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.DECK);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket", skyAccess = true)
    public static void rocketOnRoof(GameTestHelper h) {
        craterCell(h, WeaponType.ROCKET, Surface.ROOF);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "surface_rocket_nearby", skyAccess = true)
    public static void rocketNearby(GameTestHelper h) {
        nearby(h, WeaponType.ROCKET);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnSoil(GameTestHelper h) {
        bomb(h, Surface.SOIL);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnSand(GameTestHelper h) {
        bomb(h, Surface.SAND);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnGravel(GameTestHelper h) {
        bomb(h, Surface.GRAVEL);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnPaving(GameTestHelper h) {
        bomb(h, Surface.PAVED);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnRock(GameTestHelper h) {
        bomb(h, Surface.ROCK);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnDeck(GameTestHelper h) {
        bomb(h, Surface.DECK);
    }

    @GameTest(template = "runway", timeoutTicks = 600, batch = "surface_bomb", skyAccess = true)
    public static void bombOnRoof(GameTestHelper h) {
        bomb(h, Surface.ROOF);
    }
}
