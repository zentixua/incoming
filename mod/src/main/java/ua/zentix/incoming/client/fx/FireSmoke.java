package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.grid.SubstationBlock;

import java.util.Locale;

/**
 * Smoke from the mod's strike ignitions is a client cloud (baked effects-layer billows render it, {@code far/SmokePuffs}):
 * crater fuel ({@link #pool}, {@link #fuel}) and oil from failed substations ({@link #substation}). Minecraft block fire
 * is outside this system: it smokes like any game fire (vanilla or the mod managing combustion); this mod draws only
 * its own sources, rather than decorating world fire. Each source has a
 * Briggs plume ({@link FirePlume}) following world wind and air stability ({@link ClientAir}), in tube segments
 * ({@link SmokeGrid.Sink#tube}, {@link #lay}: drawn like projectile trails); flame lights from below as a sphere in free grid-light slots ({@link #lights}). Airborne smoke retains source
 * strength at emission: a position x downwind comes from the source as it was x/u seconds ago (source history every
 * {@link #PERIOD} ticks; current wind applies to the whole plume). Thus the plume grows from the source at wind speed; when
 * the source weakens or goes out, smoke near it thins, while emitted smoke retains its strength and travels downwind.
 * <p>
 * Nearby sources form one fire ({@link #merge}): salvo craters and a nearby substation burn as one column, rather than several
 * weak ones (plume rise follows total heat, ∝ F^⅓ along the path and F^⅗ at final rise; half the heat gives 20–35 % less rise).
 * Each source carries its own soot but rises in the whole fire's plume. Nuclear-detonation fires are outside this system:
 * their smoke belongs to the firestorm ({@code FireStorm}).
 */
public final class FireSmoke implements SmokeGrid.Source {
    /**
     * Plume tube segments: denser near the source where the axis is steeper (quadratic path positions); same at any eye distance
     * (the old 4 beyond 256 m with 12 billows per segment spaced them farther than their size, making distant plumes bead).
     */
    static final int SEGMENTS = 10;
    /** Sources are reassessed every this many ticks. */
    static final int PERIOD = 20;
    /** Maximum downwind plume path, m (0.75 of the last grid cascade's half-side: ~0.8 km). */
    public static final double REACH = 0.75 * 0.5 * SmokeGrid.N * SmokeGrid.CELL[SmokeGrid.CASCADES - 1];

    /** Burning oil of failed substations by block position ({@link #substation}). */
    private static final Long2ObjectOpenHashMap<FireSmoke> OIL_FIRES = new Long2ObjectOpenHashMap<>();
    /** Explosion crater fuel ({@link #pool}); server sources also keyed by crater ID (state changes, {@link #fuel}). */
    private static final java.util.List<FireSmoke> POOLS = new java.util.ArrayList<>();
    private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<FireSmoke> POOL_IDS = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
    /** Burning-crater fuel-fire cells: their fire belongs to crater fuel ({@link FireWatch} does not count it). */
    static final FuelCells FUEL = new FuelCells();

    /**
     * Burning material: heat release and source area per unit source strength (one Minecraft-fire unit is one fire block), soot
     * per joule.
     */
    public record Fuel(double watts, double area, double sootPerJoule) {
        /** Plume of a source of strength {@code units} in {@code wind} and a stability class. */
        FirePlume plume(double units, double wind, double stability) {
            return plume(units, units, wind, stability);
        }

        /**
         * Plume of a source of strength {@code units} in a fire of strength {@code fire} (same fuel units, {@link #merge}):
         * the whole fire's plume rises and spreads; soot remains the source's own.
         */
        FirePlume plume(double units, double fire, double wind, double stability) {
            double all = Math.max(fire, units);
            return new FirePlume(all * watts, Math.sqrt(all * area / Math.PI), all > 0 ? sootPerJoule * units / all : sootPerJoule, wind, stability);
        }
    }

