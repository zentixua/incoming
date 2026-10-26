package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Visual and audio probe after 3.5.0 (handoff P): the same scenes in any mod version, using only vanilla,
 * {@code /incoming} and {@link StrikeProjectile}, present in both 3.4.1 and 3.5.0. The class is identical in
 * all probe trees, so positions, time of day and seed match between variants.
 * <p>
 * Scenes ({@code incoming.probe.cases}, comma-separated; absent means all): {@code drone-day-50},
 * {@code drone-day-300}, {@code drone-night-50}, {@code drone-night-300}: Shahed into ground, viewer 50 and
 * 300 blocks away, frames after 0,1–30 s; {@code grad-day}, {@code grad-night}: 40 Grad rockets, spread 15,
 * 30 blocks away, frames up to one minute; {@code launch-behind}, {@code launch-side},
 * {@code launch-night-behind}: missile launch, camera behind the missile above the booster jet
 * ({@link #ABOVE_JET}) and beside it; {@code flyby}: Shahed over the viewer (sound); {@code water-day-50}:
 * Shahed into a 41×41×4 pond (water blast); {@code debris-night-50}: Shahed into the roof of a 9×5×9 wooden
 * house (burning debris, fire light on the world, heat shimmer); {@code substation-night-50}: Shahed into a
 * substation (arc, burning oil, hum and crackle); {@code bomb-day-50}, {@code bomb-night-50}: B-2 bomb into
 * plain soil, viewer 50 blocks away ({@code bomb-day-150}, {@code bomb-night-150}, {@code bomb-day-300}: 150
 * and 300 away; a breach with ~1 t in air makes a ground dust layer tens of metres wide, so at 50 the viewer
 * is inside it); frames start at the first bomb explosion (3.4.1: ground-entry explosion, then detonation);
 * {@code sam-side}, {@code sam-behind}, {@code sam-night-side}: SAM launches at a Shahed, viewer beside and
 * behind the SAM (launch smoke, trail, engine sound); {@code sam-burst}, {@code sam-night-burst}: SAM loads
 * when the Shahed is ~180 blocks away, burst ~70 blocks beside the viewer. Each scene has its own plain site.
 * <p>
 * Log: {@code SCENARIO probe …} for explosion or launch (client tick, game time, byte in audio.wav for slicing
 * audio), and shots. Frame time in logs/probe-frames.txt: "scene game_time ms".
 */
final class ProbeScenario {
    private record Step(int at, Runnable action) {}

    private static final List<String> ALL = List.of("drone-day-50", "drone-day-300", "drone-night-50", "drone-night-300",
            "grad-day", "grad-night", "launch-behind", "launch-side", "launch-night-behind", "flyby",
            // S8 - at the end: places of previous scenes - by their number in this list
            "water-day-50", "debris-night-50", "substation-night-50",
            // S5
            "bomb-day-50", "bomb-night-50", "bomb-day-150", "bomb-night-150", "bomb-day-300",
            // S6
            "sam-side", "sam-behind", "sam-night-side", "sam-burst", "sam-night-burst");
    /** Footage of scenes with a fire and a substation: longer - the smoke of burning oil and fire rises for tens of seconds. */
    private static final double[] FIRE_SHOTS = {0.1, 0.5, 1, 3, 10, 30, 60};
    /** SAM footage: seconds after the launch of the rocket and after the explosion. */
    private static final double[] SAM_SHOTS = {0.25, 0.5, 1, 1.5, 2, 3, 5, 8, 15};
    private static final double[] BURST_SHOTS = {0.1, 0.25, 0.5, 1, 2, 3, 6, 12};
    /** Don’t wait longer than the flight of the SAM rocket with a gap margin, tick. */
    private static final int SPEC_FLIGHT = 400;
    /** Shahed footage, seconds after the explosion. */
    private static final double[] DRONE_SHOTS = {0.1, 0.5, 1, 3, 10, 30};
    private static final double[] GRAD_SHOTS = {0.1, 0.5, 1, 3, 6, 12, 20, 30, 45, 60};
    private static final double[] LAUNCH_SHOTS = {0.5, 1, 1.5, 2, 3, 4};
    /**
     * The camera is behind - so many blocks behind the rocket along its axis and so many above the axis. It would stand directly on the axis in the jet of the
     *  accelerator: the jet goes from the nozzle along the axis backward, in 24 m its half-width in speed is ~2,3 m (0,094 length, Pope, “Turbulent
     * Flows”, table 5.1 is slightly wider), and the thickness of its aluminum oxide along the axis to the nozzle is hundreds (the frame is completely gray, like
     *  in the fog; 3.4.1 drew a trace with a flat tape and saw through him). The present view of the launch from behind is from outside the jet: above the
     *  axis at ~2,5 its half-width, ~14° from the axis; that the rocket and the jet are visible from there, the look gate footage shows.
     */
    private static final double BEHIND = 24, ABOVE_JET = 6;
    /**
     * Starting area: the top of its stone floor is y of this number (the floor is blocks at y − 1). A camera placed with its feet below the
     *  top of the floor is in a block: the server considers this a new collision (`ServerGamePacketListenerImpl.handleMovePlayer`,
     * `isPlayerCollidingWithAnythingNew`) and returns the player to the same place with a new rotation. So the camera behind
     *  stood until 23.10: the eye on 201,6 with the growth of the eye 1,62 - the legs on 199,98, and until ~1 s (until the rocket raises the camera higher)
     *  the frame was filmed from the player’s place before the start, and watched to where the rocket is - without the launcher and clouds in the frame (when the
     *  rocket left for −X, the shift was ~25 of blocks, to +X - ~3, and it was imperceptible).
     */
    private static final int LAUNCH_FLOOR = 200;
    /** A camera moved so many blocks away from where the scene placed it in the frame is a frame from the wrong point. */
    static final double CAMERA_SLIP = 1;
    /**
     * The game time from which the scene begins is as in the reference run of all scenes in a row (comparison 22.10, compare-final:
     * beginning of the scene in v3.4.1 and main - within 200 tick). The wind of the world and its stability class depend only on time
     * games ({@code util.Wind}, {@code util.Air}): at night, near the boundary of the classes D and E, the wind near the ground is 1 or 5 m/s, and the trail of the fire is
     * to stands up like a pillar, then spreads out. Sampling scenes without this would be in a different air than the standard; the scene that the game has not reached
     *  catches up with its time with accelerated ticks ({@code /tick sprint}). Time does not go backwards, so the sample scenes
     *  go in reference order ({@link #ALL}); a scene that started later (the previous scene took longer: the launch scenes are waiting for the
     *  rocket to descend), is valid as long as its air is the same as at the reference time ({@link #air}: the wind changes over the game day, and not over
     * hundreds of ticks, but there is enough at the class boundary), otherwise - the line {@code SCENARIO probe FAIL} and the exit (its catches
     * {@code tools/look_gate/check.py}). The flight time is according to the run main 22.10 (Cmain: between the launch at night and the explosion on
     *  water, like 3.4.1).
     */
    private static final java.util.Map<String, Integer> START = java.util.Map.ofEntries(
            java.util.Map.entry("drone-day-50", 902), java.util.Map.entry("drone-day-300", 2093), java.util.Map.entry("drone-night-50", 3353),
            java.util.Map.entry("drone-night-300", 4541), java.util.Map.entry("grad-day", 5821), java.util.Map.entry("grad-night", 7722),
            java.util.Map.entry("launch-behind", 9519), java.util.Map.entry("launch-side", 9792), java.util.Map.entry("launch-night-behind", 10084),
            java.util.Map.entry("flyby", 10440), java.util.Map.entry("water-day-50", 11047), java.util.Map.entry("debris-night-50", 12236),
            java.util.Map.entry("substation-night-50", 14027), java.util.Map.entry("bomb-day-50", 15830), java.util.Map.entry("bomb-night-50", 17018),
            java.util.Map.entry("bomb-day-150", 18204), java.util.Map.entry("bomb-night-150", 19390), java.util.Map.entry("bomb-day-300", 20577),
            java.util.Map.entry("sam-side", 21690), java.util.Map.entry("sam-behind", 22182), java.util.Map.entry("sam-night-side", 22634),
            java.util.Map.entry("sam-burst", 23922), java.util.Map.entry("sam-night-burst", 24810));
    /**
     * The same air: wind near the ground - no further than this, m/s, its direction - Grad, stability class (continuous,
     * {@code util.Stability}) - a fraction of the class (⚠ assessment: smoke drift per minute - within meters and Grad).
     */
    static final double SAME_WIND = 0.3, SAME_DIR = 3, SAME_CLASS = 0.1;
    /** The game time at which the current scene began; −1 - not a single one yet. */
    private long startedAt = -1;
    /** The scene has already caught up with its playing time; acceleration to it has already been ordered. */
    private boolean onTime, sprinting;

    /** How long to wait for the explosion after the command, tick. */
    private static final int BLAST_LIMIT = 2400;

    private final List<Step> steps = new ArrayList<>();
    private final List<String> cases;
    private final Writer frames;
    private int tick = -1;
    private int caseIndex = -1;
    private String current = "-";
    private Vec3 target = Vec3.ZERO;
    /** Scene locations on the plain (x, z), searched by the server at the beginning. */
    private volatile List<int[]> sites;
    private volatile boolean armed;
    private final AtomicInteger blasts = new AtomicInteger();
    private int seenBlasts, firedAt = -1, blastTick = -1;
    private boolean hover;
    /** Rocket launch: the projectile is at the client, launch tick where it was, from the side or behind. */
    private StrikeProjectile missile;
    private int launchTick = -1;
    private Vec3 launchFrom = Vec3.ZERO, lastPos = Vec3.ZERO, axis = new Vec3(0, 0, 1);
    private boolean behind;
    /**
     * Where the stage put the eye last; null - the scene camera does not move. {@link #drawnAt} - the same for the moment of the last
     *  drawn frame (it is taken by the snapshot): after a long frame, the client drives a few ticks without drawing, and
     * {@code placed} has already gone ahead to the snapshot with a rocket on the blocks (review LNB: LFX11, frame 323 ms - 7 tick in a row).
     */
    private Vec3 placed, drawnAt;
    /** SAM: where it is, the client has the rocket, tick launch, is it charged (burst scene), tick of the rupture. */
    private BlockPos sam;
    private net.minecraft.world.entity.Entity interceptor;
    private int samLaunch = -1, samBurst = -1;
    private boolean samLoaded;
    /** Flyover: closest to the viewer. */
    private StrikeProjectile drone;
    private double closest = Double.MAX_VALUE;

    ProbeScenario() {
        String prop = System.getProperty("incoming.probe.cases");
        // sample - in the order of the standard: the game time is caught up only forward
        cases = prop == null || prop.isBlank() ? ALL : ordered(prop.split(","));
        Writer w = null;
        try {
            Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("probe-frames.txt");
            Files.createDirectories(file.getParent());
            w = Files.newBufferedWriter(file);
        } catch (IOException e) {
            Incoming.LOG.warn("SCENARIO probe: no frame log", e);
        }
        frames = w;
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onFrame);
        NeoForge.EVENT_BUS.addListener((ExplosionEvent.Start e) -> {
            if (e.getLevel().isClientSide() || !armed) return;
            if (e.getExplosion().center().distanceTo(target) < 80) blasts.incrementAndGet();
        });
        at(40, () -> {
            // sound comparison - without deafening: the scenes are played in a row by one player, and the deafness from the explosion in 50 m would go into
            //  the following scenes (runs F2b7, F2b8 22.10 came with hearing, and the tails of the explosions in them were muffled)
            boolean hearing = IncomingConfig.CLIENT.nukeTinnitus.get();
            Incoming.LOG.info("SCENARIO probe hearing damage {}", hearing ? "on" : "off");
            if (hearing) {
                fail("hearing damage is on (nuke_tinnitus = true): sounds of later scenes would be muffled by earlier blasts");
                return;
            }
            cmd("weather clear");
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doFireTick true");
            Minecraft.getInstance().options.hideGui = true;
            var server = Minecraft.getInstance().getSingleplayerServer();
            // search for places - in your stream: the heights of the generator are considered noise, on the server stream these are minutes without a tick
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                var level = server.overworld();
                List<int[]> found = new ArrayList<>();
                for (int k = 0; k < cases.size(); k++) {
                    // place - by stage, and not by its number in the selection: the stage always has the same area
                    int i = Math.max(0, ALL.indexOf(cases.get(k)));
                    var near = level.findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.PLAINS),
                            new BlockPos(i * 900, 64, 0), 3200, 32, 64);
                    BlockPos p = near == null ? new BlockPos(i * 900, 64, 0) : near.getFirst();
                    found.add(flat(p.getX(), p.getZ()));
                }
                sites = found;
                Incoming.LOG.info("SCENARIO probe sites ready");
            });
        });
        at(60, this::nextCase);
    }

    /**
     * Is the air of the world in tick {@code now} the same as in tick {@code reference}, with the sun of the scene (day - at the zenith, night - under the
     *  horizon), without rain: “same”, “differs: …” or “none” - this version of the mod does not have air of the world (3.4.1: smoke and sound are not affected by
     *  game time). Reflection: the class is the same in the trees of all versions.
     */
    static String sameAir(long now, long reference, boolean night) {
        double[] a, b;
        try {
            a = air(now, night);
            b = air(reference, night);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // there is air in this version, but it cannot be read (the class has been redone): running is not good, but does not pass silently
            return "differs: air unreadable (" + e + ")";
        }
        if (a == null || b == null) return "none";
        double dir = Math.abs(Math.toDegrees(Math.atan2(Math.sin(a[1] - b[1]), Math.cos(a[1] - b[1]))));
        String numbers = String.format(Locale.ROOT, "wind %.2f/%.2f m/s, direction %.1f deg apart, class %.2f/%.2f", a[0], b[0], dir, a[2], b[2]);
        boolean same = Math.abs(a[0] - b[0]) <= SAME_WIND && dir <= SAME_DIR && Math.abs(a[2] - b[2]) <= SAME_CLASS;
        return (same ? "same: " : "differs: ") + numbers;
    }

    /**
     * Wind in 10 m above sea level, m/s, its direction, rad, and stability class in tick {@code gameTime}; null —
     *  there is no air class in this version. The names are the same as those checked by {@code AirTest.probeReadsTheAirByTheseNames}.
     */
    private static double @org.jetbrains.annotations.Nullable [] air(long gameTime, boolean night) throws ReflectiveOperationException {
        Class<?> c;
        try {
            c = Class.forName("ua.zentix.incoming.util.Air");
        } catch (ClassNotFoundException e) {
            return null;
        }
        Object air = c.getMethod("of", long.class, double.class, double.class, double.class).invoke(null, gameTime, night ? -1.0 : 1.0, 0.0, 0.0);
        Object wind = c.getMethod("wind").invoke(air);
        double speed = (double) c.getMethod("speed", double.class).invoke(air, 73.0);
        double direction = (double) wind.getClass().getMethod("direction").invoke(wind);
        return new double[]{speed, direction, (double) c.getMethod("stability").invoke(air)};
    }

    /** The sample scenes are in reference order; an unfamiliar scene is an error (its place and time are unknown). */
    static List<String> ordered(String[] names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            String name = n.trim();
            if (name.isEmpty()) continue;
            if (!ALL.contains(name)) throw new IllegalArgumentException("unknown probe case " + name + ", known: " + ALL);
            if (!out.contains(name)) out.add(name);
        }
        out.sort(java.util.Comparator.comparingInt(ALL::indexOf));
        return out;
    }

    /**
     * The run is not suitable for comparison: the line {@code SCENARIO probe FAIL} and the output - immediately, and not after an hour of shooting
     *  ({@code tools/look_gate/check.py} catches it himself).
     */
    private void fail(String why) {
        Incoming.LOG.error("SCENARIO probe FAIL {}", why);
        at(tick + 2, () -> Minecraft.getInstance().stop());
    }

    private void nextCase() {
        if (sites == null) {
            at(tick + 20, this::nextCase);
            return;
        }
        // the scene ended: and at the end the air is the same as that of the standard at the same place in its stage (the scenes go up to a minute, and the
        //  started later could cross the class boundary by the end)
        Integer ref = caseIndex >= 0 ? START.get(current) : null;
        if (ref != null && startedAt >= 0) {
            long now = Minecraft.getInstance().level.getGameTime();
            String air = sameAir(now, ref + (now - startedAt), current.contains("night"));
            Incoming.LOG.info("SCENARIO probe {} ends at game {} (reference end {}, air {})", current, now, ref + (now - startedAt), air);
            if (air.startsWith("differs")) {
                fail(current + " ends in another air than the reference (" + air + ")");
                return;
            }
        }
        caseIndex++;
        if (caseIndex >= cases.size()) {
            Incoming.LOG.info("SCENARIO probe all cases done");
            at(tick + 20, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        current = cases.get(caseIndex);
        onTime = false;
        sprinting = false;
        begin();
    }

    /** The beginning of the scene {@link #current}: first - its playing time ({@link #START}), then - the place and plan. */
    private void begin() {
        long now = Minecraft.getInstance().level.getGameTime();
        Integer start = START.get(current);
        if (!onTime && start != null && now < start - 5) {
            if (!sprinting) cmd("tick sprint " + (start - now));
            sprinting = true;
            at(tick + 10, this::begin);
            return;
        }
        if (!onTime) {
            String air = start == null ? "unknown" : sameAir(now, start, current.contains("night"));
            Incoming.LOG.info("SCENARIO probe {} game {} (reference start {}, air {})", current, now, start, air);
            if (start == null || air.startsWith("differs")) {
                fail(current + " starts at game " + now + ", reference start " + start + ": another air than the reference (" + air + ")");
                return;
            }
        }
        if (!onTime) startedAt = now;
        onTime = true;
        int[] site = sites.get(caseIndex);
        armed = false;
        firedAt = -1;
        missile = null;
        placed = null;
        drawnAt = null;
        drone = null;
        launchTick = -1;
        blastTick = -1;
        closest = Double.MAX_VALUE;
        // SAM of the previous scene - away: charged, in loaded chunks, it would beat the Shahed of the next scene in 1,5 km
        if (sam != null) {
            cmd(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", sam.getX(), sam.getY(), sam.getZ()));
            cmd(String.format(Locale.ROOT, "forceload remove %d %d %d %d", sam.getX() - 32, sam.getZ() - 48, sam.getX() + 32, sam.getZ() + 48));
        }
        sam = null;
        interceptor = null;
        samLaunch = -1;
        samBurst = -1;
        samLoaded = false;
        Incoming.LOG.info("SCENARIO probe case {} site {} {}", current, site[0], site[1]);
        boolean night = current.contains("night");
        cmd(night ? "time set 18000" : "time set 6000");
        // explosion viewer - observer: the wave of the volley throws the player back in creative mode (shots from under water)
        cmd(current.startsWith("launch") || current.equals("flyby") ? "gamemode creative" : "gamemode spectator");
        if (current.startsWith("sam")) {
            planSam(tick, site, current.endsWith("burst"));
            return;
        }
        hover = true;
        Minecraft.getInstance().player.getAbilities().flying = true;
        // first above the target: chunks and target height for the server and client
        cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 0 60", site[0], 230, site[1]));
        int s = tick;
        if (current.startsWith("launch")) planLaunch(s, site, current.contains("behind"));
        else if (current.equals("flyby")) planFlyby(s, site);
        else planBlast(s, site);
    }

    /**
     * Flat land at (x, z) according to the heights of the generator: square ±40 blocks without water and with a difference of no more than 4, and the path of the viewer
     *  on 300 blocks to −Z not higher than the target on 12; searched with rings up to 1,5 km, was not found - the point itself.
     */
    private int[] flat(int x, int z) {
        for (int r = 0; r <= 1024; r += 128) {
            int steps = r == 0 ? 1 : Math.max(8, r / 32);
            for (int k = 0; k < steps; k++) {
                double a = 2 * Math.PI * k / steps;
                int cx = x + (int) Math.round(r * Math.cos(a)), cz = z + (int) Math.round(r * Math.sin(a));
                if (dryFlat(cx, cz)) return new int[]{cx, cz};
            }
        }
        Incoming.LOG.warn("SCENARIO probe: no flat dry land near {} {}", x, z);
        return new int[]{x, z};
    }

    private boolean dryFlat(int x, int z) {
        int at = height(x, z, Heightmap.Types.WORLD_SURFACE_WG);
        if (at <= 64) return false;
        for (int dx = -40; dx <= 40; dx += 20) {
            for (int dz = -40; dz <= 40; dz += 20) {
                int top = height(x + dx, z + dz, Heightmap.Types.WORLD_SURFACE_WG);
                if (Math.abs(top - at) > 3) return false;
                if (height(x + dx, z + dz, Heightmap.Types.OCEAN_FLOOR_WG) != top) return false;
            }
        }
        for (int d = 40; d <= 300; d += 20) if (height(x, z - d, Heightmap.Types.WORLD_SURFACE_WG) > at + 12) return false;
        return true;
    }

    private int height(int x, int z, Heightmap.Types type) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        var level = server.overworld();
        return level.getChunkSource().getGenerator().getBaseHeight(x, z, type, level, level.getChunkSource().randomState());
    }

    private int groundAt(int x, int z) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        var level = server.overworld();
        var gen = level.getChunkSource().getGenerator();
        return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
    }

    /** Shahed or “Grad” salvo into the ground: the viewer hangs in front of the target, footage from the first explosion at the target. */
    private void planBlast(int s, int[] site) {
        boolean grad = current.startsWith("grad"), bomb = current.startsWith("bomb");
        int dist = grad ? 30 : current.endsWith("-300") ? 300 : current.endsWith("-150") ? 150 : 50;
        at(s + 120, () -> {
            Minecraft mc = Minecraft.getInstance();
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site[0], site[1]);
            // the target chunk might not yet have arrived to the client (height −27 in run B): diverges from the generator - generator
            int gen = groundAt(site[0], site[1]);
            if (Math.abs(y - gen) > 12) y = gen;
            target = new Vec3(site[0] + 0.5, y, site[1] + 0.5);
            // viewer - above the terrain all the way to the target (according to the generator), not lower than the specified height above the target
            int above = grad ? 10 : dist == 50 ? 18 : dist == 150 ? 30 : 40;
            double eyeY = target.y + above;
            // the generator does not know the trees: over the topography of the path - with a margin for the crown
            for (int d = 0; d <= dist; d += 8) eyeY = Math.max(eyeY, groundAt(site[0], site[1] - d) + 14);
            Vec3 eye = new Vec3(target.x, eyeY, target.z - dist);
            double lookUp = grad ? 6 : dist == 50 ? 8 : dist == 150 ? 20 : 15;
            cmd(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, target.x, target.y + lookUp, target.z));
            Incoming.LOG.info("SCENARIO probe {} target {} eye {}", current, xyz(target), xyz(eye));
        });
        // building scenes S8 - before launch, on target ground (vanilla commands: the same in any version of the mod)
        at(s + 200, () -> {
            int x = (int) Math.floor(target.x), y = (int) Math.floor(target.y), z = (int) Math.floor(target.z);
            if (current.startsWith("water")) {
                cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water", x - 20, y - 4, z - 20, x + 20, y - 1, z + 20));
                target = new Vec3(target.x, y - 0.1, target.z);
            } else if (current.startsWith("debris")) {
                cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks hollow", x - 4, y, z - 4, x + 4, y + 4, z + 4));
                target = new Vec3(target.x, y + 4.5, target.z);
            } else if (current.startsWith("substation")) {
                cmd(String.format(Locale.ROOT, "setblock %d %d %d incoming:substation", x, y, z));
                target = new Vec3(target.x, y + 0.5, target.z);
            }
        });
        at(s + 420, () -> {
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            c.droneFlightTime.set(5);
            c.bomberFlightTime.set(5);
            seenBlasts = blasts.get();
            armed = true;
            firedAt = tick;
            if (grad) cmd(String.format(Locale.ROOT, "incoming salvo rocket 40 15 at %.1f %.1f %.1f", target.x, target.y, target.z));
            else cmd(String.format(Locale.ROOT, "incoming %s at %.1f %.1f %.1f", bomb ? "bunker" : "drone", target.x, target.y, target.z));
            Incoming.LOG.info("SCENARIO probe {} fire tick {} audio {}", current, tick, audioBytes());
        });
    }

    /** The explosion came: frames according to the scene list, then the next scene. */
    private void onBlast() {
        blastTick = tick;
        Minecraft mc = Minecraft.getInstance();
        Incoming.LOG.info("SCENARIO probe {} blast tick {} game {} audio {} wall {}", current, tick, mc.level.getGameTime(), audioBytes(),
                System.currentTimeMillis());
        double[] times = current.startsWith("grad") ? GRAD_SHOTS
                : current.startsWith("debris") || current.startsWith("substation") ? FIRE_SHOTS : DRONE_SHOTS;
        int last = 0;
        for (double t : times) {
            int dt = Math.max(1, (int) Math.round(t * 20));
            last = dt;
            shot(tick + dt, label(t));
        }
        at(tick + last + 40, this::nextCase);
    }

    /** Rocket launch from a spectator's launcher; the camera is behind the rocket along its axis or on the side. */
    private void planLaunch(int s, int[] site, boolean fromBehind) {
        behind = fromBehind;
        int y = LAUNCH_FLOOR;
        at(s + 2, () -> cmd(String.format(Locale.ROOT, "forceload add %d %d %d %d", site[0] - 32, site[1] - 48, site[0] + 32, site[1] + 136)));
        at(s + 120, () -> {
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:smooth_stone", site[0] - 30, y - 1, site[1] - 40, site[0] + 30, y - 1, site[1] + 130));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks", site[0] - 2, y, site[1] + 118, site[0] + 2, y + 2, site[1] + 122));
            hover = false;
            cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 0 5", site[0], y, site[1]));
            target = new Vec3(site[0] + 0.5, y + 3, site[1] + 120.5);
        });
        at(s + 200, () -> {
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.missileFlightTime.set(12);
            firedAt = tick;
            cmd(String.format(Locale.ROOT, "incoming missile at %.1f %.1f %.1f", target.x, target.y, target.z));
            Incoming.LOG.info("SCENARIO probe {} fire tick {} audio {}", current, tick, audioBytes());
        });
    }

    private void launchEvents() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (missile == null) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile sp && sp.weapon() == WeaponType.MISSILE && sp.distanceTo(p) < 120) {
                    missile = sp;
                    launchFrom = sp.position();
                    lastPos = launchFrom;
                    Incoming.LOG.info("SCENARIO probe {} missile seen at {}", current, xyz(launchFrom));
                }
            }
            if (missile == null && firedAt >= 0 && tick - firedAt > 1200) {
                Incoming.LOG.warn("SCENARIO probe {} no missile", current);
                firedAt = -1;
                nextCase();
            }
            return;
        }
        if (launchTick < 0 && firedAt >= 0 && tick - firedAt > 1200) {
            Incoming.LOG.warn("SCENARIO probe {} no launch seen", current);
            missile = null;
            firedAt = -1;
            nextCase();
            return;
        }
        if (launchTick < 0 && missile.isRemoved()) {
            // the client removed the rocket before launch (the server stopped loading chunks, the entity came again): the same rocket -
            //  new object, the old one does not move, and the launch was never detected (run F2c 22.10)
            missile = null;
            return;
        }
        Vec3 pos = missile.position();
        Vec3 step = pos.subtract(lastPos);
        if (step.length() > 0.05) axis = step.normalize();
        lastPos = pos;
        if (launchTick < 0) {
            if (pos.distanceTo(launchFrom) < 0.3) return;
            launchTick = tick;
            Incoming.LOG.info("SCENARIO probe {} launch tick {} game {} audio {}", current, tick, mc.level.getGameTime(), audioBytes());
            int last = 0;
            for (double t : LAUNCH_SHOTS) {
                int dt = (int) Math.round(t * 20);
                last = dt;
                shot(tick + dt, label(t));
            }
            at(tick + last + 20, () -> {
                hover = true;
                missile = null;
                firedAt = -1;
                nextCase();
            });
        }
        if (!missile.isAlive()) return;
        // camera: from behind, above the jet, or from the side across the axis at the height of the rocket
        Vec3 cam;
        if (behind) {
            Vec3 up = new Vec3(0, 1, 0).subtract(axis.scale(axis.y));
            up = up.lengthSqr() < 1e-6 ? new Vec3(0, 0, 1) : up.normalize();
            cam = pos.subtract(axis.scale(BEHIND)).add(up.scale(ABOVE_JET));
        } else {
            Vec3 side = new Vec3(axis.z, 0, -axis.x);
            if (side.lengthSqr() < 1e-4) side = new Vec3(1, 0, 0);
            cam = pos.add(side.normalize().scale(30));
        }
        // feet not lower than the top of the court floor (LAUNCH_FLOOR): otherwise the server will return the player back
        double feet = Math.max(cam.y - p.getEyeHeight(), LAUNCH_FLOOR);
        cam = new Vec3(cam.x, feet + p.getEyeHeight(), cam.z);
        hover = true;
        p.getAbilities().flying = true;
        Vec3 d = pos.subtract(cam);
        float yaw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
        p.moveTo(cam.x, feet, cam.z, yaw, pitch);
        p.setDeltaMovement(Vec3.ZERO);
        placed = cam;
        if (launchTick >= 0 && (tick - launchTick) % 10 == 0) {
            Incoming.LOG.info("SCENARIO probe {} t={}s missile {} axis {} cam {} angle-to-axis {}°", current,
                    String.format(Locale.ROOT, "%.1f", (tick - launchTick) / 20.0), xyz(pos),
                    String.format(Locale.ROOT, "%.2f %.2f %.2f", axis.x, axis.y, axis.z), xyz(cam),
                    Math.round(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d.normalize().dot(axis)))))));
        }
    }

    /** Shahed passes over the viewer to the target 200 blocks ahead (the last straight section is from behind). */
    private void planFlyby(int s, int[] site) {
        at(s + 120, () -> {
            int y = groundAt(site[0], site[1]);
            int ty = groundAt(site[0], site[1] + 200);
            target = new Vec3(site[0] + 0.5, ty, site[1] + 200.5);
            hover = false;
            cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 facing %.1f %.1f %.1f", site[0], y, site[1], target.x, target.y, target.z));
        });
        at(s + 300, () -> {
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            c.droneFlightTime.set(12);
            seenBlasts = blasts.get();
            armed = true;
            firedAt = tick;
            cmd(String.format(Locale.ROOT, "incoming drone at %.1f %.1f %.1f", target.x, target.y, target.z));
            Incoming.LOG.info("SCENARIO probe {} fire tick {} audio {}", current, tick, audioBytes());
        });
    }

    private void flybyEvents() {
        Minecraft mc = Minecraft.getInstance();
        if (drone == null) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile sp && sp.weapon() == WeaponType.DRONE) drone = sp;
            }
        }
        if (drone != null && drone.isAlive()) {
            double d = drone.distanceTo(mc.player);
            if (d < closest) {
                closest = d;
                if (tick % 2 == 0) Incoming.LOG.info("SCENARIO probe flyby d={} tick {} audio {}", Math.round(d), tick, audioBytes());
            }
            // layers of the motor at the ear - volume against the measure and tone ({@code ClientSounds.describe}): checking the recording with the model
            if (tick % 10 == 0) Incoming.LOG.info("SCENARIO probe flyby at {} d={} v={} engines:{}", xyz(drone.position()), Math.round(d),
                    String.format(Locale.ROOT, "%.2f", drone.speed()), ua.zentix.incoming.client.sound.ClientSounds.describe());
        }
    }

    /**
     * SAM is on the site in 25 blocks above the ground (the sky is clear around), Shahed comes at him from behind 1400 blocks (the gap is because of 700) to point
     *  behind him. Start: SAM is charged immediately, the spectator is on the side in 45 blocks or behind him in 30 on the launch line. Gap: SAM is empty, charging,
     *  when Shahed is closer to the client 180 blocks along the line, the viewer is 70 blocks to the side of the meeting place.
     */
    private void planSam(int s, int[] site, boolean burst) {
        at(s + 2, () -> cmd(String.format(Locale.ROOT, "forceload add %d %d %d %d", site[0] - 32, site[1] - 48, site[0] + 32, site[1] + 48)));
        at(s + 120, () -> {
            // platform - above the forest and hills around: near the stage with relief covering Shahed, launch and meeting - in a different place
            //  for each version (in 3.4.1 the radar does not cover the terrain), and there is nothing to compare
            int y = groundAt(site[0], site[1]) + 26;
            sam = new BlockPos(site[0], y, site[1]);
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:smooth_stone", site[0] - 20, y - 1, site[1] - 20, site[0] + 20, y - 1, site[1] + 20));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", site[0] - 20, y, site[1] - 20, site[0] + 20, y + 30, site[1] + 20));
            cmd(String.format(Locale.ROOT, "setblock %d %d %d incoming:sam{ready:%d}", site[0], y, site[1], burst ? 0 : 4));
            hover = true;
            if (current.endsWith("behind")) {
                cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 facing %d.5 %d %d.5", site[0], y + 3, site[1] + 30, site[0], y + 30, site[1] - 40));
            } else if (!burst) {
                cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 facing %d.5 %d %d.5", site[0] + 45, y + 4, site[1], site[0], y + 22, site[1] - 10));
            } else {
                cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 facing %d.5 %d %d.5", site[0] + 70, y + 30, site[1] - 110, site[0], y + 45, site[1] - 110));
            }
            target = new Vec3(site[0] + 0.5, y, site[1] + 250.5);
            Incoming.LOG.info("SCENARIO probe {} sam {} {} {}", current, site[0], y, site[1]);
        });
        at(s + 220, () -> {
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            firedAt = tick;
            cmd(String.format(Locale.ROOT, "incoming salvo drone 1 0 at %.1f %.1f from %d %d", target.x, target.z, site[0], site[1] - (burst ? 700 : 1400)));
            Incoming.LOG.info("SCENARIO probe {} fire tick {} audio {}", current, tick, audioBytes());
        });
    }

    private void samEvents() {
        Minecraft mc = Minecraft.getInstance();
        if (sam == null) return;
        Vec3 at = Vec3.atCenterOf(sam);
        if (current.endsWith("burst") && !samLoaded) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile sp && sp.weapon() == WeaponType.DRONE && sp.getZ() > at.z - 180 && sp.getZ() < at.z) {
                    samLoaded = true;
                    cmd(String.format(Locale.ROOT, "data merge block %d %d %d {ready:1}", sam.getX(), sam.getY(), sam.getZ()));
                    Incoming.LOG.info("SCENARIO probe {} sam loaded, drone at {}", current, xyz(sp.position()));
                }
            }
        }
        if (interceptor == null && samBurst < 0) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof ua.zentix.incoming.entity.InterceptorEntity i && i.distanceToSqr(at) < 64 * 64) interceptor = i;
            }
            if (interceptor != null && samLaunch < 0) {
                samLaunch = tick;
                Incoming.LOG.info("SCENARIO probe {} launch tick {} game {} audio {}", current, tick, mc.level.getGameTime(), audioBytes());
                if (!current.endsWith("burst")) {
                    int last = 0;
                    for (double t : SAM_SHOTS) {
                        last = (int) Math.round(t * 20);
                        shot(tick + last, label(t));
                    }
                    at(tick + last + 40, this::nextCase);
                }
            }
        }
        if (interceptor != null && current.endsWith("burst") && samBurst < 0 && interceptor.isRemoved()) {
            samBurst = tick;
            Incoming.LOG.info("SCENARIO probe {} blast tick {} game {} audio {} at {}", current, tick, mc.level.getGameTime(), audioBytes(),
                    xyz(interceptor.position()));
            int last = 0;
            for (double t : BURST_SHOTS) {
                last = Math.max(1, (int) Math.round(t * 20));
                shot(tick + last, label(t));
            }
            at(tick + last + 40, this::nextCase);
        }
        if (current.endsWith("burst") && samLaunch >= 0 && samBurst < 0 && tick - samLaunch > SPEC_FLIGHT) {
            Incoming.LOG.warn("SCENARIO probe {} no burst", current);
            samLaunch = -1;
            firedAt = -1;
            nextCase();
            return;
        }
        if (firedAt >= 0 && samLaunch < 0 && tick - firedAt > BLAST_LIMIT) {
            Incoming.LOG.warn("SCENARIO probe {} no interceptor", current);
            firedAt = -1;
            nextCase();
        }
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        tick++;
        for (Step s : List.copyOf(steps)) {
            if (s.at == tick) s.action.run();
        }
        if (hover) p.getAbilities().flying = true;
        // price of effects layer once per second after the explosion (budget: “Grad” salvo - no more than 2 ms video card)
        int from = blastTick >= 0 ? blastTick : launchTick;
        if (from >= 0 && (tick - from) % 20 == 0) {
            Incoming.LOG.info("SCENARIO probe {} layer {} s: {}", current, (tick - from) / 20,
                    ua.zentix.incoming.client.fx.layer.FxLayer.describeTiming());
        }
        if (current.startsWith("launch") && firedAt >= 0) launchEvents();
        if (current.equals("flyby") && firedAt >= 0) flybyEvents();
        if (current.startsWith("sam") && firedAt >= 0) samEvents();
        if (armed && blastTick < 0 && blasts.get() > seenBlasts) {
            armed = false;
            if (current.equals("flyby")) {
                blastTick = tick;
                Incoming.LOG.info("SCENARIO probe flyby blast tick {} audio {} closest {}", tick, audioBytes(), Math.round(closest));
                at(tick + 60, this::nextCase);
            } else {
                onBlast();
            }
        }
        if (armed && firedAt >= 0 && tick - firedAt > BLAST_LIMIT) {
            armed = false;
            Incoming.LOG.warn("SCENARIO probe {} no blast in {} ticks", current, BLAST_LIMIT);
            nextCase();
        }
        if (tick % 100 == 0) {
            Runtime r = Runtime.getRuntime();
            var server = mc.getSingleplayerServer();
            Incoming.LOG.info("SCENARIO probe fps {} heap {} MB mspt {} case {}", mc.getFps(), (r.totalMemory() - r.freeMemory()) >> 20,
                    server == null ? "-" : String.format(Locale.ROOT, "%.1f", server.getAverageTickTimeNanos() / 1e6), current);
        }
    }

    private long last;

    private void onFrame(RenderFrameEvent.Post e) {
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        drawnAt = placed;
        if (frames != null && last != 0 && mc.level != null) {
            try {
                frames.write(current + " " + tick + " " + (now - last) / 1000 / 1000.0 + "\n");
                if (tick % 200 == 0) frames.flush();
            } catch (IOException ignored) {
                // frozen, no game
            }
        }
        last = now;
    }

    private static String label(double t) {
        return t == Math.rint(t) ? (int) t + "s" : String.format(Locale.ROOT, "%.1fs", t);
    }

    private void shot(int t, String label) {
        String name = current + "_" + label;
        at(t, () -> {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, "probe_" + name + ".png", mc.getMainRenderTarget(), c -> {});
            Incoming.LOG.info("SCENARIO probe shot {} tick {} fps {}", name, tick, mc.getFps());
            // explosion at the frame, and not at the moment of the server event: the explosion packet arrives later than the explosion counter
            Incoming.LOG.info("SCENARIO probe shot {} blast {}", name, ua.zentix.incoming.client.far.FarBlasts.describeLast());
            // fires near the frame: fire blocks, trail rise and wind (house, substation, fire from a fuel ball)
            Incoming.LOG.info("SCENARIO probe shot {} smoke {}", name, ua.zentix.incoming.client.fx.FireSmoke.describe());
            // frame - from the point where the scene placed the eye to draw it (the server could return the player back): otherwise
            // check.py rejects the run
            if (drawnAt != null) {
                Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
                double off = eye.distanceTo(drawnAt);
                Incoming.LOG.info("SCENARIO probe shot {} camera {} off {}", name, xyz(eye),
                        String.format(Locale.ROOT, "%.2f", off));
            }
        });
    }

    private void at(int t, Runnable r) {
        steps.add(new Step(t, r));
    }

    /** Byte in audio.wav (driver “wave” OpenAL Soft writes in real time): the location of the event in the sound. */
    private static long audioBytes() {
        try {
            return Files.size(FMLPaths.GAMEDIR.get().resolve("audio.wav"));
        } catch (IOException e) {
            return -1;
        }
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Incoming.LOG.info("SCENARIO /{}", c);
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
    }
}
