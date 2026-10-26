package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.entity.flight.ProximityFuse;
import ua.zentix.incoming.entity.Launcher;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.LauncherMount;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Autopilot;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.util.Local;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Where to place a launcher: behind and beside the shooter; for an ordered site, at the site or nearest suitable
 * place in its area ({@link Post}, {@link #PLACE_AREA}), on flat solid ground under open sky, only in ready
 * chunks (salvo loads ordered site, {@link LaunchPlaces}). Reuse a nearby owned launcher of the same weapon
 * ({@link #REUSE_RADIUS} from shooter, {@link #PLACE_REUSE} from site): salvo uses one launcher. A player has at
 * most {@link #MAX_PER_OWNER} following launchers, removing the oldest; ordered-site launchers stay until
 * “Abort”.
 * <p>
 * Shahed and missile racks are placed only where and facing such that launch sector is clear ({@link
 * #clearAhead}): during boost/climb before arming, hitting a building crashes without detonation, so a city salvo
 * from a launcher facing a building would silently vanish. Also exclude sites between towers too high to clear
 * after arming: choose another site or approach from afar.
 */
public final class LaunchSite {
    public static final double REUSE_RADIUS = 96;
    public static final int MAX_PER_OWNER = 3;
    /** Do not place a new launcher closer than this to another. */
    private static final double CLEARANCE = 9;
    /**
     * Ordered launch-site area, blocks: map gives a point, crew gets an area and places the vehicle at its
     * nearest suitable firing position: flat solid site under open sky with clear sector (clearing, road, forest
     * edge, shore), not water, under canopy, or between trunks. Driving a hundred meters takes a minute on dirt;
     * a battery firing-position area spans hundreds of meters (⚠ estimate, not a law: farther means a new site
     * chosen by operator). On Newisle, nearest suitable positions for sites lacking a launcher (#394) were 7
     * blocks at shore, 19 and 72 in forest, 50 by lake (world heightmaps). Cost is the area loaded by salvo
     * ({@link LaunchPlaces}): 15×15 chunks.
     */
    public static final double PLACE_AREA = 96;
    /** Candidate spacing within launch-site area, blocks: site check covers 5×5; spacing 2 misses none. */
    static final int PLACE_STEP = 2;
    /**
     * Owned launcher for an ordered site is one placed for the same mark ({@link LauncherEntity#mark}: within
     * {@link #CLEARANCE}), wherever it stands in the area; without mark (placed before marks were remembered),
     * within this distance of site. Moving a mark farther uses a new launcher: old one stays in the old area
     * facing the old target.
     */
    public static final double PLACE_REUSE = 40;

    /** Positions relative to player: [back, left] in blocks, preference order. */
    private static final double[][] CANDIDATES = {
            {18, 11}, {18, -11}, {24, 0}, {13, 17}, {13, -17}, {30, 13}, {30, -13}, {4, 22}, {4, -22}, {38, 0}, {24, 24}, {24, -24}
    };
    /** Positions within ordered site area: [east, south] offsets, blocks, nearest first; first is site itself. */
    private static final int[][] AREA = area(PLACE_AREA, PLACE_STEP);
    /**
     * Maximum search/placement distance from ordered site: area plus clearance to neighboring launcher, blocks.
     * This much around site must be ready ({@link LaunchPlaces#ready}).
     */
    public static final double PLACE_REACH = PLACE_AREA + CLEARANCE;

    private LaunchSite() {}

    /**
     * Firing position: center for launcher search and whose launchers count as owned there.
     *
     * @param center shooter position, or ordered site itself on ground
     * @param yaw “forward” for surrounding positions: shooter view, or target heading from ordered site (fallback
     * sites behind it)
     * @param owner launcher owner (null for console/command block)
     * @param ordered order specifies site ({@code /incoming salvo … from x z}): launcher there, or nearby if no
     * flat place; at shooter, behind and beside rather than on them
     */
    public record Post(Vec3 center, float yaw, @Nullable UUID owner, boolean ordered) {
        /** At shooting player: behind and beside, relative to view. */
        public static Post of(ServerPlayer player) {
            return new Post(player.position(), player.getYRot(), player.getUUID(), false);
        }

        /**
         * Ordered horizontal launch {@code place} toward {@code target}; null if site chunk unready (salvo loads
         * it, {@link LaunchPlaces}; nothing loaded here).
         */
        @Nullable
        public static Post ordered(ServerLevel level, Vec3 place, Vec3 target, @Nullable UUID owner) {
            int x = Mth.floor(place.x), z = Mth.floor(place.z);
            if (!Terrain.ready(level, x >> 4, z >> 4)) return null;
            Vec3 center = new Vec3(place.x, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), place.z);
            return new Post(center, FlightController.anglesTo(center, target)[0], owner, true);
        }

        public ChunkPos chunk() {
            return new ChunkPos(BlockPos.containing(center));
        }
    }

    /**
     * Nearest suitable owned launcher of this weapon at {@code post} ({@code fits}: e.g. launch sector clear).
     * Check nearest first until suitable: sector checks are costly.
     */
    @Nullable
    public static LauncherEntity existing(ServerLevel level, Post post, WeaponType weapon, Predicate<LauncherEntity> fits) {
        // Marked launcher stands within mark area; mark may have moved by clearance: box covers whole area.
        double reach = post.ordered() ? PLACE_REACH : REUSE_RADIUS;
        // Ordered-site vertical search spans whole world: site in valley, launcher on steep slope above.
        double up = post.ordered() ? level.getHeight() : 64;
        AABB box = new AABB(post.center(), post.center()).inflate(reach, up, reach);
        return level.getEntitiesOfClass(LauncherEntity.class, box, l -> l.isAlive() && l.weapon() == weapon && Objects.equals(post.owner(), l.ownerId())
                        && serves(l, post))
                .stream().sorted(Comparator.comparingDouble(l -> l.distanceToSqr(post.center()))).filter(fits).findFirst().orElse(null);
    }

    /**
     * Whether launcher {@code l} in search box belongs to firing {@code post}: any at shooter, {@link
     * #PLACE_REUSE} for ordered site.
     */
    private static boolean serves(LauncherEntity l, Post post) {
        if (!post.ordered()) return true;
        Vec3 mark = l.mark();
        return mark != null ? horizontal(mark, post.center()) < CLEARANCE : horizontal(l.position(), post.center()) <= PLACE_REUSE;
    }

    /**
     * Launcher position and heading.
     *
     * @param preferred index of proposed heading, or −1 if turned because proposed headings hit structures
     */
    public record Pick(Vec3 site, float yaw, int preferred) {}

    /** Turns from first proposed heading if no proposal is clear, °. */
    private static final float[] TURNS = {30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};
    /** MLRS arc polyline step, ticks: 2 ticks, up to ~10 blocks on booster. */
    private static final int ARC_STEP = 2;
    /**
     * Do not check MLRS arc within this horizontal distance of target: target's own roof does not block sector,
     * blocks.
     */
    private static final double NEAR_AIM = 8;
    /** Projectile hull this many blocks below axis: it must clear blocks too. */
    private static final double HULL = 1;

    /**
     * Cost limit per launcher selection (owned check and site search), world cells: {@code Level.clip} ray cell
     * ({@link #traversed}), heightmap column, climb-strip column ({@link #climbOut}). Without limit, dense city
     * search, up to 12 sites with several headings and thousands of cells each, would cost hundreds of
     * milliseconds in one tick. Exhaustion means “no site”: strike approaches from afar as without a site (once
     * per order, {@link StrikeWorld#noLaunchSite}). One check with no heightmap rejection: Shahed/missile up to
     * ~6000 cells including climb, MLRS at 400 blocks ~15000; beyond 600 on an oblique heading up to ~22000, then
     * limit (only amid towers above the entire arc).
     */
    public static final class Budget {
        /**
         * Default limit, cells: ≈8–16 ms (ray cell like blast ray step, host ~0.4 μs or more).
         */
        public static final int CELLS = 20_000;
        /**
         * Climb-strip columns per path block: 3-block width, up to 5 grid traversals, each ~1.4 columns/block on
         * oblique heading.
         */
        static final double CLIMB_COLUMNS_PER_BLOCK = 8;

        private final int limit;
        private int spent;
        private boolean out;

        public Budget() {
            this(CELLS);
        }

        public Budget(int limit) {
            this.limit = limit;
        }

        /** Charge {@code cells}; if they do not fit, budget exhausted: false, take nothing further. */
        public boolean take(int cells) {
            if (out || cells > limit - spent) {
                out = true;
                return false;
            }
            spent += cells;
            return true;
        }

        /** Limit exhausted: check lacking budget returned “occupied” before finishing. */
        public boolean out() {
            return out;
        }

        /** Cells charged. */
        public int spent() {
            return spent;
        }
    }

    /**
     * New launcher site at {@code post} with clear launch sector: each site in order ({@link Post#candidates}),
     * proposed headings ({@code yaws} from site), then turns from first heading no farther than {@code maxOff}°
     * from {@code target} direction (farther would send salvo away from target, looking broken). Null if none or
     * {@code budget} exhausted: projectile approaches without launcher.
     */
    @Nullable
    public static Pick findClear(ServerLevel level, Post post, WeaponType weapon, Vec3 target, Function<Vec3, float[]> yaws,
                                 float maxOff, Budget budget) {
        if (post.ordered()) {
            Pick[] found = new Pick[1];
            nearest(Ground.of(level), Mth.floor(post.center().x), Mth.floor(post.center().z), budget, site -> {
                if (taken(level, site)) return false;
                found[0] = pickOn(level, site, weapon, yaws.apply(site), target, maxOff, budget);
                return found[0] != null;
            });
            return found[0];
        }
        for (double[] c : CANDIDATES) {
            Vec3 off = Local.offset(post.yaw(), 0, c[1], 0, -c[0]);
            Vec3 p = post.center().add(off);
            Vec3 site = check(level, Mth.floor(p.x), Mth.floor(p.z));
            if (site == null || taken(level, site)) continue;
            Pick pick = pickOn(level, site, weapon, yaws.apply(site), target, maxOff, budget);
            if (pick != null) return pick;
            if (budget.out()) return null;
        }
        return null;
    }

    /** {@link #pickOn(ServerLevel, Vec3, WeaponType, float[], Vec3, float, Budget)} with its own default budget. */
    @Nullable
    public static Pick pickOn(ServerLevel level, Vec3 site, WeaponType weapon, float[] want, Vec3 target, float maxOff) {
        return pickOn(level, site, weapon, want, target, maxOff, new Budget());
    }

    /**
     * Trailer heading at {@code site} ({@link #pickOn(ServerLevel, LauncherMount, Vec3, WeaponType, float[],
     * Vec3, float, Budget)}).
     */
    @Nullable
    public static Pick pickOn(ServerLevel level, Vec3 site, WeaponType weapon, float[] want, Vec3 target, float maxOff, Budget budget) {
        return pickOn(level, LauncherMount.TRAILER, site, weapon, want, target, maxOff, budget);
    }

    /**
     * Rack heading on {@code mount} at {@code site}: first clear {@code want}, then turns from first heading no
     * farther than {@code maxOff}° from {@code target} heading; null if all blocked or {@code budget} exhausted.
     */
    @Nullable
    public static Pick pickOn(ServerLevel level, LauncherMount mount, Vec3 site, WeaponType weapon, float[] want, Vec3 target, float maxOff,
                              Budget budget) {
        float toTarget = FlightController.anglesTo(site, target)[0];
        for (int i = 0; i < want.length; i++) {
            if (clearAhead(level, mount, site, want[i], weapon, target, budget)) return new Pick(site, want[i], i);
            if (budget.out()) return null;
        }
        for (float t : TURNS) {
            float yaw = Mth.wrapDegrees(want[0] + t);
            if (Math.abs(Mth.wrapDegrees(yaw - toTarget)) > maxOff) continue;
            if (clearAhead(level, mount, site, yaw, weapon, target, budget)) return new Pick(site, yaw, -1);
            if (budget.out()) return null;
        }
        return null;
    }

    /** Standing launcher's sector (current heading) toward {@code target} is clear. */
    public static boolean clearAhead(ServerLevel level, Launcher launcher, Vec3 target, Budget budget) {
        return clearAhead(level, launcher.mount(), launcher.position(), launcher.yaw(), launcher.weapon(), target, budget);
    }

    /** {@link #clearAhead(ServerLevel, Vec3, float, WeaponType, Vec3, Budget)} with its own default budget. */
    public static boolean clearAhead(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, Vec3 target) {
        return clearAhead(level, site, yaw, weapon, target, new Budget());
    }

    /**
     * Trailer launch sector ({@link #clearAhead(ServerLevel, LauncherMount, Vec3, float, WeaponType, Vec3,
     * Budget)}).
     */
    public static boolean clearAhead(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, Vec3 target, Budget budget) {
        return clearAhead(level, LauncherMount.TRAILER, site, yaw, weapon, target, budget);
    }

    /**
     * Launch sector of launcher on {@code mount} at {@code site} with {@code yaw} is clear from every rack cell:
     * salvo uses all cells, and a block in just one cell's path crashes its projectile before arming.
     * Rail-to-arming path ({@link ProximityFuse#ARM_DISTANCE} horizontally) hits no blocks. Boosted projectile
     * (spec {@code LaunchProfile}) checks both nose path during boost ({@link #boostSweep}: same flight law) and
     * a climb-angle ray at the lesser of rail angle and end-of-boost pitch ({@code boostEndPitch}): projectile
     * never drops below it before arming, while boost goes above it under awnings, canopy, or building edges over
     * the ray. MLRS uses its actual tube-to-{@code target} arc (range sets speed, {@link RocketEntity}) until
     * motor burnout (armed thereafter), excluding {@link #NEAR_AIM} near target where blocks are the target. Two
     * rays: axis and {@link #HULL} lower (hull). Shahed/missile also check feasible post-arming autopilot climb
     * ({@link #climbOut}). Unready chunks are unread; their path is considered clear (projectile goes virtual
     * there).
     * <p>
     * Cost comes from {@code budget} ({@link Budget}): false if insufficient. Check outer rack cells first
     * (post-boost path, then boost), then middle: city's long ray more often hits, and outer cells usually block
     * the middle too.
     */
    public static boolean clearAhead(ServerLevel level, LauncherMount mount, Vec3 site, float yaw, WeaponType weapon, Vec3 target, Budget budget) {
        float elevation = LauncherEntity.elevation(weapon);
        WeaponSpec.Airframe air = weapon.spec().airframe();
        WeaponSpec.LaunchProfile lp = air.launchProfile();
        int slots = LauncherEntity.slots(weapon);
        List<List<Vec3[]>> boost = new ArrayList<>(slots), paths = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) {
            Vec3 rail = mount.railPoint(site, yaw, weapon, slot);
            List<Vec3> path = new ArrayList<>();
            path.add(rail);
            if (lp != null) {
                boost.add(boostSweep(rail, yaw, elevation, lp, air.noseLength()));
                double climb = Math.toRadians(Math.min(elevation, -lp.boostEndPitch()));
                double reach = ProximityFuse.ARM_DISTANCE;
                path.add(rail.add(Local.horizontal(yaw).scale(reach)).add(0, reach * Math.tan(climb), 0));
            } else {
                // Drag/wind-free parabola: before arming (tens of blocks), drag/wind path differs by less than one block.
                Vec3 v0 = Ballistics.launchVelocity(rail, target, Ballistics.ticksFor(rail, target, elevation, RocketEntity.MIN_FLIGHT));
                double reach = Math.min(ProximityFuse.ARM_DISTANCE, horizontal(rail, target) - NEAR_AIM);
                for (int k = ARC_STEP; k <= RocketEntity.BURN_TICKS; k += ARC_STEP) {
                    Vec3 at = Ballistics.at(rail, v0, k);
                    if (horizontal(rail, at) > reach) break;
                    path.add(at);
                }
            }
            List<Vec3[]> legs = new ArrayList<>(path.size());
            for (int i = 1; i < path.size(); i++) legs.add(new Vec3[]{path.get(i - 1), path.get(i)});
            paths.add(legs);
        }
        if (!clear(level, edges(paths), budget) || !clear(level, edges(boost), budget)
                || !clear(level, middle(paths), budget) || !clear(level, middle(boost), budget)) return false;
        if (weapon.spec().launch() != WeaponSpec.Launch.GUIDED) return true;
        return budget.take((int) Math.ceil(air.reliefLookahead() * Budget.CLIMB_COLUMNS_PER_BLOCK))
                && climbOut(level, paths.getFirst().getLast()[1], yaw, air);
    }

    /** First and last rack cells. */
    private static <T> List<T> edges(List<T> cells) {
        return cells.size() <= 2 ? cells : List.of(cells.getFirst(), cells.getLast());
    }

    /** Rack cells between first and last. */
    private static <T> List<T> middle(List<T> cells) {
        return cells.size() <= 2 ? List.of() : cells.subList(1, cells.size() - 1);
    }

    /**
     * Boost nose path from {@code rail}, one swept segment per tick as in flight: spec boost law ({@link
     * WeaponSpec.LaunchProfile#boost}) and nose step ({@link StrikeProjectile#noseSweep}).
     */
    private static List<Vec3[]> boostSweep(Vec3 rail, float yaw, float elevation, WeaponSpec.LaunchProfile lp, double noseLength) {
        FlightController flight = new FlightController(yaw, -elevation);
        List<Vec3[]> sweep = new ArrayList<>(lp.boostTicks());
        Vec3 pos = rail;
        double speed = 0;
        for (int tick = 1; tick <= lp.boostTicks(); tick++) {
            speed = lp.boost(flight, speed, tick);
            Vec3 dir = flight.forward();
            sweep.add(StrikeProjectile.noseSweep(pos, dir, speed, noseLength));
            pos = pos.add(dir.scale(speed));
        }
        return sweep;
    }

    /**
     * Paths of {@code cells} (segments in order for each) hit no blocks at axis or hull ({@link #HULL} below);
     * stop checking beyond first unready chunk. Same-index segments entirely above their columns' top blocks
     * ({@link #aboveBlocks}) need no rays: nothing to hit; MLRS has 80 rays per segment, and most open-terrain
     * arc qualifies. Sable craft blocks are absent from world heightmaps, so near craft trace everywhere. Charge
     * each ray and heightmap column to {@code budget} before reading; false if insufficient.
     */
    private static boolean clear(ServerLevel level, List<List<Vec3[]>> cells, Budget budget) {
        AABB all = null;
        int legs = 0;
        for (List<Vec3[]> path : cells) {
            legs = Math.max(legs, path.size());
            for (Vec3[] leg : path) all = all == null ? swept(leg) : all.minmax(swept(leg));
        }
        if (all == null) return true;
        boolean crafts = SubLevels.mayHaveCraftNear(level, all.getCenter(), Math.max(all.getXsize(), Math.max(all.getYsize(), all.getZsize())) / 2);
        boolean[] stopped = new boolean[cells.size() * 2];
        for (int k = 0; k < legs; k++) {
            int rays = 0;
            for (int c = 0; c < cells.size(); c++) {
                if (k >= cells.get(c).size()) continue;
                Vec3[] leg = cells.get(c).get(k);
                for (int ray = 0; ray < 2; ray++) {
                    if (!stopped[2 * c + ray]) rays += traversed(leg[0], leg[1]);
                }
            }
            if (rays == 0) break;
            if (!crafts && aboveBlocks(level, cells, k, rays, budget)) continue;
            for (int c = 0; c < cells.size(); c++) {
                if (k >= cells.get(c).size()) continue;
                Vec3[] leg = cells.get(c).get(k);
                for (int ray = 0; ray < 2; ray++) {
                    if (stopped[2 * c + ray]) continue;
                    double below = ray * HULL;
                    Vec3 from = leg[0].subtract(0, below, 0), end = leg[1].subtract(0, below, 0);
                    if (!budget.take(traversed(from, end))) return false;
                    Vec3 to = Terrain.readyUntil(level, from, end);
                    if (level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()))
                            .getType() != HitResult.Type.MISS) return false;
                    if (to.distanceToSqr(end) > 1e-6) stopped[2 * c + ray] = true;
                }
            }
        }
        return true;
    }

    /**
     * Cells traversed by {@code Level.clip} from {@code from} to {@code to}: block grid, one cell per crossed
     * face. Integer block offset (hull, {@link #HULL}) does not change count.
     */
    private static int traversed(Vec3 from, Vec3 to) {
        return 1 + Math.abs(Mth.floor(to.x) - Mth.floor(from.x)) + Math.abs(Mth.floor(to.y) - Mth.floor(from.y))
                + Math.abs(Mth.floor(to.z) - Mth.floor(from.z));
    }

    /** Box swept by axis and hull on {@code leg}. */
    private static AABB swept(Vec3[] leg) {
        return new AABB(leg[0], leg[1]).expandTowards(0, -HULL, 0);
    }

    /**
     * Segment {@code k} of every cell lies entirely above every column's top block (any block, including
     * non-colliding: {@code WORLD_SURFACE}), with ready column chunks. {@code Level.clip} checks only traversed
     * cells, which are air above heightmap, giving identical result. If box columns exceed {@code rays} cells
     * (long diagonal: 68×68 box, 4600 columns vs ~600 cells), or exceed {@code budget}, return false without
     * reading: rays are cheaper.
     */
    private static boolean aboveBlocks(ServerLevel level, List<List<Vec3[]>> cells, int k, int rays, Budget budget) {
        AABB box = null;
        for (List<Vec3[]> path : cells) {
            if (k < path.size()) box = box == null ? swept(path.get(k)) : box.minmax(swept(path.get(k)));
        }
        if (box == null) return true;
        int x0 = Mth.floor(box.minX), x1 = Mth.floor(box.maxX), z0 = Mth.floor(box.minZ), z1 = Mth.floor(box.maxZ);
        long columns = (long) (x1 - x0 + 1) * (z1 - z0 + 1);
        if (columns > rays || !budget.take((int) columns)) return false;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!Terrain.ready(level, x >> 4, z >> 4) || Terrain.height(level, Heightmap.Types.WORLD_SURFACE, x, z) > box.minY) return false;
            }
        }
        return true;
    }

    /**
     * Post-arming climb feasible for Shahed/missile autopilot: straight along launcher heading from arming {@code
     * gate} (checked ray end, projectile no lower) for {@link WeaponSpec.Airframe#reliefLookahead} blocks, its
     * sensor reach; climbing at cruise speed under autopilot law ({@link Autopilot#climbOver}), hull clears strip
     * terrain. Autopilot handles farther terrain, but must start where climb is possible: launcher on a street
     * between towers passes pre-arming check, yet Shahed would hit a tower 40 blocks later above its climb.
     */
    private static boolean climbOut(ServerLevel level, Vec3 gate, float yaw, WeaponSpec.Airframe air) {
        Vec3 end = gate.add(Local.horizontal(yaw).scale(air.reliefLookahead()));
        double over = Autopilot.climbOver(new double[]{gate.x, gate.z, end.x, end.z}, air.cruiseSpeed(), air.handling().climbAngle(),
                (x, z) -> StrikeProjectile.surfaceY(level, x, z));
        return over + HULL <= gate.y;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Another launcher nearby (owned different weapon or another owner's): 7.6 m trailer, racks over 4 m; do not
     * overlap.
     */
    private static boolean taken(ServerLevel level, Vec3 site) {
        return !level.getEntitiesOfClass(LauncherEntity.class, new AABB(site, site).inflate(CLEARANCE, 8, CLEARANCE), LauncherEntity::isAlive).isEmpty();
    }

    /**
     * Ground used for launcher site search: chunk readiness, heightmaps, block underfoot. World uses {@link #of};
     * site-selection tests use their own.
     */
    interface Ground {
        boolean ready(int x, int z);

        /** First air above top block excluding leaves ({@code MOTION_BLOCKING_NO_LEAVES}). */
        int ground(int x, int z);

        /** First air above top block including leaves ({@code MOTION_BLOCKING}). */
        int top(int x, int z);

        /** Block underfoot at (x, y, z) has solid top and no fluid. */
        boolean firm(int x, int y, int z);

        /** Block at (x, y, z) supports (collision shape: includes dirt paths and farmland) and has no fluid. */
        boolean dry(int x, int y, int z);

        int minY();

        static Ground of(ServerLevel level) {
            return new Ground() {
                @Override
                public boolean ready(int x, int z) {
                    return Terrain.ready(level, x >> 4, z >> 4);
                }

                @Override
                public int ground(int x, int z) {
                    return Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                }

                @Override
                public int top(int x, int z) {
                    return Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z);
                }

                @Override
                public boolean firm(int x, int y, int z) {
                    BlockPos at = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(at);
                    return state.getFluidState().isEmpty() && state.isFaceSturdy(level, at, Direction.UP);
                }

                @Override
                public boolean dry(int x, int y, int z) {
                    BlockPos at = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(at);
                    return state.getFluidState().isEmpty() && !state.getCollisionShape(level, at).isEmpty();
                }

                @Override
                public int minY() {
                    return level.getMinBuildHeight();
                }
            };
        }
    }

    private static Vec3 check(ServerLevel level, int x, int z) {
        return check(Ground.of(level), x, z);
    }

    /**
     * Flat (±1 block in 5×5), solid below center, supported and not water across entire square (7.6 m trailer:
     * water surface and shore differ by one block, so a “flat” square could be half over water; edge
     * paths/farmland support), clear overhead (leaves block too). Check cheapest, most frequent rejections first:
     * canopy over site and water below it (forest/lake in launch area), then square.
     */
    @Nullable
    static Vec3 check(Ground g, int x, int z) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (!g.ready(x + dx, z + dz)) return null;
            }
        }
        int y = g.ground(x, z);
        if (y <= g.minY() + 1 || g.top(x, z) > y + 1 || !g.firm(x, y - 1, z)) return null;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                int h = g.ground(x + dx, z + dz);
                if (Math.abs(h - y) > 1 || g.top(x + dx, z + dz) > h + 1) return null;
            }
        }
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                if ((dx != 0 || dz != 0) && !g.dry(x + dx, g.ground(x + dx, z + dz) - 1, z + dz)) return null;
            }
        }
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    /**
     * Reads per {@link Budget} cell: chunk readiness/heightmap read takes tens of ns, ray cell ~0.4 μs (⚠
     * order-of-magnitude estimate); underfoot block (state/top shape) costs {@link #BLOCK_READS} reads.
     */
    static final int READS_PER_CELL = 8, BLOCK_READS = 4;

    /** Ground counting its own reads: site check costs charged to launcher-selection limit. */
    private static final class Counted implements Ground {
        private final Ground g;
        int reads;

        Counted(Ground g) {
            this.g = g;
        }

        @Override
        public boolean ready(int x, int z) {
            reads++;
            return g.ready(x, z);
        }

        @Override
        public int ground(int x, int z) {
            reads++;
            return g.ground(x, z);
        }

        @Override
        public int top(int x, int z) {
            reads++;
            return g.top(x, z);
        }

        @Override
        public boolean firm(int x, int y, int z) {
            reads += BLOCK_READS;
            return g.firm(x, y, z);
        }

        @Override
        public boolean dry(int x, int y, int z) {
            reads += BLOCK_READS;
            return g.dry(x, y, z);
        }

        @Override
        public int minY() {
            return g.minY();
        }
    }

    /**
     * Nearest suitable launcher site to (x, z) within its area ({@link #PLACE_AREA}) accepted by {@code fits}
     * (free and clear sector); null if none or {@code budget} exhausted. Blocked-sector site also excludes
     * neighbors within {@link #CLEARANCE}: same trees/buildings block their sectors, and sector check is most
     * costly. Charge site checks by reads ({@link #READS_PER_CELL}).
     */
    @Nullable
    static Vec3 nearest(Ground ground, int x, int z, Budget budget, Predicate<Vec3> fits) {
        List<Vec3> closed = new ArrayList<>();
        Counted g = new Counted(ground);
        for (int[] o : AREA) {
            g.reads = 0;
            Vec3 site = check(g, x + o[0], z + o[1]);
            if (!budget.take(Math.max(1, (g.reads + READS_PER_CELL - 1) / READS_PER_CELL))) return null;
            if (site == null || near(closed, site)) continue;
            if (fits.test(site)) return site;
            if (budget.out()) return null;
            closed.add(site);
        }
        return null;
    }

    private static boolean near(List<Vec3> closed, Vec3 site) {
        for (Vec3 c : closed) if (horizontal(c, site) < CLEARANCE) return true;
        return false;
    }

    /** Circle sites of radius reach at spacing step: [east, south], nearest first (ties in traversal order). */
    static int[][] area(double reach, int step) {
        List<int[]> out = new ArrayList<>();
        int n = (int) Math.floor(reach / step);
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                if (Math.hypot(i * step, j * step) <= reach) out.add(new int[]{i * step, j * step});
            }
        }
        out.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return out.toArray(int[][]::new);
    }

    /**
     * New launcher for {@code post}; at shooter, remove excess old following launchers (ordered-site launchers
     * stay until “Abort”).
     */
    public static LauncherEntity deploy(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, Post post) {
        if (!post.ordered()) {
            List<LauncherEntity> mine = new ArrayList<>();
            for (var e : level.getEntities(ModEntities.LAUNCHER.get(), l -> !l.ordered() && Objects.equals(post.owner(), l.ownerId()))) mine.add(e);
            mine.sort(Comparator.comparingLong(LauncherEntity::deployedAt));
            for (int i = 0; i <= mine.size() - MAX_PER_OWNER; i++) mine.get(i).discard();
        }
        LauncherEntity l = LauncherEntity.create(level, site, yaw, weapon, post.owner(), post.ordered(), post.ordered() ? post.center() : null);
        level.addFreshEntity(l);
        return l;
    }
}