    /**
     * Transformer oil of a failed substation, spilled and burning as a pool: NFPA 92 (2015), table B.5.1,
     * hydrocarbon transformer-oil row, gives 0.039 kg/(m²·s) large-pool asymptotic burning rate and 46.4 MJ/kg.
     * The table's note c calls the rate uncertain (two data points). Using it without the pool-diameter
     * correction and rounding the heat to 46 MJ/kg are estimates (est.); soot yield 0.06 kg/kg is also an
     * estimate, not a verified transformer-oil measurement. Pool ~{@value #OIL_AREA} m² (est.: a substation
     * transformer holds hundreds of liters, with containment around it). Heat 3.6 MW, Heskestad flame ~4.5 m; enough oil for hours
     * of burning, so the source burns while the substation is out.
     */
    public static final double OIL_AREA = 2;
    public static final Fuel OIL = new Fuel(0.039 * OIL_AREA * 46e6, OIL_AREA, 0.06 / 46e6);
    /** Sources' world: on change (portal), old plumes disappear immediately. */
    private static ClientLevel world;
    private static int clock;
    private static final double[] BB = new double[4];
    private static final Vector4f COLUMN = new Vector4f();
    private static final double[] WEIGHT = new double[4];

    /** Source history record count (one per second): enough for smoke to reach the grid edge even at 1 m/s wind. */
    static final int HISTORY = 1024;

    /** Burning material. */
    final Fuel fuel;
    /** Crater fuel burning share by source age (null for non-crater sources). */
    @org.jetbrains.annotations.Nullable
    java.util.function.DoubleUnaryOperator burning;
    /** Crater fuel (cells, law, position); null for other sources. */
    @org.jetbrains.annotations.Nullable
    private FuelBurn burn;
    /**
     * Source position, strength (fire blocks or fuel sources), whole fire strength (in its fuel units,
     * {@link #merge}), burning start/end (grid clock, s; end is NaN while burning).
     */
    double x, y, z, blocks, fire;
    final double start;
    double end = Double.NaN;
    /** World changed: source leaves the grid. */
    boolean gone;
    /**
     * Current source plume in current wind/air (rise follows whole fire heat, {@link #merge}), and plume of its own
     * heat alone (hot gas and flame above source); wind direction.
     */
    FirePlume plume, own;
    double wx, wz;
    /** Visible downwind reach of the strongest historical source's plume, m; own source flame height, m. */
    double visible, flame;
    /** Source/fire history; strongest fire in that history. */
    private final ua.zentix.incoming.client.fx.smoke.History history = new ua.zentix.incoming.client.fx.smoke.History(HISTORY),
            fires = new ua.zentix.incoming.client.fx.smoke.History(HISTORY);
    private double strongest;

    private FireSmoke(Fuel fuel, double x, double y, double z, double blocks, double seconds) {
        this.fuel = fuel;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blocks = blocks;
        this.fire = blocks;
        this.start = seconds;
    }

    /** History record: at seconds, blocks blocks burned in a fire of strength fire. */
    private void record(double seconds, double blocks, double fire) {
        history.record(seconds, blocks);
        fires.record(seconds, Math.max(fire, blocks));
        strongest = Math.max(strongest, Math.max(fire, blocks));
    }

    /** Fire blocks at seconds (0 before start and after end). */
    double blocksAt(double seconds) {
        if (seconds < start || !Double.isNaN(end) && seconds >= end) return 0;
        return history.at(seconds);
    }

    /** Source's fire strength at seconds (in its fuel units). */
    double fireAt(double seconds) {
        return fires.at(seconds);
    }

    /**
     * Source wind, m/s: world wind at local height above ground, corrected for buildings and hill ({@link ClientAir#speed});
     * in buildings, no lower than roofs: a fire plume rises above them in seconds and bends in rooftop wind, not street wind
     * (⚠ Briggs rise uses one wind for the whole path).
     */
    private double wind() {
        var site = ClientAir.site(x, z);
        return ClientAir.speed(x, Math.max(y, y - site.above(y) + site.canopy()), z);
    }

