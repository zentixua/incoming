package ua.zentix.incoming.nuclear;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.util.WorldClock;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.radiation.BodyClock;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.RoundEnd;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ICBM launch and scheduled strikes (DESIGN-nuke §7): the missile launches near the launcher (visible boost), leaves
 * above the world ceiling, then flight is a timer in {@link NuclearEvents}; optional network warning near the target,
 * owner countdown even without the network. Also subscribes to world events needed by the nuclear code.
 */
public final class NuclearStrikes {
    private NuclearStrikes() {}

    /**
     * Launch. With a launching player, the missile is visible: starts 30 blocks behind them; otherwise (console, command
     * block), launch “from afar” — timer immediately.
     */
    public static boolean launch(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable ServerPlayer owner) {
        return launch(level, target, false, yieldKt, airBurst, owner);
    }

    /** @param surface target is a ground position (from the map): surface detonation, {@code target} height is only an estimate */
    public static boolean launch(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, @Nullable ServerPlayer owner) {
        return launch(level, target, surface, yieldKt, airBurst, owner, IncomingConfig.SERVER.nukeFlightTime.get());
    }

    /**
     * @param flightTicks flight from launch to detonation: world setting; a non-operator strike gives at least 90 s warning
     *                    ({@code strike.NuclearKeys})
     */
    public static boolean launch(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, @Nullable ServerPlayer owner,
                                 int flightTicks) {
        return launch(level, target, surface, yieldKt, airBurst, owner, flightTicks, null);
    }

    /** Same as order round {@code round}: the scheduled strike carries it ({@link #schedule}). */
    public static boolean launch(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, @Nullable ServerPlayer owner,
                                 int flightTicks, @Nullable RoundRef round) {
        return owner == null ? launchFrom(level, target, surface, yieldKt, airBurst, null, 0, null, flightTicks, round)
                : launchFrom(level, target, surface, yieldKt, airBurst, owner.position(), owner.getYRot(), owner.getUUID(), flightTicks, round);
    }

    /**
     * @param launcher launching player's position (missile starts 30 blocks behind them along {@code yaw}),
     *                 null — launch “from afar”, timer immediately
     */
    public static boolean launchFrom(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable Vec3 launcher, float yaw,
                                     @Nullable UUID owner) {
        return launchFrom(level, target, false, yieldKt, airBurst, launcher, yaw, owner, IncomingConfig.SERVER.nukeFlightTime.get(), null);
    }

    private static boolean launchFrom(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, @Nullable Vec3 launcher,
                                      float yaw, @Nullable UUID owner, int flightTicks, @Nullable RoundRef round) {
        if (!IncomingConfig.SERVER.nukeEnabled.get()) return false;
        yieldKt = Math.min(yieldKt, IncomingConfig.SERVER.nukeMaxYield.get());
        if (launcher == null) {
            schedule(level, target, surface, yieldKt, airBurst, target, owner, flightTicks, round);
            return true;
        }
        Vec3 back = Local.horizontal(yaw).scale(-30);
        int x = Mth.floor(launcher.x + back.x), z = Mth.floor(launcher.z + back.z);
        Vec3 pad = new Vec3(x + 0.5, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        if (icbm == null) return false;
        icbm.prepare(pad, target, owner, IcbmArrival.boost(flightTicks));
        if (!level.addFreshEntity(icbm)) return false;
        icbm.showStrike(schedule(level, target, surface, yieldKt, airBurst, pad, owner, flightTicks, round).id());
        return true;
    }

    /** Ordered ground pad: the order has already waited for LaunchPlaces, with the usual virtual timeout fallback. */
    public static boolean launchAt(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst,
                                   ua.zentix.incoming.strike.LaunchOrigin from, @Nullable UUID owner, int flightTicks) {
        return launchAt(level, target, surface, yieldKt, airBurst, from, owner, flightTicks, null);
    }

    /** Same as order round {@code round}. */
    public static boolean launchAt(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst,
                                   ua.zentix.incoming.strike.LaunchOrigin from, @Nullable UUID owner, int flightTicks, @Nullable RoundRef round) {
        if (!IncomingConfig.SERVER.nukeEnabled.get()) return false;
        int x = Mth.floor(from.at(level).x), z = Mth.floor(from.at(level).z);
        Terrain.Surface ground = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z, Terrain.Allowed.ORDER);
        Vec3 pad = new Vec3(x + 0.5, ground.y(), z + 0.5);
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        if (icbm == null) return false;
        icbm.prepare(pad, target, owner, IcbmArrival.boost(flightTicks));
        ua.zentix.incoming.strike.LaunchPlaces.hold(level, from, icbm.getUUID());
        if (Terrain.ready(level, BlockPos.containing(pad))) {
            icbm.holdChunks(level);
            boolean added = false;
            try {
                added = level.addFreshEntity(icbm);
            } finally {
                if (!added) icbm.discard();
            }
            if (!added) return false;
        } else {
            ua.zentix.incoming.strike.VirtualFlights.launch(level, icbm);
        }
        icbm.showStrike(schedule(level, target, surface, Math.min(yieldKt, IncomingConfig.SERVER.nukeMaxYield.get()), airBurst, pad, owner, flightTicks,
                round).id());
        return true;
    }

