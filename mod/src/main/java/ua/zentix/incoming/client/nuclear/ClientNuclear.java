package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.CameraShake;
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NukeClock;
import ua.zentix.incoming.nuclear.material.DustMaterial;
import ua.zentix.incoming.nuclear.material.MaterialInbox;
import ua.zentix.incoming.nuclear.radiation.RetinaScars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Everything nuclear known to the client: this dimension's detonations (model derives flash, sphere, mushroom,
 * wave, sound, rain) and flying ICBMs. Server sends one packet per event; client handles the rest.
 */
public final class ClientNuclear {
    /**
     * Client detonation. Live (neither world entry nor Flashback replay seeking) includes flash, sound
     * and shake. Visual time is the {@link AgeClock}: continuous in frame time, steered to the server's own age for the
     * detonation, with a bounded lead during a server stall.
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
         * Post-wave pall ({@link NukePall}): dust on real time, fire haze on the detonation's clock ({@link #clockTicksAt}).
         */
        public final PallField pall;
        /** Firestorm over the burning city ({@link FireStorm}): on the detonation's clock ({@link #clockTicksAt}). */
        public final FireStorm storm;
        /** Terrain under pall ({@link PallGround}): end of its ray where no world lies behind. */
        final PallGround ground;
        private SourceMaterial source;
        private UUID revision;
        private int seed = -1;

        /** Stable server revision, or the first local answer at each legacy world cell. */
        public SourceMaterial sourceMaterial() {
            if (source == null) source = new SourceMaterial(d, revision == null ? null : inbox(revision), seed, (x, z) -> {
                ClientLevel level = Minecraft.getInstance().level;
                if (level != null) {
                    double wet = FarTerrain.water(level).at(x, z);
                    if (Double.isFinite(wet)) {
                        if (wet >= 0.5) return DustMaterial.WATER;
                        if (FarTerrain.hard(level).at(x, z) >= 0.5) return DustMaterial.HARD;
                        if (FarTerrain.sand(level).at(x, z) >= 0.5) return DustMaterial.SAND;
                        return DustMaterial.SOIL;
                    }
                }
                if (ground.water(x, z) >= 0.5) return DustMaterial.WATER;
                if (ground.hard(x, z) >= 0.5) return DustMaterial.HARD;
                return ground.sand(x, z) >= 0.5 ? DustMaterial.SAND : DustMaterial.SOIL;
            });
            return source;
        }

        /** Own client ticks since packet arrival (since replay seek afterward). */
        private long age;
        /** Detonation age shown on this client ({@link AgeClock}). */
        final AgeClock clock;
        final WaveEvents events = new WaveEvents();
        final long epoch = playbackEpoch;
        private double lastFrameAge = Double.NaN;
        private long lastFrameNanos;
        private boolean discarded;
        /**
         * Estimated world clock at the end of the show ({@link NukeClock}) when the server has not said ({@link #SHOW_ENDS}:
         * a recording made before the server sent it); NaN — not estimated yet.
         */
        private double guessedShowEnd = Double.NaN;
        final NukeSounds.Schedule sounds;

        Active(Detonation d, boolean live, long clientNow) {
            this(d, live, clientNow, null);
        }

        /** Same detonation after replay seek: past, without flash/sound, age from this recording position's clock. */
        Active(Active a, long clientNow) {
            this(a.d, false, clientNow, a);
            revision = a.revision;
            seed = a.seed;
            source = a.source;
        }

        private Active(Detonation d, boolean live, long clientNow, @Nullable Active known) {
            this.d = d;
            this.live = live;
            // Live: the packet is the server's first anchor. Entry or seek: age starts from the level's game time.
            long start = live ? 0 : Math.max(0, clientNow - d.gameTime());
            if (Replay.active()) {
                DoubleSupplier birth = known != null && known.clock.replayPosition != null ? known.clock.birth
                        : Replay.birth(d.id(), d.gameTime(), start, live);
                this.clock = new AgeClock(start, live, Replay::position, birth);
            } else {
                this.clock = new AgeClock(start, live);
            }
            this.puffs = known == null ? new CloudPuffs(d) : known.puffs;
            this.wall = known == null ? new BlastWall(d) : known.wall;
            this.water = known == null ? new BurstWater() : known.water;
            this.pall = known == null ? new PallField(d) : known.pall;
            this.pall.clock(this::clockTicksAt);
            this.storm = known == null ? new FireStorm(d, pall) : known.storm;
            if (known == null) NukeWater.resolve(this, Minecraft.getInstance().level);
            this.ground = known == null ? new PallGround(d, pall) : known.ground;
            this.sounds = new NukeSounds.Schedule(this);
        }

