package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.CameraShake;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.radiation.RetinaScars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything nuclear known to the client: this dimension's detonations (model derives flash, sphere, mushroom,
 * wave, sound, rain) and flying ICBMs. Server sends one packet per event; client handles the rest.
 */
public final class ClientNuclear {
    /**
     * Client detonation. Live (neither world entry nor Flashback replay seeking) includes flash, sound
     * and shake. Visual time uses own client ticks since packet arrival rather than game time, which jumps backward during
     * server catch-up; the sphere/double flash last fractions of a second and must neither be skipped nor repeated.
     */
    public static final class Active {
        public final Detonation d;
        public final boolean live;
        /** Fireball/fire light on dust and water under epicenter ({@link CloudPuffs}). */
        public final CloudPuffs puffs;
        /** Shock-wave dust wall: rolling head and Wilson cloud ({@link BlastWall}; pall renders it, {@link NukePall}). */
        public final BlastWall wall;
        /** Water under burst ({@link NukeWater#resolve}): column in mushroom volume, base surge in pall. */
        final BurstWater water;
        /**
         * Post-wave pall ({@link NukePall}): “until morning” follows time of day at detonation, then world clock
         * ({@link PallField#daylight}).
         */
        public final PallField pall;
        /** Firestorm over the burning city ({@link FireStorm}): follows detonation age until pall morning. */
        public final FireStorm storm;
        /** Terrain under pall ({@link PallGround}): end of its ray where no world lies behind. */
        final PallGround ground;
        /** Ticks elapsed before packet arrival (world-entry packets: since detonation). */
        private final long before;
        private long age;
        final NukeSounds.Schedule sounds;

        Active(Detonation d, boolean live, long clientNow) {
            this(d, live, clientNow, new CloudPuffs(d), new BlastWall(d), new BurstWater(), null, null, null);
        }

        /** Same detonation after replay seek: past, without flash/sound, age from this recording position's clock. */
        Active(Active a, long clientNow) {
            this(a.d, false, clientNow, a.puffs, a.wall, a.water, a.pall, a.storm, a.ground);
        }

        private Active(Detonation d, boolean live, long clientNow, CloudPuffs puffs, BlastWall wall, BurstWater water, @Nullable PallField pall,
                @Nullable FireStorm storm, @Nullable PallGround ground) {
            this.d = d;
            this.live = live;
            this.before = live ? 0 : Math.max(0, clientNow - d.gameTime());
            this.puffs = puffs;
            this.wall = wall;
            this.water = water;
            ClientLevel level = Minecraft.getInstance().level;
            if (pall != null) {
                pall.rewound();
                this.pall = pall;
            } else {
                // detonation time of day from server; old-save/replay detonations use current
                // clock: jumps before entry are then invisible (nuclear.md)
                this.pall = new PallField(d, d.hasDayTime() ? d.dayTime()
                        : level == null ? 0 : level.getDayTime() - (level.getGameTime() - d.gameTime()));
            }
            // measure clock immediately: late entry after sleep shows no haze even for the frame before a tick
            if (level != null) this.pall.daylight(ticks(0), level.getGameTime(), level.getDayTime());
            this.storm = storm != null ? storm : new FireStorm(d, this.pall);
            // same terrain: known data stays; new map fills using water under epicenter
            if (ground == null) NukeWater.resolve(this, level);
            this.ground = ground != null ? ground : new PallGround(d, this.pall);
            this.sounds = new NukeSounds.Schedule(this);
        }

        /** Own client ticks since packet arrival (since replay seek afterward). */
        long age() {
            return age;
        }

        /** Ticks since detonation (with frame fraction). */
        public double ticks(float partialTick) {
            return before + age + partialTick;
        }

        /** Model seconds since detonation (world scale applied). */
        public double seconds(float partialTick) {
            return ticks(partialTick) / (20.0 * d.scale());
        }
    }

