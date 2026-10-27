package ua.zentix.incoming.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.ClientWeaponSpec.At;
import ua.zentix.incoming.client.ClientWeaponSpec.ClientAirframe;
import ua.zentix.incoming.client.ClientWeaponSpec.FarLook;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.layer.HeatPass;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.client.fx.plume.TrailModel;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.LaunchCloud;
import ua.zentix.incoming.client.fx.smoke.Matter;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.warhead.GroundMaterial;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import ua.zentix.incoming.util.Wind;
import ua.zentix.incoming.client.render.FarModels;
import ua.zentix.incoming.client.render.ProjectilePose;
import ua.zentix.incoming.client.render.WeaponModels;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.ThrustCurve;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trails of all projectiles and distant projectiles absent on client (entity beyond render distance or flying
 * outside world): path from entity or server packets ({@link FlightTracks}), look from client spec ({@link FarLook}).
 * <ul>
 * <li>Body is the actual model ({@link FarModels}: same as nearby entity, same animations, pose from path) while
 * longer than {@link FarModels#MODEL_FROM} pixels onscreen; smaller becomes a circle of silhouette area at real
 * angular size, below {@link Sight#MIN_PIXELS} spot size a proportionally fainter spot ({@link Sight#body}): same
 * sky coverage; smooth transition between. Own colour under skylight, haze in opacity ({@link Sight}).</li>
 * <li>Plume is light ({@link Sight#light}) behind body: daytime spark, nighttime dark-adapted eye glare and veil
 * over kilometres. Missile cruise turbofan and Shahed engine do not glow at night — almost no plume. One plume
 * near and far ({@link Flame} from {@link FlameModel}): entity glare and veil are here too, while light core from
 * 0.8 render distance replaces its jet ({@link #flameCore}); otherwise at render edge where entity appears, far
 * plume would vanish entirely while jet there is fogged and lacks glare. Also lights surrounding smoke and
 * ribbons ({@link FxLights}).</li>
 * <li>Trail is a ribbon through nozzle path points ({@link TrailPoints}), shared near and far from launch to
 * trail end (tapers there): width, density, buoyancy and lifetime follow trail law ({@link TrailModel}) from
 * projectile speed when laying the point and air (world wind, stability); drifts with world wind and persists
 * after projectile passes or explodes. Occupies no particle slots: hundreds of salvo missiles do not tear trails.
 * Light uses world light map at point (outside loaded world, open sky). No trail volume: smoke grid cell
 * (2–32 m) cannot resolve a thin trail. While booster jet strikes ground ({@link LaunchCloud#impinges}), engine
 * products form launch cloud ({@link LaunchCloud}, baked layer billows): those ticks' ribbon points carry only
 * jet column between nozzle and ground ({@link LaunchCloud#column}); after jet leaves ground, column remains as trail.</li>
 * <li>Behind terrain beyond render distance (depth handles nearer occlusion), {@link Sightline} ray every
 * {@link #SIGHT_PERIOD} ticks smoothly fades body and plume.</li>
 * </ul>
 * Frame is O(projectiles + trail points), allocation-free.
 */
public final class FarFlightView {
    /** Terrain ray per projectile every this many ticks. */
    static final int SIGHT_PERIOD = 4;
    /** Terrain occludes source: eye-visible region starts this many blocks above it. */
    static final double HIDDEN = 0.5;
    /** Maximum per-tick visibility change behind terrain. */
    private static final float FADE = 0.25f;
    /**
     * Ribbon tone relative to colour: near smoke billow ({@code fx/particle/smoke_*}) averages 0.76 of its colour
     * due to shading inside its image, while ribbon halo centre is 1. With the same tone, volume billows lie on
     * ribbon as its irregularities, not dark beads ({@code FarFlightViewTest}).
     */
    static final float TONE = 0.76f;
    /** Maximum trail nozzles per flight entity (B-2 has four). */
    static final int MAX_NOZZLES = 4;
    /** Nozzle ribbon-end positions in frame cache (after point positions). */
    private static final int HEAD = TrailPoints.CAPACITY;
    /**
     * Point wind-drift lag, ticks: products leave jet without wind velocity and acquire it over 0.9/(1 − 0.9)
     * ticks (est.) (smoke-particle acceleration time with 0.9 drag per tick; ribbon and its volume move alike).
     */
    static final double LAG = 0.9 / (1 - 0.9);
    /** Air for new points and frame (tick takes it from {@link ClientAir}; unit tests supply their own). */
    static Air air = Air.CALM;
    /** Point light outside loaded world is open sky. */
    private static final Light SKY = (x, y, z) -> LightTexture.FULL_SKY;

    private static final List<Far> FARS = new ArrayList<>();
    private static final Map<FlightTrack, Far> BY_TRACK = new IdentityHashMap<>();
    /** Path by point-owner number ({@link TrailPoints#owner}). */
    private static final Int2ObjectMap<Far> BY_OWNER = new Int2ObjectOpenHashMap<>();
    private static final TrailPoints POINTS = new TrailPoints();
    private static int owners;
    /** Path traversal tick (unseen paths are forgotten). */
    private static long pass;
    /** Projectiles in flight (far: body and plume; near entity: plume light). */
    private static int active;
    @Nullable
    private static ClientLevel level;
    @Nullable
    private static Sightline.Heights heights;

    // ---------------------------------------------------------------- frame: numbers persist between frames
    private static final double[] POS = new double[3], NOZZLE = new double[3], BACK = new double[3], NOSE = new double[3], OUT = new double[2], LIGHT = new double[5],
            AIM = new double[3], BIRTH = new double[2], LOOK = new double[TrailModel.LOOK], BEND = new double[3];
    /** Trail-bend seed (with point-owner number, unique to each trail). */
    private static final long BENDS = 0x3C6EF372FE94F82AL;
    private static final float[] ANGLES = new float[3];
    private static final ProjectilePose POSE = new ProjectilePose();
    private static final BlockPos.MutableBlockPos PROBE = new BlockPos.MutableBlockPos();
    /**
     * Ribbon ends in frame: position (camera-relative), half-width, opacity, colour, world light; ribbon cross
     * vector (shared at segment joints); frame numbers in which end and cross vector were computed.
     */
    private static final int ENDS = HEAD + MAX_NOZZLES;
    private static final double[] EX = new double[ENDS], EY = new double[ENDS], EZ = new double[ENDS], EH = new double[ENDS];
    private static final float[] EA = new float[ENDS], ER = new float[ENDS], EG = new float[ENDS], EB = new float[ENDS];
    private static final float[] NX = new float[ENDS], NY = new float[ENDS], NZ = new float[ENDS];
    private static final int[] EL = new int[ENDS], DRAWN = new int[ENDS], ACROSS = new int[ENDS];
    /** Tube at end: width at half centre opacity and cross-section σ, m; extinction per metre, m; albedo; billow seed. */
    private static final double[] EW = new double[ENDS], ES = new double[ENDS], EE = new double[ENDS], EO = new double[ENDS];
    private static final long[] ESEED = new long[ENDS];
    /** Point's projectile: its trail tube (places in same tube never merge, {@code SmokePuffs.Draw#mergeLevel}). */
    private static final int[] EOWNER = new int[ENDS];
    /** Frame look belongs to segments ({@link #segment}). */
    @Nullable
    private static FarView seen;
    /**
     * Tube wider than this many screen pixels (at half centre opacity) uses billows like clouds
     * ({@link SmokePuffs#tube}), narrower uses ribbon, smooth between. Ribbon is a camera-facing plane with one
     * colour across: tube appearance while cross-section is subpixel; wider becomes straight-edged, unshaded
     * strip (at eye, a plane through frame). Billows are same matter as Gaussians with six-way light: round tube
     * with lit and shaded sides, eye inside gets a veil along sight path. Crosswise light is resolved when
     * cross-section σ is a pixel or more: half-opacity width is 2.355σ, from 2.4 px; from 4.8 px (σ two pixels),
     * billows only. With 6–12 px threshold (F2b), a Grad salvo at 1.7 km (3–11 m tubes, 2–7 px) was all ribbons:
     * twelve overlaid ribbons formed a flat straight-edged strip (VXC4 frames, 22.10).
     */
    static final double PUFFS_FROM = 2.4, PUFFS_FULL = 4.8;
    /**
     * Average maximum tube-billow and eye-veil places per frame (performance, not physics): above this, next
     * frame's place spacing grows ({@link #stride}: ratio of requested places to budget, at least ×1.1); all
     * tubes stay billows, only sparser (⚠ spacing wider than billow disk leaves gaps in faint tubes); below half,
     * spacing smoothly returns (/1.02 per frame). Billow/ribbon shares ignore budget ({@link #line}): formerly
     * width threshold grew; in the 100-strike bench raid, 90–180 px trails became ribbons — bright straight strips
     * across frame (RenderDoc 25.10). A near Grad salvo or binocular tubes would otherwise yield tens of thousands
     * of quads. Each place awaits frame plan ({@code SmokePuffs.flush}: ~0.5 µs CPU); beyond fill budget they cannot
     * be shown separately: 100-strike raid at 6000 took 8 ms CPU for smoke, at 3000, 6.5 ({@code SmokeRaidTest}).
     */
    static final int CLUB_BUDGET = 3000;
    /**
     * Tube-billow place spacing as multiple of law ({@code SmokePuffs.tubeStep}; 1 — law, at most 2^7: one place
     * per segment then), and places requested by tubes this frame.
     */
    private static double stride = 1;
    private static int clubs;
    private static final float[] JOINT = new float[3];
    private static int frame;

    // ---------------------------------------------------------------- last-frame summary (describe)
    /** B-2 slot in weapon counts (its bomb has its own weapon). */
    private static final int B2 = WeaponType.values().length, SAM = B2 + 1;
    private static final int[] COUNT = new int[SAM + 1];
    private static int flights, points;
    @Nullable
    private static Far nearest;
    private static double nearD, nearT, nearBody, nearBodyAlpha, nearFlame, nearFlameAlpha, nearModel;
    private static boolean nearCoarse;
    /** Plumes in frame (including entities); nearest: range, core share, core pixels and opacity, glare, veil. */
    private static int flames;
    @Nullable
    private static Far flameOf;
    private static double flameD, flameShare, flameCorePx, flameCoreAlpha, flameGlarePx, flameVeilPx;

    private FarFlightView() {}

    /** Packed world light at point ({@code LevelRenderer#getLightColor}). */
    @FunctionalInterface
    interface Light {
        int at(double x, double y, double z);
    }

    /** One projectile path: its trail points, terrain visibility. */
    static final class Far {
        final FlightTrack track;
        final ClientAirframe airframe;
        final FarLook look;
        /** Booster thrust over time ({@code Airframe.thrust}); null — steady-burning engines. */
        @Nullable
        final ThrustCurve thrust;
        final WeaponModels.Look model;
        /** Point owner in {@link TrailPoints}. */
        final int owner;
        long seen;
        /** Path tick up to which points have been laid. */
        long cursor = -1;
        /** Current ribbons started (one per nozzle); last points, last-point tick and look. */
        boolean started;
        final long[] chain = new long[MAX_NOZZLES];
        long chainTick;
        @Nullable
        Trail style;
        /** Current ribbons' nozzles; whether from booster. */
        List<At> nozzles = List.of();
        boolean booster;
        /** Unoccluded by terrain: 1 — visible; previous tick, current and target. */
        float visPrev = 1, vis = 1, visTarget = 1;
        /**
         * Unoccluded by blocks within render distance (block ray from eye): depth hides body and plume there, but
         * not glare at eye ({@link FarSprites#light}); beyond render distance — 1.
         */
        float openPrev = 1, open = 1, openTarget = 1;
        boolean sighted;
        /** Model uses simplified detail copies (small onscreen; hysteresis prevents threshold flutter). */
        boolean coarse = true;
        /** Launch cloud: jet struck ground (null — did not); whether it does this path tick (then no ribbon points). */
        @Nullable
        LaunchCloud cloud;
        boolean impinging;
        /** Engine whose jet strikes ground ({@link LaunchCloud#column}). */
        @Nullable
        Motor cloudMotor;
        /**
         * Projectile path length from start to {@link #cursor} tick, m, and bright flame length there (core and
         * afterburning, {@link Flame#flame}; no flame — 0): how far trail points remain in plume
         * ({@link TrailModel#emerged}); in frame, path length up to frame instant.
         */
        double path, flame, framePath;

        Far(FlightTrack track, int owner) {
            this.track = track;
            this.owner = owner;
            // path without weapon is SAM missile
            this.airframe = track.weapon == null ? ClientWeaponSpec.INTERCEPTOR : ClientWeaponSpec.of(track.weapon).airframe(!track.bomber);
            this.look = airframe.far();
            this.thrust = track.weapon == null ? null : track.weapon.spec().airframe().thrust();
            this.model = airframe.model();
            Arrays.fill(chain, TrailPoints.NONE);
        }

        /** Ribbons ended: next point starts new ones. */
        void restart() {
            started = false;
            Arrays.fill(chain, TrailPoints.NONE);
        }
    }

    /** Once per client tick after {@code FlightTracks.tick}: new paths, trail points, terrain rays, forgotten paths. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        if (lv == null) {
            reset();
            return;
        }
        if (lv != level) {
            reset();
            level = lv;
            heights = FarTerrain.of(lv);
        }
        long now = FlightTracks.now();
        air = ClientAir.now();
        pass++;
        for (FlightTrack track : FlightTracks.all()) see(track);
        // SAM missiles: trail and launch cloud follow same law as projectiles
        for (FlightTrack track : FlightTracks.interceptors()) see(track);
        // a launch cloud whose feed ended takes all its heat here, one per tick, not in its budgeted emit
        LaunchCloud.settle(CLOUDS, SmokeGrid.seconds(0));
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double world = mc.options.getEffectiveRenderDistance() * 16.0;
        // world frozen: projectiles stand still, trails neither grow nor age (same-place points unnecessary)
        boolean frozen = frozen();
        active = 0;
        for (int i = FARS.size() - 1; i >= 0; i--) {
            Far f = FARS.get(i);
            if (f.seen != pass) {
                // path forgotten: after explosion trail fades itself; after all-clear (flight ended without explosion) vanishes immediately
                if (!f.track.isDead()) POINTS.kill(f.owner);
                BY_TRACK.remove(f.track);
                BY_OWNER.remove(f.owner);
                FARS.set(i, FARS.getLast());
                FARS.removeLast();
                continue;
            }
            if (frozen) {
                f.cursor = Math.max(f.cursor, Math.min(f.track.lastTick(), now - 1));
            } else {
                sample(f, Math.min(f.track.lastTick(), now - 1), POINTS, FarFlightView::light, FarFlightView::launch);
                close(f, now);
            }
            sight(f, now, eye, world);
            if (!f.track.isDead()) active++;
        }
        if (frozen) POINTS.hold();
        else POINTS.expire(now - 1);
    }

    /** Path exists this tick: its look (new path starts this tick). */
    private static void see(FlightTrack track) {
        Far f = BY_TRACK.get(track);
        if (f == null) {
            f = new Far(track, ++owners);
            BY_TRACK.put(track, f);
            BY_OWNER.put(f.owner, f);
            FARS.add(f);
        }
        f.seen = pass;
    }

    /** Path projectile name in scenario line. */
    private static String name(FlightTrack track) {
        return track.weapon == null ? "sam" : track.bomber ? "b2" : track.weapon.getSerializedName();
    }

    /** World frozen ({@code /tick freeze}): entities and particles stand still. */
    private static boolean frozen() {
        ClientLevel lv = level;
        return lv != null && !lv.tickRateManager().runsNormally();
    }

    /** Trail points without world light (unit tests): open sky. */
    static void sample(Far f, long horizon, TrailPoints points) {
        sample(f, horizon, points, SKY, null);
    }

    /** Launch cloud at path tick t (null in unit tests: no world). */
    @FunctionalInterface
    interface Launch {
        void at(Far f, long t, ClientWeaponSpec.Stage stage);
    }

    /**
     * Trail points along path through horizon tick inclusive (this tick's packet may still correct later path),
     * identically for entity and server packets: each nozzle of its phase ({@link ClientAirframe#trailNozzles})
     * every trail-look step. Look or nozzles changed (booster burnt out): ribbons end with points at change tick,
     * new ones start there too; path discontinuity (history restarted) breaks ribbons.
     */
    static void sample(Far f, long horizon, TrailPoints points, Light light, @Nullable Launch launch) {
        FlightTrack track = f.track;
        long t = f.cursor + 1, start = (long) Math.ceil(track.start());
        if (start > t) {
            t = start;
            f.restart();
        }
        for (; t <= horizon; t++) {
            FlightPhase phase = FlightPhase.byId(track.phase(t));
            ClientWeaponSpec.Stage stage = f.look.stage(phase, track.phaseAge(t), f.thrust);
            // projectile path and plume this tick: how far trail points remain in fire
            if (track.covers(t)) {
                track.at(t, POS);
                if (t - 1 >= track.start() && track.covers(t - 1)) {
                    track.at(t - 1, NOZZLE);
                    f.path += Math.sqrt(sq(POS[0] - NOZZLE[0]) + sq(POS[1] - NOZZLE[1]) + sq(POS[2] - NOZZLE[2]));
                }
                Flame flame = stage.flame(POS[1]);
                f.flame = flame == null ? 0 : flame.flame();
            }
            f.impinging = false;
            // launch cloud only during launch (booster burning): cruise jet at a slope is not launch
            if (launch != null && stage.motor() != null && phase.boosterLit()) launch.at(f, t, stage);
            Trail style = laying(stage.trail(), f.style, f.path);
            boolean booster = phase.boosterLit() && f.airframe.boosterNozzle() != null;
            if (style != f.style || style != null && booster != f.booster) {
                if (f.style != null && f.started) {
                    if (t > f.chainTick) put(f, points, t, f.style, light);
                    finish(f, points);
                }
                f.style = style;
                f.booster = booster;
                f.nozzles = style == null ? List.of() : f.airframe.trailNozzles(phase.boosterLit());
                f.restart();
            }
            if (style != null && (!f.started || t - f.chainTick >= style.step())) put(f, points, t, style, light);
        }
        f.cursor = Math.max(f.cursor, horizon);
    }

    /**
     * Trail laid by projectile at path length path from start: phase trail (phase), or if previous (laid) follows
     * real burn path ({@link Trail#reach}), retain it while path is shorter than that section (at game speed plume
     * phase ends earlier — smoke without fire afterward, not physical); at real speed, to section end, no farther.
     */
    static Trail laying(@Nullable Trail phase, @Nullable Trail laid, double path) {
        if (phase == null && laid != null && laid.reach() > 0) phase = laid;
        return phase != null && phase.reach() > 0 && path >= phase.reach() ? null : phase;
    }

    /**
     * Path ended (projectile exploded or left view): ribbons reach its last point and taper there, otherwise
     * trail end would jump back a point step (B-2: 48 blocks). If path resumes, ribbons resume too.
     */
    private static void close(Far f, long now) {
        FlightTrack track = f.track;
        long last = track.lastTick();
        if (!f.started || f.style == null || track.covers(now - 1) || last > f.cursor) return;
        if (last > f.chainTick) put(f, POINTS, last, f.style, FarFlightView::light);
        finish(f, POINTS);
    }

    /** Current ribbons end at their last points. */
    private static void finish(Far f, TrailPoints points) {
        for (long c : f.chain) points.finish(c);
    }

    /**
     * Launch cloud at path tick t (booster burning): phase engine jet hits ground before its Morton length;
     * products per tick enter cloud ({@link LaunchCloud#feed}), that tick's ribbon points belong to it
     * ({@code TrailPoints.cloud}). Ground from server chunk in packets ({@link FlightTrack#launchGround}: client
     * lacks distant chunk and not everyone has LOD terrain), otherwise client chunk height map, outside chunks
     * far-view terrain; unknown — no cloud. Nozzle from phase (booster or cruise), jet backward along axis.
     */
    private static void launch(Far f, long t, ClientWeaponSpec.Stage stage) {
        ClientLevel lv = level;
        if (lv == null || !f.track.covers(t)) return;
        f.track.at(t, NOZZLE);
        f.track.offset(t, f.airframe.nozzle(stage), NOZZLE);
        // ground from server chunk (launcher on level ground, same under nozzle), otherwise own terrain
        double ground = f.track.launchGround();
        if (Double.isNaN(ground)) ground = ground(lv, NOZZLE[0], NOZZLE[2]);
        if (Double.isNaN(ground)) return;
        Vec3 fwd = f.track.forward(t);
        // flow at current thrust share (ramp-up, decay to separation)
        Motor motor = stage.flow();
        if (motor == null || !LaunchCloud.impinges(motor, NOZZLE[1] - ground, -fwd.y)) return;
        if (f.cloud == null || f.cloud.ended()) {
            // top block under nozzle: soil gives looseness and colour; non-soil (concrete, smooth stone, mod blocks) — no dust
            BlockPos at = BlockPos.containing(NOZZLE[0], ground - 0.5, NOZZLE[2]);
            GroundMaterial known = GroundMaterial.of(lv.getBlockState(at));
            GroundMaterial mat = known != null ? known : GroundMaterial.STONE;
            Vec3 pos = new Vec3(NOZZLE[0], ground, NOZZLE[2]);
            // grid clock at path tick t: path lags client clock by (now − t) ticks
            double seconds = SmokeGrid.seconds(0) - Math.max(0, FlightTracks.now() - t) / 20.0;
            f.cloud = new LaunchCloud(FarBlasts.air(lv, pos), ground, mat.porosity, mat.r, mat.g, mat.b, seconds);
            CLOUDS.removeIf(LaunchCloud::ended);
            CLOUDS.add(f.cloud);
            SmokeGrid.add(f.cloud);
        }
        // feed instant is path tick t on grid clock (like cloud start): drives puff impulse history
        double at = SmokeGrid.seconds(0) - Math.max(0, FlightTracks.now() - t) / 20.0;
        f.impinging = f.cloud.feed(motor, 1 / 20.0, at, NOZZLE[0], NOZZLE[1], NOZZLE[2], -fwd.x, -fwd.y, -fwd.z);
        f.cloudMotor = motor;
    }

    /** Launch clouds (remove on reset). */
    private static final List<LaunchCloud> CLOUDS = new ArrayList<>();

    /** Ground height (first air) under (x, z): client chunk, otherwise map tiles ({@link FarTerrain}); NaN — unknown. */
    private static double ground(ClientLevel lv, double x, double z) {
        return heights == null ? Double.NaN : heights.at(Mth.floor(x), Mth.floor(z));
    }

    /**
     * Ribbon points at all nozzles at tick t: trail-law values from projectile speed this tick and air. Invisible
     * point (trail invisible at this speed and height) breaks ribbon: next visible point starts a new one.
     */
    private static void put(Far f, TrailPoints points, long t, Trail style, Light light) {
        // projectile speed is path length last tick (same for entity and packets); no path up to t — velocity
        // from entity or packet
        double speed;
        if (t - 1 >= f.track.start() && f.track.covers(t - 1)) {
            f.track.at(t, POS);
            f.track.at(t - 1, NOZZLE);
            double dx = POS[0] - NOZZLE[0], dy = POS[1] - NOZZLE[1], dz = POS[2] - NOZZLE[2];
            speed = Math.sqrt(dx * dx + dy * dy + dz * dz) * 20;
        } else {
            speed = f.track.velocity(t).length() * 20;
        }
        for (int k = 0, n = Math.min(MAX_NOZZLES, f.nozzles.size()); k < n; k++) {
            f.track.at(t, POS);
            f.track.offset(t, f.nozzles.get(k), POS);
            double u = air.speed(POS[1]), inv = style.inverseSpeed(speed, u);
            if (TrailModel.laid(speed, u)) TrailModel.birth(style, inv, POS[1], u, air, BIRTH);
            else BIRTH[0] = 0;
            if (BIRTH[0] <= 0) {
                points.finish(f.chain[k]);
                f.chain[k] = TrailPoints.NONE;
                continue;
            }
            double brunt = air.brunt();
            f.chain[k] = points.add(POS[0], POS[1], POS[2], t, style, light.at(POS[0], POS[1], POS[2]), f.owner, f.chain[k], (float) inv,
                    (float) (BIRTH[0] * 20), (float) (BIRTH[1] * 20), (float) u, (float) TrailModel.excess(POS[1], air),
                    (float) TrailModel.riseScale(style, inv, brunt), (float) brunt, (float) air.stability());
            // jet strikes ground: point matter belongs to launch cloud, ribbon only carries nozzle jet column
            int at = TrailPoints.index(f.chain[k]);
            points.cloud[at] = f.impinging ? f.cloud : null;
            points.column[at] = f.impinging && f.cloudMotor != null ? (float) LaunchCloud.column(f.cloudMotor, 0, inv) : 0;
            points.path[TrailPoints.index(f.chain[k])] = f.path;
            ClientLevel lv = level;
            double under = lv == null ? Double.NaN : ground(lv, POS[0], POS[2]);
            if (!Double.isNaN(under)) points.clearance[at] = (float) Math.max(0, POS[1] - under);
        }
        f.chainTick = t;
        f.started = true;
    }

    /**
     * World light at point: client has chunk — like a particle; otherwise open sky. Launch nozzle point may lie
     * in top ground block (ICBM nozzle on launcher one block below its top): inside solid block there is no
     * light, making column base nearly black — take light above it.
     */
    private static int light(double x, double y, double z) {
        ClientLevel lv = level;
        if (lv == null) return LightTexture.FULL_SKY;
        PROBE.set(x, y, z);
        if (!lv.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(PROBE.getX()), SectionPos.blockToSectionCoord(PROBE.getZ()))) {
            return LightTexture.FULL_SKY;
        }
        for (int k = 0; k < 2 && lv.getBlockState(PROBE).isSolidRender(lv, PROBE); k++) PROBE.move(Direction.UP);
        return LevelRenderer.getLightColor(lv, PROBE);
    }

    /**
     * Projectile occlusion: ray every {@link #SIGHT_PERIOD} ticks, terrain beyond render distance, blocks within
     * (for glare); smooth visibility.
     */
    private static void sight(Far f, long now, Vec3 eye, double world) {
        f.visPrev = f.vis;
        f.openPrev = f.open;
        FlightTrack track = f.track;
        if (track.isDead()) return;
        // entity needs ray only for plume glare: no fire in this phase — skip it
        if (!track.fromServer() && !hasFlame(f, now - 1)) {
            f.sighted = false;
            return;
        }
        if (f.sighted && (now + f.owner) % SIGHT_PERIOD != 0 || !track.predict(now - 1, POS)) {
            f.vis += Mth.clamp(f.visTarget - f.vis, -FADE, FADE);
            f.open += Mth.clamp(f.openTarget - f.open, -FADE, FADE);
            return;
        }
        double dx = POS[0] - eye.x, dz = POS[2] - eye.z;
        boolean inWorld = dx * dx + dz * dz <= world * world;
        boolean hidden = heights != null && !inWorld && Sightline.trace(heights, POS[0], POS[1], POS[2], eye.x, eye.y, eye.z).hidden() > HIDDEN;
        f.visTarget = hidden ? 0 : 1;
        f.openTarget = inWorld && blocked(eye, POS[0], POS[1], POS[2]) ? 0 : 1;
        if (!f.sighted) {
            f.vis = f.visPrev = f.visTarget;
            f.open = f.openPrev = f.openTarget;
        }
        f.sighted = true;
        f.vis += Mth.clamp(f.visTarget - f.vis, -FADE, FADE);
        f.open += Mth.clamp(f.openTarget - f.open, -FADE, FADE);
    }

    /** Whether projectile has visible burning plume at tick t (at its altitude). */
    private static boolean hasFlame(Far f, long t) {
        f.track.at(t, POS);
        return f.look.stage(FlightPhase.byId(f.track.phase(t)), f.track.phaseAge(t), f.thrust).flame(POS[1]) != null;
    }

    /** Client-world blocks occlude point from eye (ray to first block with a visual shape). */
    static boolean blocked(Vec3 eye, double x, double y, double z) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.clip(new ClipContext(eye, new Vec3(x, y, z), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
                CollisionContext.empty())).getType() != HitResult.Type.MISS;
    }

    /**
     * Body position of projectile {@code id} at tick fraction {@code partial}, where far view draws it
     * ({@link #collect}: path, not latest packet); false — no path or no data for this instant.
     */
    public static boolean drawnAt(java.util.UUID id, float partial, double[] out) {
        FlightTrack track = FlightTracks.get(id);
        if (track == null) return false;
        double t = frameTime(partial);
        return !(track.isDead() && t >= track.deathTick()) && track.predict(t, out);
    }

    /** Frame time for paths: path tick plus fraction (as in {@link #collect}). */
    private static double frameTime(float partial) {
        return FlightTracks.now() - 1 + (frozen() ? 0 : partial);
    }

    /** Collect trails, bodies and plumes for frame; plumes also light frame smoke and ribbons (lights). */
    static void collect(FarView view, FarSprites out, FxLights lights) {
        lit = 0;
        litEye = 0;
        flights = 0;
        points = 0;
        nearest = null;
        flames = 0;
        flameOf = null;
        Arrays.fill(COUNT, 0);
        double t = frameTime(view.partial());
        Vec3 cam = view.camera();
        // each projectile's path to frame instant: how far its points remain in plume
        for (int k = 0, n = FARS.size(); k < n; k++) {
            Far f = FARS.get(k);
            f.framePath = f.path;
            if (f.flame > 0 && f.track.covers((long) Math.floor(t)) && f.track.predict(t, POS) && f.track.covers(f.cursor)) {
                f.track.at(f.cursor, NOZZLE);
                f.framePath += Math.sqrt(sq(POS[0] - NOZZLE[0]) + sq(POS[1] - NOZZLE[1]) + sq(POS[2] - NOZZLE[2]));
            }
        }
        collectTrails(view, out, t, POINTS, air);
        for (int k = 0, n = FARS.size(); k < n; k++) {
            Far f = FARS.get(k);
            FlightTrack track = f.track;
            if (track.isDead() && t >= track.deathTick() || track.drilling() || !track.predict(t, POS)) continue;
            ClientWeaponSpec.Stage stage = f.look.stage(FlightPhase.byId(track.phase(t)), track.phaseAge(t), f.thrust);
            // trail along real burn path continues after plume phase — ribbon still reaches nozzle
            Trail trail = laying(stage.trail(), f.style, f.path);
            if (trail != null && trail == f.style && f.started) {
                // ribbons reach nozzles: not yet visible there
                for (int j = 0, m = Math.min(MAX_NOZZLES, f.nozzles.size()); j < m; j++) {
                    long c = f.chain[j];
                    if (!POINTS.holds(c) || DRAWN[TrailPoints.index(c)] != frame || POINTS.last[TrailPoints.index(c)]) continue;
                    System.arraycopy(POS, 0, NOZZLE, 0, 3);
                    track.offset(t, f.nozzles.get(j), NOZZLE);
                    int ci = TrailPoints.index(c);
                    // trail matter at nozzle is still plume (projectile without plume: already smoke)
                    TrailModel.look(trail, 0, POINTS.inverse[ci], POINTS.ice[ci] / 20, POINTS.wind[ci], POINTS.excess[ci], POINTS.rise[ci],
                            POINTS.brunt[ci], POINTS.stability[ci], TrailModel.emerged(0, f.flame), LOOK);
                    end(LOOK, NOZZLE[0] - cam.x, NOZZLE[1] - cam.y, NOZZLE[2] - cam.z, POINTS.light[ci], view, HEAD + j);
                    segment(out, TrailPoints.index(c), HEAD + j, trail);
                }
            }
            // entity present: its model draws body, PlumeRenderer plume jet; plume light here
            boolean entity = !track.fromServer();
            double dx = POS[0] - cam.x, dy = POS[1] - cam.y, dz = POS[2] - cam.z;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double tr = view.air(d, dy);
            float vis = Mth.lerp(view.partial(), f.visPrev, f.vis);
            // own engine invisible onboard: camera entity not drawn, no jet — no light either
            Flame flame = entity && onboard(track) ? null : stage.flame(POS[1]);
            double flamePx = 0, flameAlpha = 0;
            if (flame != null && vis > 0) {
                flameAt(track, t, -f.airframe.nozzle(stage).z() + flame.middle(), dx, dy, dz, OUT);
                // core share from body range, like entity renderer jet: together exactly one plume
                double bd = OUT[0], ft = view.air(bd, BACK[1]), core = flameCore(entity, view, d);
                int fc = flame.lightColor();
                float open = Mth.lerp(view.partial(), f.openPrev, f.open);
                double seen = flame.brightness() * view.exposure();
                out.light(BACK[0], BACK[1], BACK[2], flame.lightRadius(), seen * ft, ft, view.pixel(),
                        channel(fc, 0), channel(fc, 1), channel(fc, 2), vis * core, vis * open, LIGHT);
                // plume lights surrounding smoke and ribbons like a fireball, whether eye sees it or not
                lights.add(BACK[0], BACK[1], BACK[2], flame.lightRadius(), seen, Math.min(1, seen), channel(fc, 0), channel(fc, 1), channel(fc, 2));
                // also world and eye (HeatPass): no air attenuation to eye — pass computes it
                lit(BACK[0], BACK[1], BACK[2], -NOSE[0], -NOSE[1], -NOSE[2], flame, view.camera(), track.phaseAge(t) / 20,
                        flown(track, t, track.phaseAge(t)));
                flamePx = 2 * LIGHT[0] / (bd * view.pixel());
                flameAlpha = Math.min(1, LIGHT[2]) * vis * core;
                flames++;
                if (flameOf == null || bd < flameD) {
                    flameOf = f;
                    flameD = bd;
                    flameShare = core;
                    flameCorePx = flamePx;
                    flameCoreAlpha = flameAlpha;
                    flameGlarePx = 2 * LIGHT[3] / (bd * view.pixel());
                    flameVeilPx = 2 * LIGHT[4] / (bd * view.pixel());
                }
            }
            // entity body drawn by its model while within its own model render range
            if (entity && d < FarView.entityReach(view.far())) continue;
            double length = f.look.size() / (d * view.pixel());
            double model = modelShare(length);
            if (model > 0 && vis > 0 && inFrame(view, dx, dy, dz, d, FarModels.TILE_RADIUS * f.look.size())) {
                f.coarse = f.coarse ? length < FarModels.FULL_ABOVE : length < FarModels.COARSE_BELOW;
                pose(track, t, f.coarse);
                if (!out.model(f.model, POSE, dx, dy, dz, f.look.size(), view.pixel(), view.up(), (float) (model * vis * tr))) model = 0;
            } else {
                model = 0;
            }
            body(f.look.area(), d, view.pixel(), OUT);
            int c = f.look.color();
            float a = (float) (OUT[1] * vis * tr * (1 - model)), lit = view.ambient();
            out.disc(dx, dy, dz, OUT[0], channel(c, 0) * lit, channel(c, 1) * lit, channel(c, 2) * lit, a);
            double bodyPx = 2 * OUT[0] / (d * view.pixel());
            flights++;
            // SAM also has distant path: sustain trail continues beyond entity range.
            COUNT[track.weapon == null ? SAM : track.bomber ? B2 : track.weapon.ordinal()]++;
            if (nearest == null || d < nearD) {
                nearest = f;
                nearD = d;
                nearT = tr;
                nearBody = bodyPx;
                nearBodyAlpha = a;
                nearFlame = flamePx;
                nearFlameAlpha = flameAlpha;
                nearModel = model > 0 ? length : 0;
                nearCoarse = f.coarse;
            }
        }
    }

    /** Full trail-point pass into frame quads; without OpenGL, same entry used by screen check. */
    static void collectTrails(FarView view, SmokePuffs.Out out, double t, TrailPoints trail, Air air) {
        frame++;
        seen = view;
        points = 0;
        Vec3 cam = view.camera();
        for (long s = trail.first(), end = trail.end(); s < end; s++) {
            int i = TrailPoints.index(s);
            Trail style = trail.style[i];
            if (style == null) continue;
            double age = Math.max(0, t - trail.birth[i]);
            if (age >= trail.life[i]) continue;
            // point still in plume — its matter glows in it
            Far owner = BY_OWNER.get(trail.owner[i]);
            double smoke = owner == null || owner.track.isDead() ? 1 : TrailModel.emerged(owner.framePath - trail.path[i], owner.flame);
            // point in launch cloud: cloud draws its matter (baked billows); while jet strikes ground, nozzle-to-ground
            // column is jet itself (from current nozzle position); once jet leaves ground, column stays as it was
            LaunchCloud cloud = trail.cloud[i];
            if (cloud != null && owner != null && owner.cloud == cloud && owner.impinging && owner.cloudMotor != null)
                trail.column[i] = (float) LaunchCloud.column(owner.cloudMotor, owner.framePath - trail.path[i], trail.inverse[i]);
            double mass = cloud == null ? 1 : trail.column[i];
            TrailModel.look(style, age / 20, trail.inverse[i], trail.ice[i] / 20, trail.wind[i], trail.excess[i], trail.rise[i],
                    trail.brunt[i], trail.stability[i], mass * smoke, LOOK);
            double drift = trail.wind[i] * Math.max(0, age - LAG) / 20;
            // vortices larger than trail bend its axis: averaged Briggs plume is trail spreading plus axis wandering;
            // launch-cloud point stays in its cloud (wind drifts it, trail vortices do not bend it)
            if (cloud != null) BEND[0] = BEND[1] = BEND[2] = 0;
            else bend(trail, i, style, age);
            end(LOOK, trail.x[i] + air.wind().x() * drift + BEND[0] - cam.x, trail.y[i] + LOOK[2] + BEND[1] - cam.y,
                    trail.z[i] + air.wind().z() * drift + BEND[2] - cam.z, trail.light[i], view, i);
            // billow seed uses point record number: birth tick shifts during world freeze (TrailPoints.hold), causing flicker
            ESEED[i] = s * 0x2545F4914F6CDD1DL + 0x632BE59BD9B4E019L;
            EOWNER[i] = trail.owner[i];
            if (trail.last[i]) {
                EA[i] = 0;
                EE[i] = 0;
            }
            points++;
            long p = trail.prev[i];
            if (trail.holds(p) && DRAWN[TrailPoints.index(p)] == frame) segment(out, TrailPoints.index(p), i, style);
        }
    }

    /** Camera onboard this projectile (first-person view). */
    private static boolean onboard(FlightTrack track) {
        Minecraft mc = Minecraft.getInstance();
        Entity camera = mc.getCameraEntity();
        return camera != null && mc.options.getCameraType().isFirstPerson() && camera.getUUID().equals(track.id);
    }

    /** Model pose at t from path (position already in {@link #POS}). */
    private static void pose(FlightTrack track, double t, boolean coarse) {
        ProjectilePose p = POSE;
        p.x = POS[0];
        p.y = POS[1];
        p.z = POS[2];
        track.angles(t, ANGLES);
        p.yaw = ANGLES[0];
        p.pitch = ANGLES[1];
        p.roll = ANGLES[2];
        p.phase = FlightPhase.byId(track.phase(t));
        p.phaseAge = (float) track.phaseAge(t);
        p.age = (float) track.age(t);
        track.aim(t, AIM);
        p.aimX = AIM[0];
        p.aimY = AIM[1];
        p.aimZ = AIM[2];
        p.coarse = coarse;
    }

    /**
     * Frame plumes for world light ({@link #heat}): eye-relative centre, jet axis, centre height above ground,
     * axial path from nozzle exit to ground, seconds burning, straight-line projectile distance from ignition;
     * plume itself.
     */
    private static final int LIT_MAX = 16, LIT_STRIDE = 10;
    private static final double[] LIT = new double[LIT_MAX * LIT_STRIDE], LIT_W = new double[LIT_MAX];
    private static final Flame[] LIT_FLAME = new Flame[LIT_MAX];
    private static int lit;
    /** Light of all frame plumes at eye. */
    private static double litEye;

    /**
     * Straight-line projectile distance from where current flight phase burned age ticks ago to position at t,
     * blocks; position absent from path memory — infinity (hot jet gas then no shorter than its law).
     */
    private static double flown(FlightTrack track, double t, double age) {
        double from = t - age;
        if (from < track.start() || !track.covers(from) || !track.predict(t, FLOWN_AT)) return Double.POSITIVE_INFINITY;
        double x = FLOWN_AT[0], y = FLOWN_AT[1], z = FLOWN_AT[2];
        track.at(from, FLOWN_AT);
        return Math.sqrt((x - FLOWN_AT[0]) * (x - FLOWN_AT[0]) + (y - FLOWN_AT[1]) * (y - FLOWN_AT[1]) + (z - FLOWN_AT[2]) * (z - FLOWN_AT[2]));
    }

    private static final double[] FLOWN_AT = new double[3];

    /**
     * Plume flame centred at (x, y, z) relative to eye cam, with nozzle jet axis (jx, jy, jz), enters frame world
     * light; burning burn seconds, projectile travelled flown blocks since ignition.
     */
    static void lit(double x, double y, double z, double jx, double jy, double jz, Flame flame, Vec3 cam, double burn, double flown) {
        double lr = flame.lightRadius(), w = flame.brightness() * lr * lr / Math.max(x * x + y * y + z * z, lr * lr);
        // eye gets all plumes; world gets most noticeable at eye (pass takes four)
        litEye += w;
        int slot = lit;
        if (lit >= LIT_MAX) {
            slot = 0;
            for (int i = 1; i < LIT_MAX; i++) if (LIT_W[i] < LIT_W[slot]) slot = i;
            if (w <= LIT_W[slot]) return;
        } else {
            lit++;
        }
        LIT_W[slot] = w;
        ClientLevel lv = level;
        double ground = lv == null ? Double.NaN : ground(lv, x + cam.x, z + cam.z);
        int o = LIT_STRIDE * slot;
        LIT[o] = x;
        LIT[o + 1] = y;
        LIT[o + 2] = z;
        LIT[o + 3] = jx;
        LIT[o + 4] = jy;
        LIT[o + 5] = jz;
        LIT[o + 6] = Double.isNaN(ground) ? lr : Math.max(lr, y + cam.y - ground);
        // where jet axis enters ground: first block below nozzle exit that holds jet (like MOTION_BLOCKING height map:
        // solid or fluid), from blocks down from nozzle, not height map: under canopy or roof it is above nozzle
        double mid = flame.middle(), nx = x - jx * mid + cam.x, ny = y - jy * mid + cam.y, nz = z - jz * mid + cam.z;
        double under = lv == null || jy > -1e-6 ? Double.NaN : floorBelow(lv, nx, ny, nz);
        // deeper uses height map at nozzle (hundreds-of-metres hot-gas jet directed downward even from altitude;
        // ⚠ ground under nozzle, not where inclined axis meets it)
        if (Double.isNaN(under) && lv != null && jy <= -1e-6) under = ground(lv, nx, nz);
        LIT[o + 7] = Double.isNaN(under) ? Double.POSITIVE_INFINITY : Math.max(0, ny - under) / -jy;
        LIT[o + 8] = burn;
        LIT[o + 9] = flown;
        LIT_FLAME[slot] = flame;
    }

    /** Blocks to search down from nozzle for ground under jet. */
    static final int FLOOR_DEPTH = 64;
    private static final BlockPos.MutableBlockPos FLOOR = new BlockPos.MutableBlockPos();

    /**
     * Top of first world block under (x, y, z) that holds jet (solid or fluid); NaN — none within
     * {@link #FLOOR_DEPTH} blocks or no chunk. Nozzle block itself excluded: on ignition nozzle may lie in launcher block.
     */
    static double floorBelow(ClientLevel lv, double x, double y, double z) {
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        if (lv.getChunkSource().getChunkNow(bx >> 4, bz >> 4) == null) return Double.NaN;
        for (int k = 1; k <= FLOOR_DEPTH && by - k >= lv.getMinBuildHeight(); k++) {
            BlockState st = lv.getBlockState(FLOOR.set(bx, by - k, bz));
            if (Heightmap.Types.MOTION_BLOCKING.isOpaque().test(st)) return by - k + 1;
        }
        return Double.NaN;
    }

    /**
     * Frame plume light on world ({@link HeatPass.Frame#jet}: nozzle-to-Draper-point jet parts) and at next frame's
     * eye ({@link #flamesAtEye}), hot jet gas as shimmer ({@link HeatPass.Frame#exhaust}); air air, 1/block.
     */
    static void heat(HeatPass.Frame heat, double air) {
        for (int i = 0; i < lit; i++) {
            int o = LIT_STRIDE * i;
            // plumes already entered this frame's eye through previous frame ({@link #flamesAtEye})
            heat.jet(LIT[o], LIT[o + 1], LIT[o + 2], LIT[o + 3], LIT[o + 4], LIT[o + 5], LIT_FLAME[i], air, LIT[o + 6], LIT[o + 7]);
            heat.exhaust(LIT[o], LIT[o + 1], LIT[o + 2], LIT[o + 3], LIT[o + 4], LIT[o + 5], LIT_FLAME[i], LIT[o + 7], LIT[o + 8], LIT[o + 9]);
            LIT_FLAME[i] = null;
        }
        flamesAtEye = litEye;
        lit = 0;
        litEye = 0;
    }

    /** Plume light at eye in last collected frame (linear, white wall at noon — 1). */
    private static double flamesAtEye;

    static double flamesAtEye() {
        return flamesAtEye;
    }

    /** Sphere radius r centred at (dx, dy, dz), distance d, at least partly in frame (cone to screen corner). */
    static boolean inFrame(FarView view, double dx, double dy, double dz, double d, double r) {
        double angle = view.edge() + Math.asin(Math.min(1, r / d));
        if (angle >= Math.PI) return true;
        Vector3f f = view.forward();
        return (dx * f.x() + dy * f.y() + dz * f.z()) / d >= Math.cos(angle);
    }

    /**
     * Plume light-core share at distance d: packet path uses all (no jet), entity uses from 0.8 render distance
     * ({@link FarView#farShare}); nearer, core is its jet ({@code PlumeRenderer} fades by same share). Glare and
     * veil at any range: exactly one plume at render edge.
     */
    static double flameCore(boolean entity, FarView view, double d) {
        return entity ? view.farShare(d) : 1;
    }

    /**
     * Plume centre back blocks behind body along nose axis ({@link #BACK}, camera-relative); out[0] its range.
     * Axis follows path yaw and pitch, like nearby model and jet ({@code ProjectilePose#rotation}), not motion:
     * on ignition projectile still stands, so light would lie in body centre.
     */
    private static void flameAt(FlightTrack track, double t, double back, double dx, double dy, double dz, double[] out) {
        track.angles(t, ANGLES);
        double yaw = Math.toRadians(ANGLES[0]), pitch = Math.toRadians(ANGLES[1]);
        double nx = -Math.sin(yaw) * Math.cos(pitch), ny = -Math.sin(pitch), nz = Math.cos(yaw) * Math.cos(pitch);
        NOSE[0] = nx;
        NOSE[1] = ny;
        NOSE[2] = nz;
        BACK[0] = dx - nx * back;
        BACK[1] = dy - ny * back;
        BACK[2] = dz - nz * back;
        out[0] = Math.max(1e-3, Math.sqrt(BACK[0] * BACK[0] + BACK[1] * BACK[1] + BACK[2] * BACK[2]));
    }

    /**
     * Ribbon end into frame cache slot: position (camera-relative), half-width with pixel floor, opacity with
     * haze, colour, world light.
     */
    private static void end(double[] look, double dx, double dy, double dz, int light, FarView view, int slot) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double t = view.air(d, dy);
        ribbon(look[0], d, view.pixel(), OUT);
        float grey = (float) FlameModel.srgb(look[3]) * TONE;
        EX[slot] = dx;
        EY[slot] = dy;
        EZ[slot] = dz;
        EH[slot] = OUT[0];
        EA[slot] = (float) (look[1] * OUT[1] * t);
        ER[slot] = grey;
        EG[slot] = grey;
        EB[slot] = grey;
        EL[slot] = light;
        EW[slot] = look[0];
        ES[slot] = look[4];
        EE[slot] = look[5];
        EO[slot] = look[6];
        DRAWN[slot] = frame;
    }

    /**
     * Trail axis bend at point i aged age ticks into {@link #BEND}: interpolate whole-age bends computed once
     * per tick by point ({@link TrailPoints#bend}). {@link TrailModel#wander} is point's most expensive frame
     * calculation, while axis moves fractions of a metre per tick (TrailModelTest.bendsMoveSmoothly).
     */
    private static void bend(TrailPoints trail, int i, Trail style, double age) {
        int k = (int) Math.floor(age), o = 6 * i;
        float[] b = trail.bend;
        if (trail.bendAge[i] != k) {
            if (trail.bendAge[i] == k - 1) {
                System.arraycopy(b, o + 3, b, o, 3);
            } else {
                wanderAt(trail, i, style, k);
                b[o] = (float) BEND[0];
                b[o + 1] = (float) BEND[1];
                b[o + 2] = (float) BEND[2];
            }
            wanderAt(trail, i, style, k + 1);
            b[o + 3] = (float) BEND[0];
            b[o + 4] = (float) BEND[1];
            b[o + 5] = (float) BEND[2];
            trail.bendAge[i] = k;
        }
        double f = age - k;
        BEND[0] = b[o] + (b[o + 3] - b[o]) * f;
        BEND[1] = b[o + 1] + (b[o + 4] - b[o + 1]) * f;
        BEND[2] = b[o + 2] + (b[o + 5] - b[o + 2]) * f;
    }

    /** Point i's {@link TrailModel#wander} at age ticks into {@link #BEND}. */
    private static void wanderAt(TrailPoints trail, int i, Trail style, int age) {
        TrailModel.wander(BENDS ^ trail.owner[i] * 0x9E3779B97F4A7C15L, trail.path[i], age / 20.0, trail.stability[i], trail.wind[i],
                spacing(trail, i, style), trail.clearance[i], BEND);
    }

    /** Ribbon point spacing at point i, m: to previous point in same ribbon; absent — from laying speed. */
    private static double spacing(TrailPoints trail, int i, Trail style) {
        long p = trail.prev[i];
        if (!trail.holds(p)) return style.step() / (20.0 * trail.inverse[i]);
        int j = TrailPoints.index(p);
        return Math.sqrt(sq(trail.x[i] - trail.x[j]) + sq(trail.y[i] - trail.y[j]) + sq(trail.z[i] - trail.z[j]));
    }

    /** Trail tube segment ends ({@link #segment}). */
    private static final Tube TA = new Tube(), TB = new Tube();

    /**
     * Ribbon segment between frame-cache ends a and b; colour midway between theirs. Cross vector at a matches
     * previous segment in ribbon (gapless bend joint); b's is saved for next segment.
     */
    private static void segment(SmokePuffs.Out out, int a, int b, Trail style) {
        FarView view = seen;
        if (view == null) return;
        TA.set(EX[a], EY[a], EZ[a], ES[a], EE[a], EW[a], EH[a], EA[a], EL[a]);
        TB.set(EX[b], EY[b], EZ[b], ES[b], EE[b], EW[b], EH[b], EA[b], EL[b]);
        JOINT[0] = ACROSS[a] == frame ? NX[a] : Float.NaN;
        JOINT[1] = NY[a];
        JOINT[2] = NZ[a];
        double albedo = 0.5 * (EO[a] + EO[b]);
        float c = SmokePuffs.tubeColour(albedo);
        int drawn = line(view, out, TA, TB, (ER[a] + ER[b]) * 0.5f, (EG[a] + EG[b]) * 0.5f, (EB[a] + EB[b]) * 0.5f, c, c, c,
                SmokePuffs.tubeShade(albedo), style.sigma0(), ESEED[a], EOWNER[a], JOINT);
        if (drawn == CLUBS) ACROSS[b] = 0;
        if (drawn != RIBBON) return;
        NX[b] = JOINT[0];
        NY[b] = JOINT[1];
        NZ[b] = JOINT[2];
        ACROSS[b] = frame;
    }

    /**
     * Smoke tube segment end (projectile trail, fire plume), camera-relative: cross-section σ, m; extinction per
     * metre, m; half-centre-opacity width, m; ribbon half-width and opacity (pixel floor and haze included), world light.
     */
    static final class Tube {
        double x, y, z, sigma, e, width, half;
        float alpha;
        int light;
        /** Opacity fraction drawn as smoke (fire body uses {@code SmokePuffs.smokeShare}); {@link #set} sets 1. */
        double shown = 1;

        Tube set(double x, double y, double z, double sigma, double e, double width, double half, float alpha, int light) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.sigma = sigma;
            this.e = e;
            this.width = width;
            this.half = half;
            this.alpha = alpha;
            this.light = light;
            this.shown = 1;
            return this;
        }
    }

    /** {@link #line} result: tube drawn only by billows; ribbon not laid (faint); ribbon laid (b cross vector in joint). */
    static final int CLUBS = 0, NONE = 1, RIBBON = 2;

    /**
     * Smoke tube segment between ends a and b — shared by projectile trails and fire plumes: large onscreen uses
     * billows ({@link SmokePuffs#tube}: thick-layer colour (cr, cg, cb), shade exponent shade); narrow uses ribbon
     * colour (r, g, b); smooth between ({@link #puffShare}, actual width at each place and ribbon end: billow and
     * ribbon shares sum to 1). Billows share frame budget ({@link #CLUB_BUDGET}: affects spacing only, not shares),
     * their places await frame plan ({@code SmokePuffs.flush}); tube identifies tube (projectile trail, source
     * plume). joint is ribbon cross vector at a (NaN — absent, ribbon start), b on return. Ribbon only where tube
     * narrower than {@link #PUFFS_FULL} ({@link #ribbonPart}): wider gives a straight-edged strip, one colour across, no shade.
     */
    static int line(FarView view, SmokePuffs.Out out, Tube a, Tube b, float r, float g, float bl, float cr, float cg, float cb, double shade,
                    double sigma0, long seed, long tube, float[] joint) {
        double tx = b.x - a.x, ty = b.y - a.y, tz = b.z - a.z;
        double gap = FarSprites.distance(a.x, a.y, a.z, tx, ty, tz);
        // billow share at segment's widest onscreen point: zero — only ribbon for segment
        double puffs = puffShare(Math.max(a.width, b.width), gap, view.pixel());
        // billows only for segment in frame (segment sphere including billow quads: up to ~9σ from axis) or at eye
        // (eye in sphere: matter forms veil, while segment centre may be behind)
        double reach = 0.5 * Math.sqrt(tx * tx + ty * ty + tz * tz) + 9 * Math.max(a.sigma, b.sigma);
        double mx = a.x + 0.5 * tx, my = a.y + 0.5 * ty, mz = a.z + 0.5 * tz, md = Math.sqrt(mx * mx + my * my + mz * mz);
        if (puffs > 0 && (md <= reach || inFrame(view, mx, my, mz, md, reach))) {
            int held = SmokePuffs.tube(view, out, a.x, a.y, a.z, a.sigma, a.e, a.width, b.x, b.y, b.z, b.sigma, b.e, b.width, cr, cg, cb, shade,
                    sigma0, seed, stride, 0.5 * (a.shown + b.shown), tube);
            // no frame places ({@code SmokePuffs.PLACES}): segment has no places this frame, requested places count toward budget
            clubs += held < 0 ? -held - 1 : held;
        }
        // wholly narrower than {@link #PUFFS_FROM}: one strip without billows
        int drawn = puffs <= 0 ? strip(view, out, a, b, r, g, bl, joint) : ribbonPart(view, out, a, b, r, g, bl, joint);
        return drawn == NONE && puffs >= 1 ? CLUBS : drawn;
    }

    /** Eye distance to point u (0 — a, 1 — b) on segment a–b. */
    private static double distanceAt(Tube a, Tube b, double u) {
        double x = a.x + (b.x - a.x) * u, y = a.y + (b.y - a.y) * u, z = a.z + (b.z - a.z) * u;
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** Tube screen width at point u on segment a–b, pixels (width linear between ends). */
    private static double pixelsAt(Tube a, Tube b, double u, double pixel) {
        return (a.width + (b.width - a.width) * u) / Math.max(distanceAt(a, b, u) * pixel, 1e-9);
    }

    /**
     * Segment a–b ribbon only where tube screen width is narrower than {@link #PUFFS_FULL} (actual width): for
     * narrow/wide ends (or wide middle near eye), strip across wide part would be hundreds of pixels wide
     * ({@code SmokeRaidTest.mixedRibbonEndsAreCut}); piece ends where ribbon share vanishes (zero exactly at
     * {@link #PUFFS_FULL}: no opacity cut). Ribbon share along strip is linear between vertices, each from its
     * width ({@link #knots}): one end-to-end strip held wide middle to threshold then dropped from 0.39 to 0.01
     * beyond it (review #466); with a vertex only at widest point, cut moving away as √Δw changed share a quarter
     * along segment by 0.016 over 0.005 px ({@code SmokeRaidTest.ribbonShareIsContinuous}). Returns {@link #RIBBON}
     * if ribbon reached b (joint is b cross vector), otherwise {@link #NONE}.
     */
    private static int ribbonPart(FarView view, SmokePuffs.Out out, Tube a, Tube b, float r, float g, float bl, float[] joint) {
        double pixel = view.pixel();
        // narrowest segment point is an end (screen width quasiconcave, {@link #knots}): both wider than PUFFS_FULL — all wider
        if (pixelsAt(a, b, 0, pixel) >= PUFFS_FULL && pixelsAt(a, b, 1, pixel) >= PUFFS_FULL) return NONE;
        int n = knots(a, b, pixel), drawn = NONE;
        Tube prev = null;
        for (int i = 0; i < n; i++) {
            double u = KNOT_U[i];
            Tube here = KNOT_P[i] < PUFFS_FULL ? knot(a, b, u, pixel, i) : null;
            if (i > 0 && (prev != null || here != null)) {
                double was = KNOT_U[i - 1];
                if (prev == null) {
                    // wide to narrow: strip starts where tube becomes narrower than PUFFS_FULL
                    joint[0] = Float.NaN;
                    prev = cut(a, b, crossing(a, b, u, was, pixel), pixel, KNOT_TUBES[KNOT_TUBES.length - 1]);
                }
                Tube end = here != null ? here : cut(a, b, crossing(a, b, was, u, pixel), pixel, KNOT_TUBES[KNOT_TUBES.length - 1]);
                drawn = strip(view, out, prev, end, r, g, bl, joint);
                if (here == null || drawn != RIBBON) joint[0] = Float.NaN;
            }
            prev = here;
        }
        return prev != null ? drawn : NONE;
    }

    /** Maximum segment ribbon vertices: ends, widest point and three on each side. */
    private static final int KNOTS = 9;
    /** Ribbon vertices this many eye-distances from widest point ({@link #knots}). */
    private static final double[] KNOT_STEPS = {0.3, 0.9, 2};
    private static final double[] KNOT_U = new double[KNOTS], KNOT_P = new double[KNOTS];
    private static final Tube[] KNOT_TUBES = new Tube[KNOTS + 1];
    private static final double[] CUT_HALF = new double[2];

    static {
        for (int i = 0; i < KNOT_TUBES.length; i++) KNOT_TUBES[i] = new Tube();
    }

    /**
     * Segment a–b ribbon vertices into {@link #KNOT_U} (ascending segment fractions) and {@link #KNOT_P} (screen
     * width there, pixels); returns count. Screen width w/d is quasiconcave (w linear along segment, d convex:
     * {w ≥ k·d} is an interval), with one widest point found by golden-section search. Near it, width changes
     * quadratically; point where tube becomes wider than {@link #PUFFS_FULL} moves away as √Δw: vertices at
     * 0.3, 0.9 and 2 times its eye distance along segment ({@link #KNOT_STEPS}) hold ribbon share where it moves
     * (nearest has width 4 % below maximum, ribbon share there when maximum reaches threshold is 0.02). Short
     * distant segment needs none: width nearly constant along it.
     */
    private static int knots(Tube a, Tube b, double pixel) {
        double lo = 0, hi = 1, g = (Math.sqrt(5) - 1) / 2;
        double m1 = hi - g * (hi - lo), m2 = lo + g * (hi - lo), p1 = pixelsAt(a, b, m1, pixel), p2 = pixelsAt(a, b, m2, pixel);
        for (int i = 0; i < 40; i++) {
            if (p1 < p2) {
                lo = m1;
                m1 = m2;
                p1 = p2;
                m2 = lo + g * (hi - lo);
                p2 = pixelsAt(a, b, m2, pixel);
            } else {
                hi = m2;
                m2 = m1;
                p2 = p1;
                m1 = hi - g * (hi - lo);
                p1 = pixelsAt(a, b, m1, pixel);
            }
        }
        double widest = 0.5 * (lo + hi), len = Math.sqrt(sq(b.x - a.x) + sq(b.y - a.y) + sq(b.z - a.z));
        double reach = distanceAt(a, b, widest) / Math.max(len, 1e-9);
        int n = 0;
        KNOT_U[n++] = 0;
        for (int k = KNOT_STEPS.length - 1; k >= 0; k--) n = knot(n, widest - KNOT_STEPS[k] * reach);
        n = knot(n, widest);
        for (double step : KNOT_STEPS) n = knot(n, widest + step * reach);
        n = knot(n, 1);
        if (KNOT_U[n - 1] < 1) KNOT_U[n - 1] = 1;
        for (int i = 0; i < n; i++) KNOT_P[i] = pixelsAt(a, b, KNOT_U[i], pixel);
        return n;
    }

    /** Vertex u after n previous ones if inside segment and not near an existing one (within {@link #KNOT_GAP} — one). */
    private static int knot(int n, double u) {
        if (u <= KNOT_U[n - 1] + KNOT_GAP || u > 1) return n;
        if (u > 1 - KNOT_GAP) u = 1;
        KNOT_U[n] = u;
        return n + 1;
    }

    /** Ribbon vertices closer than this segment fraction count as one. */
    private static final double KNOT_GAP = 1e-4;

    /** Ribbon vertex u on a–b: end itself, otherwise segment point ({@link #cut}) in its pool slot. */
    private static Tube knot(Tube a, Tube b, double u, double pixel, int i) {
        return u <= 0 ? a : u >= 1 ? b : cut(a, b, u, pixel, KNOT_TUBES[i]);
    }

    /** Point between narrow from and wide wide on a–b where screen tube width is {@link #PUFFS_FULL} (bisection). */
    private static double crossing(Tube a, Tube b, double from, double wide, double pixel) {
        double lo = from, hi = wide;
        for (int i = 0; i < 20; i++) {
            double mid = 0.5 * (lo + hi);
            if (pixelsAt(a, b, mid, pixel) < PUFFS_FULL) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Segment a–b point u as ribbon end in out: position, σ, extinction, width, opacity linearly between ends. */
    private static Tube cut(Tube a, Tube b, double u, double pixel, Tube out) {
        double x = a.x + (b.x - a.x) * u, y = a.y + (b.y - a.y) * u, z = a.z + (b.z - a.z) * u, w = a.width + (b.width - a.width) * u;
        ribbon(w, Math.sqrt(x * x + y * y + z * z), pixel, CUT_HALF);
        out.set(x, y, z, a.sigma + (b.sigma - a.sigma) * u, a.e + (b.e - a.e) * u, w, CUT_HALF[0], a.alpha + (b.alpha - a.alpha) * (float) u,
                u < 0.5 ? a.light : b.light).shown = a.shown + (b.shown - a.shown) * u;
        return out;
    }

    /** Ribbon from a to b, each end's ribbon share from actual width ({@link #puffShare}). */
    private static int strip(FarView view, SmokePuffs.Out out, Tube a, Tube b, float r, float g, float bl, float[] joint) {
        double pixel = view.pixel();
        double keepA = 1 - puffShare(a.width, Math.sqrt(a.x * a.x + a.y * a.y + a.z * a.z), pixel);
        double keepB = 1 - puffShare(b.width, Math.sqrt(b.x * b.x + b.y * b.y + b.z * b.z), pixel);
        if (keepA <= 0 && keepB <= 0) return NONE;
        // ribbon enters frame plan like billows: fill within budget ({@code SmokePuffs.ribbon})
        return SmokePuffs.ribbon(view, out, a.x, a.y, a.z, a.half, a.alpha * (float) keepA, a.light, b.x, b.y, b.z, b.half,
                b.alpha * (float) keepB, b.light, r, g, bl, joint) ? RIBBON : NONE;
    }

    /** Tube-billow budget from previous frame, at frame start before any tube drawing ({@code FarRenderer}). */
    static void budget() {
        // places per segment ~1/spacing: requested/budget ratio thins by same factor (immediately, or first raid frames
        // leave some segments without billows); at least one place per segment
        if (clubs > CLUB_BUDGET) stride = Math.min(stride * Math.max(1.1, (double) clubs / CLUB_BUDGET), 1 << SmokePuffs.TUBE_LEVELS);
        else if (clubs < CLUB_BUDGET / 2) stride = Math.max(1, stride / 1.02);
        clubs = 0;
    }

    // ---------------------------------------------------------------- pure visibility geometry (unit tests)

    /**
     * Model share in body look at length pixels: 0 below {@link FarModels#DOT_BELOW} (dot), 1 from
     * {@link FarModels#MODEL_FROM} (model), smooth between; remainder is dot.
     */
    static double modelShare(double length) {
        double k = (length - FarModels.DOT_BELOW) / (FarModels.MODEL_FROM - FarModels.DOT_BELOW);
        k = Math.max(0, Math.min(1, k));
        return k * k * (3 - 2 * k);
    }

    /**
     * Body at range d: circle radius and opacity ({@link Sight#body}) — circle matching mean silhouette
     * {@code area}.
     */
    static void body(double area, double d, double pixel, double[] out) {
        Sight.body(Math.sqrt(area / Math.PI), d, pixel, out);
    }

    /**
     * Share of tube segment width width (at half centre opacity, m) drawn by billows when nearest point is
     * gap blocks from eye ({@link #PUFFS_FROM}, {@link #PUFFS_FULL}); pixel is pixel angle, rad.
     */
    static double puffShare(double width, double gap, double pixel) {
        double px = width / Math.max(gap * pixel, 1e-9);
        double t = Math.clamp((px - PUFFS_FROM) / (PUFFS_FULL - PUFFS_FROM), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * Ribbon width width at range d: half-width for {@link FarSprites#ribbon} and opacity fraction. Below a pixel
     * it remains, drawn at one and a half pixels proportionally fainter: ribbon covers as much sky as trail.
     */
    static void ribbon(double width, double d, double pixel, double[] out) {
        double half = width / 2, floor = 0.5 * Sight.MIN_PIXELS * pixel * d;
        out[0] = Math.max(half, floor) * FarSprites.RIBBON;
        out[1] = half >= floor ? 1 : half / floor;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static float channel(int rgb, int channel) {
        return ((rgb >> (16 - 8 * channel)) & 255) / 255f;
    }

    // ---------------------------------------------------------------- state

    static boolean isEmpty() {
        return active == 0 && POINTS.isEmpty();
    }

    /**
     * Scenario log summary (once per second): distant projectile count and kinds; nearest one's range, air
     * transmittance, body (dot) and plume in pixels with opacity, model pixel length and detail copy, terrain
     * occlusion; trail points in frame.
     * Empty — nothing distant.
     */
    public static String describe() {
        if (isEmpty() || flights == 0 && points == 0 && flames == 0) return "";
        StringBuilder sb = new StringBuilder("projectiles ").append(flights);
        if (flights > 0) {
            sb.append(" (");
            String sep = "";
            for (int i = 0; i < COUNT.length; i++) {
                if (COUNT[i] == 0) continue;
                sb.append(sep).append(i == SAM ? "sam" : i == B2 ? "b2" : WeaponType.byId(i).getSerializedName()).append(' ').append(COUNT[i]);
                sep = ", ";
            }
            sb.append(')');
        }
        Far f = nearest;
        if (f != null) {
            sb.append(String.format(Locale.ROOT, "; nearest %s: %.0f blocks, t %.2f, body %.1f px α %.2f",
                    name(f.track), nearD, nearT, nearBody, nearBodyAlpha));
            if (nearModel > 0) sb.append(String.format(Locale.ROOT, ", model %.1f px (%s)", nearModel, nearCoarse ? "coarse" : "full"));
            if (nearFlame > 0) sb.append(String.format(Locale.ROOT, ", flame %.1f px α %.2f", nearFlame, nearFlameAlpha));
            if (f.visTarget < 1) sb.append(", behind terrain");
        }
        Far g = flameOf;
        if (g != null) {
            sb.append(String.format(Locale.ROOT, "; flames %d, nearest %s (%s): %.0f blocks, core %.1f px α %.2f share %.2f, glare %.1f px, veil %.1f px",
                    flames, name(g.track), g.track.fromServer() ? "packets" : "entity", flameD, flameCorePx,
                    flameCoreAlpha, flameShare, flameGlarePx, flameVeilPx));
        }
        int sustain = 0, boost = 0, sustainInCone = 0;
        double sustainAlpha = 0;
        for (long serial = POINTS.first(); serial < POINTS.end(); serial++) {
            int i = TrailPoints.index(serial);
            if (DRAWN[i] != frame || POINTS.style[i] == null) continue;
            if (POINTS.style[i].motor() == ClientWeaponSpec.SAM_SUSTAIN) {
                sustain++;
                double d = Math.sqrt(sq(EX[i]) + sq(EY[i]) + sq(EZ[i]));
                if (seen != null && d > 0 && inFrame(seen, EX[i], EY[i], EZ[i], d, 0)) {
                    sustainInCone++;
                    sustainAlpha = Math.max(sustainAlpha, EA[i]);
                }
            }
            if (POINTS.style[i].motor() == ClientWeaponSpec.SAM_BOOST) boost++;
        }
        return sb.append("; trail ").append(points).append(" points (SAM boost ").append(boost).append(", sustain ").append(sustain)
                .append(", sustain axis in view cone ").append(sustainInCone).append(String.format(Locale.ROOT, ", alpha max %.3f)", sustainAlpha)).toString();
    }

    /** World exit or dimension change. */
    static void reset() {
        flamesAtEye = 0;
        lit = 0;
        litEye = 0;
        stride = 1;
        clubs = 0;
        FARS.clear();
        BY_TRACK.clear();
        BY_OWNER.clear();
        POINTS.clear();
        active = 0;
        flights = 0;
        points = 0;
        nearest = null;
        flames = 0;
        flameOf = null;
        level = null;
        heights = null;
        for (LaunchCloud c : CLOUDS) c.end();
        CLOUDS.clear();
    }
}
