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
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.radiation.FlashEyes;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.util.Local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ICBM launch and scheduled strikes (DESIGN-nuke §7): the missile launches near the launcher (visible boost), leaves
 * above the world ceiling, then flight is a timer in {@link NuclearEvents}; warning for those near the target, countdown
 * for everyone. Also subscribes to world events needed by the nuclear code.
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
        return owner == null ? launchFrom(level, target, surface, yieldKt, airBurst, null, 0, null, flightTicks)
                : launchFrom(level, target, surface, yieldKt, airBurst, owner.position(), owner.getYRot(), owner.getUUID(), flightTicks);
    }

    /**
     * @param launcher launching player's position (missile starts 30 blocks behind them along {@code yaw}),
     *                 null — launch “from afar”, timer immediately
     */
    public static boolean launchFrom(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable Vec3 launcher, float yaw,
                                     @Nullable UUID owner) {
        return launchFrom(level, target, false, yieldKt, airBurst, launcher, yaw, owner, IncomingConfig.SERVER.nukeFlightTime.get());
    }

    private static boolean launchFrom(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, @Nullable Vec3 launcher,
                                      float yaw, @Nullable UUID owner, int flightTicks) {
        if (!IncomingConfig.SERVER.nukeEnabled.get()) return false;
        yieldKt = Math.min(yieldKt, IncomingConfig.SERVER.nukeMaxYield.get());
        if (launcher == null) {
            schedule(level, target, surface, yieldKt, airBurst, target, owner, flightTicks);
            return true;
        }
        Vec3 back = Local.horizontal(yaw).scale(-30);
        int x = Mth.floor(launcher.x + back.x), z = Mth.floor(launcher.z + back.z);
        Vec3 pad = new Vec3(x + 0.5, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        if (icbm == null) return false;
        icbm.prepare(pad, target, owner);
        if (!level.addFreshEntity(icbm)) return false;
        schedule(level, target, surface, yieldKt, airBurst, pad, owner, flightTicks);
        return true;
    }

    /** Record a strike: detonation time = now + flight time; warning and countdown to everyone in the dimension. */
    private static void schedule(ServerLevel level, Vec3 target, boolean surface, double yieldKt, boolean airBurst, Vec3 launchPos, @Nullable UUID owner,
                                 int flightTicks) {
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        NuclearEvents.ScheduledStrike s = new NuclearEvents.ScheduledStrike(events.nextId(), target, yieldKt, airBurst, now,
                now + flightTicks, launchPos, Optional.ofNullable(owner), surface);
        events.schedule(s);
        holdGround(level, s, true);
        Incoming.LOG.info("ICBM #{}: {} kt at {} {} {}, detonation in {} s", s.id(), Math.round(yieldKt), Mth.floor(target.x), Mth.floor(target.y),
                Mth.floor(target.z), (s.detonateTime() - now) / 20);
        for (ServerPlayer p : level.players()) PacketDistributor.sendToPlayer(p, warning(s, p));
    }

    /** Warning for a specific player: alarm if within {@code warning_radius} of the target. */
    public static S2C.NukeWarning warning(NuclearEvents.ScheduledStrike s, ServerPlayer p) {
        double r = IncomingConfig.SERVER.nukeWarningRadius.get();
        boolean alarm = p.position().distanceToSqr(s.target()) <= r * r;
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
        yieldKt = Math.min(yieldKt, IncomingConfig.SERVER.nukeMaxYield.get());
        if (groundLoaded(level, target)) return NuclearWarhead.detonate(level, target, yieldKt, airBurst, owner);
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        events.schedule(new NuclearEvents.ScheduledStrike(events.nextId(), target, yieldKt, airBurst, now, now, target, Optional.ofNullable(owner), false));
        return null;
    }

    /** Cancel: scheduled strikes canceled, fallout traces removed, queues stopped. */
    public static int clear(ServerLevel level) {
        for (NuclearEvents.ScheduledStrike s : NuclearEvents.get(level).scheduled()) holdGround(level, s, false);
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
        for (ServerLevel level : levels) tick(level, clock);
    }

    /** Whether nuclear queues have server-thread work (in any dimension). */
    public static boolean pending(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.hasData(ua.zentix.incoming.registry.ModAttachments.NUCLEAR_WORLD) && NuclearWorld.get(level).busy()) return true;
        }
        return false;
    }

    private static void tick(ServerLevel level, WorkClock clock) {
        long t0 = System.nanoTime();
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        List<NuclearEvents.ScheduledStrike> due = new ArrayList<>();
        for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
            boolean loaded = groundLoaded(level, s.target());
            // detonation position held from launch (and after world load: tickets are not saved): ground height and crater soil
            // must be known without stalling the server
            holdGround(level, s, true);
            if (now >= s.detonateTime() && (loaded || now - s.detonateTime() > GIVE_UP_TICKS)) due.add(s);
        }
        for (NuclearEvents.ScheduledStrike s : due) {
            events.unschedule(s);
            holdGround(level, s, false);
            Vec3 at = s.surface() ? NuclearWarhead.surfaceAt(level, s.target()) : s.target();
            NuclearWarhead.detonate(level, at, s.yieldKt(), s.airBurst(), s.owner().orElse(null));
        }
        if (now % 1200 == 0) events.prune(now);
        long t1 = System.nanoTime();
        NuclearWorld world = NuclearWorld.get(level);
        world.tick(level, clock);
        long t2 = System.nanoTime();
        RadiationTicker.tick(level);
        long t3 = System.nanoTime();
        if (t3 - t0 > SLOW_TICK_NS && world.slowLogDue(now)) {
            long[] w = world.lastNanos();
            Incoming.LOG.warn("Nuclear tick {} ms: strikes {} ms, front {} ms, light {} ms, flash scorch {} ms, craters {} ms, ruin queue {} ms ({}), "
                            + "pre-built ruins {} ms, mob fallout {} ms, radiation {} ms",
                    (t3 - t0) / 1_000_000, (t1 - t0) / 1_000_000, w[0] / 1_000_000, w[1] / 1_000_000, w[2] / 1_000_000, w[3] / 1_000_000,
                    w[4] / 1_000_000, world.scarSummary(), w[5] / 1_000_000, w[6] / 1_000_000, (t3 - t2) / 1_000_000);
        }
    }

    private static final long SLOW_TICK_NS = 50_000_000L;

    public static void onChunkLoad(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            NuclearWorld.get(level).onChunkLoad(level, chunk);
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
            sync(p);
            RadiationTicker.sync(p);
            FlashEyes.sync(p);
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
        PacketDistributor.sendToPlayer(p, new S2C.NukeSync(List.copyOf(events.detonations()),
                events.scheduled().stream().map(s -> warning(s, p)).toList()));
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
