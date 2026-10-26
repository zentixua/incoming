package ua.zentix.incoming.strike;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.work.UnitQueue;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything lasting several ticks without a world entity: explosion timelines (seconds, unsaved), salvos ({@link
 * SalvoData}), virtual flights ({@link VirtualFlights}); latter two saved with world. Ticks at world tick end.
 * <p>
 * Playerless world stops entity ticking after 300 ticks ({@code ServerLevel.tick}, {@code emptyTime}) without
 * forced chunks; region tickets ({@link ChunkTickets}, {@link FlightTickets}) do not count. Projectiles would
 * hang while salvos/virtual flights continue. Active strike keeps world awake, like vanilla dimension transfer
 * ({@code Entity.changeDimension}) wakes destination.
 */
public final class StrikeWorld {
    private final List<Timeline> timelines = new ArrayList<>();
    private final List<Timeline> pending = new ArrayList<>();
    private final AreaLoader areas = new AreaLoader();
    private final ImpactCost impactCost = new ImpactCost();
    private final FlightLog flightLog = new FlightLog();
    /** Impact work units: {@link WorkScheduler.Lane#IMPACT} lane of shared tick budget. */
    private final UnitQueue impacts = new UnitQueue();
    /** Approach-strip areas ({@link FlightTickets#holdApproach}): center → projectiles whose strips cross it. */
    private final Map<ChunkPos, Set<UUID>> approach = new HashMap<>();
    /**
     * Launcher-site failures for current order ({@link StrikeService}): “owner weapon” → firing-position chunk
     * (player/ordered site) lacking placement. New order (single strike, first salvo shot) searches anew, as does
     * moving to another chunk; player line once per order. Null owner means console/command block.
     */
    private final Map<String, Long> noLaunchSite = new HashMap<>();
    /** Non-operator nuclear orders awaiting second key or post-warning launch. */
    private final NuclearKeys keys = new NuclearKeys();

    /** For {@link ModAttachments#STRIKE_WORLD}: per-world, lives while loaded, unsaved. */
    public StrikeWorld() {}

    public static StrikeWorld get(ServerLevel level) {
        return level.getData(ModAttachments.STRIKE_WORLD);
    }

    /** Mod preloaded areas: targets/blasts, projectile chunks, map hints, nuclear strike. */
    public AreaLoader areas() {
        return areas;
    }

    /** Non-operator nuclear orders: second key and carrier pre-launch warning. */
    NuclearKeys keys() {
        return keys;
    }

    /** Approach-strip areas and holding projectiles ({@link FlightTickets}). */
    Map<ChunkPos, Set<UUID>> approach() {
        return approach;
    }

    /** Server-thread time used by impacts this tick (slow-tick log). */
    public ImpactCost impactCost() {
        return impactCost;
    }

    /**
     * Impact work (explosions, glass, debris): timeline queues units on its tick; {@link WorkScheduler} executes
     * under shared tick budget, same tick if queue empty.
     */
    public UnitQueue impacts() {
        return impacts;
    }

    /** This world's impact lane: work units while clock permits. */
    public void workImpacts(ServerLevel level, WorkClock clock) {
        if (impacts.isEmpty()) return;
        long t0 = System.nanoTime();
        impacts.work(level, clock);
        impactCost.step(System.nanoTime() - t0);
    }

    /** Server tick end for this world's impacts: log slow ticks/long queues. */
    public void endImpactTick(ServerLevel level) {
        impactCost.endTick(level, impacts);
    }

    /** Owner order already found no launcher site in this chunk: remainder without launcher. */
    boolean noLaunchSite(@Nullable UUID owner, WeaponType weapon, ChunkPos at) {
        Long chunk = noLaunchSite.get(noSiteKey(owner, weapon));
        return chunk != null && chunk == at.toLong();
    }

    /** No launcher site; true first time this order (player line). */
    boolean rememberNoLaunchSite(@Nullable UUID owner, WeaponType weapon, ChunkPos at) {
        return noLaunchSite.put(noSiteKey(owner, weapon), at.toLong()) == null;
    }

    /** New owner order with this weapon: search site anew. */
    void newOrder(@Nullable UUID owner, WeaponType weapon) {
        noLaunchSite.remove(noSiteKey(owner, weapon));
    }

    private static String noSiteKey(@Nullable UUID owner, WeaponType weapon) {
        return owner + " " + weapon.getSerializedName();
    }

    /** Unplanned flight endings this tick: log at world tick end. */
    public FlightLog flightLog() {
        return flightLog;
    }