        /** Own client ticks since packet arrival (since replay seek afterward). */
        long age() {
            return age;
        }

        /** One client tick. */
        void tick() {
            age++;
            clock.tick();
            deliverPieces();
        }

        private void deliverPieces() {
            if (events.pending()) events.drain(ticks(0), (batch, pieces) -> {
                NukeDust.debris(this, batch, pieces);
                sounds.scatter(batch, pieces);
            }, DIAG ? (batch, due) -> eventTrace(this, batch, due, "drop", "drawn_late") : null);
        }

        /** Ticks since detonation (with frame fraction): the shown age, monotone across frames ({@link AgeClock#at}). */
        public double ticks(float partialTick) {
            return clock.at(partialTick);
        }

        /** Model seconds since detonation (world scale applied). */
        public double seconds(float partialTick) {
            return ticks(partialTick) / (20.0 * d.scale());
        }

        /**
         * The detonation's clock ({@link NukeClock}) at {@code realTicks} client ticks since detonation (now or a moment ago),
         * in ticks: real through the show, then the world clock — fire, its smoke and black rain live the world's hours.
         */
        public double clockTicksAt(double realTicks) {
            double real = realTicks / 20.0;
            if (real <= NukeClock.SHOW_S) return realTicks;
            double now = ClientClock.now();
            Double told = SHOW_ENDS.get(d.id());
            // not told by the server (old recording): as if the day had run at vanilla speed since the show's end
            if (told == null && Double.isNaN(guessedShowEnd)) guessedShowEnd = now - (real - NukeClock.SHOW_S) * 20;
            return NukeClock.fromClock(real, now - (told != null ? told : guessedShowEnd)) * 20;
        }