    /** Record a strike: detonation time = now + flight time; owner countdown and, with the early-warning network, notices to the dimension. */
    private static NuclearEvents.ScheduledStrike schedule(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, Vec3 launchPos,
                                                          @Nullable UUID owner, int flightTicks, @Nullable RoundRef round) {
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        NuclearEvents.ScheduledStrike s = new NuclearEvents.ScheduledStrike(events.nextId(), target, yieldKt, airBurst, now,
                now + flightTicks, launchPos, Optional.ofNullable(owner), surface).withRound(round);
        events.schedule(s);
        // The saved strike is the round; the ICBM entity, if any, only shows it.
        if (round != null) Orders.released(level, round, WeaponType.NUKE, owner, launchPos, target);
        holdGround(level, s, true);
        Incoming.LOG.info("ICBM #{}: {} kt at {} {} {}, detonation in {} s", s.id(), Math.round(yieldKt), Mth.floor(target.x), Mth.floor(target.y),
                Mth.floor(target.z), (s.detonateTime() - now) / 20);
        for (ServerPlayer p : level.players()) {
            if (warns(s, p)) PacketDistributor.sendToPlayer(p, warning(s, p));
        }
        // Capability/state at launch too: new recordings never fall back to the legacy countdown graphic.
        PacketDistributor.sendToPlayersInDimension(level, new S2C.NukeApproach(now,
                events.scheduled().stream().flatMap(x -> x.arrival().stream()).toList()));
        return s;
    }

    /** Network notices go to everyone only when the world's early-warning network exists. */
    public static boolean warns(NuclearEvents.ScheduledStrike s, ServerPlayer p) {
        return IncomingConfig.SERVER.earlyWarning.get() || s.owner().map(p.getUUID()::equals).orElse(false);
    }

    /** Warning for a specific player: alarm if within {@code warning_radius} of the target. */
    public static S2C.NukeWarning warning(NuclearEvents.ScheduledStrike s, ServerPlayer p) {
        double r = IncomingConfig.SERVER.nukeWarningRadius.get();
        boolean alarm = IncomingConfig.SERVER.earlyWarning.get() && p.position().distanceToSqr(s.target()) <= r * r;
        boolean mine = s.owner().map(p.getUUID()::equals).orElse(false);
        return new S2C.NukeWarning(s.id(), s.target(), s.launchPos(), s.launchTime(), s.detonateTime(), s.yieldKt(), s.airBurst(), alarm, mine,
                (float) IncomingConfig.SERVER.effectsScale());
    }

    /**
     * Detonation without flight (“nuke now” command). If the position is unloaded, schedule it for now and detonate as soon
     * as the chunk finishes loading (no server stall for generation), returning null in that case.
     */
    @Nullable
    public static Detonation detonateNow(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner) {
        return detonateNow(level, target, yieldKt, airBurst, owner, null);
    }

