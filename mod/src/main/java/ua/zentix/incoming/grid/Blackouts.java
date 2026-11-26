package ua.zentix.incoming.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.api.ClaimQuery;
import ua.zentix.incoming.warhead.Warheads;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.util.WorldClock;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.GridOutageEvent;
import ua.zentix.incoming.api.GridRestoreEvent;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.StrikeRules;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.util.RecoveryClock;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Blackout: a grid-node (substation) hit, nuclear strike or command cuts power to an area — districts go dark in a cascade
 * from the center, then light returns in random order after {@code grid.restore_minutes}, or after a nuclear strike by what
 * its blast did to each district ({@link #nuke}). Lamps placed in a dark district run on their own supply
 * ({@link #onBlockPlaced}). Entry points and event hooks here;
 * chunk work in {@link BlackoutWorld}, state in {@link PowerGrid}.
 */
public final class Blackouts {
    /** Explosion disables a node if its center is closer than this (blocks) plus blast radius: a substation is a site, not a point. */
    static final double HIT = 6;
    /** Substation equipment threshold for a chemical-charge wave, psi — like light wooden structures ({@code nuke_light}; (est.)). */
    static final double EQUIPMENT_PSI = 4;
    /** Distance at which substation failure is audible and visible (blocks). */
    private static final double FAILURE_RANGE = 320;
    /**
     * Nuclear strike: overpressure that destroys the grid — wood-frame buildings flattened, utility poles snapped, overhead
     * lines and substation yards wrecked (Glasstone and Dolan 1977, ch. V: 5 psi, moderate-to-severe damage to most
     * structures; (est.) for the grid as a whole). Power there never comes back by itself ({@link Outage#damageLightAt}).
     */
    public static final double DEAD_PSI = 5;
    /**
     * Nuclear strike: overpressure that starts damaging the distribution grid — windows out, scattered lines down under
     * debris and broken branches (Glasstone and Dolan 1977, ch. V: 1 psi, light damage; (est.) for overhead lines). Below it
     * the grid only trips.
     */
    public static final double DAMAGED_PSI = 1;
    /**
     * Largest nuclear outage, blocks: the whole damage reach up to about 250 kt at ground level (its 1 psi ring is 9 km, its
     * glass and burns reach 15 km). Not {@code grid.max_radius}: that limits substation and command outages, and a strike
     * that tears lines 9 km out must not stop going dark at 4 km. Cost, measured (unit benchmark of the cascade build):
     * 16 384 blocks is 3.3 million chunks, ≈0.3 s of blackout budget spread over ≈75 ticks (4 ms lane), 13 MB of chunk
     * offsets while the repairs last; chunks around players darken at once regardless ({@code BlackoutWorld.Sweep.early}).
     * Due chunks are issued within the same budget ({@code BlackoutWorld.ISSUE_UNIT} a unit), never all at once.
     */
    public static final double NUKE_MAX_RADIUS = 16_384;
    /** Nuclear cascade: EMP and wave darken the area almost simultaneously (blocks per tick). */
    private static final double NUKE_SPEED = 50;
    /** Commanded light restoration takes 10 s. */
    private static final int COMMAND_RESTORE_SPREAD = 200;
    /** Weakest explosion disabling a substation: ghast fireball (1) — no; creeper (3) and TNT (4) — yes. */
    private static final float MIN_BLAST = 2f;

    private Blackouts() {}

    // ---------------------------------------------------------------- entry points

    /**
     * Explosion near a grid node disables it (mod strike, TNT, creeper — the substation does not care). A wind gust
     * (wind charge, breeze) and very weak pops do not count as explosions for it.
     */
    public static void onExplosion(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        // Incoming's claim question is no explosion (api.ClaimQuery)
        if (e.getExplosion() instanceof ClaimQuery) return;
        // a strike's explosion follows the rules its order was made under; any other explosion, the config now
        StrikeRules rules = Warheads.rulesOf(e.getExplosion());
        if (!(rules != null ? rules : StrikeRules.live()).grid()) return;
        if (e.getExplosion().getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK || e.getExplosion().radius() < MIN_BLAST) return;
        Vec3 at = e.getExplosion().center();
        // Warhead explosion by charge: substation equipment is outdoors; disabled where the wave breaks
        // light structures (4 psi, pressure–impulse curve); vanilla explosions use their radius.
        double charge = Warheads.chargeOf(e.getExplosion());
        double reach = HIT + (charge > 0 ? Warheads.breakReach(EQUIPMENT_PSI, charge) : e.getExplosion().radius());
        List<Node> hit = new ArrayList<>();
        for (Node n : PowerGrid.get(level).nodes()) {
            if (Vec3.atCenterOf(n.pos()).distanceToSqr(at) <= reach * reach) hit.add(n);
        }
        // a strike's show holds the repairs it causes at real rate (Outage, RecoveryClock)
        long until = Warheads.showOf(e.getExplosion());
        hit.forEach(n -> knockOut(level, n, restoreAt(now(level)), -1, until == Long.MIN_VALUE ? Outage.NO_SHOW : until));
    }

    /**
     * Node disabled: bang, arc and sparks; its area darkens in an outward cascade. Already disabled nodes restore light
     * later (repairs restart).
     */
    public static void knockOut(ServerLevel level, Node node) {
        knockOut(level, node, restoreAt(now(level)), -1, Outage.NO_SHOW);
    }

    /**
     * Same, repairs done at {@code restoreAt} ({@link Outage#NEVER} — only by command), held while the show of detonation
     * {@code strike} (-1 — none) or of a conventional strike ending at game time {@code until} runs.
     */
    private static void knockOut(ServerLevel level, Node node, long restoreAt, int strike, long until) {
        PowerGrid grid = PowerGrid.get(level);
        long now = now(level);
        grid.downOutage(node.id(), now).ifPresentOrElse(o -> {
            Outage held = o.heldBy(strike, until);
            if (o.repairsAt() != Outage.NEVER && restoreAt > o.repairsAt()) {
                Outage later = held.restoring(restoreAt, restoreSpread());
                grid.replace(later);
                BlackoutWorld.get(level).onRestore(later);
            } else if (!held.equals(o)) {
                grid.hold(held);
            }
        }, () -> {
            Vec3 c = Vec3.atCenterOf(node.pos());
            blackout(level, c, node.radius(), speed(), node.id(), restoreAt, -1, -1, strike, until);
            PacketDistributor.sendToPlayersNear(level, null, c.x, c.y, c.z, FAILURE_RANGE, new S2C.GridFailure(c, level.random.nextLong()));
        });
        Substations.sync(level, grid, now);
    }

    /**
     * Nuclear strike cuts power everywhere it reaches (glass or burns), immediately at the flash. Light comes back by what
     * the blast did to each district ({@link Outage#damageLightAt}): hours where the grid only tripped, days in the
     * damaged belt ({@link #DAMAGED_PSI}), never where it was destroyed ({@link #DEAD_PSI}). Substations in the radius are
     * disabled — each also darkens its own area until repaired as its own district is (a destroyed one: until supply is
     * rerouted around it, {@link Outage#rerouteAt}).
     */
    public static void nuke(ServerLevel level, Detonation d, boolean grid) {
        double radius = nukeRadius(d, grid);
        if (radius <= 0) return;
        double dead = d.groundRange(BlastModel.kpa(DEAD_PSI)), damaged = Math.max(dead, d.groundRange(BlastModel.kpa(DAMAGED_PSI)));
        Outage o = blackout(level, d.burst(), radius, NUKE_SPEED, -1, Outage.NEVER, dead, damaged, d.id(), Outage.NO_SHOW);
        List<Node> hit = new ArrayList<>();
        for (Node n : PowerGrid.get(level).nodes()) {
            if (Math.hypot(n.pos().getX() + 0.5 - o.x(), n.pos().getZ() + 0.5 - o.z()) <= o.radius()) hit.add(n);
        }
        for (Node n : hit) {
            long repaired = o.lightAt(Districts.of(n.pos().getX() >> 4, n.pos().getZ() >> 4));
            knockOut(level, n, repaired == Outage.NEVER ? o.rerouteAt() : repaired, d.id(), Outage.NO_SHOW);
        }
    }

    /**
     * Nuclear grid-outage radius ({@link #nuke}; sent to clients with detonation: lamps short with EMP arcs),
     * blocks; 0 — no grid outage (blackout or grid damage disabled by setting). {@code grid}: {@code grid.enabled} of the
     * strike's order ({@code StrikeRules}).
     */
    public static double nukeRadius(Detonation d, boolean grid) {
        if (!grid || !IncomingConfig.SERVER.gridNuke.get()) return 0;
        return Math.min(d.radiusMax(), NUKE_MAX_RADIUS);
    }

    /**
     * Outage centered at {@code at}: cascade at {@code speed} blocks per tick, light returns according to
     * settings. Radius capped at {@code grid.max_radius} (node, command; a nuclear strike: {@link #NUKE_MAX_RADIUS}).
     *
     * @param node failed node causing this outage; -1 — not a node
     */
    public static Outage blackout(ServerLevel level, Vec3 at, double radius, double speed, int node) {
        return blackout(level, at, radius, speed, node, restoreAt(now(level)), -1, -1, -1, Outage.NO_SHOW);
    }

    /**
     * Same, light restored uniformly from {@code restoreAt}, or by damage ({@code dead}, {@code damaged}: {@link Outage}).
     * {@code speed} is blocks per game tick: the cascade keeps its real speed whatever the day speed (on the grid clock it
     * is divided by the clock's current rate). Repairs are held by the show of detonation {@code strike} (-1 — none) or
     * of a conventional strike ending at game time {@code until} ({@link Outage}).
     */
    private static Outage blackout(ServerLevel level, Vec3 at, double radius, double speed, int node, long restoreAt, double dead, double damaged,
                                   int strike, long until) {
        // a nuclear outage is bounded by its own measured limit (nukeRadius), the rest by the setting
        if (damaged < 0) radius = Math.min(radius, IncomingConfig.SERVER.gridMaxRadius.get());
        long now = now(level);
        Outage o = PowerGrid.get(level).addOutage(new Outage(0, at.x, at.z, radius, now, speed / rate(level), restoreAt, restoreSpread(), node, dead, damaged,
                0, strike, until));
        BlackoutWorld.get(level).onOutage(level, o);
        Orders.queue(level, new GridOutageEvent(level.dimension(), outageId(level, o), o.x(), o.z(), o.radius(), nodePos(level, o), o.byDamage()));
        String restore = o.byDamage() ? String.format(Locale.ROOT, "power returns by damage: in hours beyond %d blocks, in days beyond %d, never within %d",
                Math.round(damaged), Math.round(dead), Math.round(dead))
                : o.restoreAt() == Outage.NEVER ? "power will not come back by itself"
                : String.format(Locale.ROOT, "power returns in %.1f game hours", (o.restoreAt() - now) / Outage.TICKS_PER_HOUR);
        Incoming.LOG.info("Blackout #{} ({}): {} {}, radius {}, {}", o.id(), level.dimension().location(), Math.round(at.x), Math.round(at.z), Math.round(radius), restore);
        return o;
    }

    /**
     * Restore light now (random order over 10 s) in outages centered within {@code radius} of {@code at}; null — all.
     * Outages already restoring light finish sooner: remaining districts also take 10 s. Outside-world chunks
     * do not wait: their disk data contains real lamps ({@link ChunkSaves}).
     *
     * @return number of outages cleared
     */
    public static int restore(ServerLevel level, @Nullable Vec3 at, double radius) {
        return restore(level, at, radius, COMMAND_RESTORE_SPREAD);
    }

    /** Same, restoring light in random order over {@code spread} game ticks (0 — all districts now). */
    public static int restore(ServerLevel level, @Nullable Vec3 at, double radius, int spread) {
        PowerGrid grid = PowerGrid.get(level);
        long now = now(level);
        // the order keeps its real pace on the grid clock
        spread = (int) Math.round(spread * rate(level));
        int n = 0;
        for (Outage o : List.copyOf(grid.outages())) {
            if (at != null && Math.hypot(o.x() - at.x, o.z() - at.z) > radius) continue;
            Outage restoring;
            // districts already relit (a repair weeks long: most of them) are not issued again
            long relit = Math.min(grid.swept(o.id(), true), now - 1);
            // an order no show holds back
            if (o.repairsAt() > now) {
                // the rest are due from now: no new time falls at or before relit
                restoring = o.restoring(now, spread).released();
            } else {
                // Spread only shrinks: districts already lit remain lit.
                long left = Math.min(o.restoreSpread(), now - o.repairsAt() + spread);
                if (left >= o.restoreSpread()) continue;
                restoring = o.restoring(o.repairsAt(), (int) left).released();
                // a still dark district may now be due before relit: reissue the whole uniform restoration
                relit = Math.min(relit, o.repairsAt() - 1);
            }
            grid.replace(restoring);
            // Restart restoration cascade with the new times.
            grid.swept(o.id(), true, Long.MIN_VALUE);
            BlackoutWorld.get(level).onRestore(restoring, relit);
            Incoming.LOG.info("Blackout #{} ({}): power restored by command", o.id(), level.dimension().location());
            n++;
        }
        Substations.sync(level, grid, now);
        return n;
    }

    /** Power is back in all of outage {@code o}, now forgotten ({@link PowerGrid#prune}). */
    static void restored(ServerLevel level, Outage o) {
        Orders.queue(level, new GridRestoreEvent(level.dimension(), outageId(level, o), o.x(), o.z(), o.radius(), nodePos(level, o), o.byDamage()));
        Orders.queue(level, new ua.zentix.incoming.progress.Progress.Relit(level.dimension(), o));
        Incoming.LOG.info("Blackout #{} ({}): power restored everywhere", o.id(), level.dimension().location());
    }

    /**
     * Outage identifier for events: a name-based UUID of the dimension and the grid's saved outage number, so it needs no
     * stored field and is the same after a restart.
     */
    public static UUID outageId(ServerLevel level, Outage o) {
        return UUID.nameUUIDFromBytes(("incoming:outage/" + level.dimension().location() + "/" + o.id()).getBytes(StandardCharsets.UTF_8));
    }

    /** Substation whose failure caused {@code o}; null for a nuclear or command outage, or a node since removed. */
    @Nullable
    private static BlockPos nodePos(ServerLevel level, Outage o) {
        if (o.node() < 0) return null;
        Node n = PowerGrid.get(level).node(o.node());
        return n == null ? null : n.pos();
    }

    // ---------------------------------------------------------------- settings

    private static double speed() {
        return IncomingConfig.SERVER.gridCascadeSpeed.get() / 20;
    }

    private static long restoreAt(long now) {
        double hours = IncomingConfig.SERVER.gridRestoreHours.get();
        return hours <= 0 ? Outage.NEVER : now + Math.round(hours * Outage.TICKS_PER_HOUR);
    }

    /**
     * Grid-clock ticks per unit of {@code grid.restore_spread_seconds}: one second at vanilla day speed (20 server ticks),
     * so a unit is {@code 20 · WorldClock.SECONDS_PER_TICK} = 72 s of the world's day, and its real length follows the day
     * (its config tooltip says so; {@code RestoreSpreadTest}).
     */
    public static final int SPREAD_TICKS_PER_UNIT = 20;

    /** Spread of uniform restoration on the grid clock (world's day), from the setting. */
    private static int restoreSpread() {
        return IncomingConfig.SERVER.gridRestoreSpread.get() * SPREAD_TICKS_PER_UNIT;
    }

    // ---------------------------------------------------------------- clock

    /**
     * Grid clock: the world clock ({@link WorldClock}, 1000 ticks an hour), whole ticks. Repairs take world hours: a vanilla
     * day's hour is 50 s, a day-length mod's an hour; sleep brings power back sooner. Outage times are on this clock.
     */
    public static long now(Level level) {
        return WorldClock.ticks(level);
    }

    /** Grid clock at a coming game time {@code gameTime} (seconds ahead: a wave front's arrival). */
    public static long at(Level level, long gameTime) {
        return now(level) + Math.round((gameTime - level.getGameTime()) * rate(level));
    }

    /**
     * A conventional strike's show that was to end at game time {@code from} ends at {@code to} (its crater fuel lit,
     * {@code StrikeShow}): the outages it caused wait for it.
     */
    public static void extendShow(ServerLevel level, long from, long to) {
        if (from == Long.MIN_VALUE || to <= from) return;
        PowerGrid grid = PowerGrid.get(level);
        for (Outage o : List.copyOf(grid.outages())) if (o.until() == from) grid.hold(o.heldBy(-1, to));
    }

    /**
     * One second of outage {@code o} held by its causes' shows, with its recovery clock {@code c} and the lag it had when the
     * hold began ({@code base}); the clocks as {@link RecoveryClock#step} takes them. The result trails the grid clock by the
     * world's rate it did not take. A jump nobody was awake for ends the conventional show in {@code c}; the outage keeps that
     * end ({@link Outage#showEndsAt}), so the next second cannot hold it again until the planned end.
     */
    static Outage step(Outage o, RecoveryClock c, long base, long game, double rate, double counted, double unseen, Object dim,
                       RecoveryClock.Shows shows) {
        c.strike(o.strike());
        if (o.until() != Outage.NO_SHOW) c.until(o.until());
        c.step(game, rate, counted, unseen, dim, shows);
        return o.lagging(base + Math.round(c.behind())).showEndsAt(c.until());
    }

    /**
     * Whether the hold of {@code next} ends at grid clock {@code now}: once the blend is over, or once a repair falls due (a
     * sleep moved the clock past the first repair): a lag growing under a running restoration would rebuild it every second
     * and darken districts it relit.
     */
    static boolean releases(Outage next, RecoveryClock c, long now) {
        return c.settled() || now >= next.restoreFrom();
    }

    /**
     * Once a second: each outage whose cause's show may still hold it falls behind the grid clock by the world's rate it
     * does not take ({@link Outage#lag}, {@link RecoveryClock}): real rate through the show, then the rate blend. Once the
     * blend is over, or a repair is due, the outage is released and its lag stays.
     */
    static void hold(ServerLevel level, PowerGrid grid) {
        if (grid.outages().stream().noneMatch(Outage::held)) return;
        BlackoutWorld world = BlackoutWorld.get(level);
        NuclearEvents events = NuclearEvents.get(level);
        RecoveryClock.Shows shows = id -> events.showEnd(id, level);
        for (Outage o : List.copyOf(grid.outages())) {
            if (!o.held()) continue;
            RecoveryClock c = world.holds.computeIfAbsent(o.id(), k -> RecoveryClock.district());
            if (!world.holdBase.containsKey(o.id())) world.holdBase.put(o.id(), o.lag());
            Outage next = step(o, c, world.holdBase.get(o.id()), level.getGameTime(), WorldClock.rateClock(level), WorldClock.counted(level),
                    WorldClock.unseen(level), level.dimension(), shows);
            if (releases(next, c, now(level))) {
                next = next.released();
                world.holds.remove(o.id());
                world.holdBase.remove(o.id());
            }
            if (!next.equals(o)) grid.hold(next);
        }
        // outages forgotten, released by command or replaced meanwhile
        world.holds.keySet().removeIf(id -> grid.outages().stream().noneMatch(o -> o.id() == id && o.held()));
        world.holdBase.keySet().removeIf(id -> !world.holds.containsKey(id));
    }

    /** Grid-clock ticks per game tick now, for things that keep their real speed (cascades): never above vanilla speed. */
    static double rate(Level level) {
        return Math.max(WorldClock.REAL_RATE, Math.min(1, WorldClock.rate(level)));
    }

    // ---------------------------------------------------------------- events

    /**
     * Blackout in all dimensions uses the common tick-budget {@link WorkScheduler.Lane#GRID} lane: no more than
     * {@code WorkScheduler.GRID_SHARE} of the total budget and no more than higher lanes left. {@code levels} starts with
     * the next dimension, like nuclear queues.
     *
     * @param clock lane clock, already running
     */
    public static void work(MinecraftServer server, List<ServerLevel> levels, WorkClock clock) {
        boolean plots = server.getTickCount() % BlackoutWorld.PLOT_SCAN == 0 && ModList.get().isLoaded("sable");
        boolean second = server.getTickCount() % 20 == 0;
        for (ServerLevel level : levels) {
            PowerGrid grid = PowerGrid.get(level);
            // Substations follow grid state even without outages: a block whose chunk was unready aligns once ready.
            if (second) {
                hold(level, grid);
                Substations.sync(level, grid, now(level));
            }
            // Twins in Sable craft plots — also after restart, when no outages remain.
            List<SubLevelAccess> ships = plots ? SubLevels.all(level) : List.of();
            if (!ships.isEmpty()) BlackoutWorld.get(level).relightPlots(level, ships);
            // Worlds without outages or blackout work take no time.
            if (!grid.outages().isEmpty() || level.hasData(ModAttachments.BLACKOUT_WORLD) && BlackoutWorld.get(level).busy()) {
                BlackoutWorld.get(level).tick(level, clock);
            }
        }
    }

    /**
     * Any world has queued blackout work (higher lanes must leave budget). An outage alone is not work:
     * the cascade queues districts on schedule, with nothing for blackout to do between them.
     */
    public static boolean pending(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.hasData(ModAttachments.BLACKOUT_WORLD) && BlackoutWorld.get(level).busy()) return true;
        }
        return false;
    }

    /** Blackout budget clock — one per server (in the overworld). */
    public static WorkClock clock(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.GRID_CLOCK);
    }

    /**
     * Blackout budget clock: cheap, consistent queue work units (≈0.3 ms); estimate halves each tick so
     * one spike (GC pause, unrelated stall) cannot keep the queue at one work unit per tick ({@link WorkClock#decaying}).
     */
    public static WorkClock newClock() {
        return WorkClock.decaying(0.5);
    }

    /** Replace budget clock (tests: counting, {@link WorkClock#counting}; restore with {@link #newClock}). */
    public static void useClock(MinecraftServer server, WorkClock clock) {
        server.overworld().setData(ModAttachments.GRID_CLOCK, clock);
    }

    public static void onChunkLoad(ChunkEvent.Load e) {
        // Chunk has extinguished lamps or intersects an outage: align to grid; leave other worlds/chunks alone.
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk
                && (chunk.hasData(ModAttachments.GRID_DARK) || PowerGrid.get(level).covered(chunk.getPos().x, chunk.getPos().z)
                || ChunkLights.anyUnlit(chunk.getSections()))) {
            BlackoutWorld.get(level).enqueue(chunk.getPos().toLong());
        }
        // This chunk may be the only neighbor missing for signal-lamp reconciliation.
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk && level.hasData(ModAttachments.BLACKOUT_WORLD)) {
            BlackoutWorld.get(level).neighbourLoaded(chunk.getPos());
        }
    }

    public static void onChunkUnload(ChunkEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.BLACKOUT_WORLD)) {
            BlackoutWorld.get(level).forget(e.getChunk().getPos().toLong());
        }
    }

    /**
     * A lamp placed in a dark district stays lit: Minecraft lamps have no wire to the grid, so a lamp set up after the outage
     * runs on its placer's own supply (their redstone, their generator). The grid remembers it ({@link PowerGrid#placeOwn})
     * and darkens only lamps that stood before the district went dark — also after the chunk is saved and loaded again.
     * The lamp is the grid's again for the next outage that reaches its district after it was placed.
     */
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent e) {
        // registered last (lowest priority): a placement another mod cancels leaves no record
        if (!e.isCanceled() && e.getLevel() instanceof ServerLevel level && GridLights.isLit(e.getPlacedBlock())) {
            BlockPos pos = e.getPos();
            PowerGrid grid = PowerGrid.get(level);
            if (grid.dark(pos.getX() >> 4, pos.getZ() >> 4, now(level))) grid.placeOwn(pos, now(level));
        }
    }
}
