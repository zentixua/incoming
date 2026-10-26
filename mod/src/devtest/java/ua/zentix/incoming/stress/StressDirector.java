package ua.zentix.incoming.stress;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.warhead.Warheads;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Load director for a dedicated server (only devtest, enabled by the {@code incoming.stress} property):
 *  when players come in, according to the schedule it starts what Artem does in the game - volleys at 30 at players, points and devices
 *  300–1000 blocks, retargeting, destruction of targets, crushing craft, player leaving the edge of loading, the nuke,
 *  saving and “lights out” in the middle of the flight. Every 5 s writes the line {@code STRESS stat}: time tick, shells in the world
 *  and outside the world, ticket, memory. Monitors each projectile by UUID: disappeared without an explosion nearby - {@code STRESS lost}.
 *  Errors and warnings in the log are counted. At the end - {@code STRESS summary} and stopping the server.
 * <p>
 * {@code incoming.stress=run} - the whole scenario; {@code resume} - after a mid-flight restart (checking that
 *  flights and salvos survived the server shutdown and flew).
 */
@Mod(Incoming.MOD_ID)
public final class StressDirector {
    private static final String MODE = System.getProperty("incoming.stress");
    private static final int PLAYERS = Integer.getInteger("incoming.stress.players", 2);
    /** MLRS sample salvoes for fresh areas ({@link #probe}); {@code false} - without them (measuring A/B server stops). */
    private static final boolean PROBES = flag("incoming.stress.probes", true);
    /** Wave 4 and server stop mid-flight (continued - mode {@code resume}). */
    private static final boolean RESTART = flag("incoming.stress.restart", false);
    /** The area that the stand loads under the player’s teleport (the key is the player). */
    private static final TicketType<UUID> TELEPORT = TicketType.create("incoming_stress_teleport", Comparator.<UUID>naturalOrder());
    /** How long to wait for the teleport area before it becomes a problem in the summary, and how long at most to keep it after. */
    private static final int TELEPORT_WAIT = 1200, TELEPORT_HOLD = 100;
    /**
     * Teleport-area radius in chunks: server simulation distance + 2. Player chunks tick blocks as soon as
     * fully loaded, without waiting for neighbours ({@code DistanceManager.tickingTicketsTracker}): the player
     * simulation ticket has level {@code 31 − sim}, blocks tick through level 32, hence radius {@code sim + 1}.
     * A trial spawner or beehive at the edge reads a still-generating neighbour synchronously: at a fresh player
     * site the server stalls for 10–12 s ("stall:" with no nearby mod tickets). This is vanilla, while the rig
     * measures mod stalls, so teleport the player to an area where all block-ticking chunks and their neighbours
     * are ready. Ticket level {@code 33 − radius}: the square fully loads ahead of target areas in the generation
     * queue (levels 29–33); level 33 would wait ~2350 ticks for teleport, leaving scenario players away from their sites.
     */
    public static int teleportArea(MinecraftServer s) {
        return s.getPlayerList().getSimulationDistance() + 2;
    }

    private record Step(int at, String what, Consumer<MinecraftServer> action) {}

    /** The projectile is under observation: where was the tick in the past, where was it flying. */
    private static final class Watch {
        final String type;
        final UUID owner;
        /** With a nuclear warhead (missile, bomb): it explodes in {@code NuclearEvents}, and not with a vanilla explosion. */
        final boolean nuclear;
        Vec3 pos;
        Vec3 aim;
        boolean virtual;
        /** The flight of MLRS outside the world is now extended (the trajectory time rate is less than 1). */
        boolean stretched;
        /** The salvo is a tensile test, which includes the MLRS projectile (null - not a test). */
        @Nullable
        Probe probe;
        /** Testing a path outside the world to the surface to which the projectile belongs (null is not a test). */
        @Nullable
        GroundProbe ground;
        int seen;
        /** The object itself: an entity in a chunk that is not issued (load border), is not visible in the search, but is alive. */
        StrikeProjectile ref;

        Watch(StrikeProjectile p) {
            type = p.getType().getDescriptionId().replace("entity.incoming.", "");
            owner = p.ownerId();
            nuclear = p.isNuclear();
            update(p);
        }

        void update(StrikeProjectile p) {
            pos = p.position();
            aim = p.aimPoint();
            virtual = p.isVirtual();
            ref = p;
        }
    }

    /**
     * MLRS salvo at a fresh, never generated area (out-of-world flight extension): shells - at point
     * targets closer to {@link #PROBE_RADIUS}; the count of the beginnings and ends of the stretch and the results are included in the summary.
     */
    private static final class Probe {
        final String name;
        final Vec3 center;
        /** The idea: the flight is shorter than the loading of the area (there will be stretching) or longer. The report judges by the readiness of the area. */
        final boolean shortFlight;
        final ServerLevel level;
        final int fired;
        int launched, stretchOn, stretchOff;
        /** How many tick after launch the target chunk is ready and entities are ticking in it (-1 - not yet). */
        int readyAfter = -1;
        /** Estimated arrival of the first projectile without extension, at tick after launch. */
        int arrival = Integer.MAX_VALUE;
        /**
         * The lowest flight tempo among the sample projectiles and how many projectiles fell below {@link #DEEP}: the stretch of
         *  due to the unprepared area is deep, and the server, which stopped for seconds (vanilla synchronous loading), gives
         *  lines with a tempo of 0,99 in any salvo.
         */
        double minRate = 1;
        final Set<UUID> deep = new HashSet<>();
        final Map<String, Integer> outcomes = new TreeMap<>();

        Probe(String name, Vec3 center, boolean shortFlight, ServerLevel level, int fired) {
            this.name = name;
            this.center = center;
            this.shortFlight = shortFlight;
            this.level = level;
            this.fired = fired;
        }
    }

    /** The spread of the sample salvo and the radius along which the projectile relates to the sample (at the target point). */
    private static final int PROBE_SPREAD = 20, PROBE_RADIUS = 60;
    /** Margin to the stretch window ({@link RocketEntity#STRETCH_TICKS} to the line at the target): line - ten ticks before arrival. */
    private static final int PROBE_SLACK = 30;
    /** The tempo below is a stretch due to the target area, not due to server lag. */
    private static final double DEEP = 0.5;
    private final List<Probe> probes = new ArrayList<>();

    /**
     * Testing the off-world path to the surface: off-world shells far from players, in areas that no one is loading,
     * —as in GameTest {@code virtual_*}. The projectiles are sampled at a target point closer to {@link #PROBE_RADIUS}; to the summary -
     *  how many reached the surface outside the world and how far from the goal horizontally, how many did not wait for loading, results.
     */
    private static final class GroundProbe {
        final String name;
        /** How the test shells were launched - in the summary line, so that the result is not read as the path of a combat launch. */
        final String how;
        /** Guided projectile (missile) or aimed projectile (MLRS): it can reach the surface only at the target. */
        final boolean steering;
        /** Bomb on the point behind: the path to the surface and must be checked. */
        final boolean mustGround;
        Vec3 center;
        /**
         * How many projectiles does the test launch: fewer were fired - part of the launch was lost before the stand was sorted out (0 - not verified:
         *  and B-2 in the test both he and his bomb, which is considered {@code bombs}).
         */
        final int ordered;
        int launched;
        /** Test shells that were found outside the world: without them the path outside the world is not tested. */
        final Set<UUID> flewVirtual = new HashSet<>();
        /** Bombs dropped in the air immediately outside the world to a point behind (bunker-behind): path - along the course from the release point. */
        boolean dropBehind;
        /** Where the projectiles reached the surface outside the world and their target: x, z, target x, target z. */
        final List<double[]> groundAt = new ArrayList<>();
        /** Raising the target put the floor above the rocket, and it goes below it (like GameTest {@code virtual_rising_aim}). */
        boolean underFloor;
        final List<Double> groundOff = new ArrayList<>();
        int gaveUp;
        final Map<String, Integer> outcomes = new TreeMap<>();
        /** A target that rises mid-flight (null is a fixed point). */
        @Nullable
        ArmorStand stand;
        @Nullable
        ChunkPos forced;
        /** tick test setting: how long the rack chunk was loaded. */
        int setupTick;
        boolean raised;
        /** B-2 from a combat launch, retargeted outside the world to a point behind itself: approach again and release. */
        boolean b2Behind;
        boolean retargeted;
        int bombs;
        /** Bomb hits: from the target horizontally (early release or release not from the cruise altitude - the bomb is far from the point). */
        final List<Double> impactOff = new ArrayList<>();

        GroundProbe(String name, String how, Vec3 center, int ordered, boolean steering, boolean mustGround) {
            this.name = name;
            this.ordered = ordered;
            this.how = how;
            this.center = center;
            this.steering = steering;
            this.mustGround = mustGround;
        }
    }

    private final List<GroundProbe> groundProbes = new ArrayList<>();
    /** Mod lines from the log (logger stream): parsed into the tick of the stand. */
    private final ConcurrentLinkedQueue<String> modLines = new ConcurrentLinkedQueue<>();
    private static final Pattern GROUNDED = Pattern.compile(
            "Projectile \\S+ ([0-9a-f-]{36}) reached the surface off-world at BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\}, target BlockPos\\{x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\}");
    private static final Pattern GAVE_UP = Pattern.compile("Projectile \\S+ ([0-9a-f-]{36}) gave up waiting for target area");
    /** The guided projectile does not reach the surface further from the target horizontally - otherwise the path would end in the wrong place. */
    private static final double GROUND_OFF_LIMIT = 32;

    private final List<Step> steps = new ArrayList<>();
    /** Waits that are checked every tick (teleport is waiting for the area), up to {@code true}. */
    private final List<BooleanSupplier> waits = new ArrayList<>();
    /** Players whose teleport is still waiting for the area. */
    private final Set<String> pendingTeleports = new HashSet<>();
    /** Teleport by script: tick the script when asked, and how to start it. */
    private record TpRequest(int requestedAt, Runnable start) {}
    /** Player teleports that have not yet started (waiting for the arrival of the previous one or their tick), in order of the scenario. */
    private final Map<String, ArrayDeque<TpRequest>> deferredTeleports = new HashMap<>();
    /** Last teleport of the player who transferred it: tick the script, when asked, and when transferred. */
    private record LastTeleport(int requestedAt, int landedAt) {}
    private final Map<String, LastTeleport> lastTeleports = new HashMap<>();
    /** ticket teleport areas that are still standing: the stand ends only when they are gone. */
    private int teleportTickets;
    /** How to release each worthwhile ticket of the teleport area (the key is its key): the summary releases the remaining ones. */
    private final Map<UUID, Runnable> teleportReleases = new HashMap<>();
    /** Summary printed: no new teleports are starting, expectations have been lifted. */
    private boolean closed;
    private final Map<UUID, Watch> watched = new HashMap<>();
    private final Set<UUID> cleared = new HashSet<>();
    private final Set<UUID> overdueSeen = new HashSet<>();
    /** Explosions of this tick by the projectile whose explosion it is ({@link #blastBy}): the first for each projectile. */
    private final Map<UUID, Vec3> blastsThisTick = new HashMap<>();
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private final Map<String, Integer> launchedByType = new TreeMap<>();
    // explosions noticed during the run: “Hang up” and forgetting remove them from NuclearEvents, and all are included in the summary
    private final Set<String> detonationsSeen = new HashSet<>();
    private final ConcurrentLinkedQueue<String> problems = new ConcurrentLinkedQueue<>();
    private int warnings, errors;