    /** Plume from current wind at source height and stability. */
    private void air() {
        var world = ClientAir.now().wind();
        double u = wind();
        wx = world.speed() > 1e-6 ? world.x() : 1;
        wz = world.speed() > 1e-6 ? world.z() : 0;
        plume = fuel.plume(blocks, fire, u, ClientAir.now().stability());
        // each source has its own flame and hot gas (fire light/shimmer sum sources, rather than sources ×
        // the whole fire's flame)
        own = fuel.plume(blocks, u, ClientAir.now().stability());
        flame = own.flameHeight();
        visible = fuel.plume(strongest, u, ClientAir.now().stability()).visible(REACH);
    }

    /**
     * Sources whose plumes merge form one fire: a wind-bent plume has radius ~0.6 of its rise (entrainment
     * β = 0.6, Briggs, “Plume Rise Predictions”, 1975); two plumes join when their summed radii at final rise
     * exceed source separation: d ≤ 0.6·(Δh₁ + Δh₂) (⚠ touching-plume merge (est.)). A fire is a connected group (by
     * chains); each source's strength within it is total group heat in its fuel units.
     */
    static void merge(java.util.List<FireSmoke> all) {
        int n = all.size();
        if (n == 0) return;
        double[] x = new double[n], z = new double[n], watts = new double[n], rise = new double[n];
        double stability = ClientAir.now().stability();
        for (int i = 0; i < n; i++) {
            FireSmoke f = all.get(i);
            x[i] = f.x;
            z[i] = f.z;
            watts[i] = f.blocks * f.fuel.watts;
            rise[i] = f.fuel.plume(f.blocks, f.wind(), stability).finalRise;
        }
        double[] fire = fires(x, z, watts, rise);
        for (int i = 0; i < n; i++) all.get(i).fire = fire[i] / all.get(i).fuel.watts;
    }