    /**
     * How far client clock may lead server, ticks. Client increments game time itself,
     * server sends its time once a second: while server lags (generation, ruins, saving), client clock
     * runs ahead then jumps backward. The client-clock countdown thus reaches zero before
     * server reaches the deadline; this is not delayed detonation. Allowance is the largest backward jump over recent
     * tens of seconds (slowly decaying), plus {@link #DELAY_GRACE_TICKS}.
     */
    static final class ClockLead {
        /** Ticks decayed per tick: a one-second jump is forgotten over 20 s. */
        static final double DECAY = 0.05;
        private long last = Long.MIN_VALUE;
        private double lead;

        void tick(long now) {
            if (last != Long.MIN_VALUE && now < last + 1 && last + 1 - now < 20 * 60) {
                lead = Math.max(lead, last + 1 - now);
            } else {
                lead = Math.max(0, lead - DECAY);
            }
            last = now;
        }

        double lead() {
            return lead;
        }

        void reset() {
            last = Long.MIN_VALUE;
            lead = 0;
        }
    }

    /**
     * Client-clock ticks after deadline to wait for detonation packet before reporting delayed detonation:
     * server sends time every 20 ticks; detonation packet has network delay.
     */
    static final int DELAY_GRACE_TICKS = 50;

    /** Server game-time packet interval in ticks (same in replay, from recording). */
    static final int TIME_SYNC_TICKS = 20;

    private static final ClockLead CLOCK = new ClockLead();
    private static final Map<Integer, Active> DETONATIONS = new LinkedHashMap<>();
    private static final Map<Integer, S2C.NukeWarning> WARNINGS = new LinkedHashMap<>();
    @Nullable
    private static S2C.Radiation radiation;

    private ClientNuclear() {}

    // ---------------------------------------------------------------- packets

    /** ICBM launch; {@code live} excludes replay seeking ({@link ua.zentix.incoming.client.replay.Replay#event}): launch roar only live. */
    public static void warning(S2C.NukeWarning w, boolean live) {
        boolean fresh = !WARNINGS.containsKey(w.strikeId());
        WARNINGS.put(w.strikeId(), w);
        if (fresh && live) NukeSounds.warning(w);
    }

    /**
     * Detonation; {@code live} excludes replay seeking: flash/sound/shake only live; sought detonations appear
     * at their age. A live detonation packet already shown as past (replay sought just before it)
     * is shown live again.
     */
    public static void detonation(S2C.NukeDetonation p, boolean live) {
        Detonation d = p.detonation();
        // detonation can follow countdown: server waits for location loading (NuclearStrikes.GIVE_UP_TICKS). Detonation
        // has no strike ID; remove one warning, earliest countdown among matching place/deadline
        // (server detonates by deadline); another strike at the same point remains on screen
        WARNINGS.values().stream()
                .filter(w -> w.target().distanceToSqr(new Vec3(d.burst().x, w.target().y, d.burst().z)) < 4 && d.gameTime() >= w.detonateTime() - 40)
                .min(Comparator.comparingLong(S2C.NukeWarning::detonateTime).thenComparingInt(S2C.NukeWarning::strikeId))
                .ifPresent(w -> WARNINGS.remove(w.strikeId()));
        ClientLevel level = Minecraft.getInstance().level;
        Active known = DETONATIONS.get(d.id());
        if ((known != null && (known.live || !live)) || level == null) return;
        // previous display of same detonation (from seek): remove its burning-city loops
        if (known != null) known.sounds.stop();
        Active a = new Active(d, live, level.getGameTime());
        DETONATIONS.put(d.id(), a);
        if (live) {
            NukeFlash.detonation(a);
            FlashAftermath.detonation(d, p.blackout());
        }
    }

