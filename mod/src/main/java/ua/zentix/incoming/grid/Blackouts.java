package ua.zentix.incoming.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import ua.zentix.incoming.warhead.Warheads;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Blackout: a grid-node (substation) hit, nuclear strike or command cuts power to an area — districts go dark in a cascade
 * from the center, then light returns in random order after {@code grid.restore_minutes}. Entry points and event hooks here;
 * chunk work in {@link BlackoutWorld}, state in {@link PowerGrid}.
 */
public final class Blackouts {
    /** Explosion disables a node if its center is closer than this (blocks) plus blast radius: a substation is a site, not a point. */
    static final double HIT = 6;
    /** Substation equipment threshold for a chemical-charge wave, psi — like light wooden structures ({@code nuke_light}; (est.)). */
    static final double EQUIPMENT_PSI = 4;
    /** Distance at which substation failure is audible and visible (blocks). */
    private static final double FAILURE_RANGE = 320;
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
        if (!(e.getLevel() instanceof ServerLevel level) || !IncomingConfig.SERVER.gridEnabled.get()) return;
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
        hit.forEach(n -> knockOut(level, n));
    }

    /**
     * Node disabled: bang, arc and sparks; its area darkens in an outward cascade. Already disabled nodes restore light
     * later (repairs restart).
     */
    public static void knockOut(ServerLevel level, Node node) {
        PowerGrid grid = PowerGrid.get(level);
        long now = level.getGameTime();
        grid.downOutage(node.id(), now).ifPresentOrElse(o -> {
            if (o.restoreAt() != Outage.NEVER) {
                Outage later = o.restoring(restoreAt(now), restoreSpread());
                grid.replace(later);
                BlackoutWorld.get(level).onRestore(later);
            }
        }, () -> {
            Vec3 c = Vec3.atCenterOf(node.pos());
            blackout(level, c, node.radius(), speed(), node.id());
            PacketDistributor.sendToPlayersNear(level, null, c.x, c.y, c.z, FAILURE_RANGE, new S2C.GridFailure(c, level.random.nextLong()));
        });
        Substations.sync(level, grid, now);
    }

    /**
     * Nuclear strike cuts power everywhere it reaches (glass or burns), immediately at the flash; substations in this radius
     * are disabled — each also darkens its own area.
     */
    public static void nuke(ServerLevel level, Detonation d) {
        double radius = nukeRadius(d);
        if (radius <= 0) return;
        Outage o = blackout(level, d.burst(), radius, NUKE_SPEED, -1);
        List<Node> hit = new ArrayList<>();
        for (Node n : PowerGrid.get(level).nodes()) {
            if (Math.hypot(n.pos().getX() + 0.5 - o.x(), n.pos().getZ() + 0.5 - o.z()) <= o.radius()) hit.add(n);
        }
        hit.forEach(n -> knockOut(level, n));
    }

    /**
     * Nuclear grid-outage radius ({@link #nuke}; sent to clients with detonation: lamps short with EMP arcs),
     * blocks; 0 — no grid outage (blackout or grid damage disabled by setting).
     */
    public static double nukeRadius(Detonation d) {
        if (!IncomingConfig.SERVER.gridEnabled.get() || !IncomingConfig.SERVER.gridNuke.get()) return 0;
        return Math.min(d.radiusMax(), IncomingConfig.SERVER.gridMaxRadius.get());
    }

    /**
     * Outage centered at {@code at}: cascade at {@code speed} blocks per tick, light returns according to
     * settings. Radius capped at {@code grid.max_radius}, regardless of origin (node, command, nuclear strike).
     *
     * @param node failed node causing this outage; -1 — not a node
     */
    public static Outage blackout(ServerLevel level, Vec3 at, double radius, double speed, int node) {
        radius = Math.min(radius, IncomingConfig.SERVER.gridMaxRadius.get());
        long now = level.getGameTime();
        Outage o = PowerGrid.get(level).addOutage(at.x, at.z, radius, now, speed, restoreAt(now), restoreSpread(), node);
        BlackoutWorld.get(level).onOutage(o);
        Incoming.LOG.info("Blackout #{} ({}): {} {}, radius {}, {}", o.id(), level.dimension().location(), Math.round(at.x), Math.round(at.z), Math.round(radius),
                o.restoreAt() == Outage.NEVER ? "power will not come back by itself" : String.format(Locale.ROOT, "power returns in %d min",
                        (o.restoreAt() - now) / 1200));
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

    /** Same, restoring light in random order over {@code spread} ticks (0 — all districts now). */
    public static int restore(ServerLevel level, @Nullable Vec3 at, double radius, int spread) {
        PowerGrid grid = PowerGrid.get(level);
        long now = level.getGameTime();
        int n = 0;
        for (Outage o : List.copyOf(grid.outages())) {
            if (at != null && Math.hypot(o.x() - at.x, o.z() - at.z) > radius) continue;
            Outage restoring;
            if (o.restoreAt() > now) {
                restoring = o.restoring(now, spread);
            } else {
                // Spread only shrinks: districts already lit remain lit.
                long left = Math.min(o.restoreSpread(), now - o.restoreAt() + spread);
                if (left >= o.restoreSpread()) continue;
                restoring = o.restoring(o.restoreAt(), (int) left);
            }
            grid.replace(restoring);
            // Restart restoration cascade with the new times.
            grid.swept(o.id(), true, Long.MIN_VALUE);
            BlackoutWorld.get(level).onRestore(restoring);
            Incoming.LOG.info("Blackout #{} ({}): power restored by command", o.id(), level.dimension().location());
            n++;
        }
        Substations.sync(level, grid, now);
        return n;
    }

    // ---------------------------------------------------------------- settings

    private static double speed() {
        return IncomingConfig.SERVER.gridCascadeSpeed.get() / 20;
    }

    private static long restoreAt(long now) {
        int minutes = IncomingConfig.SERVER.gridRestoreMinutes.get();
        return minutes == 0 ? Outage.NEVER : now + minutes * 1200L;
    }

    private static int restoreSpread() {
        return IncomingConfig.SERVER.gridRestoreSpread.get() * 20;
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
            if (second) Substations.sync(level, grid, level.getGameTime());
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

    /** A lamp placed in a dark district goes out. */
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent e) {
        if (e.getLevel() instanceof ServerLevel level && GridLights.isLit(e.getPlacedBlock())) {
            BlockPos pos = e.getPos();
            if (PowerGrid.get(level).dark(pos.getX() >> 4, pos.getZ() >> 4, level.getGameTime())) {
                BlackoutWorld.get(level).lampPlaced(level, pos);
            }
        }
    }
}