    /** Add timeline; first tick at end of current world tick. */
    public void add(Timeline timeline) {
        pending.add(timeline);
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (level.players().isEmpty() && busy(level)) level.resetEmptyTime();
        // Area loading is not simulation: runs in frozen world (trailer waits for shot loading).
        if (level.hasData(ModAttachments.STRIKE_WORLD)) get(level).areas.tick(level);
        // “/tick freeze” stops entities: virtual projectiles, salvos, explosions stop with them.
        if (!level.tickRateManager().runsNormally()) return;
        SalvoData.get(level).tick(level);
        VirtualFlights.get(level).tick(level);
        if (level.hasData(ModAttachments.STRIKE_WORLD)) {
            StrikeWorld world = get(level);
            world.keys.tick(level, level.getServer().getPlayerList().getPlayers());
            world.tick(level);
            world.flightLog.flush();
        }
    }

    /** Active world strike: in-world/virtual projectiles, salvos, explosions. */
    private static boolean busy(ServerLevel level) {
        if (SalvoData.get(level).size() > 0 || VirtualFlights.get(level).size() > 0) return true;
        if (level.hasData(ModAttachments.STRIKE_WORLD) && !get(level).idle()) return true;
        List<StrikeProjectile> any = new ArrayList<>(1);
        level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved(), any, 1);
        return !any.isEmpty();
    }

    /** No pending timeline or impact unit, including delayed collapse/after-effects. An empty impact queue alone is not completion. */
    public boolean idle() {
        return timelines.isEmpty() && pending.isEmpty() && impacts.isEmpty();
    }

    private void tick(ServerLevel level) {
        timelines.addAll(pending);
        pending.clear();
        long t0 = System.nanoTime();
        timelines.removeIf(t -> {
            boolean done;
            try {
                done = !t.tick(level);
            } catch (RuntimeException e) {
                Incoming.LOG.error("Strike timeline failed with an exception and was removed", e);
                done = true;
            }
            if (done) t.end(level);
            return done;
        });
        impactCost.step(System.nanoTime() - t0);
    }

    /**
     * Clear everything without work: timelines/impact queue release areas. For server-stop tests ({@code
     * WorkGameTests.stopFinishesQueue}); leftovers must not reach later tests.
     */
    public void dropAll(ServerLevel level) {
        timelines.addAll(pending);
        pending.clear();
        List<Timeline> all = new ArrayList<>(timelines);
        timelines.clear();
        for (Timeline t : all) t.end(level);
        impacts.clear(level);
    }

    /** All world projectiles, in-world and virtual ({@link VirtualFlights}). */
    public static List<StrikeProjectile> projectiles(ServerLevel level) {
        List<StrikeProjectile> all = new ArrayList<>(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved()));
        all.addAll(VirtualFlights.get(level).flights());
        return all;
    }

    /**
     * Player's active projectiles across worlds: flying in-world/virtual, unlaunched salvo rounds, nuclear orders
     * awaiting second key/warning end ({@link NuclearKeys}), for {@code max_active_per_player}.
     */
    public static int active(MinecraftServer server, UUID owner) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            n += SalvoData.get(level).remaining(owner);
            n += get(level).keys().waiting(owner);
            for (StrikeProjectile p : projectiles(level)) {
                if (owner.equals(p.ownerId())) n++;
            }
        }
        return n;
    }

    /**
     * Abort: finish ongoing explosions (already happened), cancel salvos and refund online owners' paid
     * unlaunched rounds ({@link Munitions}).
     *
     * @param nuclear include nuclear (nuclear abort)
     * @return canceled salvo count
     */
    public static int clearSalvos(ServerLevel level, boolean nuclear) {
        return SalvoData.get(level).cancel(level, null, level.getServer().getPlayerList().getPlayers(), nuclear);
    }

    /**
     * Server stop (including singleplayer “Save and Quit”): in-world projectiles go virtual and save with world,
     * resuming after startup; otherwise they wait in chunk files until someone returns, losing target-area
     * tickets after restart. Finish impact queue first without budget ({@link UnitQueue#finish(ServerLevel)}).
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        // Pending nuclear orders unsaved: refund owners while still online (players saved afterward).
        NuclearKeys.cancelAll(event.getServer(), event.getServer().getPlayerList().getPlayers(), null, "server stopping");
        for (ServerLevel level : event.getServer().getAllLevels()) {
            // Impact work unsaved: finish started/queued work now, otherwise world would retain explosions
            // half removed (timelines not yet queuing detonations are lost).
            if (level.hasData(ModAttachments.STRIKE_WORLD)) get(level).impacts.finish(level);
            for (StrikeProjectile p : level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved())) {
                p.parkForShutdown(level);
            }
            releaseAreas(level);
        }
    }

    /**
     * Stop: release all mod world areas (targets, approach strips, player arrival, map hints, blasts). Unsaved;
     * owners reacquire after startup (virtual projectiles, players). Keeping them during stop would launch
     * generation awaited by {@code util/StopDrain} (after salvo, beyond its limit).
     */
    public static void releaseAreas(ServerLevel level) {
        if (level.hasData(ModAttachments.STRIKE_WORLD)) get(level).areas.releaseAll(level);
    }
}