        /** Detonation-clock seconds now. */
        public double clockSeconds(float partialTick) {
            return clockTicksAt(ticks(partialTick)) / 20.0;
        }
    }

    /** Live presentation runs on processed client ticks, with bounded steering toward confirmed server time. */
    static final class AgeClock {
        /** Plan 4.11 presentation contract (ticks and nominal speed, not physical). SLEW is 0.2× per second. */
        static final double LEAD = 200, DELAY = 2;
        private static final double RESPONSE = 40, BRAKE = 60, SLEW = 0.2 / 20;
        private static final double MIN_SPEED = 0.85, MAX_SPEED = 1.2;
        private final @Nullable DoubleSupplier replayPosition;
        private final DoubleSupplier birth;
        private long own, anchor, confirmed;
        private double at, anchorAt, shown, speed = 1, resyncUntil;
        private boolean estimated, sampled, over, waitingForTarget, farBehind;
        private static final long TICK_NANOS = 1_000_000_000L / 20;
        private long lastTickNanos, expectedOwn;
        private double lastRate = 1;

        AgeClock(long start, boolean live) {
            this(start, live, null, () -> 0);
        }

        AgeClock(long start, boolean live, @Nullable DoubleSupplier position, DoubleSupplier birth) {
            replayPosition = position;
            this.birth = birth;
            confirmed = live ? start : -1;
            anchor = start;
            shown = start;
            over = !live && start >= NukeClock.SHOW_TICKS - TIME_SYNC_TICKS;
            estimated = !live && !over;
        }

        void anchor(long age) {
            if (age < 0 || age < confirmed) return;
            confirmed = age;
            if (replayPosition != null || over || !estimated && age <= anchor) return;
            anchor = Math.max(0, age);
            anchorAt = at;
            if (estimated) {
                if (!sampled) shown = anchor;
                else if (shown > anchor) waitingForTarget = true;
                else if (shown < anchor) resyncUntil = at + RESPONSE;
                estimated = false;
            }
            boolean behind = anchor - shown > BRAKE;
            if (behind && !farBehind) resyncUntil = at + RESPONSE;
            farBehind = behind;
        }

        long confirmed() {
            return confirmed;
        }

        void ended() {
            over = true;
            waitingForTarget = false;
            resyncUntil = 0;
        }

        void tick() {
            tick(System.nanoTime());
        }

        // Wall time detects a missed client tick. It never adds a tick to the picture.
        void tick(long nanos) {
            if (replayPosition != null) return;
            long elapsed = nanos - lastTickNanos;
            if (lastTickNanos != 0 && elapsed >= TICK_NANOS) {
                // At low FPS several client ticks run in one frame. Count the previous batch before declaring a loss.
                if (own < expectedOwn && anchor > shown + 1 && !estimated && !over && resyncUntil <= at)
                    resyncUntil = at + RESPONSE;
                expectedOwn = own + elapsed / TICK_NANOS;
            }
            lastTickNanos = nanos;
            advance(++own);
        }

        double shown() {
            return replayPosition == null ? shown : Math.max(0, replayPosition.getAsDouble() - birth.getAsDouble());
        }

        double at(double partial) {
            if (replayPosition != null) return shown();
            sampled = true;
            advance(own + partial);
            return shown;
        }

        private void advance(double now) {
            double dt = now - at;
            if (dt <= 0) return;
            double target = anchor + Math.min(RESPONSE, at - anchorAt) - DELAY;
            double step;
            if (over || estimated) {
                step = slew(1, dt);
            } else if (waitingForTarget && target < shown) {
                speed = 0.5;
                step = dt * speed;
            } else if (at < resyncUntil && target > shown) {
                // Only processed time advances. Finish the catch at nominal speed when we intercept the target.
                double extra = Math.min(2 * dt, target - shown);
                step = dt + extra;
                speed = 1;
                if (extra < 2 * dt) resyncUntil = 0;
            } else {
                waitingForTarget = false;
                resyncUntil = 0;
                double error = target - shown;
                // Suppress corrections inside the two-tick packet jitter buffer.
                double correction = error / RESPONSE * Math.min(1, Math.abs(error) / DELAY);
                double steering = Math.clamp(1 + correction, MIN_SPEED, MAX_SPEED);
                double brake = Math.clamp((LEAD - (shown - anchor)) / BRAKE, 0, 1);
                step = slew(steering * brake, dt);
            }
            if (!over && !estimated) {
                double remaining = Math.max(0, anchor + LEAD - shown);
                // The linear brake divides the remaining distance by BRAKE. Snap its tail once that speed is below one ULP.
                step = remaining <= Math.ulp(shown) * BRAKE ? remaining : Math.min(step, remaining);
                if (step == remaining) speed = 0;
            }
            if (DIAG) lastRate = step / dt;
            shown += step;
            at = now;
        }

        // Integral of a linear ramp followed by constant speed. Tick queries may split a render interval.
        private double slew(double demand, double dt) {
            double change = Math.clamp(demand - speed, -SLEW * dt, SLEW * dt);
            speed += change;
            return speed * dt - 0.5 * change * Math.abs(change) / SLEW;
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

    private static final IcbmApproaches APPROACHES = new IcbmApproaches();
    public static IcbmApproaches approaches() { return APPROACHES; }
    public static void approach(S2C.NukeApproach p) { APPROACHES.snapshot(p.time(), p.arrivals()); }

    static final boolean DIAG = Boolean.getBoolean("incoming.nukeDiag");
    private static long playbackEpoch;
    private static final ClockLead CLOCK = new ClockLead();
    private static final Map<Integer, Active> DETONATIONS = new LinkedHashMap<>();
    private static final Map<Integer, S2C.NukeWarning> WARNINGS = new LinkedHashMap<>();
    @Nullable
    private static S2C.Radiation radiation;

    // Packets may precede the burst or NukeSync in a Flashback snapshot.
    private static final Map<UUID, MaterialInbox> MATERIALS = new LinkedHashMap<>();
    private static MaterialInbox inbox(UUID id) {
        return MATERIALS.computeIfAbsent(id, unused -> new MaterialInbox());
    }
    public static void materialSources(S2C.NukeMaterialSources p) {
        var active = new java.util.HashSet<>(p.sources());
        MATERIALS.keySet().retainAll(active);
    }
    public static void material(S2C.NukeMaterial p) {
        if (p.tombstone()) MATERIALS.remove(p.id());
        else inbox(p.id()).receive(p);
    }

    private ClientNuclear() {}

    // ---------------------------------------------------------------- packets

    /** ICBM launch; {@code live} excludes replay seeking ({@link ua.zentix.incoming.client.replay.Replay#event}): launch roar only live. */
    public static void warning(S2C.NukeWarning w, boolean live) {
        boolean fresh = !WARNINGS.containsKey(w.strikeId());
        WARNINGS.put(w.strikeId(), w);
        if (fresh && live) NukeSounds.warning(w);
    }

    /** Scheduled strikes cancelled ({@link S2C.NukeCancelled}): their warnings end; detonations and other strikes stay. */
    public static void cancelled(S2C.NukeCancelled p) {
        for (int id : p.strikes()) WARNINGS.remove(id);
    }

    /**
     * Detonation; {@code live} excludes replay seeking: flash/sound/shake only live; sought detonations appear
     * at their age. A live detonation packet already shown as past (replay sought just before it)
     * is shown live again.
     */
    public static void detonation(S2C.NukeDetonation p, boolean live) {
        Detonation d = p.detonation();
        ClientLevel level = Minecraft.getInstance().level;
        if (p.strikeId() >= 0) {
            WARNINGS.remove(p.strikeId());
            APPROACHES.finish(p.strikeId(), live || level == null ? 0 : Math.max(0, level.getGameTime() - d.gameTime()));
        }
        // Old recordings have no scheduled-strike identity. Only those use the historical location match.
        // detonation can follow countdown: server waits for location loading (NuclearStrikes.GIVE_UP_TICKS). Detonation
        // has no strike ID; remove one warning, earliest countdown among matching place/deadline
        // (server detonates by deadline); another strike at the same point remains on screen
        if (p.strikeId() < 0) WARNINGS.values().stream()
                .filter(w -> w.target().distanceToSqr(new Vec3(d.burst().x, w.target().y, d.burst().z)) < 4 && d.gameTime() >= w.detonateTime() - 40)
                .min(Comparator.comparingLong(S2C.NukeWarning::detonateTime).thenComparingInt(S2C.NukeWarning::strikeId))
                .ifPresent(w -> WARNINGS.remove(w.strikeId()));
        Active known = DETONATIONS.get(d.id());
        if ((known != null && (known.live || !live)) || level == null) return;
        // previous display of same detonation (from seek): remove its burning-city loops
        if (known != null) known.sounds.stop();
        Active a = new Active(d, live, level.getGameTime());
        a.revision = p.material().orElse(null);
        a.seed = p.ground();
        DETONATIONS.put(d.id(), a);
        if (live) {
            NukeLight.detonation(level);
            NukeFlash.detonation(a);
            FlashAftermath.detonation(d, partial -> {
                // Sync can replace the active clock. Smoke must follow the clock still driving this detonation.
                Active current = DETONATIONS.get(d.id());
                return current != null && d.equals(current.d) ? current.clock.at(partial) : Double.POSITIVE_INFINITY;
            }, p.blackout());
        }
    }

    /**
     * The server's world clock at the end of each ended show in this dimension ({@link S2C.NukeClock}); the latest packet
     * replaces it whole, and a replay seek keeps it (the detonations are rebuilt, this is not).
     */
    private static final Map<Integer, Double> SHOW_ENDS = new HashMap<>();

    /** The server's clock: the client's world clock continues from it, and each ended show follows it from its end. */
    public static void clock(S2C.NukeClock p) {
        ClientClock.set(p.clock());
        SHOW_ENDS.clear();
        for (S2C.NukeClock.ShowEnd e : p.ends()) SHOW_ENDS.put(e.id(), e.at());
        for (Active a : DETONATIONS.values()) if (SHOW_ENDS.containsKey(a.d.id())) a.clock.ended();
    }

    /** The server's game time this tick ({@link S2C.NukeTick}): each detonation's anchor ({@link AgeClock}). */
    public static void anchor(S2C.NukeTick p) {
        for (Active a : DETONATIONS.values()) {
            long age = p.gameTime() - a.d.gameTime();
            a.clock.anchor(age);
            if (DIAG) Incoming.LOG.info("nukeAnchor,{},{},{},{},{}", a.epoch, a.d.id(),
                    System.nanoTime(), a.clock.at, age);
        }
    }

    /** World entry/dimension change: all current state, without flash/hit (already passed). */
    public static void sync(S2C.NukeSync p) {
        playbackEpoch++;
        stopAll();
        APPROACHES.reset();
        Map<Integer, Active> old = new HashMap<>(DETONATIONS);
        DETONATIONS.clear();
        WARNINGS.clear();
        ClientLevel level = Minecraft.getInstance().level;
        long now = level == null ? 0 : level.getGameTime();
        for (Detonation d : p.detonations()) {
            // same detonation (respawn, replay position after it): as after seek, dust/pall/storm
            // retain history (cannot repeat the young storm's comparison with fire later)
            Active known = old.get(d.id());
            Active a = known != null && known.d.equals(d) ? new Active(known, now) : new Active(d, false, now);
            UUID revision = p.materials().get(d.id());
            if (!java.util.Objects.equals(a.revision, revision)) {
                a.revision = revision;
                a.seed = -1;
                a.source = null;
            }
            DETONATIONS.put(d.id(), a);
        }
        // A live sync supersedes the dimension, including recalled sources. Recording snapshots can deliver
        // their batches before the matching detonation and retain them until the seek finishes.
        if (!ua.zentix.incoming.client.replay.Replay.active()) MATERIALS.keySet().retainAll(p.materials().values());
        for (S2C.NukeWarning w : p.warnings()) WARNINGS.put(w.strikeId(), w);
        NukeDust.reset();
        NukePall.reset();
        NukeLight.reset();
    }

    /** A discontinuous marker invalidates the old playback epoch before any queued event can cross its front. */
    public static void seekStarted() {
        for (Active a : DETONATIONS.values()) {
            a.discarded = true;
            a.events.clear();
            a.sounds.clearClatter();
        }
    }

    /**
     * A seek reconstructs each past show at its recording age and removes future shows.
     * Historical world time still identifies launches whose recording positions are unknown.
     */
    public static void rewound() {
        playbackEpoch++;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        // old detonations disappear (later than recording position) or become past; stop driving their echoes/loops
        stopAll();
        DETONATIONS.values().removeIf(a -> a.clock.replayPosition != null
                ? a.clock.birth.getAsDouble() > Replay.packetPosition() : a.d.gameTime() > now + TIME_SYNC_TICKS);
        DETONATIONS.replaceAll((id, a) -> new Active(a, now));
        WARNINGS.values().removeIf(w -> w.launchTime() > now + TIME_SYNC_TICKS);
        CLOCK.reset();
        APPROACHES.reset();
        NukeFlash.reset();
        Deafness.reset();
        NukeDust.reset();
        NukePall.reset();
        NukeLight.reset();
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
        NukeLight.reset();
    }

    public static void radiation(S2C.Radiation p) {
        radiation = p;
    }

    /** Blocks removed by wave in a nearby chunk: pieces fly ({@link NukeDust#debris}), ruins crumble afterward. */
    public static void debris(S2C.NukeDebris p, boolean live) {
        Active a = DETONATIONS.get(p.detonation());
        if (a == null || !live || a.discarded) {
            if (DIAG) {
                if (a != null) eventTrace(a, null, Double.NaN, "drop", "seek");
                else Incoming.LOG.info("nukeEvent,{},{},-1,-1,NaN,NaN,NaN,drop,no_source", playbackEpoch, p.detonation());
            }
            return;
        }
        var receipt = a.events.receive(p, a.clock.confirmed(), a.age(), piece -> a.d.arrivalTicks(
                Vec3.atCenterOf(piece.pos()).distanceTo(a.d.burst())));
        if (receipt == null) {
            if (DIAG) {
                var repeated = new WaveEvents.Receipt(a.clock.confirmed(), a.events.ordinal(p), p, a.age());
                eventTrace(a, repeated, Double.NaN, "drop", a.clock.confirmed() < 0 ? "no_anchor" : "duplicate");
            }
            return;
        }
        if (DIAG) {
            for (var piece : p.pieces()) {
                double due = a.d.arrivalTicks(Vec3.atCenterOf(piece.pos()).distanceTo(a.d.burst()));
                boolean fresh = receipt.tick() - due <= NukeDust.DEBRIS_LATE;
                eventTrace(a, receipt, due, fresh ? "due" : "drop", fresh ? "fresh" : "server_late");
            }
        }
        NukeDust.received();
        a.sounds.after.ruins(p.pieces());
        a.deliverPieces();
    }

    static void eventTrace(Active a, @Nullable WaveEvents.Receipt batch, double due, String action, String reason) {
        double shown = a.ticks(0);
        Incoming.LOG.info("nukeEvent,{},{},{},{},{},{},{},{},{}", a.epoch, a.d.id(),
                batch == null ? a.clock.confirmed() : batch.tick(), batch == null ? -1 : batch.ordinal(),
                due, shown, shown - due, action, reason);
    }

    static void traceFrame(float partial) {
        for (Active a : DETONATIONS.values()) {
            double shown = a.ticks(partial);
            long now = System.nanoTime();
            double elapsed = (now - a.lastFrameNanos) / 50_000_000.0;
            double frameRate = (shown - a.lastFrameAge) / elapsed;
            Incoming.LOG.info("nukeFrame,{},{},{},{},{},{},{},{}", a.epoch, a.d.id(),
                    now, shown, a.clock.confirmed(), shown - a.clock.confirmed(),
                    a.clock.replayPosition == null ? a.clock.lastRate : frameRate, frameRate < 0.1);
            a.lastFrameAge = shown;
            a.lastFrameNanos = now;
        }
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
            new PallField(d);
        }
    }

    public static void reset() {
        playbackEpoch++;
        MATERIALS.clear();
        stopAll();
        DETONATIONS.clear();
        SHOW_ENDS.clear();
        WARNINGS.clear();
        radiation = null;
        CLOCK.reset();
        APPROACHES.reset();
        NukeFlash.reset();
        // retinal burns are server player state: seeking leaves them, exit forgets them (entry sends again)
        NukeFlash.scars(RetinaScars.NONE);
        NukeSounds.reset();
        NukeSky.reset();
        Deafness.reset();
        NukeDust.reset();
        NukePall.reset();
        NukeLight.reset();
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
        ClientClock.tick(level, mc.isPaused());
        APPROACHES.tick();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Sightline.Heights heights = ground(level);
        // column-top water for pall map (its matter); absent under ceiling (Nether), like terrain
        FarTerrain.Water water = heights == null ? null : FarTerrain.water(level), hard = heights == null ? null : FarTerrain.hard(level),
                sand = heights == null ? null : FarTerrain.sand(level);
        for (Iterator<Active> it = DETONATIONS.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            a.tick();
            if (!a.discarded) a.sounds.tick();
            // terrain/water under pall: for rays without world behind and for matter (ceiling dimensions use ground
            // under epicenter)
            // water under epicenter before pall map: map fills unknowns from it
            NukeWater.resolve(a, level);
            double wall = a.wall.reach(a.seconds(0));
            double seen = Math.hypot(eye.x - a.d.burst().x, eye.z - a.d.burst().z);
            if (a.pall.visible(a.ticks(0)) || wall > 0) a.ground.survey(a.ticks(0), wall, seen, heights, water, hard, sand);
            // firestorm tornadoes follow terrain beneath them
            // the tick's own age: a frame-fraction query of the next tick would advance the clock past every frame of this one
            if (heights != null) a.storm.track(a.ticks(0) / 20.0, heights);
            NukeWater.tick(a, level, eye);
            NukeDust.front(a, eye);
            if (a.ticks(0) > lifeTicks(a.d) && a.clockSeconds(0) > a.d.blackRainEndSeconds() && !a.pall.visible(a.ticks(0))) {
                a.sounds.stop();
                if (a.revision != null && !ua.zentix.incoming.client.replay.Replay.active())
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ua.zentix.incoming.net.C2S.MaterialRetired(a.revision));
                MATERIALS.remove(a.revision);
                it.remove();
            }
        }
        // warning lives until detonation (or cancel; server resends all), capped at location-wait deadline
        WARNINGS.values().removeIf(w -> now > w.detonateTime() + NuclearStrikes.GIVE_UP_TICKS + 100);
        NukeSky.tick(level);
        NukeSounds.tick();
        NukeLight.tick();
        NukeFlash.tick();
        Deafness.tick(level);
        NukeDust.tick();
        NukePall.tick(level, eye);
        SoundFilters.loops();
        Geiger.tick();
    }

    /**
     * Detonation leaves client after mushroom disperses and sound reaches distant listeners (Distant Horizons: 20 km), real
     * ticks; black rain must also have ended, on the detonation's clock (client draws no precipitation: server sends dose
     * rate for counter).
     */
    private static double lifeTicks(Detonation d) {
        return Math.max(CloudPuffs.lifeSeconds(d) * 20 * d.scale(), d.arrivalTicks(d.blocks(20_000)) + 200);
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