    /** World entry/dimension change: all current state, without flash/hit (already passed). */
    public static void sync(S2C.NukeSync p) {
        stopAll();
        Map<Integer, Active> old = new HashMap<>(DETONATIONS);
        DETONATIONS.clear();
        WARNINGS.clear();
        ClientLevel level = Minecraft.getInstance().level;
        long now = level == null ? 0 : level.getGameTime();
        for (Detonation d : p.detonations()) {
            // same detonation (respawn, replay position after it): as after seek, dust/pall/storm
            // retain history (cannot repeat the young storm's comparison with fire later)
            Active known = old.get(d.id());
            DETONATIONS.put(d.id(), known != null && known.d.equals(d) ? new Active(known, now) : new Active(d, false, now));
        }
        for (S2C.NukeWarning w : p.warnings()) WARNINGS.put(w.strikeId(), w);
        NukeDust.reset();
        NukePall.reset();
    }

    /**
     * Flashback replay sought: everything before this recording position is past, without flash/sound/deafness,
     * at its age by world clock; later detonations/launches are removed (arrive again when replay reaches them). Client clock
     * in replay follows the last recorded time packet, sent once a second: a detonation within the next second
     * is retained (age 0).
     */
    public static void rewound() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        // old detonations disappear (later than recording position) or become past; stop driving their echoes/loops
        stopAll();
        DETONATIONS.values().removeIf(a -> a.d.gameTime() > now + TIME_SYNC_TICKS);
        DETONATIONS.replaceAll((id, a) -> new Active(a, now));
        WARNINGS.values().removeIf(w -> w.launchTime() > now + TIME_SYNC_TICKS);
        CLOCK.reset();
        NukeFlash.reset();
        Deafness.reset();
        NukeDust.reset();
        NukePall.reset();
    }

    /**
     * Player respawned after death: hearing, eyes and wind lean belong to the body; new body is intact and upright. Dimension
     * change (including End exit) uses the same clone event, retaining hearing/eyes.
     */
    public static void respawned(ClientPlayerNetworkEvent.Clone e) {
        if (!e.getOldPlayer().isDeadOrDying()) return;
        Deafness.reset();
        CameraShake.reset();
        NukeFlash.respawned();
    }

    public static void radiation(S2C.Radiation p) {
        radiation = p;
    }

    /** Blocks removed by wave in a nearby chunk: pieces fly ({@link NukeDust#debris}), ruins crumble afterward. */
    public static void debris(S2C.NukeDebris p) {
        Active a = DETONATIONS.get(p.detonation());
        if (a == null) return;
        NukeDust.debris(a, p);
        a.sounds.debris(p);
    }

    /**
     * Warm what detonation builds on the frame thread at packet arrival ({@link Active}: dust wall/pall, dust-layer
     * laws {@code DustModel}, front-arrival tables): class loading and hotspot compilation for surface
     * and airbursts. Otherwise first detonation per launch costs three times the frame time. Call in background once per launch
     * ({@code IncomingClient.login}).
     */
    public static void warm() {
        for (boolean surface : new boolean[]{true, false}) {
            Detonation d = new Detonation(-1, new Vec3(0, surface ? 64 : 564, 0), 64, 15, surface, 0, 0, 5, 20_000, 0, 1, false, 1);
            new BlastWall(d, false);
            new PallField(d, 0);
        }
    }

    public static void reset() {
        stopAll();
        DETONATIONS.clear();
        WARNINGS.clear();
        radiation = null;
        CLOCK.reset();
        NukeFlash.reset();
        // retinal burns are server player state: seeking leaves them, exit forgets them (entry sends again)
        NukeFlash.scars(RetinaScars.NONE);
        NukeSounds.reset();
        NukeSky.reset();
        Deafness.reset();
        NukeDust.reset();
        NukePall.reset();
    }

    /** Stop all detonation sounds (detonations disappear or are replaced). */
    private static void stopAll() {
        for (Active a : DETONATIONS.values()) a.sounds.stop();
    }

    // ---------------------------------------------------------------- tick

    /**
     * Terrain top under pall/tornadoes; null in ceiling dimensions (heightmap top is ceiling, not ground).
     * No terrain over void (End): NaN.
     */
    @Nullable
    private static Sightline.Heights ground(ClientLevel level) {
        if (level.dimensionType().hasCeiling()) return null;
        Sightline.Heights heights = FarTerrain.of(level);
        int floor = level.getMinBuildHeight() + 1;
        return (x, z) -> {
            double h = heights.at(x, z);
            return h <= floor ? Double.NaN : h;
        };
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        long now = level.getGameTime();
        CLOCK.tick(now);
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Sightline.Heights heights = ground(level);
        // column-top water for pall map (its matter); absent under ceiling (Nether), like terrain
        FarTerrain.Water water = heights == null ? null : FarTerrain.water(level);
        for (Iterator<Active> it = DETONATIONS.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            a.age++;
            a.sounds.tick();
            a.pall.daylight(a.ticks(0), level.getGameTime(), level.getDayTime());
            // terrain/water under pall: for rays without world behind and for matter (ceiling dimensions use ground
            // under epicenter)
            // water under epicenter before pall map: map fills unknowns from it
            NukeWater.resolve(a, level);
            double wall = a.wall.reach(a.seconds(0));
            double seen = Math.hypot(eye.x - a.d.burst().x, eye.z - a.d.burst().z);
            if (a.pall.visible(a.ticks(0)) || wall > 0) a.ground.survey(a.ticks(0), wall, seen, heights, water);
            // firestorm tornadoes follow terrain beneath them
            if (heights != null) a.storm.track(a.ticks(1) / 20.0, heights);
            NukeWater.tick(a, level, eye);
            NukeDust.front(a, eye);
            if (a.ticks(0) > lifeTicks(a.d) && !a.pall.visible(a.ticks(0))) {
                a.sounds.stop();
                it.remove();
            }
        }
        // warning lives until detonation (or cancel; server resends all), capped at location-wait deadline
        WARNINGS.values().removeIf(w -> now > w.detonateTime() + NuclearStrikes.GIVE_UP_TICKS + 100);
        NukeSky.tick(level);
        NukeSounds.tick();
        NukeFlash.tick();
        Deafness.tick(level);
        NukeDust.tick();
        NukePall.tick(level, eye);
        SoundFilters.loops();
        Geiger.tick();
    }

    /**
     * Detonation leaves client after mushroom disperses, sound reaches distant listeners (Distant Horizons: 20 km), and
     * black rain ends (client draws no precipitation: server sends dose rate for counter).
     */
    private static double lifeTicks(Detonation d) {
        return Math.max(Math.max(CloudPuffs.lifeSeconds(d) * 20 * d.scale(), d.arrivalTicks(d.blocks(20_000)) + 200),
                d.blackRainEndTicks());
    }

    // ---------------------------------------------------------------- rendering and sound

    /** Client detonation {@code id} clock: ticks since detonation ({@link Active#ticks}); NaN if absent. */
    static double ticksOf(int id) {
        Active a = DETONATIONS.get(id);
        return a == null ? Double.NaN : a.ticks(0);
    }

    public static List<Active> detonations() {
        return DETONATIONS.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(DETONATIONS.values()));
    }

    public static boolean isEmpty() {
        return DETONATIONS.isEmpty() && WARNINGS.isEmpty();
    }

    public static Iterable<S2C.NukeWarning> warnings() {
        return WARNINGS.values();
    }

    /**
     * Server is delaying detonation (loading impact location), rather than merely client clock leading: deadline passed
     * with allowance for server lag and packet delay.
     */
    public static boolean detonationOverdue(S2C.NukeWarning w, double now) {
        return overdue(now, w.detonateTime(), CLOCK.lead());
    }

    static boolean overdue(double now, long detonateTime, double lead) {
        return now - detonateTime > DELAY_GRACE_TICKS + lead;
    }

    @Nullable
    public static S2C.Radiation radiationState() {
        return radiation;
    }

}