    /**
     * Fire heat for each source, W: sources at (x, z), heat {@code watts}, final own-plume rise
     * {@code rise}, merge by chains when d ≤ 0.6·(Δh₁ + Δh₂) ({@link #merge}).
     */
    static double[] fires(double[] x, double[] z, double[] watts, double[] rise) {
        int n = x.length;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Math.hypot(x[i] - x[j], z[i] - z[j]) <= 0.6 * (rise[i] + rise[j])) parent[root(parent, i)] = root(parent, j);
            }
        }
        double[] sum = new double[n], out = new double[n];
        for (int i = 0; i < n; i++) sum[root(parent, i)] += watts[i];
        for (int i = 0; i < n; i++) out[i] = sum[root(parent, i)];
        return out;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    /** Once per client tick: sources every {@link #PERIOD}. */
    public static void tick(ClientLevel level) {
        if (level != world) {
            reset();
            world = level;
        }
        if (++clock % PERIOD != 0) return;
        double now = SmokeGrid.seconds(0);
        for (var it = OIL_FIRES.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            FireSmoke f = e.getValue();
            BlockPos at = BlockPos.of(e.getLongKey());
            // client has the chunk: inspect block; restored power or removed substation means oil no longer burns
            if (level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4) != null && !SubstationBlock.out(level.getBlockState(at))) {
                f.end = now;
                it.remove();
                continue;
            }
        }
        for (var it = POOLS.iterator(); it.hasNext(); ) {
            FireSmoke f = it.next();
            double share = f.burning.applyAsDouble(now - f.start);
            f.blocks = share;
            if (share <= 0 && now > f.start) {
                f.end = now;
                it.remove();
                if (f.burn != null) POOL_IDS.remove(f.burn.id, f);
                columns();
            }
        }
        // nearby sources form one fire; then record history and build its plume
        java.util.List<FireSmoke> burning = new java.util.ArrayList<>(OIL_FIRES.values());
        burning.addAll(POOLS);
        merge(burning);
        for (FireSmoke f : burning) {
            f.record(now, f.blocks, f.fire);
            f.air();
        }
    }

    /**
     * Substation failed ({@code GridEffects.failure}; late joiners learn from a nearby block random tick): transformer oil
     * ({@link #OIL}) burns above it while power is out.
     */
    public static void substation(ClientLevel level, BlockPos pos) {
        if (level != world) {
            reset();
            world = level;
        }
        long key = pos.asLong();
        if (OIL_FIRES.containsKey(key)) return;
        double now = SmokeGrid.seconds(0);
        FireSmoke f = new FireSmoke(OIL, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, now);
        f.record(now, 1, 1);
        f.air();
        OIL_FIRES.put(key, f);
        SmokeGrid.add(f);
    }

    /**
     * Server crater state ({@code S2C.FuelFire}): new crater creates a smoke source ({@link #pool}); existing source moves to
     * where fuel now burns, using the share of cells not extinguished early ({@link FuelBurn#share}). Returns new fuel
     * (for sound), or null if the crater already existed.
     */
    @org.jetbrains.annotations.Nullable
    public static FuelBurn fuel(ClientLevel level, int id, ua.zentix.incoming.nuclear.model.FuelPool law, java.util.List<BlockPos> cells,
                                long smothered, double age) {
        if (level != world) {
            reset();
            world = level;
        }
        FireSmoke known = POOL_IDS.get(id);
        if (known != null && known.burn != null) {
            known.burn.update(cells, smothered);
            Vec3 c = known.burn.centre();
            known.x = c.x;
            known.y = c.y;
            known.z = c.z;
            columns();
            return null;
        }
        FuelBurn burn = new FuelBurn(id, law, cells.size(), cells, smothered);
        POOL_IDS.put(id, pool(level, burn, age));
        return burn;
    }

    /**
     * Crater fuel burns as Minecraft fire on cells (server, {@code warhead/FuelFires}) under {@code burn}, with the law's heat
     * and fuel soot, age-based {@link FuelBurn#share} ({@code age} s already since impact): a client source
     * above the burning cells' centroid; flame lights smoke and world. Cell fire is the blocks themselves; nuclear city fire
     * ({@link FireWatch}) excludes them ({@link FuelCells}).
     */
    static FireSmoke pool(ClientLevel level, FuelBurn burn, double age) {
        if (level != world) {
            reset();
            world = level;
        }
        Vec3 c = burn.centre();
        double now = SmokeGrid.seconds(0);
        var law = burn.law;
        Fuel fuel = new Fuel(law.watts(), law.area(), law.fuel().sootYield / law.fuel().heatJPerKg);
        FireSmoke f = new FireSmoke(fuel, c.x, c.y, c.z, 1, now - age);
        f.burning = burn::share;
        f.burn = burn;
        f.record(now, 1, 1);
        f.air();
        POOLS.add(f);
        columns();
        SmokeGrid.add(f);
        return f;
    }

    /** Rebuild fuel-cell index ({@link #FUEL}) from burning craters. */
    private static void columns() {
        FUEL.index(POOLS.stream().filter(f -> f.burn != null).map(f -> f.burn.live()).toList());
    }

    /** World change/replay seek: no sources (grid reset removes its sources). */
    public static void reset() {
        for (FireSmoke f : OIL_FIRES.values()) f.gone = true;
        for (FireSmoke f : POOLS) f.gone = true;
        OIL_FIRES.clear();
        POOLS.clear();
        POOL_IDS.clear();
        FUEL.clear();
        world = null;
    }

    /**
     * Plume at seconds: from source to the path smoke has traveled since ignition, no farther than the strongest
     * source's visible plume ({@link #lay}).
     */
    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        double u = plume.wind, stability = plume.stability;
        double far = Math.min(visible, u * (seconds - start));
        if (!Double.isNaN(end) && u * (seconds - end) >= far) return false;
        if (far <= 0) return true;
        lay(fuel, x, y, z, wx, wz, u, stability, far, plume.size, path -> blocksAt(seconds - path / u), path -> fireAt(seconds - path / u), out);
        return true;
    }

    /**
     * Plume of fuel source (x, y, z), wind (wx, wz) at speed u, to path far, in tube segments ({@link SmokeGrid.Sink#tube})
     * between axis positions at path xₖ (quadratic: steeper axis near source), same at any eye distance: the renderer chooses quads
     * per segment by screen size (ribbon far away). Axis position at path x follows the source when this smoke was emitted
     * (x/u seconds ago, blocks/fire are source/fire strength along path): its plume rise and σ, and its soot per meter;
     * no burning then means no soot: segment ends where smoke ends (boundary from source history); empty segments
     * are also submitted (without soot). sigma0 is σ at the source.
     */
    public static void lay(Fuel fuel, double x, double y, double z, double wx, double wz, double u, double stability, double far, double sigma0,
                    java.util.function.DoubleUnaryOperator blocks, java.util.function.DoubleUnaryOperator fire, SmokeGrid.Sink out) {
        int n = SEGMENTS + 1;
        double[] path = new double[n], b = new double[n];
        for (int k = 0; k < n; k++) {
            double f = (double) k / SEGMENTS;
            path[k] = far * f * f;
            b[k] = blocks.applyAsDouble(path[k]);
        }
        FirePlume[] plume = new FirePlume[n];
        for (int k = 0; k < n; k++) if (b[k] > 0) plume[k] = fuel.plume(b[k], fire.applyAsDouble(path[k]), u, stability);
        // where nothing burned: no soot, geometry from nearest burning position (matter falls to zero along segment)
        for (int k = 0; k < n; k++) {
            if (plume[k] != null) continue;
            for (int j = 1; j < n && plume[k] == null; j++) {
                if (k - j >= 0 && b[k - j] > 0) plume[k] = plume[k - j];
                else if (k + j < n && b[k + j] > 0) plume[k] = plume[k + j];
            }
            if (plume[k] == null) return;
        }
        sigma0 = Math.max(sigma0, 0.25);
        for (int k = 1; k < n; k++) {
            boolean burnA = b[k - 1] > 0, burnB = b[k] > 0;
            // always submit the segment (empty means no soot): segment index seeds its billows at the sink; dropping a segment must not
            // shift billows throughout the plume farther downwind
            if (!burnA && !burnB) {
                empty(out, x, y, z, wx, wz, path[k - 1], path[k], plume[k - 1], plume[k], sigma0);
                continue;
            }
            double xa = path[k - 1], xb = path[k];
            FirePlume pa = plume[k - 1], pb = plume[k];
            // smoke edge (source ignited/extinguished within segment) follows source history: segment starts there, with full matter to
            // the edge; edge travels steadily downwind instead of ramping node to node and snapping as it crosses a node
            if (burnA != burnB) {
                double lo = xa, hi = xb;
                for (int i = 0; i < 24; i++) {
                    double m = 0.5 * (lo + hi);
                    if (blocks.applyAsDouble(m) > 0 == burnA) lo = m;
                    else hi = m;
                }
                if (burnA) {
                    xb = lo;
                    pb = pa;
                } else {
                    xa = hi;
                    pa = pb;
                }
            }
            // center optical depth at both ends below visibility threshold: segment invisible
            if (Math.max(pa.tau(xa), pb.tau(xb)) < CloudModel.SEEN) {
                empty(out, x, y, z, wx, wz, xa, xb, pa, pb, sigma0);
                continue;
            }
            double ha = pa.rise(xa), hb = pb.rise(xb), mid = 0.5 * (xa + xb);
            // plume soot is ṁ/u per downwind path meter; per segment-axis meter, reduced by axis/path length ratio
            // (near source where plume rises steeply)
            double dx = xb - xa, len = Math.sqrt(dx * dx + (hb - ha) * (hb - ha)), along = len > 0 ? dx / len : 1;
            double kelvin = CloudModel.T_AIR_K + pb.excess(0.5 * (ha + hb));
            out.tube(x + wx * xa, y + ha, z + wz * xa, pa.sigmaY(xa), pa.sigmaZ(xa), pa.sootPerMetre() * along, 0, x + wx * xb, y + hb,
                    z + wz * xb, pb.sigmaY(xb), pb.sigmaZ(xb), pb.sootPerMetre() * along, 0, 1, 1, 1, kelvin, pb.riseSpeed(mid), sigma0);
        }
    }

    /** Soot-free plume segment (nothing burned or invisible): only segment index at the sink. */
    private static void empty(SmokeGrid.Sink out, double x, double y, double z, double wx, double wz, double xa, double xb, FirePlume pa,
                              FirePlume pb, double sigma0) {
        out.tube(x + wx * xa, y + pa.rise(xa), z + wz * xa, pa.sigmaY(xa), pa.sigmaZ(xa), 0, 0, x + wx * xb, y + pb.rise(xb), z + wz * xb,
                pb.sigmaY(xb), pb.sigmaZ(xb), 0, 0, 1, 1, 1, CloudModel.T_AIR_K, 0, sigma0);
    }

    @Override
    public boolean ended() {
        return gone || !Double.isNaN(end) && SmokeGrid.seconds(0) - end > REACH / plume.wind;
    }

    @Override
    public long seed() {
        return Double.doubleToLongBits(start) * 31 + Double.doubleToLongBits(x) * 17 + Double.doubleToLongBits(z);
    }

    /** The plume is laid within the shared preparation budget and drawn from its last complete snapshot. */
    @Override
    public boolean budgeted() {
        return true;
    }

    /**
     * Flame lights smoke from below in free grid-light slots (explosion/nuclear balls take the others), most noticeable first
     * (brightness × R²/d²): a visible-flame sphere ({@link FirePlume#flameLight}) of half flame-height radius above source; linear light
     * × exposure, air in w.
     */
    public static void lights(Vec3 eye, double exposure, double air, Matrix4f centres, Matrix4f light) {
        if (OIL_FIRES.isEmpty() && POOLS.isEmpty()) return;
        FirePlume.flameLight(BB);
        Vector4f column = COLUMN;
        double[] weight = WEIGHT;
        for (int i = 0; i < 4; i++) {
            centres.getColumn(i, column);
            weight[i] = column.w > 0 ? Double.MAX_VALUE : 0;
        }
        for (FireSmoke f : all()) {
            double r = 0.5 * f.flame;
            double dx = f.x - eye.x, dy = f.y + r - eye.y, dz = f.z - eye.z;
            double w = BB[3] * r * r / Math.max(dx * dx + dy * dy + dz * dz, r * r);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (weight[i] < weight[slot]) slot = i;
            if (w <= weight[slot]) continue;
            weight[slot] = w;
            centres.setColumn(slot, column.set((float) dx, (float) dy, (float) dz, (float) r));
            light.setColumn(slot, column.set((float) (BB[0] * exposure), (float) (BB[1] * exposure), (float) (BB[2] * exposure), (float) air));
        }
    }

    /** All sources: substation oil and crater fuel. */
    private static Iterable<FireSmoke> all() {
        return () -> java.util.stream.Stream.concat(OIL_FIRES.values().stream(), POOLS.stream()).iterator();
    }

    /** On-screen source fire (share of white; presentation): surrounding flame light no brighter than it (like fuel pools, {@code FarBlasts}). */
    static final double FLAME_ON_SCREEN = 0.8;

    /**
     * Source flame lights surrounding particles, billows and ribbons ({@link FxLights}): a visible-flame sphere
     * ({@link FirePlume#flameLight}) of half flame-height radius above source, with frame eye adaptation. Without it, only the moon
     * would light smoke above a fire at night.
     */
    public static void lights(FarView view, FxLights lights) {
        if (OIL_FIRES.isEmpty() && POOLS.isEmpty()) return;
        FirePlume.flameLight(BB);
        double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
        Vec3 cam = view.camera();
        for (FireSmoke f : all()) {
            if (!Double.isNaN(f.end)) continue;
            double r = 0.5 * f.flame;
            lights.add(f.x - cam.x, f.y + r - cam.y, f.z - cam.z, r, BB[3] * view.exposure(), FLAME_ON_SCREEN,
                    (float) (BB[0] / m), (float) (BB[1] / m), (float) (BB[2] / m));
        }
    }

    /**
     * Fuel-source flame where the world no longer draws it: substation oil has no own fire blocks; Minecraft fire
     * on crater fuel cells fades in fog beyond 0.8 render distance and becomes subpixel, leaving only a gray point 300 m
     * from a 2–3 MW fire at night. Body is a disk of side-view flame area (Heskestad height × source diameter), beyond 0.8
     * render distance (nearer, fire blocks themselves supply flame); light/color match flame light on smoke
     * ({@link FirePlume#FLAME_LUMINANCE}, {@link FirePlume#FLAME_COLOR}, with frame eye adaptation); glare/veil at
     * any distance, like fireballs/plumes ({@link FarSprites#light}). The world draws distant wood fire itself.
     */
    public static void far(FarView view, FarSprites out) {
        if (OIL_FIRES.isEmpty() && POOLS.isEmpty()) return;
        Vec3 cam = view.camera();
        int c = FirePlume.FLAME_COLOR;
        float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, b = (c & 255) / 255f;
        for (FireSmoke f : (Iterable<FireSmoke>) () -> java.util.stream.Stream.concat(OIL_FIRES.values().stream(), POOLS.stream()).iterator()) {
            if (!Double.isNaN(f.end) || f.gone) continue;
            if (f.blocks <= 0 || f.flame <= 0) continue;
            // flame from side: Heskestad height (current source strength: fuel uses remaining burning share) × source diameter (oil:
            // all its sources), an equal-area disk at half height
            double area = f.burning == null ? f.blocks * f.fuel.area : f.fuel.area;
            double across = 2 * Math.sqrt(area / Math.PI), radius = Math.sqrt(f.flame * across / Math.PI);
            double dx = f.x - cam.x, dy = f.y + 0.5 * f.flame - cam.y, dz = f.z - cam.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz), w = view.farShare(d), t = view.air(d, dy);
            // core beyond 0.8 render distance; glare/veil at any distance, like plume and ball
            out.light(dx, dy, dz, radius, FirePlume.FLAME_LUMINANCE * view.exposure() * t, t, view.pixel(), r, g, b, w, 1, FAR_LIGHT);
        }
    }

    private static final double[] FAR_LIGHT = new double[5];

    /**
     * Source light/hot gas on the frame world ({@code HeatPass}): hot gas over each burning source (Heskestad
     * column) and flame light from substation oil/crater fuel: Minecraft fire already lights the world with block light (level
     * 15 fades over ~14 blocks, while 2–3 MW fuel flame lights tens of meters around it at night: without own light the night
     * frame is darker than before, FV check); fuel flame light originates at the centroid of its cells on blocks.
     */
    public static void heat(Vec3 eye, ua.zentix.incoming.client.fx.layer.HeatPass.Frame f, double air) {
        if (OIL_FIRES.isEmpty() && POOLS.isEmpty()) return;
        FirePlume.flameLight(BB);
        for (FireSmoke fire : all()) {
            if (!Double.isNaN(fire.end) || fire.gone) continue;
            double dx = fire.x - eye.x, dy = fire.y - eye.y, dz = fire.z - eye.z;
            f.plume(dx, dy, dz, fire.own, 1);
            double r = 0.5 * fire.flame;
            f.light(dx, dy + r, dz, r, BB[0], BB[1], BB[2], air, r);
        }
    }

    /** For the {@code SCENARIO} line: oil/fuel source strength, flame, rise. */
    public static String describe() {
        FireSmoke first = OIL_FIRES.isEmpty() ? null : OIL_FIRES.values().iterator().next();
        String oil = first == null ? "" : String.format(Locale.ROOT, " oil %d (%.1f MW, flame %.1f, rise %.0f)", OIL_FIRES.size(),
                OIL.watts() / 1e6, first.flame, first.plume.finalRise);
        if (!POOLS.isEmpty()) {
            FireSmoke p = POOLS.get(POOLS.size() - 1);
            oil += String.format(Locale.ROOT, " pools %d (%.1f MW, fire %.1f MW, rise %.0f)", POOLS.size(), p.blocks * p.fuel.watts() / 1e6,
                    p.fire * p.fuel.watts() / 1e6, p.plume.finalRise);
        }
        return oil.isEmpty() ? "fires 0" : "fires" + oil;
    }
}