    /**
     * Same as order round {@code round}: released now at the burst point; it ends at the burst, now or when the chunk is
     * ready.
     */
    @Nullable
    public static Detonation detonateNow(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner,
                                         @Nullable RoundRef round) {
        yieldKt = Math.min(yieldKt, IncomingConfig.SERVER.nukeMaxYield.get());
        if (round != null) Orders.released(level, round, WeaponType.NUKE, owner, target, target);
        if (groundLoaded(level, target)) {
            Detonation d = NuclearWarhead.detonate(level, target, yieldKt, airBurst, owner, round);
            if (round != null) Orders.ended(level, round, WeaponType.NUKE, owner, RoundEnd.nuclear(d.burst(), d.yieldKt()));
            return d;
        }
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        events.schedule(new NuclearEvents.ScheduledStrike(events.nextId(), target, yieldKt, airBurst, now, now, target, Optional.ofNullable(owner), false)
                .withRound(round));
        return null;
    }

    /**
     * Cancel the scheduled strikes carrying group name {@code tag} ({@code strike.Order#tagged}): their rounds end
     * recalled; detonations and their aftermath stay.
     *
     * @return strikes cancelled
     */
    public static int cancelTagged(ServerLevel level, String tag) {
        NuclearEvents events = NuclearEvents.get(level);
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (NuclearEvents.ScheduledStrike s : List.copyOf(events.scheduled())) {
            if (!s.round().map(r -> tag.equals(r.tag())).orElse(false)) continue;
            events.cancel(s);
            holdGround(level, s, false);
            s.round().ifPresent(r -> Orders.ended(level, r, WeaponType.NUKE, s.owner().orElse(null), RoundEnd.recalled(null)));
            Incoming.LOG.info("ICBM #{} cancelled with its tag {}", s.id(), tag);
            ids.add(s.id());
        }
        if (ids.isEmpty()) return 0;
        // the missiles showing them (no round of their own: nothing ends with them)
        java.util.function.Predicate<ua.zentix.incoming.entity.StrikeProjectile> shows = p -> p instanceof IcbmEntity i && ids.contains(i.strike());
        List<UUID> gone = new ArrayList<>(ua.zentix.incoming.strike.VirtualFlights.get(level).clear(level, shows));
        for (IcbmEntity i : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(IcbmEntity.class), shows::test)) {
            gone.add(i.getUUID());
            i.discard();
        }
        // only these strikes: a full resync would restart the client's live detonations
        PacketDistributor.sendToPlayersInDimension(level, new S2C.NukeCancelled(List.copyOf(ids)));
        PacketDistributor.sendToPlayersInDimension(level, new S2C.NukeApproach(level.getGameTime(),
                events.scheduled().stream().flatMap(x -> x.arrival().stream()).toList()));
        if (!gone.isEmpty()) PacketDistributor.sendToAllPlayers(new S2C.Cleared(Optional.of(net.minecraft.Util.NIL_UUID), false, gone));
        return ids.size();
    }

    /** Cancel: scheduled strikes canceled (their rounds recalled), fallout traces removed, queues stopped. */
    public static int clear(ServerLevel level) {
        for (NuclearEvents.ScheduledStrike s : List.copyOf(NuclearEvents.get(level).scheduled())) {
            holdGround(level, s, false);
            s.round().ifPresent(r -> Orders.ended(level, r, WeaponType.NUKE, s.owner().orElse(null), RoundEnd.recalled(null)));
        }
        int n = NuclearEvents.get(level).clear();
        NuclearWorld.get(level).clear(level);
        sync(level);
        return n;
    }

    /**
     * If the chunk never loads (world without generation?), detonate anyway at target height. The client displays
     * “detonation delayed” for the same duration after countdown zero.
     */
    public static final int GIVE_UP_TICKS = 600;
    /**
     * Epicenter area loaded from launch, like a projectile target area ({@link FlightTickets#DISTANCE}): 3×3 chunks are
     * needed (ground height, crater soil, Sable neighbors), but generation is queued by ticket level; at level 32 (radius 1)
     * the position would not finish loading during the whole 90 s flight while salvos hold level-29 target areas.
     */
    private static final int GROUND_AREA = FlightTickets.DISTANCE;
    private static final TicketType<UUID> GROUND = TicketType.create("incoming_nuclear_ground", Comparator.<UUID>naturalOrder());

    private static boolean groundLoaded(ServerLevel level, Vec3 target) {
        return Terrain.ready(level, BlockPos.containing(target));
    }

    /**
     * The detonation position loads from launch (ICBM flight takes a minute and a half), like a projectile target area
     * ({@link AreaLoader}): a level-32 ticket 10 s before detonation would queue behind salvo target areas, and on a slow
     * server the countdown would reach zero before the ground was ready.
     * The area loads but does not tick: detonation needs the ground, not life on it; a minute and a half of ticking 7×7
     * chunks around the target would be extra work. Key is the strike number (tickets are not saved: after world load the
     * area is acquired again in the tick).
     */
    private static void holdGround(ServerLevel level, NuclearEvents.ScheduledStrike s, boolean hold) {
        AreaLoader.Area area = new AreaLoader.Area(GROUND, new ChunkPos(BlockPos.containing(s.target())), GROUND_AREA, new UUID(0L, s.id()), false);
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }

    // ---------------------------------------------------------------- world events

    /**
     * Nuclear work in all dimensions uses the shared budget's NUCLEAR lane ({@code WorkScheduler}): after impacts, with the
     * deadline they leave (excluding blackout's share while it has work); the clock is already running. Worlds rotate as
     * the lane does (the first gets the full deadline and always one work unit). The background ruin pool
     * ({@code RuinWorkers}) is outside the clock.
     */
    public static void work(List<ServerLevel> levels, WorkClock clock) {
        long materialBudget = 500_000;
        for (ServerLevel level : levels) {
            long start = System.nanoTime();
            long began = clock.begin();
            NuclearWorld.get(level).materials.tick(level, NuclearEvents.get(level), start + Math.min(materialBudget, clock.remainingNanos()));
            clock.spent(began);
            materialBudget = Math.max(0, materialBudget - (System.nanoTime() - start));
            tick(level, clock);
        }
    }

    /** Whether nuclear queues have server-thread work (in any dimension). */
    public static boolean pending(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.hasData(ua.zentix.incoming.registry.ModAttachments.NUCLEAR_WORLD) && NuclearWorld.get(level).busy()) return true;
        }
        return false;
    }

    private static void tick(ServerLevel level, WorkClock clock) {
        long t0 = System.nanoTime(), gc0 = ua.zentix.incoming.util.GcPauses.millis();
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        List<NuclearEvents.ScheduledStrike> due = new ArrayList<>();
        List<IcbmArrival> approaches = new ArrayList<>();
        for (NuclearEvents.ScheduledStrike original : List.copyOf(events.scheduled())) {
            NuclearEvents.ScheduledStrike s = original;
            boolean loaded = groundLoaded(level, s.target());
            // detonation position held from launch (and after world load: tickets are not saved): ground height and crater soil
            // must be known without stalling the server
            holdGround(level, s, true);
            double dive = ua.zentix.incoming.strike.WeaponSpec.NUKE.airframe().diveSpeed();
            long start = IcbmArrival.start(s.launchTime(), s.detonateTime(), IcbmArrival.velocity(s.launchPos(), s.target(), dive));
            if (s.arrival().isEmpty() && start < s.detonateTime() && now >= start && now < s.detonateTime() && loaded) {
                Vec3 at = s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target();
                Detonation geometry = NuclearWorld.get(level).planned(NuclearWarhead.geometry(level, at, s.yieldKt(),
                        s.airBurst(), (float) IncomingConfig.SERVER.effectsScale()));
                IcbmArrival arrival = new IcbmArrival(s.id(), start, s.detonateTime(), geometry,
                        IcbmArrival.velocity(s.launchPos(), geometry.burst(), dive));
                events.commit(s, arrival);
                s = s.withArrival(arrival);
            }
            if (now < s.detonateTime()) s.arrival().ifPresent(approaches::add);
            if (now >= s.detonateTime() && (s.arrival().isPresent() || loaded || now - s.detonateTime() > GIVE_UP_TICKS)) due.add(s);
        }
        for (NuclearEvents.ScheduledStrike s : due) {
            events.unschedule(s);
            holdGround(level, s, false);
            Vec3 at = s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target();
            Detonation committed = s.arrival().map(IcbmArrival::geometry).orElse(null);
            RoundRef round = s.round().orElse(null);
            Detonation d = NuclearWarhead.detonate(level, at, s.yieldKt(), s.airBurst(), s.owner().orElse(null),
                    committed == null ? (float) IncomingConfig.SERVER.effectsScale() : committed.scale(), committed, s.id(), round);
            if (round != null) Orders.ended(level, round, WeaponType.NUKE, s.owner().orElse(null), RoundEnd.nuclear(d.burst(), d.yieldKt()));
        }
        // One batched clocked snapshot per server tick, only during terminal flight. No client extrapolation to
        // a nominal countdown: a stalled server stops motion upstream, never a spark hovering at the burst.
        if (!approaches.isEmpty() || !due.isEmpty())
            PacketDistributor.sendToPlayersInDimension(level, new S2C.NukeApproach(now, List.copyOf(approaches)));
        // the clocks once a second to every player, with or without detonations (the hearing clock follows them), and the
        // moment a show ends
        boolean ended = !events.endShows(level).isEmpty();
        if (ended || now % 20 == 0) PacketDistributor.sendToPlayersInDimension(level, clockPacket(level));
        // the server's age for each detonation every tick while a show runs: clients never draw a burst ahead of it
        if (showing(events, level)) PacketDistributor.sendToPlayersInDimension(level, new S2C.NukeTick(now));
        if (now % 1200 == 0) events.prune(level);
        long t1 = System.nanoTime();
        NuclearWorld world = NuclearWorld.get(level);
        world.tick(level, clock);
        // a nuclear round's impact work (strike.OrderStatus): its front and hits, and its crater, which loads its own chunks;
        // ruins resuming in chunks loaded later do not reopen it. Craft blasts waiting for their area count as conventional
        // impact work of the same round (BlastFront).
        events.settleImpacts(d -> world.frontBusy(d, now) || world.craterDigging(d));
        long t2 = System.nanoTime();
        BodyClock.tick(level);
        RadiationTicker.tick(level);
        long t3 = System.nanoTime();
        if (t3 - t0 > SLOW_TICK_NS && world.slowLogDue(now)) {
            long[] w = world.lastNanos();
            Incoming.LOG.warn("Nuclear tick {} ms: strikes {} ms, front {} ms, light {} ms, flash scorch {} ms, craters {} ms, ruin queue {} ms ({}), "
                            + "pre-built ruins {} ms, mob fallout {} ms, disk settlement {} ms, radiation {} ms; GC pauses inside {} ms",
                    (t3 - t0) / 1_000_000, (t1 - t0) / 1_000_000, w[0] / 1_000_000, w[1] / 1_000_000, w[2] / 1_000_000, w[3] / 1_000_000,
                    w[4] / 1_000_000, world.scarSummary(), w[5] / 1_000_000, w[6] / 1_000_000, w[7] / 1_000_000, (t3 - t2) / 1_000_000,
                    ua.zentix.incoming.util.GcPauses.millis() - gc0);
        }
    }

    private static final long SLOW_TICK_NS = 50_000_000L;

    public static void onChunkLoad(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            NuclearWorld.get(level).onChunkLoad(level, chunk);
        }
    }

    /** Living entity enters a level: what detonations did at its place while it was not in memory ({@code LateExposure}). */
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) {
        if (e.getLevel() instanceof ServerLevel level && e.getEntity() instanceof net.minecraft.world.entity.LivingEntity living) {
            NuclearWorld.get(level).onEntityJoin(level, living, e.loadedFromDisk());
        }
    }

    public static void onChunkUnload(ChunkEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            NuclearWorld.get(level).onChunkUnload(level, chunk);
        }
    }

    /** Chunk sent to a player: ruins due but not yet applied count in the detonation summary. */
    public static void onChunkSent(net.neoforged.neoforge.event.level.ChunkWatchEvent.Sent e) {
        NuclearWorld.get(e.getLevel()).onChunkSent(e.getLevel(), e.getChunk());
    }

    /** Login and dimension change: this dimension's active detonations and missiles in flight. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            ua.zentix.incoming.compat.FlashbackReplay.materialViewer(p);
            sync(p);
            RadiationTicker.sync(p);
            FlashEyes.sync(p);
            ua.zentix.incoming.nuclear.radiation.Ears.sync(p);
        }
    }

    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            sync(p);
            RadiationTicker.sync(p);
            FlashEyes.sync(p);
        }
    }

    /**
     * After death, dose and retinal burns reset (attachment not copied) — the client must be told. Respawn may be in another
     * dimension (died in the Nether, respawned in the Overworld), without {@code PlayerChangedDimensionEvent}: resend
     * detonations and ICBMs in flight too.
     */
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            sync(p);
            RadiationTicker.sync(p);
            FlashEyes.sync(p);
        }
    }

    private static void sync(ServerPlayer p) {
        NuclearEvents events = NuclearEvents.get(p.serverLevel());
        var warnings = events.scheduled().stream().filter(s -> warns(s, p)).map(s -> warning(s, p)).toList();
        var ids = events.materialIds();
        var announced = new ArrayList<ua.zentix.incoming.nuclear.material.MaterialBirth>();
        for (var w : warnings) {
            var birth = events.material(w.strikeId());
            if (birth != null) {
                announced.add(birth);
                ids.put(w.strikeId(), birth.id);
            }
        }
        PacketDistributor.sendToPlayer(p, new S2C.NukeSync(List.copyOf(events.detonations()), warnings, ids));
        ua.zentix.incoming.nuclear.material.MaterialDelivery.snapshot(p, events.visibleMaterials(), announced);
        PacketDistributor.sendToPlayer(p, new S2C.NukeApproach(p.serverLevel().getGameTime(),
                events.scheduled().stream().flatMap(s -> s.arrival().stream()).toList()));
        PacketDistributor.sendToPlayer(p, clockPacket(p.serverLevel()));
    }

    /** Some detonation in this dimension is still in its show ({@link S2C.NukeTick}). */
    static boolean showing(NuclearEvents events, ServerLevel level) {
        for (Detonation d : events.detonations()) if (!events.showEnded(d, level)) return true;
        return false;
    }

    /** The world clocks and this dimension's detonation anchors, for its players ({@link S2C.NukeClock}). */
    static S2C.NukeClock clockPacket(ServerLevel level) {
        List<S2C.NukeClock.Anchor> anchors = new java.util.ArrayList<>();
        for (NuclearEvents.Anchor a : NuclearEvents.get(level).anchors()) {
            ua.zentix.incoming.util.StrikeClock c = a.clock();
            anchors.add(new S2C.NukeClock.Anchor(a.id(), c.jumpsAtStrike(), c.endAge(), c.rateAtEnd(), c.jumpsAtEnd(), c.rateAtRamp()));
        }
        return new S2C.NukeClock(WorldClock.now(level), List.of(), new S2C.NukeClock.Clocks(WorldClock.rateClock(level),
                WorldClock.pace(level), WorldClock.counted(level), WorldClock.unseen(level), anchors));
    }

    /** A blast front still crosses {@code level}: a counted jump waits for it ({@link WorldClock#holdJumpsWhile}). */
    public static boolean frontCrossing(ServerLevel level) {
        if (!level.hasData(ua.zentix.incoming.registry.ModAttachments.NUCLEAR_WORLD)) return false;
        NuclearWorld world = NuclearWorld.get(level);
        long now = level.getGameTime();
        for (Detonation d : NuclearEvents.get(level).detonations()) if (world.frontBusy(d, now)) return true;
        return false;
    }

    private static void sync(ServerLevel level) {
        for (ServerPlayer p : level.players()) sync(p);
    }

    /** Surface point at the target position, above or below the estimate (map target); chunk not ready — estimate. */

    /** Ground point beneath the target (for a player or entity strike — at their position). */
    public static Vec3 ground(ServerLevel level, Vec3 at) {
        Terrain.Surface ground = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(at.x), Mth.floor(at.z), Terrain.Allowed.CHUNK);
        return ground.known() ? new Vec3(at.x, Math.min(at.y, ground.y()), at.z) : at;
    }
}