    private int tick = -1;
    /** From what tick server is the script clock waiting for players to take their seats ({@code MAX_VALUE} - no longer waiting). */
    private int placingSince = -1;
    private long tickStart;
    private long windowMax, windowSum;
    private int windowTicks;
    private long worstTick;
    private int worstTickAt;
    private final List<Long> allTicks = new ArrayList<>();
    private long heapBaseline = -1;
    private boolean finishing;
    private int quietSince = -1;
    private int finishingSince = -1;
    /** The stop waits until the generation of chunks ends: from what tick and how many ticks in a row does it stop. */
    private int settlingSince = -1;
    private int settleQuiet;
    /** How many ticks in a row without generation - “settled down”, and how long to wait at most. */
    private static final int SETTLE_QUIET = 100, SETTLE_MAX = 6000;
    /** At what age does the projectile hit the “long flies” line (once every 10 s). */
    private static final int LONG_LIVED = 2400;
    /** Start of the current tick for the guard (the guard thread is reading). */
    private volatile long watchdogTickStart;
    /** The server is ticking inside (between {@code ServerTickEvent.Pre} and {@code Post}), rather than waiting for the next one. */
    private volatile boolean inTick;

    public StressDirector(IEventBus modBus) {
        if (MODE == null) return;
        checkCollector();
        // the explosion damage source field is being searched now: it was not found - the stand does not start, and does not fall on the first explosion
        ExplosionSource.init();
        NeoForge.EVENT_BUS.addListener(this::onStarted);
        NeoForge.EVENT_BUS.addListener(this::onTickPre);
        NeoForge.EVENT_BUS.addListener(this::onTickPost);
        NeoForge.EVENT_BUS.addListener(this::onExplosion);
        NeoForge.EVENT_BUS.addListener(this::onLeave);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onStopping);
        LogWatch.install(problems, () -> warnings++, () -> errors++, modLines::add);
        if ("resume".equals(MODE)) planResume();
        else plan();
    }

    // ---------------------------------------------------------------- script

    private void at(int t, String what, Consumer<MinecraftServer> action) {
        steps.add(new Step(t, what, action));
    }

    /** Command on behalf of the player (as if he had typed it): the launcher is his, the shells are his. */
    private void as(int t, String player, String command) {
        at(t, player + ": /" + command, s -> {
            ServerPlayer p = need(s, player, "/" + command);
            if (p == null) return;
            run(s, p.createCommandSourceStack().withPermission(4), command);
        });
    }

    /** Waiting for this tick; new ones added from them are checked from the next one. Open for inspections. */
    public void runWaits() {
        for (BooleanSupplier w : List.copyOf(waits)) if (w.getAsBoolean()) waits.remove(w);
    }

    /** Garbage collectors JVM (names MXBean: "G1 Young Generation", "ZGC Major Cycles"…). */
    private static String collectors() {
        return String.join(", ", ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList());
    }

    /**
     * The collector and the pile that {@code build.gradle} ordered ({@code INCOMING_RIG_JVM=artem} → {@code incoming.stress.gc=ZGC Minor},
     * {@code incoming.stress.heap=8192}) are those with which JVM works: otherwise the run would silently measure another JVM
     *  (for example, with G1 toolchain instead of ZGC). The collector - at the beginning of the name MXBean: "ZGC Minor" is only
     *  for the generational ZGC (for the non-generational - "ZGC Cycles").
     */
    private static void checkCollector() {
        String want = System.getProperty("incoming.stress.gc");
        if (want != null && !want.isBlank() && ManagementFactory.getGarbageCollectorMXBeans().stream().noneMatch(b -> b.getName().startsWith(want)))
            throw new IllegalStateException("stand: assembler ordered " + want + ", and JVM works with " + collectors());
        Integer heapMb = Integer.getInteger("incoming.stress.heap");
        long maxMb = Runtime.getRuntime().maxMemory() >> 20;
        if (heapMb != null && maxMb < heapMb)
            throw new IllegalStateException("stand: a bunch ordered " + heapMb + " MB, and JVM works with " + maxMb + " MB");
    }

    /** Stand switch from environment variable (via {@code build.gradle}): true/1/yes or false/0/no. */
    private static boolean flag(String property, boolean fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isBlank()) return fallback;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("stand: " + property + "=" + v + " — expected true/false or 1/0");
        };
    }

    private void plan() {
        at(0, "preparation", s -> {
            run(s, "gamerule doDaylightCycle false");
            run(s, "gamerule doWeatherCycle false");
            run(s, "gamerule doMobSpawning false");
            run(s, "gamerule sendCommandFeedback false");
            run(s, "time set 6000");
            for (ServerPlayer p : s.getPlayerList().getPlayers()) s.getPlayerList().op(p.getGameProfile());
            // the stand measures the load of hits, not reconnaissance: the Host remote control hits friends hundreds of blocks away, without seeing them
            ua.zentix.incoming.IncomingConfig.SERVER.sightRules.set(false);
            place(s, "Host", 0, 0);
            place(s, "Friend1", 480, 320);
            place(s, "Friend2", -620, 420);
        });
        // MLRS in a fresh area in 1500 blocks, away from players, until the wave 1: flight (~400 tick) is longer than loading the area
        //  (alone 80–120 tick, cloud, 4 cores) - no deep stretching should, small is acceptable (server
        //  stopped for seconds). Following the 1 wave, the sample would measure the generation queue, not the stretching: dozens of fresh
        //  wave regions are ahead, the region is ready after ~360–900 tick
        if (PROBES) at(200, "MLRS for a fresh area in 1500 blocks", s -> probe(s, "Host", "fresh-1500", -1060, -1060, false));
        // wave 1: volleys at 30 - MLRS at the player, Shahed at a point in 800 blocks, barrage at the player, missiles at the player
        as(300, "Host", "incoming salvo rocket 30 150 Friend1");
        as(320, "Host", "incoming salvo drone 30 150 at 800 ~ -200");
        as(340, "Friend1", "incoming salvo loiter 30 150 Friend2");
        as(360, "Friend2", "incoming salvo missile 20 80 Friend1");
        // MLRS over a fresh area in 250 blocks: the flight is shorter than the loading of the area - the stretch is expected to be deep
        if (PROBES) at(380, "MLRS for a fresh area in 250 blocks", s -> probe(s, "Host", "fresh-250", -180, -175, true));
        // craft Sable from the second friend: missiles hit him, then he gets crushed
        at(500, "craft at Friend2", s -> buildCraft(s, "Friend2"));
        at(560, "rockets and Shahed by craft", s -> strikeCraft(s, "Host", 10, 30));
        // targets that die on approach: residents in 900 blocks, we kill half
        at(700, "target residents at Friend2", s -> villagers(s, "Host", "Friend2", 8));
        at(1100, "death of targets", s -> killHalfVillagers(s));
        // retargeting everything in the world - every 150 tick several projectiles
        for (int t = 800; t <= 3000; t += 150) at(t, "retargeting", this::retargetSome);
        // the player goes off the edge of the load in the middle of hitting it and returns
        at(1300, "Friend1 flies to 3000 blocks", s -> tp(s, "Friend1", 3480, 320));
        at(1900, "Friend1 is back", s -> tp(s, "Friend1", 480, 320));
        // bombers and missiles to another world and the player in flight
        as(1500, "Friend2", "incoming salvo bunker 6 60 Host");
        at(1700, "Friend2 to Nether and back", s -> tp(s, "Friend2", s.getLevel(Level.NETHER), 0, 80, 0));
        at(2100, "Friend2 from Nether", s -> tp(s, "Friend2", -620, 420));
        // the nuke in 1000 blocks while the volleys are going on: flight ICBM 1800 tick - detonation on 3000, until “All Lights” (it cancels it too)
        as(1200, "Host", "incoming nuke at 0 ~ -1000 15 air");
        as(2320, "Friend1", "incoming salvo drone 30 150 Host");
        as(2340, "Host", "incoming salvo rocket 30 150 Friend2");
        // Friend1 goes out in the middle of the strike and returns (the client - through 300 of his tick); exit - at the command of the director,
        //  and not according to the client's watch: otherwise he would get to the step where Friend1 launches himself, and the volley of missiles would be missed
        at(2400, "Friend1 comes out mid impact", s -> leave(s, "Friend1"));
        at(2600, "maintaining peace in mid-flight", s -> run(s, "save-all"));
        // “Cutback” in the middle of wave 2, then wave 3
        at(3200, "Lights out", s -> {
            markCleared(s);
            as(s, "Host", "incoming clear");
        });
        as(3300, "Host", "incoming salvo loiter 30 150 at 600 ~ 600");
        as(3320, "Friend1", "incoming salvo missile 30 150 Friend2");
        as(3340, "Friend2", "incoming salvo rocket 30 150 Host");
        at(3400, "Friend1 comes out mid impact", s -> leave(s, "Friend1"));
        // off-world path to the surface far from everyone, in areas that no one is loading - after the “All Lights” (he removed
        // by them in flight): a bomb on a point behind, dropped immediately off-world (without B-2), MLRS and missiles at a target below the terrain (y 40)
        // from under the relief above the path, missiles at a target that, in the middle of an out-of-world flight, rises 40 blocks. Not with
        //  restart: stopping mid-flight (t=5200) would have left the samples unexamined
        if (!RESTART) {
            at(3500, "bombs on a point behind, out of the world", s -> groundBombs(s, "bunker-behind", -2200, 2200, 3));
            at(3520, "MLRS on a target below the terrain", s -> groundDeep(s, "deep-rocket", WeaponType.ROCKET, 10, 1100, -1300));
            at(3540, "missiles at targets below the terrain", s -> groundDeep(s, "deep-missile", WeaponType.MISSILE, 5, -1600, -300));
            at(3560, "missiles at a target that will rise", s -> groundRising(s, "rising-aim", 1500, 1500));
            at(3580, "B-2 with retargeting to a point behind, outside the world", s -> groundB2Behind(s, "b2-behind", 2600, -2400));
        }
        // wave 4 - for restart: server stop mid-flight, continuation - mode resume
        if (RESTART) {
            as(4600, "Host", "incoming salvo drone 20 100 at 700 ~ 700");
            as(4620, "Host", "incoming salvo missile 10 100 at -700 ~ 700");
            at(5200, "mid-flight stop", s -> {
                summary(s, "restart");
                s.halt(false);
            });
        } else {
            at(4200, "we're waiting for everything to arrive", s -> finishing = true);
        }
    }

    private void planResume() {
        at(0, "after restart", s -> {
            int virt = 0, salvos = 0;
            for (ServerLevel l : s.getAllLevels()) {
                virt += VirtualFlights.get(l).flights().size();
                salvos += SalvoData.get(l).size();
            }
            log("resume: out of the world %d, volleys %d", virt, salvos);
        });
        at(40, "we're waiting for everything to arrive", s -> finishing = true);
    }

    // ---------------------------------------------------------------- actions

    private void place(MinecraftServer s, String name, int x, int z) {
        tp(s, name, x, z);
    }

    /** Player for step; if it is missing, a step is missed, and this is a problem in the summary (the stand does not pass with fewer steps). */
    @Nullable
    private ServerPlayer need(MinecraftServer s, String name, String what) {
        ServerPlayer p = s.getPlayerList().getPlayerByName(name);
        if (p == null) {
            log("skip %s: no player %s", what, name);
            problems.add("шаг пропущен, нет игрока " + name + ": " + what);
        }
        return p;
    }

    /** The line where the stand client exits the game himself, like a player in the middle of a hit ({@link StressClient}). */
    static final String LEAVE = "incoming-stress: leave";

    private void leave(MinecraftServer s, String name) {
        ServerPlayer p = need(s, name, "exit");
        if (p == null) return;
        p.sendSystemMessage(Component.literal(LEAVE));
        log("%s comes out", name);
    }

    /** Teleport to the upper world at a height of 200: height without loading a chunk, then the player flies in creative mode. */
    private void tp(MinecraftServer s, String name, int x, int z) {
        tp(s, name, s.overworld(), x, 200, z);
    }

    /**
     * Teleporting the player, like a game with loading in the background: first the area ({@link #teleportArea}) is loaded (ticket loading,
     *  without tick: {@code DistanceManager.addTicket} does not touch the tick count, and the block entities of the area do not tick next to
     *  with unready neighbors), the player is transferred when everyone is ready. Immediately into the unready area,
     * {@code teleportTo} loads chunks synchronously directly into the tick - and the server stops at 2–8 s the
     *  stand itself. The player's teleport begins as many ticks after the arrival of the previous one as the script
     *  allocated between them, and while the previous one is waiting for the area, he is also waiting ({@link #nextTeleport}): the canceled one would have left the player
     *  without a place (returning from a long flight and from Nether would have canceled the flight itself), the one started immediately turned would
     * fly or Nether in one tick, and both would immediately wait for the area in one tick, and the player would end up where he was
     *  sent earlier.
     */
    private void tp(MinecraftServer s, String name, @Nullable ServerLevel level, int x, int y, int z) {
        if (closed) return;
        ServerPlayer p = need(s, name, "teleport");
        if (p == null) return;
        if (level == null) {
            problems.add("шаг пропущен: телепорт " + name + ", мира назначения нет");
            return;
        }
        ArrayDeque<TpRequest> queue = deferredTeleports.computeIfAbsent(name, k -> new ArrayDeque<>());
        int requestedAt = tick;
        queue.add(new TpRequest(requestedAt, () -> begin(s, name, level, x, y, z, requestedAt)));
        if (queue.size() == 1 && !pendingTeleports.contains(name)) nextTeleport(name);
        else log("tp %s → %d %d: in line for the previous teleport", name, x, z);
    }

    /**
     * The player's first delayed teleport is as many ticks after the previous one's arrival as the script allowed between
     *  them (not before it was asked). Before the start, he remains in the queue: the next teleport stands behind him, and not
     *  starts on its own.
     */
    private void nextTeleport(String name) {
        ArrayDeque<TpRequest> queue = deferredTeleports.get(name);
        TpRequest next = queue == null ? null : queue.peek();
        if (next == null) return;
        LastTeleport last = lastTeleports.get(name);
        int start = last == null ? tick : Math.max(tick, last.landedAt() + next.requestedAt() - last.requestedAt());
        Runnable run = () -> {
            queue.poll();
            if (tick > next.requestedAt()) {
                log("tp %s starts at %d tick later than the script: the former teleport was waiting for the area", name, tick - next.requestedAt());
            }
            next.start().run();
        };
        // the step in this tick will no longer be executed (tick steps are iterated over the copy): now - immediately, otherwise - step by step
        if (start <= tick) run.run();
        else at(start, "delayed teleport " + name, sv -> run.run());
    }

    /** Start teleport: the area is loaded, the player is transferred when ready. Open for inspections. */
    public void begin(MinecraftServer s, String name, ServerLevel level, int x, int y, int z, int requestedAt) {
        if (closed) return;
        ChunkPos centre = new ChunkPos(x >> 4, z >> 4);
        DistanceManager tickets = level.getChunkSource().chunkMap.getDistanceManager();
        UUID key = UUID.randomUUID();
        int area = teleportArea(s), ticketLevel = ChunkLevel.byStatus(FullChunkStatus.FULL) - area;
        tickets.addTicket(TELEPORT, centre, ticketLevel, key);
        teleportTickets++;
        Runnable release = () -> {
            if (teleportReleases.remove(key) == null) return;
            tickets.removeTicket(TELEPORT, centre, ticketLevel, key);
            teleportTickets--;
        };
        teleportReleases.put(key, release);
        // tick the server, not the script: while the players are standing, the script clock is running
        int since = s.getTickCount();
        // from which tick server the player is not in the game (-1 - he is in the game)
        int[] offlineSince = {-1};
        pendingTeleports.add(name);
        log("tp %s → %s %d %d: loading the area", name, level.dimension().location(), x, z);
        waits.add(() -> {
            boolean ready = areaReady(level, centre, area);
            // not the limit: the teleport waits further (and holds the ticket), but in the summary this is a problem
            if (!ready && s.getTickCount() - since == TELEPORT_WAIT) {
                log("tp %s → %d %d: the area is not ready for %d tick, we wait further", name, x, z, TELEPORT_WAIT);
                problems.add(String.format(Locale.ROOT, "teleport %s is waiting for area %d %d longer %d tick", name, x, z, TELEPORT_WAIT));
            }
            // there is no player - exited: the exit proceeds according to the script clock, and the teleport could move behind the previous one and fall out for a while,
            //  while he is out of the game (the return of Friend1, delayed by hundreds of ticks, occurs at his exit - “a step is missed”).
            //  The teleport is waiting for his entry, the area holds; stand player input - himself, via 300 client tick
            ServerPlayer now = s.getPlayerList().getPlayerByName(name);
            if (now == null) {
                if (offlineSince[0] < 0) {
                    offlineSince[0] = s.getTickCount();
                    log("tp %s → %d %d: the player is not in the game, we are waiting for his entry", name, x, z);
                } else if (s.getTickCount() - offlineSince[0] == TELEPORT_WAIT) {
                    problems.add(String.format(Locale.ROOT, "teleport %s waits longer for player to enter %d tick", name, TELEPORT_WAIT));
                }
                return false;
            }
            offlineSince[0] = -1;
            if (!ready) return false;
            pendingTeleports.remove(name);
            now.teleportTo(level, x + 0.5, y, z + 0.5, now.getYRot(), 0);
            LastTeleport last = lastTeleports.get(name);
            log("tp %s → %d %d, the area was waiting for %d ticks%s", name, x, z, s.getTickCount() - since, last == null ? ""
                : String.format(Locale.ROOT, "; in the same place %d tick, according to the script %d", tick - last.landedAt(), requestedAt - last.requestedAt()));
            lastTeleports.put(name, new LastTeleport(requestedAt, tick));
            nextTeleport(name);
            // further the place is held by the player’s ticket: ours is released when they stand on the square around the chunk where the player is
            //  now (ChunkMap.move - after confirmation of the teleport by the client, and the player’s ticket stands several per tick,
            // DistanceManager.ticketThrottler: a square released in one center would lose full loading on edges),
            // —or through TELEPORT_HOLD, if the player left the area (in another world, flew further). The exiting area waits until
            //  entry, and the sample comes from the entrance: the area released after it would be unloaded, and the tick of the enterer would load its chunk
            //  synchronously behind the volley generation queue (stopping for tens of seconds). Tickets
            //  of the player - according to the viewing range of the server (ChunkMap.setServerViewDistance → updatePlayerTickets). Release
            //  early - the player is in an unready area: isInWall in his tick loads the chunk synchronously. Coverage - around the player,
            //  and not the center: the stand's friends fly and leave the central chunk for a tick (in the center, all areas of
            //  would be released through TELEPORT_HOLD "without a ticket player", for a standing Host - through 6 tick)
            int placed = s.getTickCount();
            // from which tick server to count the period: transfer or entry of the player who left after it
            int[] from = {placed};
            boolean[] away = {false};
            waits.add(() -> {
                ServerPlayer there = s.getPlayerList().getPlayerByName(name);
                if (there == null) {
                    if (!away[0]) log("teleport area %s remains until the player enters", name);
                    away[0] = true;
                    from[0] = s.getTickCount();
                    return false;
                }
                away[0] = false;
                boolean inArea = there.serverLevel() == level && there.chunkPosition().getChessboardDistance(centre) <= area;
                boolean arrived = inArea && playerTicketsCover(level, there.chunkPosition(), Math.min(area, s.getPlayerList().getViewDistance()));
                // a player in the area waits for his ticket (up to TELEPORT_WAIT), a player who leaves - no longer than TELEPORT_HOLD
                if (!arrived && s.getTickCount() - from[0] < (inArea ? TELEPORT_WAIT : TELEPORT_HOLD)) return false;
                release.run();
                log("teleport area %s released after %d ticks%s", name, s.getTickCount() - placed, arrived ? "" : ", the player’s ticket is not on it");
                return true;
            });
            return true;
        });
    }

    /**
     * Summary: ticketed teleport areas should be released, waiting and delayed teleports should be removed - the ticket in the summary line
     *  would have been a leak, and waiting would have added a problem after it (a player who never entered: the client crashed, disconnected
     *  by timeout). New teleports do not start after this. How many tickets have been issued? Open for inspections.
     */
    public int closeTeleports() {
        closed = true;
        int left = teleportReleases.size();
        for (Runnable release : List.copyOf(teleportReleases.values())) release.run();
        waits.clear();
        deferredTeleports.clear();
        pendingTeleports.clear();
        return left;
    }

    /** Ticket-worthy teleport areas (checks). */
    public int teleportTicketCount() {
        return teleportTickets;
    }

    /** Run (check) problems. */
    public List<String> problemList() {
        return List.copyOf(problems);
    }

    /**
     * On each chunk of the square {@code radius} around {@code centre} there is a player’s ticket ({@code TicketType.PLAYER}, within range of
     * ): the area is held by the player himself.
     */
    private static boolean playerTicketsCover(ServerLevel level, ChunkPos centre, int radius) {
        var map = ticketMap(level.getChunkSource().chunkMap.getDistanceManager());
        if (map == null) return false;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                var set = map.get(ChunkPos.asLong(centre.x + dx, centre.z + dz));
                if (set == null || set.stream().noneMatch(t -> t.getType() == TicketType.PLAYER)) return false;
            }
        }
        return true;
    }

    private static boolean areaReady(ServerLevel level, ChunkPos centre, int area) {
        for (int dx = -area; dx <= area; dx++) {
            for (int dz = -area; dz <= area; dz++) if (!Terrain.ready(level, centre.x + dx, centre.z + dz)) return false;
        }
        return true;
    }

    /**
     * craft Sable in 24 blocks from the player, on the ground: the player flies at a height of 200, and a craft collected in the air,
     *  would fall to the impact of dozens of blocks. It is then searched by its UUID, and not by location.
     */
    private void buildCraft(MinecraftServer s, String near) {
        ServerPlayer p = need(s, near, "craft");
        if (p == null) return;
        ServerLevel level = p.serverLevel();
        BlockPos at = p.blockPosition().offset(24, 0, 0);
        // fill and Sable would load unready chunks directly into tick (the stand itself would stand up for tens of seconds): wait for ready ones
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!Terrain.ready(level, (at.getX() >> 4) + dx, (at.getZ() >> 4) + dz)) {
                    log("chunks for craft are still loading - in a second");
                    at(tick + 20, "craft " + near + " (repeat)", sv -> buildCraft(sv, near));
                    return;
                }
            }
        }
        BlockPos c = new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()) + 1, at.getZ());
        run(s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks", c.getX() - 4, c.getY(), c.getZ() - 3, c.getX() + 4, c.getY() + 2, c.getZ() + 3));
        run(s, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", c.getX() - 2, c.getY() + 3, c.getZ() - 1, c.getX() + 2, c.getY() + 3, c.getZ() + 1));
        run(s, s.createCommandSourceStack(), String.format(Locale.ROOT, "sable assemble area %d %d %d %d %d %d", c.getX() - 4, c.getY(), c.getZ() - 3, c.getX() + 4, c.getY() + 3, c.getZ() + 3));
        craftCenter = Vec3.atCenterOf(c);
        findCraft(s, 0);
    }

    /** UUID assembled craft: Sable can complete it not in the same tick - we are looking for up to a second. */
    private void findCraft(MinecraftServer s, int attempt) {
        var subs = ua.zentix.incoming.compat.SubLevels.near(s.overworld(), craftCenter, 8);
        if (!subs.isEmpty()) {
            craftId = subs.get(0).getUniqueId();
            log("craft %s assembled from %.0f %.0f %.0f", craftId, craftCenter.x, craftCenter.y, craftCenter.z);
        } else if (attempt < 20) {
            at(tick + 1, "search craft", sv -> findCraft(sv, attempt + 1));
        } else {
            problems.add(String.format(Locale.ROOT, "craft failed %.0f %.0f %.0f", craftCenter.x, craftCenter.y, craftCenter.z));
        }
    }

    private Vec3 craftCenter;
    @Nullable
    private UUID craftId;

    private void strikeCraft(MinecraftServer s, String shooter, int missiles, int drones) {
        ServerPlayer p = need(s, shooter, "blow to craft");
        if (p == null) return;
        if (craftId == null) {
            problems.add("шаг пропущен: удар по аппарату, аппарат не построен");
            return;
        }
        var sub = ua.zentix.incoming.compat.SubLevels.byId(s.overworld(), craftCenter, craftId);
        if (sub == null) {
            problems.add("шаг пропущен: удар по аппарату, аппарата " + craftId + " больше нет");
            return;
        }
        Vec3 c = ua.zentix.incoming.compat.SubLevels.center(sub);
        log("craft %s y %.0f %.0f %.0f", craftId, c.x, c.y, c.z);
        var aim = new ua.zentix.incoming.strike.ServerActions.Aim(new Target.OfSubLevel(ua.zentix.incoming.compat.SubLevels.toPlot(sub, c)), c, null);
        // the stand director is like an operator’s team: without rules and ammunition from the inventory
        ua.zentix.incoming.strike.ServerActions.strike(p, ua.zentix.incoming.strike.WeaponType.MISSILE, missiles, 6, aim,
                ua.zentix.incoming.strike.Loadout.Nuke.DEFAULT, ua.zentix.incoming.strike.Waypoints.NONE, null, false);
        ua.zentix.incoming.strike.ServerActions.strike(p, ua.zentix.incoming.strike.WeaponType.DRONE, drones, 10, aim,
                ua.zentix.incoming.strike.Loadout.Nuke.DEFAULT, ua.zentix.incoming.strike.Waypoints.NONE, null, false);
    }

    /**
     * MLRS salvo from 10 at a point on the ground (x, z): height - without loading a chunk ({@link Target.Ground#at}), the area is fresh,
     *  if no one was nearby. The projectiles refer to the sample at the target point.
     */
    private void probe(MinecraftServer s, String shooter, String name, int x, int z, boolean shortFlight) {
        ServerPlayer p = need(s, shooter, "salvo-test " + name);
        if (p == null) return;
        ServerLevel level = p.serverLevel();
        // “fresh” - the target chunk is not ready (not yet generated or loaded): then the projectile is waiting for the area; The chunk holder
        //  is in memory and is far beyond the drawing range, so don’t judge by it
        boolean fresh = !Terrain.ready(level, x >> 4, z >> 4);
        Target.Ground ground = Target.Ground.at(level, x, z);
        Vec3 point = ground.pos();
        probes.add(new Probe(name, point, shortFlight, level, tick));
        log("probe %s: MLRS 10 at %d %d %d, %.0f blocks from %s, area %s", name, x, (int) point.y, z, Math.hypot(x - p.getX(), z - p.getZ()),
                shooter, fresh ? "not ready" : "already ready");
        if (!fresh) problems.add("probe " + name + ": area is already ready - tension not checked");
        // as operator command: no ammunition from inventory
        ua.zentix.incoming.strike.ServerActions.strike(p, ua.zentix.incoming.strike.WeaponType.ROCKET, 10, PROBE_SPREAD,
                new ua.zentix.incoming.strike.ServerActions.Aim(ground, point, null), ua.zentix.incoming.strike.Loadout.Nuke.DEFAULT,
                ua.zentix.incoming.strike.Waypoints.NONE, null, false);
    }

    /** Bombs immediately out of world (without B-2: dropping {@code drop} in the air) to a point in 300 blocks behind and 150 below (as GameTest virtualMissNeverFallsBelowGround). */
    private void groundBombs(MinecraftServer s, String name, int x, int z, int count) {
        ServerLevel level = s.overworld();
        Vec3 behind = null;
        for (int i = 0; i < count; i++) {
            Vec3 from = new Vec3(x + 48 * i + 0.5, level.getSeaLevel() + 200, z + 0.5);
            Vec3 aim = from.add(0, -150, -300);
            if (behind == null) behind = aim;
            BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
            if (bomb == null) continue;
            bomb.drop(from, BomberEntity.levelVelocity(0), aim, null, null);
            VirtualFlights.launch(level, bomb);
        }
        if (behind != null) {
            GroundProbe pr = new GroundProbe(name, "the bomb is immediately outside the world, without B-2 and its entry; ballistics, unguided",
                    behind.add(48 * (count - 1) / 2.0, 0, 0), count, false, true);
            pr.dropBehind = true;
            groundProbes.add(pr);
        }
        log("probe %s: %d bombs outside the world at %d %d toward a point behind", name, count, x, z);
    }

    /** A salvo from afar (without a player: launch outside the world) at a point on y 40 - below the terrain, above the terrain. */
    private void groundDeep(MinecraftServer s, String name, WeaponType weapon, int count, int x, int z) {
        ServerLevel level = s.overworld();
        Vec3 aim = new Vec3(x + 0.5, 40, z + 0.5);
        groundProbes.add(new GroundProbe(name, "combat launch from afar (dispatch)", aim, count, true, false));
        log("probe %s: %s %d at %d 40 %d (terrain above target %d), area %s", name, weapon.name().toLowerCase(Locale.ROOT), count, x, z,
                (int) Target.Ground.at(level, x, z).pos().y, Terrain.ready(level, x >> 4, z >> 4) ? "already ready" : "not ready");
        ua.zentix.incoming.strike.ServerActions.dispatch(level, "stand " + name, 0, weapon, count, 8,
                new ua.zentix.incoming.strike.ServerActions.Aim(new Target.Point(aim), aim, null), ua.zentix.incoming.strike.Loadout.Nuke.DEFAULT,
                ua.zentix.incoming.strike.Waypoints.NONE, null);
    }

    /** Holds a sample rack chunk: level 31 - entities are ticking (like a forced download), loading in the background. */
    private static final TicketType<ChunkPos> PROBE_TICKET = TicketType.create("incoming_stress_probe", Comparator.comparingLong(ChunkPos::toLong));

    /** The height of the sample stand rising-aim: below the sea, like the target below the relief of the samples deep. */
    private static final int RISING_AIM_Y = 40;
    /** Missiles on the sample rack rising-aim. */
    private static final int RISING_SHOTS = 3;

    /**
     * Missiles from afar along the rack (target-entity) in its forcedly loaded chunk: the rack is placed when the chunk
     *  is ready (without synchronous loading), on y {@link #RISING_AIM_Y} - below the sea, and a rocket outside the world goes below sea level
     *  (half of the flight over unready chunks); when the rocket is still outside the world, far from the rack, it rises to 40 blocks
     *  and the floor rises above the rocket - like GameTest virtualMissileClimbsAfterRisingAim.
     */
    private void groundRising(MinecraftServer s, String name, int x, int z) {
        ServerLevel level = s.overworld();
        GroundProbe pr = new GroundProbe(name, "combat launch from afar (dispatch) on the rack", new Vec3(x + 0.5, level.getSeaLevel(), z + 0.5), RISING_SHOTS, true, false);
        pr.forced = new ChunkPos(x >> 4, z >> 4);
        pr.setupTick = tick;
        // ticket in the background, like the target area: setChunkForced loads a fresh chunk immediately (tick to 8 s)
        level.getChunkSource().addRegionTicket(PROBE_TICKET, pr.forced, 2, pr.forced);
        groundProbes.add(pr);
        log("probe %s: target armor stand at %d %d, waiting for its chunk", name, x, z);
    }

    /**
     * B-2 combat launch from afar (outside the world, far from players); when he is out of the world in 400–1000 blocks from a point, he is
     *  retargeted to a point in 60 blocks behind him, closer to the drop range (like GameTest
     * bomberRetargetedJustBehindHitsOnReattack): he leaves, enters again and drops - a bomb period, not before.
     */
    private void groundB2Behind(MinecraftServer s, String name, int x, int z) {
        ServerLevel level = s.overworld();
        Target.Ground aim = Target.Ground.at(level, x + 0.5, z + 0.5);
        GroundProbe pr = new GroundProbe(name, "combat launch B-2 from afar (dispatch), retargeting off-world to 60 blocks behind", aim.pos(), 0, true, false);
        pr.b2Behind = true;
        groundProbes.add(pr);
        log("probe %s: B-2 at %d %d %d", name, x, (int) aim.pos().y, z);
        ua.zentix.incoming.strike.ServerActions.dispatch(level, "stand " + name, 0, WeaponType.BUNKER, 1, 0,
                new ua.zentix.incoming.strike.ServerActions.Aim(aim, aim.pos(), null), ua.zentix.incoming.strike.Loadout.Nuke.DEFAULT,
                ua.zentix.incoming.strike.Waypoints.NONE, null);
    }

    /** Sample stand - when its chunk is ready; rise - when the rocket is outside the world in 300–1000 blocks from it. */
    private void tickGroundProbes(MinecraftServer s) {
        ServerLevel level = s.overworld();
        for (GroundProbe pr : groundProbes) {
            if (pr.b2Behind && !pr.retargeted) retargetBehind(level, pr);
            if (pr.forced == null) continue;
            BlockPos c = BlockPos.containing(pr.center);
            if (pr.stand == null && Terrain.ready(level, c) && level.isPositionEntityTicking(c)) {
                ArmorStand stand = EntityType.ARMOR_STAND.create(level);
                if (stand == null) continue;
                int y = RISING_AIM_Y;
                stand.setNoGravity(true);
                stand.moveTo(pr.center.x, y, pr.center.z);
                level.addFreshEntity(stand);
                pr.stand = stand;
                pr.center = stand.position();
                log("probe %s: armor stand at %d %d %d (chunk loaded for %d ticks), launching %d missiles from afar", pr.name, c.getX(), y, c.getZ(), tick - pr.setupTick, RISING_SHOTS);
                ua.zentix.incoming.strike.ServerActions.dispatch(level, "stand " + pr.name, 0, WeaponType.MISSILE, RISING_SHOTS, 0,
                        new ua.zentix.incoming.strike.ServerActions.Aim(Target.OfEntity.center(stand), stand.position(), null),
                        ua.zentix.incoming.strike.Loadout.Nuke.DEFAULT, ua.zentix.incoming.strike.Waypoints.NONE, null);
                continue;
            }
            if (pr.stand == null) continue;
            if (pr.raised) {
                // half a flight outside the world: sea level above unfinished chunks, but not below the target by 16 blocks
                for (Watch w : watched.values())
                    if (w.ground == pr && w.virtual && w.pos.y < Math.min(level.getSeaLevel(), w.aim.y - 16)) pr.underFloor = true;
                continue;
            }
            for (Watch w : watched.values()) {
                if (w.ground != pr || !w.virtual) continue;
                double d = Math.hypot(w.pos.x - pr.stand.getX(), w.pos.z - pr.stand.getZ());
                if (d > 300 && d < 1000) {
                    pr.raised = true;
                    pr.stand.teleportTo(pr.stand.getX(), pr.stand.getY() + 40, pr.stand.getZ());
                    pr.center = pr.stand.position();
                    log("probe %s: target raised by 40 blocks, out-of-world missile at %.0f blocks", pr.name, d);
                    break;
                }
            }
        }
    }

    private void retargetBehind(ServerLevel level, GroundProbe pr) {
        for (Watch w : watched.values()) {
            if (w.ground != pr || !(w.ref instanceof BomberEntity b) || !w.virtual || b.hasReleased()) continue;
            Vec3 to = new Vec3(w.aim.x - w.pos.x, 0, w.aim.z - w.pos.z);
            double d = to.length();
            if (d < 400 || d > 1000) continue;
            Vec3 back = w.pos.subtract(to.scale(60 / d));
            Target.Ground behind = Target.Ground.at(level, back.x, back.z);
            if (!b.retarget(behind, behind.pos())) {
                problems.add("probe-surface " + pr.name + ": B-2 did not accept retargeting");
                pr.retargeted = true;
                return;
            }
            pr.retargeted = true;
            pr.center = behind.pos();
            log("probe %s: B-2 outside the world %.0f blocks from target retargeted to %d %d %d (60 blocks behind)", pr.name, d,
                    (int) behind.pos().x, (int) behind.pos().y, (int) behind.pos().z);
            return;
        }
    }

    /** Mod lines: the path outside the world reached the surface, did not wait for the area to load. */
    private void readModLines() {
        for (String line; (line = modLines.poll()) != null; ) {
            Matcher m = GROUNDED.matcher(line);
            if (m.find()) {
                Watch w = watched.get(UUID.fromString(m.group(1)));
                if (w != null && w.ground != null) {
                    double dx = Integer.parseInt(m.group(2)) - Integer.parseInt(m.group(5));
                    double dz = Integer.parseInt(m.group(4)) - Integer.parseInt(m.group(7));
                    w.ground.groundOff.add(Math.hypot(dx, dz));
                    w.ground.groundAt.add(new double[] {Integer.parseInt(m.group(2)) + 0.5, Integer.parseInt(m.group(4)) + 0.5,
                            Integer.parseInt(m.group(5)) + 0.5, Integer.parseInt(m.group(7)) + 0.5});
                }
                continue;
            }
            m = GAVE_UP.matcher(line);
            if (m.find()) {
                gaveUpIds.add(UUID.fromString(m.group(1)));
                Watch w = watched.get(UUID.fromString(m.group(1)));
                if (w != null && w.ground != null) w.ground.gaveUp++;
            }
        }
    }

    @Nullable
    private GroundProbe groundProbeFor(Vec3 aim) {
        for (GroundProbe pr : groundProbes)
            if (Math.hypot(aim.x - pr.center.x, aim.z - pr.center.z) < PROBE_RADIUS) return pr;
        return null;
    }

    @Nullable
    private Probe probeFor(Vec3 aim) {
        for (Probe pr : probes) if (Math.hypot(aim.x - pr.center.x, aim.z - pr.center.z) < PROBE_RADIUS) return pr;
        return null;
    }

    private final List<UUID> villagers = new ArrayList<>();

    private void villagers(MinecraftServer s, String shooter, String near, int n) {
        ServerLevel level = s.overworld();
        ServerPlayer p = need(s, shooter, "target residents");
        ServerPlayer host = need(s, near, "target residents");
        if (host == null) return;
        Vec3 at = host.position().add(-40, 0, 30);
        for (int i = 0; i < n; i++) {
            BlockPos pos = BlockPos.containing(at.add(i * 12, 0, 0));
                        Entity e = EntityType.VILLAGER.create(level);
            if (e == null) continue;
            if (!ua.zentix.incoming.util.Terrain.ready(level, pos)) continue; // we don’t load chunk for the sake of purpose
            e.moveTo(pos.getX() + 0.5, ua.zentix.incoming.util.Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()), pos.getZ() + 0.5);
            level.addFreshEntity(e);
            villagers.add(e.getUUID());
            if (p != null) run(s, p.createCommandSourceStack().withPermission(4), "incoming drone " + e.getStringUUID());
        }
        log("target residents: %d", villagers.size());
    }

    private void killHalfVillagers(MinecraftServer s) {
        int k = 0;
        for (int i = 0; i < villagers.size(); i += 2) {
            Entity e = s.overworld().getEntity(villagers.get(i));
            if (e != null) {
                e.kill();
                k++;
            }
        }
        log("targets killed: %d of %d", k, villagers.size());
    }

    private void retargetSome(MinecraftServer s) {
        ServerLevel level = s.overworld();
        List<StrikeProjectile> inWorld = new ArrayList<>(level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> true));
        int n = 0;
        for (int i = 0; i < inWorld.size() && n < 4; i += 3) {
            StrikeProjectile p = inWorld.get(i);
            List<ServerPlayer> players = s.getPlayerList().getPlayers();
            boolean atPlayer = level.random.nextBoolean() && !players.isEmpty();
            Target t;
            Vec3 point;
            if (atPlayer) {
                ServerPlayer v = players.get(level.random.nextInt(players.size()));
                if (v.level() != level) continue;
                t = new Target.OfEntity(v.getUUID(), new Vec3(0, 1, 0));
                point = v.position().add(0, 1, 0);
            } else {
                point = p.position().add(level.random.nextInt(401) - 200, 0, level.random.nextInt(401) - 200);
                BlockPos col = BlockPos.containing(point);
                int y = Terrain.ready(level, col) ? Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, col.getX(), col.getZ()) : 70;
                point = new Vec3(point.x, y, point.z);
                t = new Target.Point(point);
            }
            if (p.retarget(t, point)) n++;
        }
        log("retargeted: %d of %d in the world", n, inWorld.size());
    }

    private void markCleared(MinecraftServer s) {
        cleared.addAll(watched.keySet());
    }

    // ---------------------------------------------------------------- observation

    private void onStarted(ServerStartedEvent e) {
        log("server started, mode %s, tests %s, restart %s, waiting for players: %d; JVM: collector %s, heap up to %d MB", MODE, PROBES ? "on" : "off",
                RESTART ? "on" : "off", PLAYERS, collectors(), Runtime.getRuntime().maxMemory() >> 20);
        startWatchdog(e.getServer());
    }

    private void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        log("input: %s (players %d)", e.getEntity().getGameProfile().getName(), e.getEntity().getServer().getPlayerCount());
    }

    private void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        log("output: %s", e.getEntity().getGameProfile().getName());
    }

    /** How the projectile left the world (reason for removal, phase, age) - to analyze the missing. */
    private final Map<UUID, String> leftHow = new HashMap<>();

    private void onLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof StrikeProjectile p)) return;
        leftHow.put(p.getUUID(), p.getRemovalReason() + " phase " + p.flightPhase().getSerializedName() + " age " + p.age()
                + " ticking " + ((ServerLevel) e.getLevel()).isPositionEntityTicking(p.blockPosition()));
    }

    /** The shell is removed, the result is its explosion, which may come later ({@link #BLAST_WAIT}). */
    private record Gone(UUID id, Watch w, Vec3 end, @Nullable String how, int at, Level level, long gameTime) {}

    /**
     * How many ticks a removed projectile waits for its explosion: in an unready area, the vanilla explosion
     * is deferred until loading ({@code Warheads.whenReady}), up to {@link Warheads#DEFERRED_GIVE_UP_TICKS},
     * then cancelled. A projectile that reenters the world and is shot down in the same tick explodes later.
     * The damage source identifies its explosion ({@link #blastBy}); a neighbor cannot claim it, so waiting
     * the full duration is safe: an area chunk on the rig can take more than 1400 ticks to load.
     */
    private static final int BLAST_WAIT = Warheads.DEFERRED_GIVE_UP_TICKS + 20;
    private final List<Gone> awaitingBlast = new ArrayList<>();
    /** Shells that the mod removed without waiting for the target area to load ({@link #GAVE_UP}). */
    private final Set<UUID> gaveUpIds = new HashSet<>();

    /**
     * Whose explosion: the projectile is a direct source of explosion damage (warhead {@code Warheads.explode}, including one delayed until
     *  is ready for the area, and a crash at the start). Out of place: the shell explodes at the target in 20 blocks ahead of the cleaning site
     *  (step and detonation range), and in the salvo nearby neighbors explode. {@link Explosion} does not have an open damage source field.
     */
    @Nullable
    public static UUID blastBy(Explosion x) {
        StrikeProjectile p = blastProjectile(x);
        return p == null ? null : p.getUUID();
    }

    /** The projectile whose explosion is ({@link #blastBy}): the object itself - already removed from the world, with the location and phase at the time of impact. */
    @Nullable
    public static StrikeProjectile blastProjectile(Explosion x) {
        DamageSource source;
        try {
            source = (DamageSource) ExplosionSource.FIELD.get(x);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return source != null && source.getDirectEntity() instanceof StrikeProjectile p ? p : null;
    }

    /** {@code Explosion.damageSource}: searched when starting the stand ({@link #init}) or at the first question of the test, whose explosion. */
    private static final class ExplosionSource {
        static final Field FIELD;

        /** The class is loaded - the field is found (otherwise there is an initialization error here at startup). */
        static void init() {}

        static {
            try {
                FIELD = Explosion.class.getDeclaredField("damageSource");
                FIELD.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void ended(Watch w, String outcome) {
        outcomes.merge(w.type + ":" + outcome, 1, Integer::sum);
        if (w.probe != null) w.probe.outcomes.merge(outcome, 1, Integer::sum);
        if (w.ground != null) w.ground.outcomes.merge(outcome, 1, Integer::sum);
    }

    /** The explosion counts; the bomb has samples - also how far from the point it hit (the place of cleaning). */
    private void impact(Gone g) {
        ended(g.w, "impact");
        if (g.w.ground != null && "bunker_buster".equals(g.w.type))
            g.w.ground.impactOff.add(Math.hypot(g.end.x - g.w.aim.x, g.end.z - g.w.aim.z));
    }

    private void lost(Gone g, String note) {
        Watch w = g.w;
        // end - according to the object itself: a projectile removed outside the world (the power reserve has run out) does not send a departure event, and “left”
        //  then speaks of its last departure from the world into flight outside the world, before the end
        log("lost %s %s at %d %d %d, last seen at %d %d %d (target %d %d %d, out of world %b, end: %s phase %s age %d, no explosion %d ticks%s; "
                        + "last departure from the world: %s)",
                w.type, g.id, (int) g.end.x, (int) g.end.y, (int) g.end.z, (int) w.pos.x, (int) w.pos.y, (int) w.pos.z,
                (int) w.aim.x, (int) w.aim.y, (int) w.aim.z, w.virtual,
                w.ref.getRemovalReason(), w.ref.flightPhase().getSerializedName(), w.ref.age(), tick - g.at, note, g.how);
        ended(w, "lost");
    }

    private void onExplosion(ExplosionEvent.Start e) {
        UUID by = blastBy(e.getExplosion());
        if (by != null) blastsThisTick.putIfAbsent(by, e.getExplosion().center());
    }

    private void onTickPre(ServerTickEvent.Pre e) {
        tickStart = System.nanoTime();
        watchdogTickStart = tickStart;
        inTick = true;
    }

    /**
     * Watchman: tick takes longer than 0,5 s - the server thread stack in the log (then once every 2 s, until 5 snapshots to stop). So you can see
     *  who is holding the tick: vanilla loading of chunks, a mod, or the stand itself.
     */
    private void startWatchdog(MinecraftServer s) {
        Thread server = s.getRunningThread();
        Thread dog = new Thread(() -> {
            long dumpedFor = 0;
            int dumps = 0;
            while (s.isRunning()) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ex) {
                    return;
                }
                long start = watchdogTickStart;
                long ms = (System.nanoTime() - start) / 1_000_000;
                // first stack - at 500 ms (whose long tick: ours, Sable or teleport), then - every 2 s
                if (start == 0 || ms < 500) continue;
                if (dumpedFor != start) {
                    dumpedFor = start;
                    dumps = 0;
                }
                if (dumps >= 5 || ms < (dumps == 0 ? 500 : 2000L * dumps)) continue;
                if (dumps == 0) diagnoseStall(s, start);
                dumps++;
                StringBuilder sb = new StringBuilder();
                StackTraceElement[] st = server.getStackTrace();
                for (int i = 0; i < Math.min(60, st.length); i++) sb.append("\n    at ").append(st[i]);
                Incoming.LOG.info("STRESS tick goes {} ms, server stack: {}", ms, sb);
            }
        }, "incoming-stress-watchdog");
        dog.setDaemon(true);
        dog.start();
    }

    /**
     * What's waiting for a worthwhile tick: a task in the queue of each world's chunk thread ({@code ServerChunkCache.mainThreadProcessor}).
     * Synchronous loading ({@code ServerChunkCache.getChunk}) waits in {@code managedBlock} and executes the tasks of this
     * queue - the report is written from the server thread in the middle of a stop, without racing with ticket maps. The task that reached the
     *  thread after this tick is silent.
     */
    private void diagnoseStall(MinecraftServer s, long stallStart) {
        for (ServerLevel level : s.getAllLevels()) {
            try {
                Field f = net.minecraft.server.level.ServerChunkCache.class.getDeclaredField("mainThreadProcessor");
                f.setAccessible(true);
                ((java.util.concurrent.Executor) f.get(level.getChunkSource())).execute(() -> {
                    // only in the middle of the same stop: after it the queue executes the task between tick or in the next
                    if (inTick && watchdogTickStart == stallStart) log("stop: %s", stallReport(level));
                });
            } catch (ReflectiveOperationException ex) {
                log("stop: chunk thread queue unavailable (%s)", ex);
                return;
            }
        }
    }

    /**
     * Line for measuring stops: which chunk is loading synchronously (unready chunk with ticket {@code unknown} - its
     *  puts {@code ServerChunkCache.getChunk}), ticket levels 5×5 around it (33 - fully loaded, 32 -
     *  blocks are ticking, 31 are entities; “·” - there is no chunk) and tick count levels are in the same place (blocks are ticking, where both are not larger than 32), players are
     *  in 16 chunks (offset from it), ticket mode in 8 chunks, how many The world is waiting for chunks until they are fully loaded.
     */
    private static String stallReport(ServerLevel level) {
        var map = ticketMap(level.getChunkSource().chunkMap.getDistanceManager());
        if (map == null) return "tickets not available";
        var chunkMap = level.getChunkSource().chunkMap;
        net.minecraft.server.level.TickingTracker ticking = tickingTracker(chunkMap.getDistanceManager());
        StringBuilder sb = new StringBuilder(level.dimension().location().toString());
        int sync = 0;
        for (var en : map.long2ObjectEntrySet()) {
            if (en.getValue().stream().noneMatch(t -> t.getType() == TicketType.UNKNOWN)) continue;
            ChunkPos c = new ChunkPos(en.getLongKey());
            // ticket unknown lives until the end of the tick: for ready-made chunks it remains from previous synchronous downloads
            if (Terrain.ready(level, c.x, c.z)) continue;
            ChunkHolder h = chunkMap.getVisibleChunkIfPresent(c.toLong());
            sb.append(String.format(Locale.ROOT, " | synchronously %d %d (block %d %d), status %s, levels:", c.x, c.z, c.getMinBlockX(), c.getMinBlockZ(),
                    h == null ? "-" : h.getLatestStatus()));
            for (int dz = -2; dz <= 2; dz++) {
                sb.append(' ');
                for (int dx = -2; dx <= 2; dx++) {
                    ChunkHolder n = chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(c.x + dx, c.z + dz));
                    sb.append(n == null || n.getTicketLevel() > 33 ? " ·" : String.format(Locale.ROOT, "%3d", n.getTicketLevel()).substring(1));
                    if (dx < 2) sb.append(',');
                }
                if (dz < 2) sb.append(" /");
            }
            // tick count levels (32 and less - blocks are ticking): who started ticking the neighbor of an unready chunk
            if (ticking != null) {
                sb.append(",tick:");
                for (int dz = -2; dz <= 2; dz++) {
                    sb.append(' ');
                    for (int dx = -2; dx <= 2; dx++) {
                        sb.append(String.format(Locale.ROOT, "%3d", ticking.getLevel(new ChunkPos(c.x + dx, c.z + dz))).substring(1));
                        if (dx < 2) sb.append(',');
                    }
                    if (dz < 2) sb.append(" /");
                }
            }
            for (ServerPlayer p : level.players()) {
                ChunkPos pc = p.chunkPosition();
                if (Math.max(Math.abs(pc.x - c.x), Math.abs(pc.z - c.z)) <= 16) {
                    sb.append(String.format(Locale.ROOT, ", player %s in %d %d", p.getGameProfile().getName(), pc.x - c.x, pc.z - c.z));
                }
            }
            Map<String, Integer> near = new TreeMap<>();
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -8; dz <= 8; dz++) {
                    var set = map.get(ChunkPos.asLong(c.x + dx, c.z + dz));
                    if (set == null) continue;
                    for (Ticket<?> t : set) if (t.getType().toString().contains("incoming")) near.merge(t.getType().toString(), 1, Integer::sum);
                }
            }
            sb.append(", ticket mod in 8 chunks ").append(near);
            if (++sync >= 8) break;
        }
        // ticket unknown also puts getChunk on a low status, which has already returned: there are several such chunks that are not ready,
        //  one of them is loaded synchronously - the report shows everything (up to 8), and not the first
        if (sync == 0) sb.append(" | no synchronous loading");
        int waiting = 0;
        for (ChunkHolder h : chunkMap.getChunks()) {
            if (h.getTicketLevel() <= 33 && h.getLatestStatus() != net.minecraft.world.level.chunk.status.ChunkStatus.FULL) waiting++;
        }
        return sb.append(" | waiting for full download ").append(waiting).append(" chunks").toString();
    }

    /** Count tick {@code DistanceManager} (blocks tick at level 32 and less), to report a stop. */
    @Nullable
    private static net.minecraft.server.level.TickingTracker tickingTracker(DistanceManager d) {
        try {
            Field f = DistanceManager.class.getDeclaredField("tickingTicketsTracker");
            f.setAccessible(true);
            return (net.minecraft.server.level.TickingTracker) f.get(d);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> ticketMap(DistanceManager d) {
        try {
            Field tf = DistanceManager.class.getDeclaredField("tickets");
            tf.setAccessible(true);
            return (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>) tf.get(d);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private void onTickPost(ServerTickEvent.Post e) {
        inTick = false;
        MinecraftServer s = e.getServer();
        long took = System.nanoTime() - tickStart;
        if (tick < 0) {
            if (s.getPlayerCount() < PLAYERS) return;
            log("all players are in place, let's start");
        }
        if (tick == 0 && placingSince != Integer.MAX_VALUE && !pendingTeleports.isEmpty()) {
            // the scenario clock stands until the players take their places: wave 1 hits them; but no longer
            // 2 × TELEPORT_WAIT - otherwise, in an area that is still not ready, the stand would not have reached the summary
            if (placingSince < 0) placingSince = s.getTickCount();
            runWaits();
            if (pendingTeleports.isEmpty()) {
                // the wait for this tick has already passed - the scenario from the next
                log("players are in place, the script clock is running");
                return;
            } else if (s.getTickCount() - placingSince >= 2 * TELEPORT_WAIT) {
                problems.add("players are not in place for " + 2 * TELEPORT_WAIT + " tick(" + pendingTeleports + "), the script goes without them");
                log("%s players are not in place, the script clock is running", pendingTeleports);
                placingSince = Integer.MAX_VALUE;
            }
            return;
        }
        tick++;
        windowSum += took;
        windowTicks++;
        windowMax = Math.max(windowMax, took);
        allTicks.add(took);
        if (took > worstTick) {
            worstTick = took;
            worstTickAt = tick;
        }
        if (took > 250_000_000L) log("long tick %d ms", took / 1_000_000);
        for (Step st : List.copyOf(steps)) {
            if (st.at == tick) {
                log("step: %s", st.what);
                try {
                    st.action.accept(s);
                } catch (RuntimeException ex) {
                    problems.add("step \"" + st.what + "» fell: " + ex);
                    Incoming.LOG.error("STRESS step {} fell", st.what, ex);
                }
            }
        }
        runWaits();
        track(s);
        if (tick % 100 == 0) stat(s);
        if (tick % 200 == 0) {
            // long-lived: from these lines you can see whether the projectile is circling, waiting for the target area or flying far away
            for (var en : watched.entrySet()) if (en.getValue().ref.age() >= LONG_LIVED) describe("flies for a long time", en.getKey(), en.getValue());
        }
        if (settlingSince >= 0) settle(s);
        else if (finishing) finishWhenQuiet(s);
    }

    private void seeDetonations(MinecraftServer s) {
        for (ServerLevel l : s.getAllLevels())
            for (var d : NuclearEvents.get(l).detonations())
                if (detonationsSeen.add(l.dimension().location() + "#" + d.id())) unclaimedDetonations.add(new Seen(l, d));
    }

    /** An explosion noticed by the Stand in its world. */
    private record Seen(ServerLevel level, Detonation d) {}

    /** Explosions that have not yet been counted against any projectile (for ICBM - none: its impact is controlled by a timer). */
    private final List<Seen> unclaimedDetonations = new ArrayList<>();
    /** Further than this, horizontally from the place of harvesting, the detonation is not his: the point of impact is no further than the step and range of the detonation. */
    private static final double NUKE_MATCH = 32;

    /**
     * Nuclear detonation of a projectile: a rocket and a bomb with a nuclear warhead ({@code Loadout.Nuke.onCarrier}) explode through
     * {@code NuclearWarhead.detonate} - detonation in {@code NuclearEvents} in the cleaning tick at the point of impact, there is no vanilla
     * {@link Explosion}, and without this such a projectile would be counted as lost.
     */
    @Nullable
    private Detonation nuclearBy(Gone g) {
        // an ordinary shell placed next to the explosion will not take it away; of the suitable ones - the closest to the place of cleaning
        if (!g.w().nuclear) return null;
        Seen best = null;
        double bestDist = NUKE_MATCH;
        for (Seen seen : unclaimedDetonations) {
            Detonation d = seen.d();
            if (seen.level() != g.level() || d.gameTime() < g.gameTime() - 1) continue;
            double dist = Math.hypot(d.burst().x - g.end().x, d.burst().z - g.end().z);
            if (dist <= bestDist) {
                best = seen;
                bestDist = dist;
            }
        }
        if (best == null) return null;
        unclaimedDetonations.remove(best);
        return best.d();
    }

    /** All shells according to UUID: new, alive (in the world or outside it), missing - with or without an explosion nearby. */
    private void track(MinecraftServer s) {
        seeDetonations(s);
        readModLines();
        tickGroundProbes(s);
        for (Probe pr : probes) {
            BlockPos c = BlockPos.containing(pr.center);
            if (pr.readyAfter < 0 && Terrain.ready(pr.level, c) && pr.level.isPositionEntityTicking(c)) pr.readyAfter = tick - pr.fired;
        }
        Map<UUID, StrikeProjectile> now = new LinkedHashMap<>();
        for (ServerLevel level : s.getAllLevels()) {
            for (StrikeProjectile p : level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> !p.isRemoved())) now.put(p.getUUID(), p);
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (!p.isRemoved() && now.put(p.getUUID(), p) != null) problems.add("double UUID in the world and outside the world: " + p.getUUID());
            }
        }
        for (Map.Entry<UUID, StrikeProjectile> en : now.entrySet()) {
            Watch w = watched.get(en.getKey());
            if (w == null) {
                w = new Watch(en.getValue());
                watched.put(en.getKey(), w);
                launchedByType.merge(w.type, 1, Integer::sum);
                w.ground = groundProbeFor(w.aim);
                if (w.ground != null) {
                    w.ground.launched++;
                    if ("bunker_buster".equals(w.type)) w.ground.bombs++;
                }
                if (en.getValue() instanceof RocketEntity && w.ground == null) {
                    w.probe = probeFor(w.aim);
                    if (w.probe != null) {
                        w.probe.launched++;
                        w.probe.arrival = Math.min(w.probe.arrival, tick - w.probe.fired + en.getValue().etaTicks());
                    }
                }
            } else {
                w.update(en.getValue());
            }
            w.seen = tick;
            if (w.ground != null && w.virtual) w.ground.flewVirtual.add(en.getKey());
            // the path of concrete-piercing bombs entirely and all projectiles outside the world: a bomb (hundreds of blocks
            //  from the target) or an MLRS missile (under the world) can disappear outside the world
            //  extension of the MLRS flight outside the world (the target area is not ready): the beginning and end - in a separate line
            if (en.getValue() instanceof RocketEntity r) {
                if (w.probe != null) {
                    w.probe.minRate = Math.min(w.probe.minRate, r.timeRate());
                    if (r.timeRate() < DEEP) w.probe.deep.add(en.getKey());
                }
                boolean stretched = r.timeRate() < 0.999;
                if (stretched != w.stretched) {
                    log("stretch %s %s: %s, tempo %.2f, at %d %d %d, to target %.0f", w.type, en.getKey(), stretched ? "started" : "it's over",
                            r.timeRate(), (int) w.pos.x, (int) w.pos.y, (int) w.pos.z, w.pos.distanceTo(w.aim));
                    w.stretched = stretched;
                    if (w.probe != null) {
                        if (stretched) w.probe.stretchOn++;
                        else w.probe.stretchOff++;
                    }
                }
            }
            if ((w.virtual || "bunker_buster".equals(w.type)) && tick % 10 == 0) {
                log("path %s %s: %d %d %d, outside the world %b, phase %s, age %d, to the goal %.0f horizontally, %.0f in height",
                        w.type, en.getKey(), (int) w.pos.x, (int) w.pos.y, (int) w.pos.z, w.virtual, w.ref.flightPhase().getSerializedName(), w.ref.age(),
                        Math.hypot(w.aim.x - w.pos.x, w.aim.z - w.pos.z), w.pos.y - w.aim.y);
            }
        }
        for (var it = watched.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            Watch w = en.getValue();
            if (w.seen == tick) continue;
            if (!w.ref.isRemoved()) {
                if (tick - w.seen == 200) log("the %s %s projectile is not visible in the 200 tick search, but is not removed (for %d %d %d)", w.type, en.getKey(),
                        (int) w.pos.x, (int) w.pos.y, (int) w.pos.z);
                continue;
            }
            it.remove();
            // where it ended - according to the object itself: having returned to the world, the projectile rises above the relief (materialize, the same object)
            //  and can explode at the same tick, without hitting the stand bust; the last noticed location is still outside the world,
            //  under the terrain (otherwise an MLRS rocket raised from y 51 to ~95 and exploded is counted as “lost”)
            Gone g = new Gone(en.getKey(), w, w.ref.position(), leftHow.remove(en.getKey()), tick, w.ref.level(), w.ref.level().getGameTime());
            if (cleared.contains(en.getKey())) ended(w, "cleared");
            else if ("bomber".equals(w.type)) ended(w, "bomber-gone");
            // ICBM - only acceleration: above the sky it retracts itself, the blow further leads NuclearStrikes according to the timer
            else if ("icbm".equals(w.type) && w.pos.y > s.overworld().getMaxBuildHeight()) ended(w, "boost-done");
            else awaitingBlast.add(g);
        }
        // a removed shell is waiting for its explosion BLAST_WAIT tick: the explosion of an unready area is postponed until it is loaded
        Detonation nuke;
        for (var it = awaitingBlast.iterator(); it.hasNext(); ) {
            Gone g = it.next();
            Vec3 blast = blastsThisTick.get(g.id);
            if (gaveUpIds.contains(g.id)) {
                // the mod itself removed it without waiting for the target area (WARN with UUID): not a loss, but a failure due to the waiting period
                it.remove();
                ended(g.w, "gave-up");
            } else if (blast != null) {
                it.remove();
                // sample explosions and deferred ones - in a line: they show whose explosion was counted and where
                if (tick > g.at || g.w.probe != null || g.w.ground != null)
                    log("explosion %s %s at %d %d %d at t=%d (via %d tick after cleaning), to the cleaning point %.0f, before the last one noticed %.0f",
                            g.w.type, g.id, (int) blast.x, (int) blast.y, (int) blast.z, tick, tick - g.at, blast.distanceTo(g.end), blast.distanceTo(g.w.pos));
                impact(g);
            } else if ((nuke = nuclearBy(g)) != null) {
                it.remove();
                log("nuclear explosion No. %d %s %s at %d %d %d, to the cleaning site %.0f", nuke.id(), g.w.type, g.id,
                        (int) nuke.burst().x, (int) nuke.burst().y, (int) nuke.burst().z, nuke.burst().distanceTo(g.end));
                ended(g.w, "nuclear");
            } else if (tick - g.at >= BLAST_WAIT) {
                it.remove();
                lost(g, "");
            }
        }
        blastsThisTick.clear();
    }

    private void stat(MinecraftServer s) {
        int inWorld = 0, virt = 0, salvos = 0, nukes = 0;
        for (ServerLevel l : s.getAllLevels()) {
            inWorld += l.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), p -> true).size();
            virt += VirtualFlights.get(l).flights().size();
            salvos += SalvoData.get(l).size();
            nukes += NuclearEvents.get(l).detonations().size();
        }
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        log("stat t=%d mspt avg %.1f max %.1f | in the world %d outside %d volleys %d explosions %d | ticket %s | chunks %d | heap %d MB | warn %d err %d",
                tick, windowSum / 1e6 / Math.max(1, windowTicks), windowMax / 1e6, inWorld, virt, salvos, nukes, tickets(s),
                s.overworld().getChunkSource().getLoadedChunksCount(), used, warnings, errors);
        windowSum = windowMax = 0;
        windowTicks = 0;
    }

    /**
     * ticket mod in all worlds by type (leak - tickets that remain when everything has arrived); not in the upper world -
     *  with the name of the world ahead.
     */
    private static String tickets(MinecraftServer s) {
        Map<String, Integer> byType = new TreeMap<>();
        for (ServerLevel level : s.getAllLevels()) {
            var map = ticketMap(level.getChunkSource().chunkMap.getDistanceManager());
            if (map == null) return "?";
            String prefix = level.dimension() == Level.OVERWORLD ? "" : level.dimension().location().getPath() + "/";
            for (SortedArraySet<Ticket<?>> set : map.values()) {
                for (Ticket<?> t : set) {
                    String type = t.getType().toString();
                    if (type.contains("incoming") || type.contains("neoforge") || type.contains("forced")) byType.merge(prefix + type, 1, Integer::sum);
                }
            }
        }
        return byType.toString();
    }

    /** The projectile's power reserve ran out more than a second into the flight, but it is still flying. */
    private static boolean overdue(StrikeProjectile p) {
        return p.rangeLeft() < -20 * p.cruiseSpeed();
    }

    /** The state of the projectile: power reserve, pursuit and waiting for the target area - from NBT, this is not visible anywhere else from the outside. */
    private void describe(String what, UUID id, Watch w) {
        StrikeProjectile p = w.ref;
        CompoundTag tag = new CompoundTag();
        p.saveWithoutId(tag);
        log("%s %s %s at %s phase %s (%d tick) age %d stock %.0f chase %.0f waited for area %d target %s at %s out of world %b removed %s ticking %b",
                what, w.type, id, p.blockPosition().toShortString(), p.flightPhase().getSerializedName(), tag.getInt("phase_age"),
                p.age(), p.rangeLeft(), tag.getCompound("tracker").getDouble("chased"), tag.getInt("area_wait"), p.target(),
                BlockPos.containing(p.aimPoint()).toShortString(), p.isVirtual(), p.getRemovalReason(),
                p.level() instanceof ServerLevel l && l.isPositionEntityTicking(p.blockPosition()));
    }

    private void finishWhenQuiet(MinecraftServer s) {
        // and teleports: delayed return and area ticket after the bulletin - false TELEPORT in the “ticket” and a problem past the bulletin;
        //  and removed shells waiting to explode (BLAST_WAIT)
        int flying = watched.size() + awaitingBlast.size(), teleports = teleportTickets;
        for (ArrayDeque<TpRequest> queue : deferredTeleports.values()) teleports += queue.size();
        for (ServerLevel l : s.getAllLevels()) {
            flying += SalvoData.get(l).size();
        }
        // the sample, whose stand is still waiting for its chunk (generation in the background), has not yet been launched: otherwise the report would have released the ticket
        //  and the sample would have failed as “not a single projectile”
        for (GroundProbe pr : groundProbes) if (pr.forced != null && pr.stand == null) flying++;
        if (flying + teleports > 0) {
            quietSince = -1;
            if (finishingSince < 0) finishingSince = tick;
            // a projectile can honestly fly longer than the window (late launch from a salvo, waiting for a target area, chasing a target),
            //  failure - only overdue: alive after its lifespan; or the whole window went out entirely
            boolean overdue = false;
            for (var en : watched.entrySet()) {
                if (!overdue(en.getValue().ref)) continue;
                overdue = true;
                if (overdueSeen.add(en.getKey())) {
                    describe("expired", en.getKey(), en.getValue());
                    problems.add("projectile " + en.getValue().type + " " + en.getKey() + " alive after lifespan");
                }
            }
            int waited = tick - finishingSince;
            if (waited > 3600 && overdue || waited > 12000) {
                log("unfinished within allotted time: projectiles, salvos and probes %d, teleports %d (area tickets %d, queued %d)",
                        flying, teleports, teleportTickets, teleports - teleportTickets);
                if (teleports > 0) problems.add("teleporters did not end at the end of the run: " + teleports);
                for (var en : watched.entrySet()) describe("  stayed", en.getKey(), en.getValue());
                for (GroundProbe pr : groundProbes)
                    if (pr.forced != null && pr.stand == null) log("  test %s: the rack chunk was not loaded after %d tick", pr.name, tick - pr.setupTick);
                summary(s, "timeout");
                stopWhenSettled(s);
            }
            return;
        }
        if (quietSince < 0) quietSince = tick;
        // let the timelines and nuclear queues finish, then look at the ticket and memory
        if (tick - quietSince == 400) {
            System.gc();
            summary(s, "done");
            stopWhenSettled(s);
        }
    }

    /**
     * Stop the server when chunk generation is finished. Vanilla stop (1.21.1) removes the ticket and unloads
     *  chunks into {@code ChunkMap.processUnloads} from {@code hasMoreTime = () -> true}: unloading a chunk to which
     *  also holds a link from the neighbor generation ({@code generationRefCount > 0}), immediately puts itself in the unloading queue again,
     *  and the cycle through this queue does not end. The server thread spins in it and does not reach the tasks with which the generation of
     *  would end - the stop hangs endlessly (thousands of chunks with generation links, hundreds of tasks
     *  in the queue of the server thread). Therefore, first the players leave, and the server stops when
     * {@link #SETTLE_QUIET} tick in a row and no chunks have generation links.
     */
    private void stopWhenSettled(MinecraftServer s) {
        settlingSince = tick;
        for (ServerPlayer p : List.copyOf(s.getPlayerList().getPlayers())) p.connection.disconnect(Component.literal("Стенд закончен"));
        log("completion: players are disconnected, waiting for the end of chunk generation");
    }

    private void settle(MinecraftServer s) {
        int generating = 0;
        for (ServerLevel l : s.getAllLevels()) {
            for (ChunkHolder holder : l.getChunkSource().chunkMap.getChunks()) if (holder.getGenerationRefCount() > 0) generating++;
        }
        settleQuiet = generating == 0 ? settleQuiet + 1 : 0;
        int waited = tick - settlingSince;
        if (settleQuiet >= SETTLE_QUIET || waited >= SETTLE_MAX) {
            if (generating == 0) log("chunk generation has ended, stop after %d tick", waited);
            else log("the generation of chunks did not end within the %d tick (for %d chunks), the stop may freeze", waited, generating);
            s.halt(false);
        }
    }

    private void summary(MinecraftServer s, String why) {
        List<Long> sorted = new ArrayList<>(allTicks);
        sorted.sort(null);
        long p50 = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        long p99 = sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.99)));
        Runtime rt = Runtime.getRuntime();
        // ticket trials to release until the summary line: the tickets that remain in it are a leak
        for (GroundProbe pr : groundProbes) {
            if (pr.forced == null) continue;
            s.overworld().getChunkSource().removeRegionTicket(PROBE_TICKET, pr.forced, 2, pr.forced);
        }
        int teleportsLeft = closeTeleports();
        if (teleportsLeft > 0) log("summary releases ticket teleport areas: %d", teleportsLeft);
        // summary before the end of the wait (timeout, restart): those who did not wait for their explosion are lost
        seeDetonations(s);
        for (Gone g : awaitingBlast) {
            if (nuclearBy(g) != null) ended(g.w(), "nuclear");
            else lost(g, ", summary before the end of the wait");
        }
        awaitingBlast.clear();
        int nukes = detonationsSeen.size();
        // ICBM was launched, but there was no detonation: the strike was lost (or canceled ahead of schedule - then the stand schedule is incorrect)
        if (nukes == 0 && launchedByType.containsKey("icbm")) problems.add("ICBM launched, but no nuclear detonation");
        // samples to the surface (t=3500+): their projectiles in “launched” and “results”, their distant areas - in ticks
        //  (generation)
        int probeShots = groundProbes.stream().mapToInt(pr -> pr.launched).sum();
        log("summary %s: tick %d, mspt p50 %.1f p99 %.1f worst %.0f on t=%d | launched %s | results %s | nuclear explosions %d | in flight %d | ticket %s | heap %d MB | warn %d err %d"
                        + " | including surface probes (since #118): projectiles %d, distant areas %d",
                why, tick, p50 / 1e6, p99 / 1e6, worstTick / 1e6, worstTickAt, launchedByType, outcomes, nukes, watched.size(), tickets(s),
                (rt.totalMemory() - rt.freeMemory()) >> 20, warnings, errors, probeShots, groundProbes.size());
        for (Probe pr : probes) {
            // we judge by the readiness of the area for arrival, and not by design: a server that has stopped for seconds does not count the tick, and
            // generation is ongoing at this time - the short flight area is in time, and after a wave of salvos the long
            // flight is waiting for the generation line
            int ready = pr.readyAfter < 0 ? Integer.MAX_VALUE : pr.readyAfter;
            String expect;
            List<String> fails = new ArrayList<>();
            if (ready >= pr.arrival) {
                expect = "deep stretch: area ready later than arrival";
                if (pr.deep.isEmpty()) fails.add("the area is ready later than arrival, but there is no deep stretching");
            } else if (ready <= pr.arrival - RocketEntity.STRETCH_TICKS - PROBE_SLACK) {
                expect = "without deep stretching: the area is ready long before arrival";
                if (!pr.deep.isEmpty()) fails.add("the area is ready long before arrival, and the deep stretch at " + pr.deep.size());
            } else {
                expect = "any: area ready shortly before arrival";
            }
            if (pr.launched == 0) fails.add("not a single shell");
            if (pr.outcomes.getOrDefault("lost", 0) > 0) fails.add("lost " + pr.outcomes.get("lost"));
            // mod failure in terms of waiting time for the area - like samples to the surface: not a failure, but “not verified”
            log("probe %s (%s): launched %d, arrival without stretching %d ticks after launch, area ready after %d, stretching began %d, "
                            + "ended %d, deep (tempo < %.1f) at %d, lowest tempo %.2f - expected %s; results %s - %s",
                    pr.name, pr.shortFlight ? "the flight is shorter than the loading area" : "the flight is longer than the loading area", pr.launched, pr.arrival, pr.readyAfter,
                    pr.stretchOn, pr.stretchOff, DEEP, pr.deep.size(), pr.minRate, expect, pr.outcomes,
                    probeVerdict(fails, pr.outcomes.getOrDefault("gave-up", 0)));
            for (String f : fails) problems.add("probe " + pr.name + ": " + f);
        }
        for (GroundProbe pr : groundProbes) groundSummary(pr);
        for (String p : problems) log("problem: %s", p);
    }

    /** A line of testing the path outside the world to the surface and its verdict (trials are only carried out in a run to the end, without restarting). */
    private void groundSummary(GroundProbe pr) {
        List<Double> off = new ArrayList<>(pr.groundOff);
        off.sort(null);
        double max = off.isEmpty() ? 0 : off.get(off.size() - 1);
        double median = off.isEmpty() ? 0 : off.get(off.size() / 2);
        int vanished = pr.outcomes.getOrDefault("lost", 0);
        List<String> fails = new ArrayList<>();
        if (pr.launched == 0) fails.add("not a single shell");
        else if (pr.ordered > 0 && pr.launched != pr.ordered) fails.add("launched " + pr.launched + " from " + pr.ordered);
        if (vanished > 0) fails.add("disappeared without an explosion: " + vanished);
        if (pr.steering && max > GROUND_OFF_LIMIT) fails.add(String.format(Locale.ROOT, "to the surface in %.0f blocks from the target (> %.0f)", max, GROUND_OFF_LIMIT));
        if (pr.mustGround && off.isEmpty()) fails.add("not a single one has reached the surface outside the world - the path has not been tested");
        if (pr.forced != null && !pr.raised) fails.add("the target did not rise - the out-of-world rocket did not catch the rise");
        else if (pr.forced != null && !pr.underFloor) fails.add("after lifting the target, the out-of-world rocket never went below the floor - the rise of the floor has not been verified");
        if (pr.flewVirtual.size() < pr.launched)
            fails.add("found out of the world " + pr.flewVirtual.size() + " from " + pr.launched + " - the path outside the world is not tested for everyone");
        int ended = pr.outcomes.values().stream().mapToInt(Integer::intValue).sum();
        if (ended < pr.launched) fails.add("the result is " + ended + " from " + pr.launched);
        if (pr.dropBehind) {
            // airborne release at 200 over the sea, the target is at 300 behind and at 150 below: the bomb does not steer and falls along the course (+z)
            //  from the release point, it does not go steeper than twice the release height, sideways - no
            for (double[] g : pr.groundAt) {
                double ahead = g[1] - (g[3] + 300), side = g[0] - g[2];
                if (ahead <= 0 || ahead > 400 || Math.abs(side) >= 8)
                    fails.add(String.format(Locale.ROOT, "to the surface not on course from the release point: forward %.0f, sideways %.0f", ahead, side));
            }
        }
        double impactMax = pr.impactOff.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        if (pr.b2Behind) {
            if (!pr.retargeted) fails.add("B-2 was not caught outside the world in 400–1000 blocks from the point - there was no retargeting");
            else if (pr.bombs == 0) fails.add("the bomb was not dropped");
            if (impactMax > GROUND_OFF_LIMIT) fails.add(String.format(Locale.ROOT, "the bomb hit %.0f blocks from point (> %.0f)", impactMax, GROUND_OFF_LIMIT));
        }
        log("probe-surface %s (%s): launched %d, reached surface outside world %d (horizontal from target: maximum %.0f, median %.0f), "
                        + "didn’t wait for %d to load, found %d%s out of the world, results %s - %s",
                pr.name, pr.how, pr.launched, off.size(), max, median, pr.gaveUp, pr.flewVirtual.size(),
                pr.b2Behind ? String.format(Locale.ROOT, ", bombs %d, bomb hits from point: greatest %.0f", pr.bombs, impactMax) : "",
                pr.outcomes, probeVerdict(fails, pr.outcomes.getOrDefault("gave-up", 0)));
        for (String f : fails) problems.add("probe-surface " + pr.name + ": " + f);
    }

    /**
     * Verdict of the test (stretching and to the surface): failure - according to checks; the projectile that the mod removed without waiting for the target area
     *  (waiting limit for slow generation), something for which the test was not checked - “not verified”, not ok and not a failure.
     */
    public static String probeVerdict(List<String> fails, int gaveUps) {
        if (!fails.isEmpty()) return "провал: " + String.join("; ", fails);
        return gaveUps > 0 ? "не проверено: не дождались района цели " + gaveUps : "ok";
    }

    private void onStopping(ServerStoppingEvent e) {
        log("server stops");
    }

    // ---------------------------------------------------------------- official

    private static void run(MinecraftServer s, String command) {
        run(s, s.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static void run(MinecraftServer s, CommandSourceStack src, String command) {
        s.getCommands().performPrefixedCommand(src, command);
    }

    private void as(MinecraftServer s, String player, String command) {
        ServerPlayer p = need(s, player, "/" + command);
        if (p != null) run(s, p.createCommandSourceStack().withPermission(4), command);
    }

    private static void log(String fmt, Object... args) {
        Incoming.LOG.info("STRESS " + String.format(Locale.ROOT, fmt, args));
    }
}
