package ua.zentix.incoming.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarRenderer;
import ua.zentix.incoming.client.screen.RemoteScreen;
import ua.zentix.incoming.client.sound.ClientSounds;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scenario for a client without a window (./gradlew runClientScenario in a virtual display): creates a world, fires
 *  in turn Shahed, a rocket, a bomb and a salvo, takes frames (run/scenario/screenshots) and writes to the log what is heard
 *  (loudness and tone of the engines), then exits. Only turns on system property incoming.scenario.
 */
@Mod(value = Incoming.MOD_ID, dist = Dist.CLIENT)
public final class ClientScenario {
    static final String WORLD = "incoming_scenario";

    private record Step(int at, Runnable action) {}

    private final List<Step> steps = new ArrayList<>();
    /**
     * Scenario commands, {@code wait:nuke}: tick, from which the steps are waiting for the detonation packet ({@code -1} - they are not waiting), and how many
     *  ticks they are already waiting for; the detonations that the client knew when the steps stood up are not the same detonations.
     */
    private int nukeGate = -1, nukeGateWaited;
    /** {@code wait:blast}: tick, from which steps wait for the next explosion on the embedded server; explosions from launch. */
    private final java.util.ArrayDeque<Integer> blastGates = new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicInteger serverBlasts = new java.util.concurrent.atomic.AtomicInteger();
    private int blastGateSeen = -1, blastGateWaited;
    private final java.util.Set<Integer> nukeGateSeen = new java.util.HashSet<>();
    private boolean started;
    private int tick = -1;
    private Vec3 target = Vec3.ZERO;
    /** Scenario {@code nuke}: the viewer’s place is on the observation deck (the wave then carries him off it). */
    private BlockPos deck = BlockPos.ZERO;
    private boolean nuke;
    /** Scenario {@code pall}: pall after the wave; epicenter */
    private boolean pall;
    /** Scenario {@code mushroom}: a mushroom with volume from afar. */
    private boolean mushroom;
    private Vec3 pallAt = Vec3.ZERO;
    /** Scenario firestorm ({@link #planFirestorm}): night or day, heights of shooting points, a point inside the tornado. */
    private boolean firestorm, fsNight;
    private int fsFarY, fsRoofY;
    private Vec3 fsInside = Vec3.ZERO, fsLook = Vec3.ZERO;
    private double fsWhirlAge = 300;
    /** Scenario {@code debris}: houses and trees near the camera that are broken by a wave; tick when the front arrives at them (−1 - there is no explosion). */
    private boolean debris;
    private volatile BlockPos debrisSite;
    private int debrisStage, debrisAt;
    /** Scenario {@code wall}: a wall of dust passes by the camera; how many frames of her passage have already been filmed. */
    private boolean wall;
    private int wallStage;
    /**
     * Scenario pall-front: camera location (in the air, in 400 blocks west of the epicenter); pall-low - camera near the ground below the
     *  ground below the epicenter ({@link #planPallFront}).
     */
    private boolean front, frontLow;
    private Vec3 frontAt = Vec3.ZERO;
    /** Scenario pall-mine: pall during the day near the ground, in the adit below it and in the mine to the sky ({@link #mineShots}). */
    private boolean frontMine;
    private int detTick = -1;
    /** Nuclear scenario: a point in the sediment trail and a game tick when to go there (−1 - we’re not waiting). */
    private Vec3 falloutSpot = Vec3.ZERO;
    private long falloutGo = -1;
    /** Effects script: burst of hits, current projectile, tick of its explosion. */
    private java.util.ArrayDeque<String> fx;
    /** Effects at specified points ({@code incoming.fx.targets}): impact point for each point {@link #fx}. */
    private java.util.ArrayDeque<double[]> fxTargets;
    /**
     * Pillar after the explosion ({@code fx-late}): after so many ticks after the explosion - frames from half of the rendering and from behind its edge.
     *  There are no particles of the explosion by this time (the smoke of the ball lasts until 36 s), the pillar stands for a minute.
     */
    private static final int FX_LATE = 900;
    private boolean fxLate;
    private Vec3 eye = Vec3.ZERO;
    private StrikeProjectile watched;
    private String current;
    private int flightShots;
    /**
     * Approach from the ground ({@code incoming.fx.approach=true}, only with {@code incoming.fx.targets}): the viewer stands in 40 blocks
     *  east of the target and looks north, where the projectile comes from, approach frames every 8 tick until the explosion.
     */
    private final boolean approach = Boolean.getBoolean("incoming.fx.approach");
    /**
     * Viewer position relative to the effects target ({@code incoming.fx.eye=up,back}, blocks; default is to the side
     * and slightly above): for example, {@code 70,45} is above, as if from an airship.
     */
    private static final double[] FX_EYE = fxEye(System.getProperty("incoming.fx.eye"));
    /**
     * Hit a given point ({@link #fxTargets}): launch from the console of the built-in server and explode your projectile. The projectile from
     *  of the client is searched for UUID and again after leaving for flight outside the world; the next goal is only after the explosion (or deadline).
     */
    private volatile StrikeWatch strike;
    /** tick the client when the strike command left ({@link #strike}), and the client already had the projectile. */
    private int strikeTick = -1;
    private boolean reached;
    /** There is no strike for so many ticks after the command - “no impact”, the next goal. */
    private static final int FX_IMPACT_LIMIT = 2400;
    /** Approach viewer: how many blocks are above the highest pillar in {@link #FX_VIEW_RADIUS} from the target, and how long to wait for ready chunks. */
    private static final int FX_VIEW_ABOVE = 35, FX_VIEW_RADIUS = 64, FX_VIEW_WAIT = 600;
    /** Video from the board: current version (“dry”, then “wet”), tick launch and captured footage. */
    private String onboard;
    private int onboardFired = -1, onboardFrames;
    /** The most particles in front of the lens are in the frames from the last line of the log (counted in the frame with the camera of this frame). */
    private int lensMax;
    /** Models scenario: no particles needed. */
    private boolean models;
    /** Script replay: launch as launch, then check for replay Flashback ({@link ReplayCheck}). */
    private boolean replay;
    /** The spectator hangs in the air (far-models): after the teleport, the server sends abilities, and the flight is reset. */
    private boolean hover;
    /** Scenario {@code mushroom}: where the epicenter is from the viewer (turn, like the player), - where the land is. */
    private int mushroomYaw = 180;
    /** Scenario {@code mushroom}: shadow of a mushroom on the ground ({@code INCOMING_MUSHROOM=shadow}). */
    private boolean shadowView;
    /** Scenario {@code mushroom}: light of a ball on the ground at night ({@code INCOMING_MUSHROOM=glow}). */
    private boolean glowView;
    /** Scenario {@code mushroom}: Host video view over Newisle ({@code INCOMING_MUSHROOM=film}). */
    private boolean filmView;
    /** Scenario {@code mushroom}: light of a ball on the ground during the day at 4 km ({@code INCOMING_MUSHROOM=flash}). */
    private boolean flashView;
    /** Scenario {@code mushroom} with {@code film}: spectator (unknown before entering the world). */
    private volatile Vec3 filmEye;
    /** Scenario {@code mushroom}: dust from the ground and pall from different heights ({@code INCOMING_MUSHROOM=blanket}). */
    private boolean blanketView;
    /** Once every tick sound is recorded in the log. */
    private int soundEvery = 10;
    /** Nuclear explosion near the water ({@code nuke-water}, {@code nuke-lake}): footage from the explosion ({@link #waterEvents}). */
    private boolean water, lake;
    /** Detonation location and viewer ({@link #planNukeWater}): server selects, client steps await. */
    private volatile Vec3 waterBurst, waterEye;
    private volatile int waterGround;
    /** client tick when the viewer is transferred to the shooting location found by the server (-1 - the server is still searching). */
    private int waterFoundTick = -1;
    /** The client has a detailed city for measuring fire ({@code incoming.scenario.city}), null - no. */
    @org.jetbrains.annotations.Nullable
    private final FireCity city = FireCity.of(System.getProperty("incoming.scenario.city"));

    public ClientScenario(IEventBus modBus) {
        String scenario = System.getProperty("incoming.scenario");
        if (scenario == null) return;
        ua.zentix.incoming.gametest.TestScale.realWaveOnStart();
        FrameTimes.startIfRequested();
        // script lines, sandbox and samples write the layer time on the video card in parts: the sample is always running
        ua.zentix.incoming.client.fx.layer.LayerTimes.always();
        if (scenario.startsWith("mp-")) {
            new MultiplayerScenario("mp-a".equals(scenario)); // your own server, without a script world (tools/mp_scenario.sh)
            return;
        }
        if ("leak".equals(scenario)) {
            new LeakCheck(); // entries into the world and memory measurements in the menu are their own (LeakCheck), the world is opened by itself on each entry
            return;
        }
        if ("sandbox".equals(scenario)) {
            new Sandbox(); // sandbox effects: your own flat world, commands /fx, hot swap (tools/sandbox.sh)
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        if ("trailer".equals(scenario)) {
            new ua.zentix.incoming.scenario.trailer.Trailer(); // your script and recording (tools/trailer)
            return;
        }
        if ("strike-profile".equals(scenario)) {
            new StrikeProfile(); // steps and server tick measurement - your own (StrikeProfile); world - player copy (onScreen)
            return;
        }
        if ("probe".equals(scenario)) {
            new ProbeScenario(); // look gate of pictures and sound: same scenes in any version (tools/look_gate)
            return;
        }
        if ("guide".equals(scenario)) {
            new GuideShots(); // player Guide Frames (docs/guide, tools/guide_images.sh)
            return;
        }
        if ("flash".equals(scenario)) {
            new FlashScenario(); // flash close up: smoke, silhouettes, EMP, haze (FlashScenario)
            return;
        }
        if (scenario.startsWith("flyby-")) {
            new FlybySound(scenario.substring("flyby-".length())); // sound cases one by one, results in log (FlybySound)
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevel);
        String mode = scenario;
        if ("nuke".equals(mode)) planNuke();
        else if ("nuke-water".equals(mode) || "nuke-lake".equals(mode)) planNukeWater("nuke-lake".equals(mode));
        else if ("pall".equals(mode)) planPall();
        else if ("mushroom".equals(mode)) planMushroom();
        else if ("firestorm".equals(mode)) planFirestorm();
        else if ("wall".equals(mode)) planWall();
        else if ("debris".equals(mode)) planDebris();
        else if ("pall-front".equals(mode) || "pall-low".equals(mode)) planPallFront("pall-low".equals(mode));
        else if ("pall-mine".equals(mode)) {
            frontMine = true;
            planPallFront(false);
        }
        else if ("fx".equals(mode) || "fx-night".equals(mode) || "fx-late".equals(mode)) planFx("fx-night".equals(mode), "fx-late".equals(mode));
        else if ("smoke-salvo".equals(mode) || "smoke-salvo-pall".equals(mode) || "smoke-column".equals(mode) || "smoke-fire".equals(mode)) planSmoke(mode);
        else if ("launch".equals(mode)) planLaunch();
        else if ("replay".equals(mode)) {
            replay = true;
            planLaunch();
        }
        else if ("rocket".equals(mode)) planRocket();
        else if ("loiter".equals(mode)) planLoiter();
        else if ("models".equals(mode)) planModels();
        else if ("far-models".equals(mode)) planFarModels();
        else if ("flame-edge".equals(mode)) planFlameEdge();
        else if ("hud".equals(mode)) planHud();
        else if ("map".equals(mode)) planMap();
        else if ("target-map".equals(mode)) planTargetMap();
        else if ("salvo-map".equals(mode)) planSalvoMap();
        else if ("route-map".equals(mode)) planRouteMap(false);
        else if ("launch-map".equals(mode)) planRouteMap(true);
        else if ("occlusion".equals(mode)) planOcclusion();
        else if ("nuke-profile".equals(mode)) planNukeProfile();
        else if ("onboard".equals(mode)) planOnboard();
        else if ("salvo-bench".equals(mode)) planSalvoBench();
        else if ("flyby".equals(mode)) planFlyby();
        else if ("commands".equals(mode)) planCommands();
        else plan();
    }

    private void onScreen(ScreenEvent.Init.Post e) {
        if (started || !(e.getScreen() instanceof TitleScreen)) return;
        started = true;
        Minecraft mc = Minecraft.getInstance();
        // player's world copy (tools/prod_client.py --world): open it instead of creating a scenario world
        String world = System.getProperty("incoming.world");
        if (world != null) {
            mc.createWorldOpenFlows().openWorld(world, () -> Incoming.LOG.error("SCENARIO world did not open {}", world));
            return;
        }
        createWorld(e.getScreen());
    }

    /** The scenario world is recreated (the previous one is deleted); {@code parent} - screen where to return if the world is not created. */
    static void createWorld(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        deleteOldWorld(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD));
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20260927L, List.of("trailer", "guide").contains(System.getProperty("incoming.scenario")), false),
                WorldPresets::createNormalWorldDimensions, parent);
    }

    private static void deleteOldWorld(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ex) {
            Incoming.LOG.warn("SCENARIO failed to delete old script world", ex);
        }
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        tick++;
        if (nukeGate >= 0 && tick >= nukeGate) holdForNuke();
        if (!blastGates.isEmpty() && tick >= blastGates.getFirst()) holdForBlast();
        for (Step s : List.copyOf(steps)) {
            if (s.at == tick) s.action.run();
        }
        if (nuke) nukeEvents();
        if (water) waterEvents();
        if (pall) pallEvents();
        if (mushroom) mushroomEvents();
        if (firestorm) firestormEvents();
        if (wall) wallEvents();
        if (debris) debrisEvents();
        if (front) frontEvents();
        if (city != null) city.tick(tick);
        if (fx != null) fxEvents();
        smokeEvents();
        if (onboard != null) onboardEvents();
        // close-up models: exhaust smoke and trail would cover them
        if (models) {
            mc.particleEngine.setLevel(mc.level);
            ua.zentix.incoming.client.fx.particle.FxPool.INSTANCE.clear();
        }
        if (hover) p.getAbilities().flying = true;
        if (tick % soundEvery == 0) logSound();
        if (tick % 10 == 0 && (!ua.zentix.incoming.client.hud.ClientFlights.all().isEmpty() || ua.zentix.incoming.client.cam.ProjectileCamera.isActive())) logFlights();
        if (tick % 100 == 0) {
            // the embedded server shares the processor with the client: its tick is next to fps - whose drawdown is it
            var server = mc.getSingleplayerServer();
            if (server == null) Incoming.LOG.info("SCENARIO fps {}", mc.getFps());
            else Incoming.LOG.info("SCENARIO fps {}, server {} ms/tick", mc.getFps(), String.format(java.util.Locale.ROOT, "%.1f", server.getAverageTickTimeNanos() / 1e6));
        }
        if (tick % 20 == 0) {
            String far = FarRenderer.describe();
            if (!far.isEmpty()) Incoming.LOG.info("SCENARIO far {}", far);
            String nuke = ua.zentix.incoming.client.nuclear.NukeSounds.describe();
            if (!nuke.isEmpty()) Incoming.LOG.info("SCENARIO nuke-sound {}", nuke);
            String blast = ua.zentix.incoming.client.sound.BlastSounds.describe();
            if (!blast.isEmpty()) Incoming.LOG.info("SCENARIO blast-sound {}", blast);
        }
    }

    private void plan() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 170 0 0 40");
        });
        // target - land in 90 blocks along +Z; spectator - on a column in 45 blocks from it and in 25 above it
        at(240, () -> {
            // here impacts are checked, not launch: approach from afar and the shortest flight (launch - scenario launch)
            quickFlights();
            aimAhead(90);
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glass", (int) Math.floor(target.x), (int) target.y + 24,
                    (int) Math.floor(target.z) - 45, (int) Math.floor(target.x), (int) target.y + 24, (int) Math.floor(target.z) - 45));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", target.x, target.y + 25, target.z - 44.5,
                    target.x, target.y, target.z));
        });
        strike(300, "drone", 240);
        strike(560, "missile", 260);
        strike(840, "bunker", 300);
        at(1160, () -> {
            cmd(String.format(java.util.Locale.ROOT, "incoming salvo drone 3 12 at %.1f %.1f %.1f", target.x, target.y, target.z));
        });
        for (int t = 1200; t <= 1460; t += 20) shot(t, "salvo");
        at(1500, () -> {
            cmd("give @s incoming:strike_designator");
            Minecraft.getInstance().options.keyUse.setDown(true);
        });
        shot(1530, "scope");
        at(1540, () -> {
            Minecraft.getInstance().options.keyUse.setDown(false);
            Minecraft.getInstance().setScreen(new RemoteScreen());
        });
        shot(1550, "remote");
        at(1560, () -> Minecraft.getInstance().setScreen(null));
        at(1580, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Nuclear strike (DESIGN-nuke §12): ICBM starts from the player and flies 90 s to the target in 2 km (ground 15 kt),
     *  frames of launch, warhead entry, flash, ball, front and mushroom; then - in a trail of precipitation, under the black rain,
     *  with a Geiger counter in hand.
     */
    private void planNuke() {
        nuke = true;
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 170 0 0 0");
        });
        at(200, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            int x = (int) Math.floor(p.getX()), z = (int) Math.floor(p.getZ());
            int ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            // observation deck over the surrounding hills; target - 2 km to the north (−Z)
            int y = Math.max(ground, 170);
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 1, y + 29, z - 1, x + 1, y + 29, z + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 180 -8", x, y + 30, z));
            deck = new BlockPos(x, y + 30, z);
            cmd("give @s incoming:geiger_counter");
            target = new Vec3(x + 0.5, 320, z - 2000 + 0.5); // height 320 - the server will be lowered to the ground
        });
        at(260, () -> cmd(String.format(java.util.Locale.ROOT, "incoming nuke at %.1f %.1f %.1f 15 ground", target.x, target.y, target.z)));
        // the rocket launches behind us: we look back at the launch, then back to the target
        at(262, () -> cmd("tp @s ~ ~ ~ 0 -25"));
        for (int t : new int[]{270, 300, 340, 400, 500}) shot(t, "launch");
        at(520, () -> cmd("tp @s ~ ~ ~ 180 -8"));
        // further - from real events: the server may lag behind the client's frame counter
    }

    /**
     * Nuclear explosion near water (DESIGN-nuke §6, “Water”), 15 kt on the ground, without flight ({@code nuke now}), during the day.
     * {@code nuke-water}: spectator (observer - the wave does not throw him off) in 20 blocks above the sea in 40 blocks
     *  from the shore of the nearest warm sea, explosion - at sea at 1,5 km: a pillar, a ring of
     *  fog, a dark ring of the front and a wall of splashes on the water near the shore, then a base wave. {@code nuke-lake}: plain,
     *  in front of the viewer is a pond 120×140 blocks (from 30 to 170 blocks), the explosion is on land in 1,5 km behind it: the front goes through the pond
     *  dark a ring with a wall of splashes, but the dust of the wall does not rise above the water.
     */
    private void planNukeWater(boolean lake) {
        water = true;
        this.lake = lake;
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            // observer: a wave of 1,5 km would throw the viewer off the site in the middle of filming
            cmd("gamemode spectator");
            Minecraft.getInstance().options.hideGui = true;
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                BlockPos origin = BlockPos.ZERO.atY(64);
                if (lake) {
                    var found = level.findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.PLAINS), origin, 6400, 32, 64);
                    var gen = level.getChunkSource().getGenerator();
                    var random = level.getChunkSource().randomState();
                    BlockPos plains = found == null ? origin : found.getFirst(), at = plains;
                    // a place from which +Z to the explosion there is land (there is a sea near the plain), and without snow (in a cold forest a pond
                    //  would freeze): along a grid around the found plain; two points of sushi cost heat
                    int best = -1, bestLand = 0;
                    boolean bestWarm = false;
                    for (int k = 0; k < 81 && best < 39; k++) {
                        int cx = plains.getX() + ((k % 9) - 4) * 256, cz = plains.getZ() + ((k / 9) - 4) * 256, land = 0;
                        for (int r = 0; r <= 1600; r += 100)
                            if (gen.getBaseHeight(cx, cz + r, Heightmap.Types.OCEAN_FLOOR_WG, level, random) >= 65) land++;
                        BlockPos pos = new BlockPos(cx, 64, cz);
                        boolean warm = !level.getBiome(pos).value().coldEnoughToSnow(pos);
                        int score = 2 * land + (warm ? 5 : 0);
                        if (score > best) {
                            best = score;
                            bestLand = land;
                            bestWarm = warm;
                            at = pos;
                        }
                    }
                    waterGround = gen.getBaseHeight(at.getX(), at.getZ(), Heightmap.Types.WORLD_SURFACE_WG, level, random);
                    Incoming.LOG.info("SCENARIO lake at {} ground {} ({} of 17 points on land, warm {})", at.toShortString(), waterGround, bestLand,
                            bestWarm);
                    // pond chunks - immediately: the observer loads them in the background, and by the time the pond was filled (tick 300) they might not have existed yet
                    for (int cx = (at.getX() - 64) >> 4; cx <= (at.getX() + 64) >> 4; cx++)
                        for (int cz = (at.getZ() - 8) >> 4; cz <= (at.getZ() + 176) >> 4; cz++) level.getChunk(cx, cz);
                    waterEye = new Vec3(at.getX() + 0.5, 0, at.getZ() + 0.5);
                    waterBurst = new Vec3(at.getX() + 0.5, 320, at.getZ() + 1500.5);
                } else {
                    // a warm sea (a frozen sea has ice and icebergs), from it to the beginning of the world - to the shore
                    var found = level.findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.OCEAN) || b.is(net.minecraft.world.level.biome.Biomes.LUKEWARM_OCEAN)
                            || b.is(net.minecraft.world.level.biome.Biomes.WARM_OCEAN) || b.is(net.minecraft.world.level.biome.Biomes.DEEP_LUKEWARM_OCEAN), origin, 6400, 32, 64);
                    var gen = level.getChunkSource().getGenerator();
                    var random = level.getChunkSource().randomState();
                    BlockPos sea = found == null ? origin : found.getFirst(), at = sea;
                    double len = Math.max(1, Math.hypot(sea.getX(), sea.getZ()));
                    for (int r = 0; r < len; r += 8) {
                        int x = sea.getX() - (int) Math.round(sea.getX() / len * r), z = sea.getZ() - (int) Math.round(sea.getZ() / len * r);
                        if (gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random) >= 62) {
                            at = new BlockPos(x, 64, z);
                            break;
                        }
                    }
                    // sea - in the direction from the coast where the bottom of the generator is below sea level all the way to the explosion and on the sides
                    //  from the viewer (the cape is on the side - land dust flies over the water near the camera)
                    double best = -1, bestAngle = 0;
                    for (int k = 0; k < 24; k++) {
                        double a = k * Math.PI * 2 / 24;
                        double score = 0;
                        for (int r = 100; r <= 1700; r += 100) {
                            int x = at.getX() + (int) Math.round(Math.cos(a) * r), z = at.getZ() + (int) Math.round(Math.sin(a) * r);
                            if (gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random) < 58) score += 1;
                        }
                        for (int r = 100; r <= 400; r += 100) {
                            for (int side = -1; side <= 1; side += 2) {
                                int x = at.getX() + (int) Math.round(Math.cos(a) * r - Math.sin(a) * 150 * side);
                                int z = at.getZ() + (int) Math.round(Math.sin(a) * r + Math.cos(a) * 150 * side);
                                if (gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random) < 58) score += 1;
                            }
                        }
                        if (score > best) {
                            best = score;
                            bestAngle = a;
                        }
                    }
                    Incoming.LOG.info("SCENARIO water shore {} sea direction {}° ({} of 25 points over water)", at.toShortString(),
                            Math.round(Math.toDegrees(bestAngle)), (int) best);
                    // viewer - above the water in 40 blocks from the shore
                    waterEye = new Vec3(at.getX() + 0.5 + Math.cos(bestAngle) * 40, 0, at.getZ() + 0.5 + Math.sin(bestAngle) * 40);
                    waterBurst = new Vec3(at.getX() + 0.5 + Math.cos(bestAngle) * 1540, ua.zentix.incoming.client.fx.layer.WaterDecal.SEA_LEVEL, at.getZ() + 0.5 + Math.sin(bestAngle) * 1540);
                }
                Minecraft.getInstance().execute(() -> {
                    cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f 140 %.1f", waterEye.x, waterEye.z));
                    waterFoundTick = tick;
                });
            });
        });
        // platform and view - when the chunks came around
        at(300, this::placeWaterViewer);
        at(6000, () -> {
            Incoming.LOG.info("SCENARIO water: no detonation");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Spectator's area, pond and view; detonation - via 120 tick. The server is still looking for a location (generating pond chunks on a loaded
     *  machine takes tens of seconds) - again in a second; chunks around the transferred viewer - 260 tick.
     */
    private void placeWaterViewer() {
        if (waterFoundTick < 0) {
            at(tick + 20, this::placeWaterViewer);
            return;
        }
        if (tick < waterFoundTick + 260) {
            at(waterFoundTick + 260, this::placeWaterViewer);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int x = (int) Math.floor(waterEye.x), z = (int) Math.floor(waterEye.z);
        // near the pond - the height of the generator (without trees; the client’s chunk might not have arrived yet)
        int ground = lake ? waterGround : mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int y = lake ? ground + 8 : Math.max(ground, ua.zentix.incoming.client.fx.layer.WaterDecal.SEA_LEVEL) + 20;
        if (lake) {
            // pond: two blocks of water below ground level near the viewer; above it and up to the viewer there is air on 40 blocks (hills
            //  and trees otherwise cover the pond); fill - stripes, no more than 32768 blocks
            for (int z0 = 0; z0 <= 170; z0 += 6) {
                int z1 = Math.min(170, z0 + 5);
                cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 60, ground, z + z0, x + 60, ground + 40, z + z1));
            }
            for (int z0 = 30; z0 <= 170; z0 += 35) {
                int z1 = Math.min(170, z0 + 34);
                cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water", x - 60, ground - 2, z + z0, x + 60, ground - 1, z + z1));
            }
        }
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 1, y - 1, z - 1, x + 1, y - 1, z + 1));
        waterEye = new Vec3(x + 0.5, y, z + 0.5);
        look(waterBurst.x, 63 + (lake ? 120 : 210), waterBurst.z);
        at(tick + 120, () -> cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %.1f %.1f %.1f 15 ground", waterBurst.x, waterBurst.y,
                waterBurst.z)));
    }

    /** Viewer {@link #waterEye} looks at the dot. */
    private void look(double x, double y, double z) {
        cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", waterEye.x, waterEye.y, waterEye.z, x, y, z));
    }

    /** Explosion near water: time frames from the explosion, line of water in the log; end - after the last frame. */
    private void waterEvents() {
        if (tick % 10 == 0) Incoming.LOG.info("SCENARIO water {}", ua.zentix.incoming.client.nuclear.NukeWater.describe());
        // pall near the water: what is it made of near the camera (spray, base wave, dust), what is under it, demolition
        if (tick % 100 == 0 && detTick >= 0) Incoming.LOG.info("SCENARIO water pall {}", ua.zentix.incoming.client.nuclear.NukePall.describe());
        if (detTick >= 0 || ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty()) return;
        detTick = tick;
        steps.removeIf(s -> s.at >= 5999);
        var d = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
        Incoming.LOG.info("SCENARIO detonation at tick {}: ground {} at {}, eye {} blocks away", tick, String.format(java.util.Locale.ROOT, "%.1f", d.groundY()),
                xyz(d.burst()), Math.round(Math.hypot(d.burst().x - waterEye.x, d.burst().z - waterEye.z)));
        if (!lake && "far".equals(System.getenv("INCOMING_WATER"))) {
            // from afar: 4 km from the explosion along a line through the viewer near the shore (~2,5 km beyond the shore), at a height of 400 (~340 blocks
            //  above the sea), a narrow corner - the entire pillar, its crown, narrowing and falling until the wall of spray reached the viewer
            double ax = waterEye.x - waterBurst.x, az = waterEye.z - waterBurst.z, l = Math.hypot(ax, az);
            waterEye = new Vec3(waterBurst.x + ax / l * 4000, 400, waterBurst.z + az / l * 4000);
            Minecraft.getInstance().options.fov().set(30);
            look(waterBurst.x, 63 + 450, waterBurst.z);
            for (int s : new int[]{2, 4, 6, 8, 10, 12, 15, 18, 21, 25, 30, 40, 60}) shot(tick + 20 * s, "water_far_" + s + "s");
            at(tick + 20 * 60 + 20, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        int[] frames = {2, 8, 16, 24, 32, 44, 60, 76, 92, 104, 112, 120, 128, 136, 144, 152, 160, 176, 200, 240, 300, 400, 600, 900, 1400, 2000};
        String name = lake ? "lake" : "water";
        for (int dt : frames) shot(tick + dt, name);
        // on the side of the explosion: a ring on the water and a wall of splashes along the shore
        at(tick + 2040, () -> {
            double ax = waterBurst.x - waterEye.x, az = waterBurst.z - waterEye.z, l = Math.hypot(ax, az);
            look(waterEye.x - az / l * 200, waterEye.y - 20, waterEye.z + ax / l * 200);
        });
        shot(tick + 2060, name + "_side");
        at(tick + 2080, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * pall after the nuclear wave (the “movie-level nuke” plan, step 2): ground 15 kt at noon (epicenter on land,
     *  at 1,5 km north of the spawn point). Shots from a hill 4 km to the south (the pall stands behind the front, covers the city;
     *  detonation - when the hill chunks are already built), from inside the pall at 900 m from the epicenter (above the ruins, to the edge, up -
     *  to the sun), from a height, at dusk and at night (fires below). Pall quality - {@code INCOMING_PALL} (LOW default:
     * software OpenGL slow); in the log - lines {@code SCENARIO pall}.
     */
    private void planPall() {
        pall = true;
        at(40, () -> {
            String q = System.getenv().getOrDefault("INCOMING_PALL", "LOW");
            ua.zentix.incoming.IncomingConfig.CLIENT.nukeDustQuality.set(ua.zentix.incoming.IncomingConfig.Client.DustQuality.valueOf(q));
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 2500 180 0");
        });
        at(600, () -> {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            int x = (int) Math.floor(p.getX()), z = (int) Math.floor(p.getZ());
            // hill: a platform above the ground and above everything in front (towards the epicenter) in the drawing - 4 km south of the epicenter
            int y = Math.max(mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), 64) + 40;
            for (int ahead = 8; ahead <= 160; ahead += 8) {
                for (int side = -48; side <= 48; side += 16) y = Math.max(y, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + side, z - ahead) + 12);
            }
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 1, y - 1, z - 1, x + 1, y - 1, z + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 180 -4", x, y, z));
            pallAt = new Vec3(x + 0.5, 320, z - 4000 + 0.5);
        });
        at(660, () -> cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %.1f %.1f %.1f 15 ground", pallAt.x, pallAt.y, pallAt.z)));
    }

    /**
     * Volumetric mushroom ({@code MushroomField}): ground 15 kt at 10 km (north, or first clockwise direction with land);
     * viewer in the air 200 blocks above ground looks slightly upward at ground zero; frames {@code pall_mushroom_<seconds>s}
     * from 2 s to 4 minutes show the ball rolling into a ring, cap rising and spreading, stem and skirt; repeat at seconds
     * 22 and 72, 2 s after 20 and 70, to show how cap billows moved and changed. At night ({@code INCOMING_MUSHROOM=night}),
     * glow follows temperature; default morning, sun from the side. Quality: {@code INCOMING_PALL} (default MEDIUM).
     * <p>
     * Mushroom shadow ({@code INCOMING_MUSHROOM=shadow}): detonation 5 km east, viewer at height 200 looks west and down;
     * no mushroom in frame, only ground receiving its shadow. After 3 minutes, frames {@code pall_shadow_<time>} with
     * eastern sun at different altitudes (shadow extends west by cap height divided by tangent of solar altitude: from
     * 1875 to 3125 it covers the viewer, its edge crosses before and after), and western sun (8000: no shadow, same pall).
     * <p>
     * Ball light on the ground at night ({@code INCOMING_MUSHROOM=glow}): detonation 3 km east, viewer at height 200 looks
     * east and down; ball light on the ground ahead, ball itself near the top of frame; frames {@code pall_glow_<seconds>s}
     * from 1 to 14 s: white, yellow, orange light, then darkness when the cap rolls up.
     * <p>
     * Host video view ({@code INCOMING_MUSHROOM=film}): airburst 15 kt (optimum height, ~590 m) at {@link #FILM_M} blocks
     * over land by day; spectator viewer (not swept away by the wave) 40 blocks above ground looks 20° upward at ground zero.
     * Frames {@code pall_film_front_<tenths>ds} from 1 to 3.5 s (front approaches viewer), {@code pall_film_<seconds>s} every
     * 2 s from 4 to 40 (dust wall passes viewer at ~7 s, then viewer is in dust: mushroom, stem and cap must remain visible),
     * then {@code pall_film_up_*} at 55° upward: stem grows up to cap.
     * <p>
     * Ball light by day ({@code INCOMING_MUSHROOM=flash}; complaint 18.10: "second sun" at 3–5 km and sharp shadows across
     * the map): airburst 15 kt at {@link #FLASH_M} blocks over land, clear morning; spectator viewer 30 blocks above ground
     * looks across the explosion direction (ball off-screen left) and down; slopes facing the ball and facing away share
     * a frame. Frames {@code pall_flash_<ticks>t} before flash and 2–60 ticks afterward.
     */
    private void planMushroom() {
        mushroom = true;
        boolean night = "night".equals(System.getenv("INCOMING_MUSHROOM"));
        if ("shadow".equals(System.getenv("INCOMING_MUSHROOM"))) {
            shadowView = true;
            at(40, () -> {
                cmd("time set 2000");
                cmd("weather clear");
                cmd("gamerule doDaylightCycle false");
                cmd("tp @s 0.5 200 0.5 90 35");
                hover = true;
            });
            at(400, () -> cmd("incoming nuke now at 5000.5 320 0.5 15 ground"));
            return;
        }
        if ("glow".equals(System.getenv("INCOMING_MUSHROOM"))) {
            glowView = true;
            at(40, () -> {
                cmd("time set 18000");
                cmd("weather clear");
                cmd("gamerule doDaylightCycle false");
                cmd("tp @s 0.5 200 0.5 -90 20");
                hover = true;
            });
            at(400, () -> cmd("incoming nuke now at 3000.5 320 0.5 15 ground"));
            return;
        }
        if ("blanket".equals(System.getenv("INCOMING_MUSHROOM"))) {
            blanketView = true;
            at(20, () -> mushroomYaw = landYaw(BLANKET_M));
            at(40, () -> {
                String q = System.getenv().getOrDefault("INCOMING_PALL", "MEDIUM");
                ua.zentix.incoming.IncomingConfig.CLIENT.nukeDustQuality.set(ua.zentix.incoming.IncomingConfig.Client.DustQuality.valueOf(q));
                // as on the host servers: the distant wave is slower than the real one
                ua.zentix.incoming.IncomingConfig.SERVER.scalePreset.set(ua.zentix.incoming.IncomingConfig.ScalePreset.CUSTOM);
                ua.zentix.incoming.IncomingConfig.SERVER.nukeWaveSpeed.set(Double.parseDouble(System.getenv().getOrDefault("INCOMING_WAVE_SPEED", "0.2")));
                // eye - above the relief of the first 800 blocks towards the epicenter (the hill near the eye would cover the wall)
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                var gen = level.getChunkSource().getGenerator();
                double sx = -Math.sin(Math.toRadians(mushroomYaw)), sz = Math.cos(Math.toRadians(mushroomYaw));
                int ground = 0;
                for (int i = 0; i <= 40; i++) {
                    ground = Math.max(ground, gen.getBaseHeight((int) Math.round(20 * i * sx), (int) Math.round(20 * i * sz),
                            Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()));
                }
                filmEye = new Vec3(0.5, ground + 20, 0.5);
                cmd("time set 2500");
                cmd("weather clear");
                cmd("gamerule doDaylightCycle false");
                cmd("gamemode spectator");
                filmLook(2);
                hover = true;
            });
            at(400, () -> {
                double x = 0.5 - BLANKET_M * Math.sin(Math.toRadians(mushroomYaw)), z = 0.5 + BLANKET_M * Math.cos(Math.toRadians(mushroomYaw));
                cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %.1f 320 %.1f 15 air", x, z));
            });
            return;
        }
        if ("flash".equals(System.getenv("INCOMING_MUSHROOM"))) {
            flashView = true;
            at(20, () -> mushroomYaw = landYaw(FLASH_M));
            at(40, () -> {
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                int ground = level.getChunkSource().getGenerator().getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, level,
                        level.getChunkSource().randomState());
                cmd("time set 2500");
                cmd("weather clear");
                cmd("gamerule doDaylightCycle false");
                cmd("gamemode spectator");
                cmd(String.format(java.util.Locale.ROOT, "tp @s 0.5 %d 0.5 %d 25", ground + 30, mushroomYaw + 90));
                hover = true;
            });
            pallShot(395, "flash_before");
            at(400, () -> {
                double x = 0.5 - FLASH_M * Math.sin(Math.toRadians(mushroomYaw)), z = 0.5 + FLASH_M * Math.cos(Math.toRadians(mushroomYaw));
                cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %.1f 320 %.1f 15 air", x, z));
            });
            return;
        }
        if ("film".equals(System.getenv("INCOMING_MUSHROOM"))) {
            filmView = true;
            at(20, () -> mushroomYaw = landYaw(FILM_M));
            at(40, () -> {
                String q = System.getenv().getOrDefault("INCOMING_PALL", "MEDIUM");
                ua.zentix.incoming.IncomingConfig.CLIENT.nukeDustQuality.set(ua.zentix.incoming.IncomingConfig.Client.DustQuality.valueOf(q));
                var level = Minecraft.getInstance().getSingleplayerServer().overworld();
                int ground = level.getChunkSource().getGenerator().getBaseHeight(0, 0, Heightmap.Types.WORLD_SURFACE_WG, level,
                        level.getChunkSource().randomState());
                filmEye = new Vec3(0.5, ground + 40, 0.5);
                cmd("time set 2500");
                cmd("weather clear");
                cmd("gamerule doDaylightCycle false");
                cmd("gamemode spectator");
                filmLook(-20);
                hover = true;
            });
            at(400, () -> {
                double x = 0.5 - FILM_M * Math.sin(Math.toRadians(mushroomYaw)), z = 0.5 + FILM_M * Math.cos(Math.toRadians(mushroomYaw));
                cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %.1f 320 %.1f 15 air", x, z));
            });
            return;
        }
        at(20, () -> mushroomYaw = landYaw(10000));
        at(40, () -> {
            String q = System.getenv().getOrDefault("INCOMING_PALL", "MEDIUM");
            ua.zentix.incoming.IncomingConfig.CLIENT.nukeDustQuality.set(ua.zentix.incoming.IncomingConfig.Client.DustQuality.valueOf(q));
            cmd(night ? "time set 18000" : "time set 1500");
            cmd("weather clear");
            cmd("tp @s 0.5 270 0.5 " + mushroomYaw + " -14");
            hover = true;
        });
        at(400, () -> {
            double x = 0.5 - 10000 * Math.sin(Math.toRadians(mushroomYaw)), z = 0.5 + 10000 * Math.cos(Math.toRadians(mushroomYaw));
            cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %.1f 320 %.1f 15 ground", x, z));
        });
    }

    /** Scenario {@code mushroom} with {@code flash}: from the viewer to the epicenter along the ground, blocks. */
    private static final double FLASH_M = 4000;

    /** Scenario {@code mushroom} with {@code film}: from the viewer to the epicenter along the ground, blocks (host video camera above Newisle). */
    private static final double FILM_M = 1383;

    /** Scenario {@code mushroom} with {@code blanket}: from the viewer to the epicenter along the ground, blocks. */
    private static final double BLANKET_M = 3000;
    /** Scenario {@code mushroom} with {@code blanket}: the height of the viewer above the ground, blocks (near the ground, just above the pall - up to the plane). */
    private static final int[] BLANKET_UP = {60, 150, 400, 1000, 2500};

    /**
     * Scenario {@code mushroom} with {@code blanket}: viewer above the ground at {@code up} blocks, {@code back} blocks from the epicenter
     *  (on the line to the previous location), looking at the ground below the epicenter, but no higher than 8° down.
     */
    private void blanketLook(int up, double back) {
        double k = back / BLANKET_M;
        double x = filmEye.x + (1 - k) * -BLANKET_M * Math.sin(Math.toRadians(mushroomYaw)), z = filmEye.z + (1 - k) * BLANKET_M * Math.cos(Math.toRadians(mushroomYaw));
        double pitch = Math.max(8, Math.toDegrees(Math.atan2(up - 20, back)));
        cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f %d %.1f", x, filmEye.y - 20 + up, z, mushroomYaw, pitch));
    }

    /** Scenario {@code mushroom} with {@code film}: the viewer is in place, looking at the epicenter with this tilt. */
    private void filmLook(int pitch) {
        cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f %d %d", filmEye.x, filmEye.y, filmEye.z, mushroomYaw, pitch));
    }

    /**
     * Where is the land from the viewer in {@code dist} blocks according to the generator (turn like the player): the first direction is from the north along
     * hourly, where the land is under the epicenter and in 600 blocks around (skirt, pall), but there is no such thing - where there is more land, but under the
     *  epicenter there is land first of all.
     */
    private int landYaw(double dist) {
        // the seed scenario has a sea to the north: there is a water explosion there, and a column of water would cover the ball. Immediately, before the teleport: noise
        //  generator is read and world generation threads simultaneously
        var level = Minecraft.getInstance().getSingleplayerServer().overworld();
        var gen = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        int bestYaw = 180, best = -1;
        for (int k = 0; k < 36 && best < 18; k++) {
            int yaw = 180 + 10 * k;
            double sx = -Math.sin(Math.toRadians(yaw)), sz = Math.cos(Math.toRadians(yaw));
            int land = 0;
            for (int i = 0; i < 9; i++) {
                double r = i == 0 ? 0 : 600, a = Math.PI / 4 * i;
                int x = (int) Math.round(dist * sx + r * Math.cos(a)), z = (int) Math.round(dist * sz + r * Math.sin(a));
                if (gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random) >= 64) land += i == 0 ? 10 : 1;
            }
            if (land > best) {
                best = land;
                bestYaw = yaw;
            }
        }
        Incoming.LOG.info("SCENARIO mushroom burst to yaw {} (epicentre on land {}, {} of 8 points around)", bestYaw, best >= 10, best % 10);
        return bestYaw;
    }

    /** Scenario {@code mushroom}: frames - from the arrival of the explosion to the client. */
    private void mushroomEvents() {
        if (detTick >= 0 || ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty()) return;
        detTick = tick;
        Incoming.LOG.info("SCENARIO detonation at tick {}, burst {}", tick, xyz(ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d.burst()));
        if (shadowView) {
            // the wave passed, cap above 5 km; frame - after 3 s after the time change (pall accumulates light over frames)
            int t = tick + 20 * 180;
            for (int time : new int[]{1500, 1625, 1750, 1875, 2500, 3125, 3250, 3375, 8000}) {
                at(t, () -> cmd("time set " + time));
                pallShot(t + 60, "shadow_" + time);
                t += 80;
            }
            at(t + 20, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        if (glowView) {
            for (int s : new int[]{1, 2, 3, 5, 7, 9, 11, 14}) pallShot(tick + 20 * s, "glow_" + s + "s");
            at(tick + 20 * 14 + 20, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        if (blanketView) {
            // dust from the ground: the front moves towards the viewer (for 0,2 the speed of sound is ~45 s before it)
            for (int s = 6; s <= 54; s += 6) pallShot(tick + 20 * s, "blanket_ground_" + s + "s");
            // pall from a height: in a minute and in five; frame - through 3 s after transfer (pall accumulates light over frames)
            int t = tick + 20 * 60;
            for (int minute : new int[]{1, 5}) {
                if (minute == 5) t = Math.max(t, tick + 20 * 300);
                for (int up : BLANKET_UP) {
                    at(t, () -> blanketLook(up, BLANKET_M));
                    pallShot(t + 60, "blanket_" + minute + "m_up" + up);
                    t += 100;
                }
                at(t, () -> blanketLook(1000, 1500));
                pallShot(t + 60, "blanket_" + minute + "m_over1000");
                t += 100;
            }
            at(t + 20, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        if (flashView) {
            for (int t : new int[]{2, 4, 6, 10, 20, 40, 60}) pallShot(tick + t, "flash_" + t + "t");
            at(tick + 80, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        if (filmView) {
            // the wave front approaches the viewer: a ring of refraction along its edge against the sky (tenths of a second)
            for (int ds = 10; ds <= 35; ds += 5) pallShot(tick + 2 * ds, "film_front_" + ds + "ds");
            for (int s = 4; s <= 40; s += 2) pallShot(tick + 20 * s, "film_" + s + "s");
            // cap above the frame: look up, frame - through 3 s (pall accumulates light across frames)
            at(tick + 20 * 41, () -> filmLook(-55));
            for (int s : new int[]{44, 50, 60, 90}) pallShot(tick + 20 * s, "film_up_" + s + "s");
            at(tick + 20 * 90 + 20, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        for (int s : new int[]{2, 5, 10, 20, 22, 40, 70, 72, 120, 240}) pallShot(tick + 20 * s, "mushroom_" + s + "s");
        at(tick + 20 * 240 + 20, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /** Scenario {@code pall}: frames - from the arrival of the explosion to the client. */
    private void pallEvents() {
        if (detTick >= 0 || ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty()) return;
        detTick = tick;
        var d = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
        Incoming.LOG.info("SCENARIO detonation at tick {}, burst {}", tick, xyz(d.burst()));
        int ex = (int) Math.floor(d.burst().x), ez = (int) Math.floor(d.burst().z);
        // from the hill: pall stands behind the front and walks towards the viewer
        for (int dt : new int[]{60, 200, 400, 700, 1000, 1300}) pallShot(tick + dt, "hill");
        // from the inside: 900 m southeast of the epicenter, a site in 10 blocks above the ruins (they can be seen sinking in the dust)
        int ix = ex + 400, iz = ez + 800;
        at(tick + 1340, () -> {
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 220 %d.5 180 0", ix, iz));
            // then the camera in flight to the end (each tick): from above and at night above the fires - a frame from the place where it was moved
            hover = true;
        });
        at(tick + 1480, () -> {
            int y = Minecraft.getInstance().level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz) + 10;
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", ix - 1, y - 1, iz - 1, ix + 1, y - 1, iz + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 155 0", ix, y, iz));
        });
        pallShot(tick + 1600, "inside");
        at(tick + 1610, () -> cmd("tp @s ~ ~ ~ -25 -2"));
        pallShot(tick + 1700, "inside_edge");
        at(tick + 1710, () -> cmd("tp @s ~ ~ ~ 155 -75"));
        pallShot(tick + 1800, "inside_up");
        // from a height: 500 blocks, 2 km to the southeast, looking at the epicenter (do not fall in flight)
        at(tick + 1820, () -> {
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d 500 %d facing %d 70 %d", ex + 1500, ez + 1500, ex, ez));
        });
        pallShot(tick + 2000, "altitude");
        pallShot(tick + 2100, "altitude");
        // night from the hill: haze over the city, fires below
        at(tick + 2120, () -> {
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d 160 %d facing %d 120 %d", ex, ez + 4000, ex, ez));
            cmd("time set 13000");
        });
        pallShot(tick + 2300, "dusk");
        at(tick + 2310, () -> cmd("time set 18000"));
        pallShot(tick + 2450, "night");
        at(tick + 2470, () -> {
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d 120 %d facing %d 60 %d", ex + 300, ez + 900, ex, ez));
        });
        pallShot(tick + 2650, "night_inside");
        at(tick + 2700, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Epicenter of scenario firestorm: where in 1,6 km around there is the most flammable land by biome ({@link #pickEpicentre}); in the world
     *  scenario, the spawn point has ice, snow and sea, but they don’t burn. We are not looking for a village: there is none closer than 1,6 km, and the search cost
     *  minutes of server flow.
     */
    private volatile int fsX = 0, fsZ = -1000;
    /** The shooting points are 2 km and 800 m south of the epicenter. */
    private static final int FS_FAR = 2000, FS_ROOF = 800;
    /** How long to wait for ready chunks after transfer (software OpenGL builds sections slowly). */
    private static final int FS_LOAD = 350;
    /** Pall measurement windows on a video card with and without a storm (one at a time). */
    private static final int FS_GPU_WINDOWS = 16;

    /**
     * Firestorm (the “movie-level nuke” plan, step 4): air 15 CT over the forest and plains (at the spawn point there is ice
     *  and the sea), frames after ~1, 5 and 15 minutes - from 2 km (40 blocks above the ground and above the terrain in front), from
     *  roofs in 800 m (30 above the ground) and from inside the fire zone of the fire tornado. Before the explosion, the viewer flies around the points at 2 km and
     * 800 m (chunks are ready). Minutes are skipped: the server's clock is forward, the client's is rewinding, like a replay Flashback
     *  ({@code ClientNuclear.rewound}: picture - by age of detonation). Night ({@code INCOMING_FIRESTORM=night}, default) -
     *  detonation at 4000 (morning pall - after 16,7 minutes), immediately after it - {@code time set 18000} and rewind: morning remains at
     *  detonation time, on the screen - night. Pall quality - {@code INCOMING_PALL} (by default MEDIUM, as in the setting);
     *  in the log - lines {@code SCENARIO firestorm} (pall and storm, time on the video card), at the roof after 5 minutes - windows according to 2 s
     *  with and without a storm in turn (lines {@code SCENARIO firestorm gpu}).
     */
    private void planFirestorm() {
        firestorm = true;
        fsNight = !"day".equals(System.getenv().getOrDefault("INCOMING_FIRESTORM", "night"));
        hover = true;
        at(40, () -> {
            String q = System.getenv().getOrDefault("INCOMING_PALL", "MEDIUM");
            ua.zentix.incoming.IncomingConfig.CLIENT.nukeDustQuality.set(ua.zentix.incoming.IncomingConfig.Client.DustQuality.valueOf(q));
            cmd("time set 4000");
            cmd("weather clear");
            pickEpicentre();
        });
        at(80, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %d 200 %d 180 0", fsX, fsZ + FS_ROOF)));
        at(600, () -> {
            fsRoofY = around(fsX, fsZ + FS_ROOF) + 30;
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d 200 %d 180 0", fsX, fsZ + FS_FAR));
        });
        at(1150, () -> {
            fsFarY = Math.max(around(fsX, fsZ + FS_FAR) + 40, ahead(fsX, fsZ + FS_FAR) + 15);
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 facing %d %d %d", fsX, fsFarY, fsZ + FS_FAR, fsX, fsFarY, fsZ));
        });
        at(1250, () -> cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %d.5 320 %d.5 15 air", fsX, fsZ)));
    }

    /** The highest relief is in front (to the north, up to 180 blocks, with a stripe of 64 to the sides) at the client. */
    private static int ahead(int x, int z) {
        int top = 0;
        for (int a = 16; a <= 180; a += 8) {
            for (int side = -32; side <= 32; side += 16) top = Math.max(top, ground(x + side, z - a));
        }
        return top;
    }

    /** The client's highest relief is in the 8 blocks around the column (the shooting point is above it, and not in the ice spire nearby). */
    private static int around(int x, int z) {
        int top = 0;
        for (int dx = -8; dx <= 8; dx += 2) {
            for (int dz = -8; dz <= 8; dz += 2) top = Math.max(top, ground(x + dx, z + dz));
        }
        return top;
    }

    /**
     * Is the place on fire according to the biome of the world (without chunks: noise of server biomes): forest, taiga, plains, savannah, jungle, meadows - yes; sea,
     * rivers, beaches, snow and ice, deserts and mountains - no (the storm does not burn water, ice and snow).
     */
    private static boolean burns(net.minecraft.server.level.ServerLevel level, int x, int z) {
        var source = level.getChunkSource();
        var biome = source.getGenerator().getBiomeSource().getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(x), net.minecraft.core.QuartPos.fromBlock(64),
                net.minecraft.core.QuartPos.fromBlock(z), source.randomState().sampler());
        if (biome.value().getBaseTemperature() < 0.3f) return false;
        return biome.is(net.minecraft.tags.BiomeTags.IS_FOREST) || biome.is(net.minecraft.tags.BiomeTags.IS_TAIGA) || biome.is(net.minecraft.tags.BiomeTags.IS_SAVANNA)
                || biome.is(net.minecraft.tags.BiomeTags.IS_JUNGLE) || biome.is(net.minecraft.world.level.biome.Biomes.PLAINS)
                || biome.is(net.minecraft.world.level.biome.Biomes.SUNFLOWER_PLAINS) || biome.is(net.minecraft.world.level.biome.Biomes.MEADOW);
    }

    /**
     * Epicenter: from points through 500 blocks in 4 km from the point of appearance - the one with the most burning places in 1,6 km (rings through
     * 400 blocks), and land under the shooting points in 800 m and 2 km to the south.
     */
    private void pickEpicentre() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            var level = server.overworld();
            int bestX = fsX, bestZ = fsZ;
            double best = -1;
            for (int cx = -4000; cx <= 4000; cx += 500) {
                for (int cz = -4000; cz <= 4000; cz += 500) {
                    int hit = 0, all = 0;
                    for (int r = 0; r <= 1600; r += 400) {
                        int n = r == 0 ? 1 : r / 50;
                        for (int i = 0; i < n; i++) {
                            double a = 2 * Math.PI * i / n;
                            all++;
                            if (burns(level, cx + (int) (r * Math.cos(a)), cz + (int) (r * Math.sin(a)))) hit++;
                        }
                    }
                    double score = (double) hit / all + (burns(level, cx, cz + FS_ROOF) ? 0.1 : 0) + (burns(level, cx, cz + FS_FAR) ? 0.1 : 0);
                    if (score > best) {
                        best = score;
                        bestX = cx;
                        bestZ = cz;
                    }
                }
            }
            fsX = bestX;
            fsZ = bestZ;
            Incoming.LOG.info("SCENARIO firestorm epicentre {} {} (burnable land score {})", fsX, fsZ, String.format(java.util.Locale.ROOT, "%.2f", best));
        }).join();
    }

    /** The top of the relief in the client’s column (there is no chunk - 64). */
    private static int ground(int x, int z) {
        var level = Minecraft.getInstance().level;
        if (!level.hasChunk(x >> 4, z >> 4)) return 64;
        return Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), 63);
    }

    /** Scenario {@code firestorm}: frames - from the arrival of the explosion to the client. */
    private void firestormEvents() {
        if (detTick >= 0 || ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty()) return;
        detTick = tick;
        var d = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
        Incoming.LOG.info("SCENARIO detonation at tick {}, burst {}, ground {}, night {}", tick, xyz(d.burst()), d.groundY(), fsNight);
        int t = tick;
        if (fsNight) {
            at(t + 5, () -> cmd("time set 18000"));
            // morning pall and storm - again according to the time of day at the moment of detonation (4000), and not according to 18000
            at(t + 15, ua.zentix.incoming.client.nuclear.ClientNuclear::rewound);
        }
        int gy = (int) Math.round(d.groundY());
        int far = fsZ + FS_FAR, roof = fsZ + FS_ROOF;
        if (System.getenv("INCOMING_FIRESTORM_QUICK") != null) {
            at(t + 40, () -> {
                fsPickWhirl(d, gy);
                fsSkip(fsWhirlAge);
            });
            at(t + 80, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", fsInside.x, gy + 60, fsInside.z)));
            at(t + 80 + FS_LOAD, () -> fsFace("inside"));
            fsShot(t + 140 + FS_LOAD, "inside");
            at(t + 150 + FS_LOAD, () -> fsGo(fsX, fsFarY, far));
            at(t + 150 + 2 * FS_LOAD, () -> fsFace("far"));
            fsShot(t + 210 + 2 * FS_LOAD, "far");
            at(t + 260 + 2 * FS_LOAD, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        // in a minute: from 2 km, from the roof in 800 m, from inside the tornado, which will go to 5 minute
        at(t + 100, () -> fsFace("far"));
        fsShot(t + 1200, "far");
        at(t + 1210, () -> fsGo(fsX, fsRoofY, roof));
        at(t + 1210 + FS_LOAD, () -> fsFace("roof"));
        fsShot(t + 1270 + FS_LOAD, "roof");
        int in = t + 1280 + FS_LOAD;
        at(in, () -> {
            fsPickWhirl(d, gy);
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", fsInside.x, gy + 60, fsInside.z));
        });
        at(in + FS_LOAD + 150, () -> fsFace("inside"));
        fsShot(in + FS_LOAD + 220, "inside");
        // to the tornado: after 20 s after its start
        int five = in + FS_LOAD + 240;
        at(five, () -> fsSkip(fsWhirlAge));
        fsShot(five + 120, "inside");
        fsShot(five + 220, "inside");
        at(five + 230, () -> fsGo(fsX, fsFarY, far));
        at(five + 230 + FS_LOAD, () -> fsFace("far"));
        fsShot(five + 290 + FS_LOAD, "far");
        int r5 = five + 300 + FS_LOAD;
        at(r5, () -> fsGo(fsX, fsRoofY, roof));
        at(r5 + FS_LOAD, () -> fsFace("roof"));
        fsShot(r5 + FS_LOAD + 60, "roof");
        // the same point: pall time on a video card with and without a storm - windows by 2 s in turn (shared machine: in a row
        //  by 10 s one window would fall on someone else's load entirely)
        int gpu = r5 + FS_LOAD + 70;
        for (int k = 0; k < FS_GPU_WINDOWS; k++) {
            boolean on = k % 2 == 0;
            at(gpu + 40 * k, () -> ua.zentix.incoming.client.nuclear.NukePall.firestorm(on));
            at(gpu + 40 * k + 35, () -> Incoming.LOG.info("SCENARIO firestorm gpu {}: {}", on ? "with firestorm" : "without firestorm",
                    ua.zentix.incoming.client.fx.layer.PallPass.describe()));
        }
        int after = gpu + 40 * FS_GPU_WINDOWS;
        at(after, () -> ua.zentix.incoming.client.nuclear.NukePall.firestorm(false));
        fsShot(after + 40, "roof_nostorm");
        at(after + 50, () -> ua.zentix.incoming.client.nuclear.NukePall.firestorm(true));
        // in 15 minutes
        int fifteen = after + 60;
        at(fifteen, () -> fsSkip(900));
        fsShot(fifteen + 120, "roof");
        at(fifteen + 130, () -> fsGo(fsX, fsFarY, far));
        at(fifteen + 130 + FS_LOAD, () -> fsFace("far"));
        fsShot(fifteen + 190 + FS_LOAD, "far");
        int i15 = fifteen + 200 + FS_LOAD;
        at(i15, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", fsInside.x, gy + 60, fsInside.z)));
        at(i15 + FS_LOAD, () -> fsFace("inside"));
        fsShot(i15 + FS_LOAD + 60, "inside");
        at(i15 + FS_LOAD + 100, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Point inside: in 200–360 blocks to the side (across the wind, over land) from the fire tornado, whose middle is closest to the
     * 5 minute - to it at the 20 second of its life; there are no tornadoes - in 600 m southeast of the epicenter, towards the epicenter.
     */
    private void fsPickWhirl(ua.zentix.incoming.nuclear.Detonation d, int gy) {
        var a = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast();
        ua.zentix.incoming.client.nuclear.FireStorm.Whirl best = null;
        for (var w : a.storm.whirls()) if (best == null || Math.abs(w.startS() + 20 - 300) < Math.abs(best.startS() + 20 - 300)) best = w;
        if (best == null) {
            fsWhirlAge = 300;
            fsInside = new Vec3(d.burst().x + 420, gy, d.burst().z + 420);
            fsLook = new Vec3(d.burst().x, gy + 40, d.burst().z);
        } else {
            fsWhirlAge = best.startS() + 20;
            double bx = best.baseX(fsWhirlAge + 5), bz = best.baseZ(fsWhirlAge + 5);
            double px = -Math.sin(d.windDir()), pz = Math.cos(d.windDir());
            // side and distance - where the land is burning under the camera, and not the sea or ice
            var level = Minecraft.getInstance().getSingleplayerServer().overworld();
            int bestDry = -1;
            for (int dist : new int[] {200, 280, 360}) {
                for (int side : new int[] {1, -1}) {
                    double x = bx + side * dist * px, z = bz + side * dist * pz;
                    int dry = 0;
                    for (int k = -2; k <= 2; k++) if (burns(level, (int) Math.floor(x + 24 * k * pz), (int) Math.floor(z - 24 * k * px))) dry++;
                    if (dry > bestDry) {
                        bestDry = dry;
                        fsInside = new Vec3(x, gy, z);
                    }
                }
            }
            fsLook = new Vec3(bx, gy + 45, bz);
        }
        Incoming.LOG.info("SCENARIO firestorm whirls {}, filming one at {} s from {}", a.storm.whirls(), String.format(java.util.Locale.ROOT, "%.0f", fsWhirlAge), xyz(fsInside));
    }

    /** Transfer to the shooting point (height - according to the relief, look - then, {@link #fsFace}). */
    private static void fsGo(double x, int y, double z) {
        cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f", x + 0.5, y, z + 0.5));
    }

    /** View from the shooting point: from 2 km and from the roof - at the epicenter above the ground, from the inside - at the tornado (height - according to the finished relief). */
    private void fsFace(String where) {
        var d = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
        int gy = (int) Math.round(d.groundY());
        // chunks of the point are already ready (the camera is there FS_LOAD tick): the flight before the detonation might not have caught them
        fsRoofY = Math.max(fsRoofY, around(fsX, fsZ + FS_ROOF) + 30);
        fsFarY = Math.max(fsFarY, around(fsX, fsZ + FS_FAR) + 40);
        switch (where) {
            case "far" -> cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 facing %.1f %d %.1f", fsX, fsFarY, fsZ + FS_FAR, d.burst().x, gy + 60, d.burst().z));
            case "roof" -> cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 facing %.1f %d %.1f", fsX, fsRoofY, fsZ + FS_ROOF, d.burst().x, gy + 90, d.burst().z));
            default -> {
                int y = around((int) Math.floor(fsInside.x), (int) Math.floor(fsInside.z)) + 20;
                cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f facing %.1f %.1f %.1f", fsInside.x, y, fsInside.z, fsLook.x, fsLook.y, fsLook.z));
            }
        }
    }

    /**
     * Time skip to {@code ageSeconds} after the detonation: the server clock goes there (all players get a time packet), the
     *  client has a rewind, like a replay: storm, pall and everything nuclear - according to the new age of the detonation.
     */
    private void fsSkip(double ageSeconds) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        var d = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
        long target = d.gameTime() + Math.round(ageSeconds * 20);
        Incoming.LOG.info("SCENARIO firestorm skip to {} s after the burst", String.format(java.util.Locale.ROOT, "%.0f", ageSeconds));
        server.execute(() -> {
            var level = server.overworld();
            ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(target);
            server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                    level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        });
        at(tick + 15, ua.zentix.incoming.client.nuclear.ClientNuclear::rewound);
    }

    /** Scenario frame {@code firestorm}: name - point, night or day and seconds after the explosion; line about pall and storm. */
    private void fsShot(int t, String name) {
        at(t, () -> {
            var list = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations();
            int age = list.isEmpty() ? 0 : (int) Math.round(list.getLast().ticks(0) / 20.0);
            String full = String.format(java.util.Locale.ROOT, "fs_%s_%s_%04ds", fsNight ? "night" : "day", name, age);
            Incoming.LOG.info("SCENARIO firestorm {} at {}: {}; layer {}", full, xyz(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()),
                    ua.zentix.incoming.client.nuclear.NukePall.describe(), ua.zentix.incoming.client.fx.layer.FxLayer.describeTiming());
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, full + ".png", mc.getMainRenderTarget(), c -> {});
        });
    }

    /** Frame of the script {@code pall} with a line about pall at the camera and in the frame. */
    private void pallShot(int t, String name) {
        at(t, () -> Incoming.LOG.info("SCENARIO pall {} at {}: {}; layer {}; near {}", name, xyz(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()),
                ua.zentix.incoming.client.nuclear.NukePall.describe(), ua.zentix.incoming.client.fx.layer.FxLayer.describeTiming(),
                ua.zentix.incoming.client.nuclear.NukeDust.describe()));
        shot(t, "pall_" + name);
    }

    /**
     * A wall of dust at the camera: ground 15 kt in 1,5 km to the north, the viewer in the air in 10 blocks above the forest on the hill looks
     *  at the epicenter; frames - when the head of the wall is in the 110, 60 and 20 blocks, at the moment of arrival and while the wall is passing by (dust from the
     *  camera and the wall itself in one frame). Night - {@code INCOMING_WALL=night} (default is noon). The place is permanent
     *  (the height of the site according to the relief would depend on which chunks had time to load, and the frames before and after editing would not match
     * ), the viewer is in flight (a wave pushes him from the site). The world at the aisle moves slowly ({@code /tick rate 4}):
     *  on the software OpenGL the head would go a hundred blocks per frame.
     */
    private void planWall() {
        wall = true;
        boolean night = "night".equals(System.getenv("INCOMING_WALL"));
        at(40, () -> {
            cmd(night ? "time set 18000" : "time set 6000");
            cmd("weather clear");
            cmd("tp @s 0.5 150 0.5 180 -10");
            hover = true;
        });
        at(600, () -> cmd("tp @s 0.5 150 0.5 180 -10"));
        at(660, () -> {
            Minecraft.getInstance().options.hideGui = true;
            cmd("incoming nuke now at 0.5 320 -1499.5 15 ground");
        });
    }

    /** Scenario {@code wall}: frames based on where the wall head is, not based on the counter (the server may lag). */
    private void wallEvents() {
        var list = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations();
        if (list.isEmpty() || wallStage > 5) return;
        var a = list.getLast();
        if (detTick < 0) {
            detTick = tick;
            Incoming.LOG.info("SCENARIO detonation at tick {}, burst {}", tick, xyz(a.d.burst()));
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double gap = Math.hypot(cam.x - a.d.burst().x, cam.z - a.d.burst().z) - a.wall.wall(a.seconds(0)).head();
        String day = "night".equals(System.getenv("INCOMING_WALL")) ? "night" : "day";
        double[] gaps = {400, 110, 60, 20, -15, -60};
        if (gap > gaps[wallStage]) return;
        if (wallStage == 0) cmd("tick rate 4");
        else {
            Incoming.LOG.info("SCENARIO wall {} gap {} at {}; near {}", day, String.format(java.util.Locale.ROOT, "%.0f", gap), xyz(cam),
                    ua.zentix.incoming.client.nuclear.NukeDust.describe());
            shot(tick + 1, "wall_" + day + "_" + wallStage);
        }
        if (wallStage == 5) {
            at(tick + 10, () -> cmd("tick rate 20"));
            at(tick + 40, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
        }
        wallStage++;
    }

    /**
     * Debris and dust near the camera (N3): by day, a 41×41 pad on the nearest plain (stone 10 blocks deep, otherwise
     * part of the pad would hang over a hollow and ruin collapse would drop it all; cleared above), with a stone-brick
     * house with glass panes and a plank roof, a plank house with glass, and three oaks; ground 15 kt to the north at
     * {@code INCOMING_DEBRIS} meters (default 2000). A spectator viewer (not swept away by the wave) 60 blocks south of
     * the houses and 15 above them looks at them: the front reaches the viewer later than the houses. Frames
     * {@code debris_<ticks>t} are relative to front arrival at the houses (client detonation clock): 10 ticks before
     * and 1–200 ticks after; a {@code SCENARIO debris} log line gives wall head position relative to houses and camera,
     * and what was torn off ({@code NukeDust.describe}).
     */
    private void planDebris() {
        debris = true;
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("gamemode spectator");
            hover = true;
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                var found = level.findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.PLAINS), BlockPos.ZERO.atY(64), 6400, 32, 64);
                BlockPos c = found == null ? BlockPos.ZERO : found.getFirst();
                level.getChunk(c.getX() >> 4, c.getZ() >> 4);
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
                debrisSite = new BlockPos(c.getX(), y, c.getZ());
                Incoming.LOG.info("SCENARIO debris site {}", debrisSite.toShortString());
            });
        });
    }

    /** Scenario {@code debris}: location (the server is looking for a plain), construction, explosion, personnel - when the front arrives at the houses. */
    private void debrisEvents() {
        BlockPos o = debrisSite;
        if (o == null) return;
        int x = o.getX(), y = o.getY(), z = o.getZ();
        if (debrisStage == 0) {
            debrisStage = 1;
            debrisAt = tick;
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 180 10", x, y + 15, z + 60));
            return;
        }
        if (debrisStage == 1 && tick >= debrisAt + 100) {
            debrisStage = 2;
            debrisAt = tick;
            String[] build = {
                    "fill %1$d %3$d %5$d %2$d %4$d %6$d minecraft:air".formatted(x - 20, x + 20, y, y + 17, z - 20, z + 20),
                    "fill %1$d %3$d %5$d %2$d %4$d %6$d minecraft:stone".formatted(x - 20, x + 20, y - 10, y - 1, z - 20, z + 20),
                    // stone house: windows towards the viewer and towards the explosion, roof made of boards
                    "fill %d %d %d %d %d %d minecraft:stone_bricks hollow".formatted(x - 12, y, z - 4, x - 4, y + 6, z + 4),
                    "fill %d %d %d %d %d %d minecraft:glass_pane".formatted(x - 10, y + 2, z + 4, x - 6, y + 3, z + 4),
                    "fill %d %d %d %d %d %d minecraft:glass_pane".formatted(x - 10, y + 2, z - 4, x - 6, y + 3, z - 4),
                    "fill %d %d %d %d %d %d minecraft:oak_planks".formatted(x - 12, y + 7, z - 4, x - 4, y + 7, z + 4),
                    // plank house with glass
                    "fill %d %d %d %d %d %d minecraft:oak_planks hollow".formatted(x + 4, y, z - 3, x + 12, y + 5, z + 3),
                    "fill %d %d %d %d %d %d minecraft:glass".formatted(x + 6, y + 2, z + 3, x + 10, y + 3, z + 3),
                    "fill %d %d %d %d %d %d minecraft:glass".formatted(x + 6, y + 2, z - 3, x + 10, y + 3, z - 3),
                    "place feature minecraft:oak %d %d %d".formatted(x - 8, y, z + 12),
                    "place feature minecraft:oak %d %d %d".formatted(x + 8, y, z + 12),
                    "place feature minecraft:oak %d %d %d".formatted(x, y, z - 14)};
            for (int i = 0; i < build.length; i++) {
                String c = build[i];
                at(tick + 1 + 4 * i, () -> cmd(c));
            }
            return;
        }
        if (debrisStage == 2 && tick >= debrisAt + 100) {
            debrisStage = 3;
            Minecraft.getInstance().options.hideGui = true;
            String metres = System.getenv("INCOMING_DEBRIS");
            int range = metres == null ? 2000 : Integer.parseInt(metres.strip());
            cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %d.5 320 %d.5 15 ground", x, z - range));
            return;
        }
        var list = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations();
        if (debrisStage != 3 || list.isEmpty()) return;
        debrisStage = 4;
        var a = list.getLast();
        detTick = tick;
        Vec3 houses = new Vec3(x + 0.5, y + 3, z + 0.5);
        double wait = a.d.arrivalTicks(houses.distanceTo(a.d.burst())) - a.ticks(0);
        int arrival = tick + (int) Math.round(wait);
        Incoming.LOG.info("SCENARIO detonation at tick {}, burst {}, front at the houses in {} ticks", tick, xyz(a.d.burst()), Math.round(wait));
        for (int dt : new int[]{-10, 1, 2, 4, 7, 10, 15, 20, 40, 200}) {
            String name = String.format(java.util.Locale.ROOT, "debris_%s%03dt", dt < 0 ? "m" : "", Math.abs(dt));
            at(arrival + dt, () -> {
                Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                double head = a.wall.wall(a.seconds(0)).head(), front = a.d.frontRadius(a.ticks(0));
                Incoming.LOG.info("SCENARIO debris {} front {} past the houses, {} past the camera; head {}; near {}; layer {}", name,
                        String.format(java.util.Locale.ROOT, "%.1f", front - houses.distanceTo(a.d.burst())),
                        String.format(java.util.Locale.ROOT, "%.1f", front - cam.distanceTo(a.d.burst())),
                        String.format(java.util.Locale.ROOT, "%.1f", head - Math.hypot(houses.x - a.d.burst().x, houses.z - a.d.burst().z)),
                        ua.zentix.incoming.client.nuclear.NukeDust.describe(), ua.zentix.incoming.client.fx.layer.FxLayer.describeTiming());
            });
            shot(arrival + dt, name);
        }
        at(arrival + 230, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /** The epicenter of the scenario pall-front (ground explosion: the ground takes height). */
    private static final Vec3 FRONT_GZ = new Vec3(0.5, 320, -1499.5);

    /**
     * pall behind the front at night, like a replay frame from the host (camera at 400 m from the ground 15 CT, at 120 m above the ground, looking
     *  slightly upward, FOV 50): behind the epicenter is a block of towers (they are being demolished wave, ruins are standing). Frames - from the arrival of the front
     *  to the camera: 0, 2, 5, 10, 20 and 60 s (pall_front_*), in the log - pall at the camera. Quality - {@code INCOMING_PALL}
     *  (default MEDIUM). {@code INCOMING_EXPORT=2560x1440} - like the output of the video Flashback: from the explosion the window reports
     *  the output size ({@code Window.getWidth/getHeight}) and the game frame - this size, larger than the window (Flashback does the same
     *  with its window mixin and {@code Minecraft.resizeDisplay}).
     * <p>
     * {@code low} (scenario {@code pall-low}): the epicenter is on a pole in 45 blocks above the ground near the camera, the camera is in 8 blocks
     *  above the ground, that is, below the ground below the epicenter (like the trailer shot from the valley), the view is at the epicenter almost along the
     *  horizon. The pall ray, behind which there is no world (behind the drawing and between LOD Distant Horizons), should end on the
     *  relief, and not on the plane of the earth under the epicenter: below it there is a void without pall, an even cut along the horizon. Frames -
     *  through 5, 20 and 60 s after the front (pall_low_*), the camera is put in place in front of each (the wave blows it away).
     */
    private void planPallFront(boolean low) {
        front = true;
        frontLow = low;
        int[] towersX = {90, 150, 210, 270};
        int[] towersZ = {-1560, -1440};
        at(40, () -> {
            String q = System.getenv().getOrDefault("INCOMING_PALL", "MEDIUM");
            ua.zentix.incoming.IncomingConfig.CLIENT.nukeDustQuality.set(ua.zentix.incoming.IncomingConfig.Client.DustQuality.valueOf(q));
            Minecraft.getInstance().options.fov().set(50);
            cmd(frontMine ? "time set 6000" : "time set 18000");
            cmd("weather clear");
            cmd("forceload add 64 -1616 303 -1385");
            if (low) cmd(String.format(java.util.Locale.ROOT, "forceload add %d %d %d %d", (int) FRONT_GZ.x - 8, (int) FRONT_GZ.z - 8,
                    (int) FRONT_GZ.x + 8, (int) FRONT_GZ.z + 8));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f 200 %.1f -90 -3", FRONT_GZ.x - 400, FRONT_GZ.z));
            hover = true;
        });
        // block of towers behind the epicenter: from y 40 (under any terrain) to 40–70 blocks above sea level; twice - suddenly chunk
        //  has not yet loaded the first time
        for (int when : new int[]{480, 560}) {
            at(when, () -> {
                int k = 0;
                for (int x : towersX) {
                    for (int z : towersZ) {
                        int top = 63 + 40 + 10 * (k++ % 4);
                        cmd(String.format(java.util.Locale.ROOT, "fill %d 40 %d %d %d %d minecraft:stone_bricks hollow", x, z, x + 13, top, z + 13));
                    }
                }
            });
        }
        at(600, () -> {
            Minecraft mc = Minecraft.getInstance();
            int x = (int) Math.floor(FRONT_GZ.x - (frontMine ? 1500 : 400)), z = (int) Math.floor(FRONT_GZ.z);
            int ground = Math.max(mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), 63);
            int above = low ? 8 : frontMine ? 3 : 120;
            frontAt = new Vec3(x + 0.5, ground + above, z + 0.5);
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f -90 -3", frontAt.x, frontAt.y, frontAt.z));
            Incoming.LOG.info("SCENARIO pall-front camera at {} ({} above ground {})", xyz(frontAt), above, ground);
            if (low) {
                // the epicenter is on the pillar: the ground underneath it is 45 blocks higher than the ground near the camera (if it’s not higher there anyway)
                int gx = (int) Math.floor(FRONT_GZ.x), gz = (int) Math.floor(FRONT_GZ.z);
                cmd(String.format(java.util.Locale.ROOT, "fill %d 40 %d %d %d %d minecraft:stone", gx - 3, gz - 3, gx + 3, ground + 45, gz + 3));
            }
        });
        at(660, () -> {
            Minecraft.getInstance().options.hideGui = true;
            cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %.1f %.1f %.1f 15 ground", FRONT_GZ.x, FRONT_GZ.y, FRONT_GZ.z));
            String export = System.getenv("INCOMING_EXPORT");
            if (export != null && export.matches("\\d+x\\d+")) {
                String[] wh = export.split("x");
                exportSize(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            }
        });
    }

    /** Scenario pall-front: frames - from the arrival of the front to the camera (according to the client’s explosion model). */
    private void frontEvents() {
        if (detTick >= 0 || ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty()) return;
        detTick = tick;
        var d = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int arrive = (int) Math.ceil(d.arrivalTicks(cam.distanceTo(d.burst())));
        Incoming.LOG.info("SCENARIO detonation at tick {}, burst {}, ground {}, front at the camera in {} ticks", tick, xyz(d.burst()),
                String.format(java.util.Locale.ROOT, "%.0f", d.groundY()), arrive);
        if (frontMine) {
            mineShots(tick + arrive);
        } else if (frontLow) {
            for (int s : new int[]{5, 20, 60}) {
                int t = tick + arrive + 20 * s;
                // the wave blows the camera away: in front of the frame - back into place
                at(t - 4, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f -90 -3", frontAt.x, frontAt.y, frontAt.z)));
                pallShot(t, "low_" + s + "s");
                if (s == 60) offShot(t, "low_" + s + "s_off");
            }
        } else {
            for (int s : new int[]{0, 2, 5, 10, 20, 60}) {
                int t = tick + arrive + 20 * s;
                pallShot(t, "front_" + s + "s");
                if (s > 0) offShot(t, "front_" + s + "s_off");
            }
        }
        at(tick + arrive + 1240, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /** A frame without pall takes so many ticks: with a shaderpack on software OpenGL the frame is drawn less often. */
    private static final int OFF_WAIT = System.getenv("INCOMING_SHADERS") != null ? 40 : 16;

    /**
     * Scenario pall-mine (in the daytime, 1500 blocks from ground 15 kt): behind the front (through 8 s, when the wave passed) at the camera -
     * fence, gratings and pillar in 3–5 blocks in front of the pall (thin before the distant: under the shader pack, which smoothes the edges,
     *  along the contour there should be no background border without pall), below it in 24 blocks there is an adit 80 blocks to the east (further windows
     *  of outside air, OpenAir) with a luminous ceiling and a shaft at its end to the sky.
     *  Frames through 20 s after the front: near the ground, in the adit along it (there shouldn’t be a pall there: there is no dust underground),
     *  from the bottom of the shaft up (the mine is open to the sky - there is dust in it), and after 2 and 4 minutes at the ground there is a fence and a pillar against the background of a
     *  veil that thins and goes with the wind;
     *  for everyone - the same moment without pall ({@code _off}).
     */
    private void mineShots(int front) {
        int x = (int) Math.floor(frontAt.x), z = (int) Math.floor(frontAt.z);
        var where = new Object() { int ground, deep; };
        at(front + 160, () -> {
            Minecraft mc = Minecraft.getInstance();
            where.ground = Math.max(mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), 63) - 1;
            where.deep = where.ground - 24;
            int g = where.ground, y = where.deep;
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone hollow", x - 2, y - 1, z - 3, x + 80, y + 4, z + 3));
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:glowstone", x - 1, y + 4, z - 2, x + 79, y + 4, z + 2));
            // mine - up to the sky above its mouth (the relief there is higher than that of the chamber)
            int top = Math.max(g, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + 77, z));
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x + 76, y, z - 1, x + 78, top + 3, z + 1));
            fences(x, g, z);
            Incoming.LOG.info("SCENARIO pall-mine ground {} tunnel floor {} shaft at x {}..{}", g, y, x + 76, x + 78);
        });
        // stages - through 120 tick: transition, frame through 60 (the fog of black rain near the camera in the adit melts ~1,5 s, NukeSky),
        //  frame without pall - after it
        int t = front + 400;
        at(t - 60, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f -90 -12", x + 0.5, where.ground + 2.6, z + 0.5)));
        pallShot(t, "mine_surface");
        offShot(t, "mine_surface_off", OFF_WAIT);
        at(t + 120, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f -90 0", x + 0.5, where.deep + 1.6, z + 0.5)));
        pallShot(t + 180, "mine_tunnel");
        offShot(t + 180, "mine_tunnel_off", OFF_WAIT);
        at(t + 240, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f -90 -70", x + 77.5, where.deep + 1.6, z + 0.5)));
        pallShot(t + 300, "mine_shaft");
        offShot(t + 300, "mine_shaft_off", OFF_WAIT);
        // the edge of the blocks against the background of the pall: the pall near the camera thins out and goes with the wind, the fence and pillar are in its background
        at(t + 360, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f -90 -12", x + 0.5, where.ground + 2.6, z + 0.5)));
        int[] ages = {120, 240};
        for (int i = 0; i < ages.length; i++) {
            int age = ages[i], at = t + 370 + 220 * i, turn = at + 104 + OFF_WAIT;
            at(at, () -> fsSkip(age));
            pallShot(at + 100, "mine_rim_" + age + "s");
            offShot(at + 100, "mine_rim_" + age + "s_off", OFF_WAIT);
            // and a look in motion (the accumulation of pall transfers the past to a new place): 0,25° tick two seconds before the frame -
            //  after the frame without pall (turned off pall resets the accumulated)
            for (int k = 0; k <= 40; k++) {
                float yaw = -100 + 0.25f * k;
                at(turn + k, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f %.2f -12", x + 0.5, where.ground + 2.6, z + 0.5, yaw)));
            }
            pallShot(turn + 40, "mine_rim_" + age + "s_turning");
        }
        at(t + 370 + 220 * ages.length, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Scenario pall-mine: fence, bars and post in 3–5 blocks east of the camera above the ground {@code g} - thin and edge of block
     *  in front of pall (in dense pall several blocks are visible).
     */
    private void fences(int x, int g, int z) {
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_fence", x + 3, g + 1, z - 6, x + 3, g + 3, z - 1));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:iron_bars", x + 5, g + 1, z - 6, x + 5, g + 6, z + 6));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x + 4, g + 1, z + 2, x + 4, g + 9, z + 2));
    }

    /**
     * The same frame without pall (quality OFF for two or three frames; on llvmpipe ticks come in batches between frames - shot via
     * 8 tick): what pall does with the frame - the light of the wall of dust and the world behind it.
     */
    private void offShot(int t, String name) {
        offShot(t, name, 8);
    }

    /** The same, the picture is done using {@code wait} tick (with the shader pack on the software OpenGL the frame is drawn less often). */
    private void offShot(int t, String name, int wait) {
        var quality = ua.zentix.incoming.IncomingConfig.CLIENT.nukeDustQuality;
        var was = new Object() { ua.zentix.incoming.IncomingConfig.Client.DustQuality q; };
        at(t + 2, () -> {
            was.q = quality.get();
            quality.set(ua.zentix.incoming.IncomingConfig.Client.DustQuality.OFF);
        });
        shot(t + 2 + wait, "pall_" + name);
        at(t + 3 + wait, () -> quality.set(was.q));
    }

    /**
     * The game frame size is {@code w}×{@code h}, larger than the window - like the video output Flashback: the window reports this size
     *  (window frame size fields; for Flashback - mixin {@code Window.getWidth/getHeight}), and {@code resizeDisplay}
     *  sets his game frame. The script frames are shot from the game frame - this size.
     */
    private static void exportSize(int w, int h) {
        Minecraft mc = Minecraft.getInstance();
        try {
            for (String name : new String[]{"framebufferWidth", "framebufferHeight"}) {
                java.lang.reflect.Field f = com.mojang.blaze3d.platform.Window.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(mc.getWindow(), name.endsWith("Width") ? w : h);
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        mc.resizeDisplay();
        Incoming.LOG.info("SCENARIO export size: window reports {}x{}, game frame {}x{}", mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);
    }

    /** Nuclear scenario: footage of the warhead entering and after the explosion - from the moment the client received the explosion. */
    private void nukeEvents() {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level.getGameTime();
        for (var w : ua.zentix.incoming.client.nuclear.ClientNuclear.warnings()) {
            long left = w.detonateTime() - now;
            // the warhead comes from the launch side (from behind) almost vertically: on the 2 we look back and up
            if (left == 45) cmd("tp @s ~ ~ ~ 0 -65");
            if (left == 38 || left == 30) shot(tick + 1, "reentry");
            if (left == 22) cmd("tp @s ~ ~ ~ 180 -8");
        }
        if (falloutGo >= 0 && now >= falloutGo) {
            falloutGo = -1;
            falloutEvents();
        }
        if (detTick >= 0 || ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().isEmpty()) return;
        detTick = tick;
        Incoming.LOG.info("SCENARIO detonation at tick {}", tick);
        for (int dt : new int[]{1, 2, 4, 10, 20, 40, 70, 90, 100, 112, 118, 125, 140, 160, 200, 400}) shot(tick + dt, "nuke");
        // the front passed the observation deck and threw the viewer off it: below it there was a wall, a pond and foliage (wallFrames). Throw -
        //  server tick, and the server may lag behind the client's frame counter: the step waits for the server clock
        var nd = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
        long thrown = nd.gameTime() + (long) Math.ceil(nd.arrivalTicks(Math.sqrt(deck.distToCenterSqr(nd.burst())))) + 4;
        at(tick + 1, () -> wallFrames(thrown));
        // the entire mushroom is visible from afar: 9 km south of the epicenter, looking up at 30°
        at(tick + 560, () -> {
            var d = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
            int x = (int) Math.floor(d.burst().x), z = (int) Math.floor(d.burst().z + 9_000);
            cmd(String.format(java.util.Locale.ROOT, "fill %d 229 %d %d 229 %d minecraft:stone_bricks", x - 1, z - 1, x + 1, z + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 230 %d.5 180 -30", x, z));
        });
        for (int dt : new int[]{600, 1200, 2400, 3600}) shot(tick + dt, "cloud");
        // in the wake of precipitation - downwind, not far from the epicenter (the wind speed at the detonation has its own); precipitation there - not before
        //  arrives from the cloud, and not before the mushroom is removed (the last frame - through 3600 tick), and black rain from the smoke of
        //  fires goes there until 440 s after the explosion (8800 tick). Deadline - according to the game clock: the server may lag behind the client's frame counter
        var fd = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().getLast().d;
        double fm = Math.min(3000, fd.windSpeed() * 150) * fd.scale();
        falloutSpot = new Vec3(fd.burst().x + Math.cos(fd.windDir()) * fm, 200, fd.burst().z + Math.sin(fd.windDir()) * fm);
        falloutGo = fd.gameTime() + Math.max(3000, (long) Math.ceil(fd.falloutArrivalTicks(falloutSpot.x, falloutSpot.z))) + 900;
    }

    /**
     * Scenario {@code nuke}, when the server reached tick {@code thrown} (the front passed the observation deck and threw the viewer
     *  off it): below it in the air there is a wall, logs, grass and a pond 3×3, foliage on the wall, on the sides of it there is a crown in 30 blocks
     *  and the roof ridge in 50 - in front of the sky, almost along the horizon from the eye. In the stripe of ripples behind the front, there is a dark ring of stickers on the
     *  water - on the pond, and not in stripes along the rows of blocks on the side faces and not in dots along the edge of the foliage and roof in front of the sky
     *  (the water is only a surface, and neighboring pixels are at the same height). It is built after the front - the ruins of the wave do not touch it;
     *  frames {@code nuke_wall_*} - without HUD (the chat closed the pond) and through 2 s: on llvmpipe sections of new blocks are built in
     *  several frames. Then the viewer is again on the observation deck, looking at the mushroom (frame {@code nuke} through 20 s after
     *  of the explosion).
     */
    private void wallFrames(long thrown) {
        if (Minecraft.getInstance().getSingleplayerServer().overworld().getGameTime() < thrown) {
            at(tick + 1, () -> wallFrames(thrown));
            return;
        }
        int x = deck.getX(), y = deck.getY() - 16, z = deck.getZ(), b = y - 6;
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:grass_block", x - 7, b, z - 13, x + 7, b, z - 5));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:short_grass", x - 7, b + 1, z - 13, x + 7, b + 1, z - 5));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 1, b + 1, z - 10, x + 1, b + 1, z - 8));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x - 1, b - 1, z - 10, x + 1, b - 1, z - 8));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water", x - 1, b, z - 10, x + 1, b, z - 8));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 7, b, z - 14, x + 7, b + 8, z - 14));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_log", x - 4, b + 1, z - 12, x - 4, b + 8, z - 12));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:birch_log", x + 3, b + 1, z - 11, x + 3, b + 8, z - 11));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_leaves[persistent=true]", x - 7, b + 9, z - 14, x + 7, b + 9, z - 14));
        // to the right of the wall is a crown in 30 blocks, to the left is a ridge in 50: in front of the sky, from the horizon of the eye to 10° above it
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_leaves[persistent=true]", x + 15, y + 1, z - 32, x + 21, y + 5, z - 28));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 40, y + 2, z - 50, x - 28, y + 2, z - 50));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_brick_stairs[facing=north]", x - 40, y + 3, z - 50, x - 28, y + 3, z - 50));
        cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 1, y - 1, z - 1, x + 1, y - 1, z + 1));
        cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 180 20", x, y, z));
        at(tick + 21, () -> Minecraft.getInstance().options.hideGui = true);
        at(tick + 41, () -> Incoming.LOG.info("SCENARIO water {}; near {}", ua.zentix.incoming.client.nuclear.NukeWater.describe(),
                ua.zentix.incoming.client.nuclear.NukeDust.describe()));
        shot(tick + 41, "nuke_wall");
        shot(tick + 71, "nuke_wall");
        // again to the observation deck (it could have been demolished by ruins; the whole one - without the error line “No blocks were filled” in the frame
        //  mushroom) - mushroom, as in the frames before the throw
        at(tick + 72, () -> {
            Minecraft.getInstance().options.hideGui = false;
            int d = deck.getY() - 1;
            cmd(String.format(java.util.Locale.ROOT, "execute unless block %d %d %d minecraft:stone_bricks run fill %d %d %d %d %d %d minecraft:stone_bricks",
                    x, d, z, x - 1, d, z - 1, x + 1, d, z + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 180 -8", x, deck.getY(), z));
        });
    }

    /** In the trail of precipitation, while there is black rain from the smoke of fires (80–440 s after the explosion), the counter is in hand. */
    private void falloutEvents() {
        cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f 200 %.1f 0 0", falloutSpot.x, falloutSpot.z));
        at(tick + 60, () -> {
            LocalPlayer p = Minecraft.getInstance().player;
            int x = (int) Math.floor(p.getX()), z = (int) Math.floor(p.getZ());
            int y = Minecraft.getInstance().level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            // platform: suddenly there is water
            cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", x - 1, y, z - 1, x + 1, y, z + 1));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %d.5 %d %d.5 0 0", x, y + 1, z));
        });
        for (int dt : new int[]{140, 180, 220}) shot(tick + dt, "fallout");
        at(tick + 240, () -> Incoming.LOG.info("SCENARIO radiation {}", ua.zentix.incoming.client.nuclear.ClientNuclear.radiationState()));
        at(tick + 260, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Close-up effects: the viewer hangs in the air 70 blocks from the target and 25 above it (flying in creative mode,
     *  the explosion does not blow him away), in turn Shahed, rocket, bomb; the frames are from a real explosion (the shell disappeared), and not
     *  according to the counter. At the end - the start of ICBM in 80 blocks: torch, trail, cloud near the table. {@code fx-night} - the same at night.
     * {@code fx-late} - Shahed and a rocket, and each has a pillar after the explosion near and far ({@link #lateFrames}).
     */
    private void planFx(boolean night, boolean late) {
        String spec = System.getProperty("incoming.fx.targets");
        if (spec != null) {
            planFxAt(spec, night);
            return;
        }
        fxLate = late;
        fx = new java.util.ArrayDeque<>(late ? List.of("drone", "missile") : List.of("drone", "missile", "bunker", "icbm"));
        at(40, () -> {
            cmd(night ? "time set 18000" : "time set 6000");
            cmd("weather clear");
            Minecraft.getInstance().options.hideGui = true;
            // level ground: the nearest plain (on hills, the target and ICBM table are hidden behind the slopes)
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                var found = server.overworld().findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.PLAINS),
                        net.minecraft.core.BlockPos.ZERO.atY(64), 6400, 32, 64);
                var at = found == null ? net.minecraft.core.BlockPos.ZERO : found.getFirst();
                Minecraft.getInstance().execute(() -> cmd(String.format(java.util.Locale.ROOT, "tp @s %d 170 %d 0 40", at.getX(), at.getZ())));
            });
        });
        at(240, () -> {
            quickFlights();
            aimAhead(90);
            eye = new Vec3(target.x + 0.5, target.y + (FX_EYE == null ? 18 : FX_EYE[0]), target.z - (FX_EYE == null ? 50 : FX_EYE[1]));
            Minecraft.getInstance().player.getAbilities().flying = true;
            view();
        });
        at(300, this::nextFx);
    }

    /**
     * Volumetric smoke from ordinary explosions (smoke grid, {@code client/fx/smoke}): {@code smoke-salvo} is a Grad rack
     * (40 projectiles with spread 15) on a plain 30 blocks in front of the viewer by day; frames {@code smoke_salvo_<seconds>s}
     * from first burst to one minute. {@code smoke-salvo-pall} is the same salvo in a live pall of ground 15 kt at 2.5 km
     * (worst GPU-budget case: smoke volume and pall in one frame); {@code SCENARIO smoke} lines give pall GPU pass time
     * (on llvmpipe, for comparison only). {@code smoke-column} is a Shahed on a plain; after 30 s the viewer flies through
     * its cloud (same cloud model: center, wind drift): {@code smoke_column_*} frames outside, entering, inside and above.
     * Once per second, a {@code SCENARIO smoke} line gives grid sources and billows, and the pall pass.
     */
    private void planSmoke(String mode) {
        boolean fire = "smoke-fire".equals(mode);
        boolean salvo = !"smoke-column".equals(mode) && !fire, withPall = "smoke-salvo-pall".equals(mode);
        at(40, () -> {
            // smoke above a burning house - at night: the flame shines on the smoke from below
            cmd(fire && !"day".equals(System.getenv("INCOMING_SMOKE_FIRE")) ? "time set 18000" : "time set 6000");
            cmd("weather clear");
            cmd("gamerule doDaylightCycle false");
            Minecraft.getInstance().options.hideGui = true;
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                var found = server.overworld().findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.PLAINS),
                        net.minecraft.core.BlockPos.ZERO.atY(64), 6400, 32, 64);
                var at = found == null ? net.minecraft.core.BlockPos.ZERO : found.getFirst();
                Minecraft.getInstance().execute(() -> cmd(String.format(java.util.Locale.ROOT, "tp @s %d 170 %d 0 40", at.getX(), at.getZ())));
            });
        });
        at(240, () -> {
            quickFlights();
            aimAhead(90);
            eye = salvo ? target.add(0, 10, -30) : fire ? target.add(0, 12, -60) : target.add(0, 25, -120);
            Minecraft.getInstance().player.getAbilities().flying = true;
            hover = true;
            if (fire) {
                // server keeps chunks at home: fill does nothing in an unloaded location
                cmd(String.format(java.util.Locale.ROOT, "forceload add %d %d %d %d", (int) target.x - 16, (int) target.z - 16, (int) target.x + 16,
                        (int) target.z + 16));
                cmd("gamerule doFireTick true");
            }
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, target.x,
                    target.y + (salvo ? 6 : fire ? 20 : 40), target.z));
        });
        int start = fire ? 400 : 300;
        if (fire) {
            // wooden house 15×7×15 on target: Shahed hits its roof, the walls remain on fire
            at(340, () -> cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks hollow", (int) target.x - 7,
                    (int) target.y, (int) target.z - 7, (int) target.x + 7, (int) target.y + 6, (int) target.z + 7)));
        }
        if (withPall) {
            at(start, () -> cmd(String.format(java.util.Locale.ROOT, "incoming nuke now at %.1f %.1f %.1f 15 ground", target.x, target.y, target.z - 2500)));
            start += 1400;
        }
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ExplosionEvent.Start e) -> {
            if (!e.getLevel().isClientSide() && smokeBlastTick < 0 && smokeArmed && e.getExplosion().center().distanceTo(target) < 60) smokeBlastTick = tick;
        });
        at(start, () -> {
            smokeArmed = true;
            if (salvo) cmd(String.format(java.util.Locale.ROOT, "incoming salvo rocket 40 15 at %.1f %.1f %.1f", target.x, target.y, target.z));
            else cmd(String.format(java.util.Locale.ROOT, "incoming drone at %.1f %.1f %.1f", target.x, target.y + (fire ? 6 : 0), target.z));
        });
        for (int t = 20; t <= 20000; t += 20) {
            at(t, () -> {
                Incoming.LOG.info("SCENARIO smoke sources {}, pall {}, layer {}, {}", ua.zentix.incoming.client.fx.smoke.SmokeGrid.sources(),
                        ua.zentix.incoming.client.fx.layer.PallPass.describe(), ua.zentix.incoming.client.fx.layer.FxLayer.describeTiming(),
                        ua.zentix.incoming.client.fx.FireSmoke.describe());
            });
        }
        smokeMode = mode;
    }

    /** Footage {@link #planSmoke} from the first explosion at the target. */
    private void smokeEvents() {
        if (smokeMode == null || smokeBlastTick < 0 || smokeShotsPlanned) return;
        smokeShotsPlanned = true;
        int b = smokeBlastTick;
        Incoming.LOG.info("SCENARIO smoke first blast at tick {}", b);
        if ("smoke-fire".equals(smokeMode)) {
            // did it catch fire from the impact: no - the fire on the roof sets the scenario (smoke follows any fire; where it comes from is up to the server)
            at(b + 60, () -> {
                int seen = ua.zentix.incoming.client.fx.FireWatch.total();
                Incoming.LOG.info("SCENARIO smoke fire after blast: {} fire blocks seen", seen);
                if (seen < 8) {
                    // fire in the house: the one near the boards stays on (the fire goes out on its own without support)
                    Incoming.LOG.info("SCENARIO smoke fire placed by the scenario inside the house");
                    cmd(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:fire replace minecraft:air", (int) target.x - 6,
                            (int) target.y + 1, (int) target.z - 6, (int) target.x + 6, (int) target.y + 5, (int) target.z + 6));
                }
            });
            String light = "day".equals(System.getenv("INCOMING_SMOKE_FIRE")) ? "smoke_fire_day_" : "smoke_fire_";
            for (int s : new int[]{5, 15, 30, 60, 90}) shot(b + 20 * s, light + s + "s");
            // from afar: in ~200 blocks and higher (chunks are already there - the same drawing edge)
            at(b + 20 * 95, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", target.x - 80,
                    target.y + 30, target.z - 180, target.x, target.y + 60, target.z)));
            shot(b + 20 * 100, light + "far_100s");
            shot(b + 20 * 120, light + "far_120s");
            at(b + 20 * 122, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        if (!"smoke-column".equals(smokeMode)) {
            String name = "smoke-salvo-pall".equals(smokeMode) ? "smoke_salvo_pall" : "smoke_salvo";
            for (int s : new int[]{1, 3, 6, 12, 20, 30, 45, 60}) shot(b + 20 * s, name + "_" + s + "s");
            at(b + 20 * 62, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        // cloud Shahed via 30 s: center according to the same model (wind drift, height), the viewer flies it downwind
        shot(b + 20, "smoke_column_1s");
        shot(b + 100, "smoke_column_5s");
        shot(b + 300, "smoke_column_15s");
        int at = b + 600;
        double t = 30;
        var model = new ua.zentix.incoming.client.fx.smoke.CloudModel(ua.zentix.incoming.client.fx.BlastSource.of(ua.zentix.incoming.strike.WeaponType.DRONE, false),
                ua.zentix.incoming.client.fx.smoke.CloudModel.Air.of(ua.zentix.incoming.client.fx.ClientAir.now(), 0.5));
        Vec3 c = target.add(model.driftX(t), model.height(t), model.driftZ(t));
        double r = model.radius(t);
        Incoming.LOG.info("SCENARIO smoke column at 30 s: centre {} radius {}", xyz(c), Math.round(r));
        double[][] path = {{-3, 0}, {-1.5, 0}, {-0.6, 0}, {0, 0}, {0.6, 0}, {0, 2.2}};
        String[] names = {"outside", "near", "entering", "centre", "leaving", "above"};
        for (int i = 0; i < path.length; i++) {
            Vec3 e = c.add(0, path[i][1] * r, path[i][0] * r);
            // by 20 tick to place: the frame on llvmpipe goes up to a second, the picture should catch the camera in place
            int when = at + 20 * i;
            at(when, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", e.x, e.y, e.z, c.x, c.y, c.z + 2 * r)));
            shot(when + 3, "smoke_column_" + names[i]);
        }
        at(at + 20 * path.length + 20, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    private String smokeMode;
    private int smokeBlastTick = -1;
    private boolean smokeArmed, smokeShotsPlanned;

    /**
     * Measurement of an explosion on a copy of the player's world (tools/prod_client.py nuke-profile --world ...): 15 CT in the air in 300 blocks
     *  from the player, then the second one in 600 on the other side. The detonation line in the log gives a breakdown of the time; next - how many
     *  chunks are loaded and entities in the world at this moment.
     */
    private void planNukeProfile() {
        for (int[] shot : new int[][]{{600, 300}, {1800, -600}}) {
            at(shot[0], () -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                var player = Minecraft.getInstance().player;
                double x = player.getX(), z = player.getZ() + shot[1];
                server.execute(() -> {
                    var level = server.overworld();
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z);
                    int entities = 0;
                    for (var ignored : level.getAllEntities()) entities++;
                    Incoming.LOG.info("SCENARIO nuke-profile before: chunks={} entities={} players={}", level.getChunkSource().getLoadedChunksCount(),
                            entities, level.players().size());
                    ua.zentix.incoming.nuclear.NuclearWarhead.detonate(level, new Vec3(x, y, z), 15, true, null);
                });
            });
        }
        at(2400, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Loading large volleys onto copies of the player's world (tools/prod_client.py salvo-bench --world ... --prop
     * incoming.frametimes=true): MLRS 30 pieces with scatter 150 at a point in 500 blocks ahead, then 30 Shahed there.
     *  Commands - on behalf of the player with server rights (cheats can be turned off in the player’s world). Once per second in the log —
     * average server tick time (mspt); frame time - in logs/frametimes.txt.
     */
    private void planSalvoBench() {
        String[] salvo = {"rocket", "drone"};
        for (int i = 0; i < salvo.length; i++) {
            String weapon = salvo[i];
            at(600 + i * 2400, () -> {
                var mc = Minecraft.getInstance();
                var p = mc.player;
                String c = String.format(java.util.Locale.ROOT, "execute as %s at @s run incoming salvo %s 30 150 at %.1f ~ %.1f",
                        p.getGameProfile().getName(), weapon, p.getX(), p.getZ() + 500);
                var server = mc.getSingleplayerServer();
                server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c));
                Incoming.LOG.info("SCENARIO salvo-bench fire {}", weapon);
            });
        }
        for (int t = 20; t <= 6000; t += 20) {
            at(t, () -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                int fps = Minecraft.getInstance().getFps();
                // entities, items and chunks - in the server stream: what its tick is doing (volleys drop blocks with objects)
                server.execute(() -> {
                    int entities = 0, items = 0, chunks = 0;
                    for (var level : server.getAllLevels()) {
                        chunks += level.getChunkSource().getLoadedChunksCount();
                        for (var e : level.getAllEntities()) {
                            entities++;
                            if (e instanceof net.minecraft.world.entity.item.ItemEntity) items++;
                        }
                    }
                    Incoming.LOG.info("SCENARIO salvo-bench mspt={} fps={} entities={} items={} chunks={}",
                            String.format(java.util.Locale.ROOT, "%.1f", server.getAverageTickTimeNanos() / 1e6), fps, entities, items, chunks);
                });
            });
        }
        at(6000, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Effects at specified points in the player's world ({@code tools/prod_client.py fx --world … --prop incoming.fx.targets=…}):
     * semicolon-separated entries, each {@code weapon@x,z} (impact on column top: building roof) or {@code weapon@x,y,z}.
     * Viewer hovers 60 blocks north and 25 above the point; impact is 200 ticks after teleporting (chunk loading).
     * Frames are relative to the actual explosion, as in {@code fx}; filenames are {@code weapon-number_tick.png}.
     * With {@link #approach}, the viewer is on the ground beside the target, and the projectile approaches from the north.
     */
    private void planFxAt(String spec, boolean night) {
        fx = new java.util.ArrayDeque<>();
        fxTargets = new java.util.ArrayDeque<>();
        int n = 0;
        for (String item : spec.split(";")) {
            String[] a = item.strip().split("@");
            String[] c = a.length == 2 ? a[1].split(",") : new String[0];
            if (c.length != 2 && c.length != 3) {
                Incoming.LOG.warn("SCENARIO fx.targets: “{}” is missing - you need weapon@x,z or weapon@x,y,z", item);
                continue;
            }
            double[] p = new double[3];
            p[0] = Double.parseDouble(c[0]);
            p[1] = c.length == 3 ? Double.parseDouble(c[1]) : Double.NaN;
            p[2] = Double.parseDouble(c[c.length - 1]);
            fx.add(a[0].strip() + "-" + ++n);
            fxTargets.add(p);
        }
        at(40, () -> {
            // in the player's world, survival mode: the server does not allow the client to fly, and the viewer would fall towards the goal
            cmd("gamemode spectator");
            cmd(night ? "time set 18000" : "time set 6000");
            cmd("weather clear");
            Minecraft.getInstance().options.hideGui = true;
            quickFlights();
        });
        NeoForge.EVENT_BUS.addListener(this::onServerBlast);
        at(100, this::nextFx);
    }

    private static double[] fxEye(String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] a = spec.split(",");
        return new double[]{Double.parseDouble(a[0].strip()), Double.parseDouble(a[1].strip())};
    }

    private void view() {
        cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, target.x, target.y + 8, target.z));
    }

    private void nextFx() {
        current = fx.poll();
        watched = null;
        flightShots = 0;
        if (current == null) {
            at(tick + 20, () -> {
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (fxTargets != null) {
            double[] p = fxTargets.poll();
            String weapon = current.substring(0, current.lastIndexOf('-'));
            eye = new Vec3(p[0] + 0.5, (Double.isNaN(p[1]) ? mc.player.getY() : p[1]) + 25, p[2] - 60);
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f", eye.x, Math.max(eye.y, 150), eye.z));
            mc.player.getAbilities().flying = true;
            String label = current;
            current = "wait";
            // chunks around the target at the client - via 200 tick after the transfer or later: try until you are ready
            int from = tick;
            boolean[] aimed = {false};
            for (int t = 200; t <= 200 + FX_VIEW_WAIT; t += 20) {
                boolean last = t == 200 + FX_VIEW_WAIT;
                at(from + t, () -> {
                    if (!aimed[0]) aimed[0] = fxAim(p, weapon, label, last);
                });
            }
            return;
        }
        // there is only one target, but the column of the previous shell lasts for a minute and is visible up close: in each frame there is only its explosion
        FarRenderer.reset();
        mc.player.getAbilities().flying = true;
        view();
        if (current.equals("icbm")) {
            // ICBM from a table in 80 blocks to the side of the viewer, the target is far away: flies beyond the ceiling of the world without detonation
            Vec3 pad = new Vec3(eye.x + 60, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) eye.x + 60, (int) eye.z), eye.z);
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                var icbm = ua.zentix.incoming.registry.ModEntities.ICBM.get().create(level);
                icbm.prepare(pad, pad.add(0, 0, 40_000), null);
                level.addFreshEntity(icbm);
            });
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, pad.x, pad.y + 20, pad.z));
            for (int dt : new int[]{8, 20, 40, 60, 90}) shot(tick + dt, "icbm");
            at(tick + 100, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, pad.x, pad.y + 120, pad.z)));
            for (int dt : new int[]{110, 140, 200, 300, 500}) shot(tick + dt, "icbm");
            at(tick + 520, this::nextFx);
            return;
        }
        cmd(String.format(java.util.Locale.ROOT, "incoming %s at %.1f %.1f %.1f", current, target.x, target.y, target.z));
    }

    /**
     * Impact point, viewer and launch for point {@code fx.targets}. {@code false} - the client’s chunks around the target are not
     *  ready yet (repeat later; {@code force} - last time: without ready chunks, the item is skipped).
     */
    private boolean fxAim(double[] p, String weapon, String label, boolean force) {
        Minecraft mc = Minecraft.getInstance();
        int cx = (int) Math.floor(p[0]), cz = (int) Math.floor(p[2]);
        // height without a ready chunk - the bottom of the world: the viewer would stand in the building, the target would go to the bottom of the world (v3: rocket 1
        //  on y −64, the height map of the chunk is still empty) - the pillar at the bottom of the world is also “not ready”
        int floor = mc.level.getMinBuildHeight() + 4;
        int top = Integer.MIN_VALUE;
        for (int x = cx - FX_VIEW_RADIUS; x <= cx + FX_VIEW_RADIUS; x += 4) {
            for (int z = cz - FX_VIEW_RADIUS; z <= cz + FX_VIEW_RADIUS; z += 4) {
                int h = mc.level.hasChunk(x >> 4, z >> 4) ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) : Integer.MIN_VALUE;
                if (h <= floor) {
                    if (force) {
                        Incoming.LOG.warn("SCENARIO {} skipped: chunks around target not loaded by client (column {} {} at {}) — FAIL", label, x, z,
                                h == Integer.MIN_VALUE ? "no chunk" : h);
                        current = "wait";
                        at(tick + 1, this::nextFx);
                        return true;
                    }
                    return false;
                }
                top = Math.max(top, h);
            }
        }
        int y = Double.isNaN(p[1]) ? mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, cx, cz) : (int) p[1];
        target = new Vec3(cx + 0.5, y, cz + 0.5);
        String command = String.format(java.util.Locale.ROOT, "incoming %s at %.1f %.1f %.1f", weapon, target.x, target.y, target.z);
        if (approach) {
            // above the roofs and crowns in 40 blocks to the east: both the target and the last hundreds of blocks of approach from the north are in the frame
            eye = new Vec3(cx + 40.5, Math.max(top, y) + FX_VIEW_ABOVE, cz + 0.5);
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z,
                    target.x, target.y + 15, target.z - 40));
            // approach course: yaw 0 - to the south, the projectile comes from the north
            command = "execute rotated 0 0 run " + command;
        } else {
            eye = FX_EYE == null ? target.add(0, 25, -60) : target.add(0, FX_EYE[0], -FX_EYE[1]);
            view();
        }
        BlockPos eyeBlock = BlockPos.containing(eye);
        Incoming.LOG.info("SCENARIO fx target {} at {}, viewer {} ({} at eye)", label, target, eye, mc.level.getBlockState(eyeBlock));
        current = label;
        strike = new StrikeWatch();
        strikeTick = tick;
        reached = false;
        // from the console: there is no shooter, approach is direct from the north (fromAfar), the viewer does not have a launcher
        String run = command;
        StrikeWatch w = strike;
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            var before = StrikeWatch.projectiles(level);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), run);
            if (!w.launched(before, StrikeWatch.projectiles(level))) Incoming.LOG.warn("SCENARIO {}: launch not found - no new shells or several", label);
        });
        return true;
    }

    /** Explosion on the embedded server: {@link #strike} projectile impact, if it is his. */
    private void onServerBlast(net.neoforged.neoforge.event.level.ExplosionEvent.Start e) {
        StrikeWatch w = strike;
        if (w == null || e.getLevel().isClientSide()) return;
        Vec3 at = e.getExplosion().center();
        if (w.onBlast(ua.zentix.incoming.stress.StressDirector.blastBy(e.getExplosion()), at, tick, e.getLevel().getGameTime())) {
            Incoming.LOG.info("SCENARIO {} blast at {} ({} blocks from target)", current, at, Math.round(at.distanceTo(target)));
        }
    }

    /** Impact on a given point: a projectile on UUID, frames of approach, explosion - on the explosion of its projectile on the server. */
    private void fxTargetEvents() {
        StrikeWatch w = strike;
        if (w == null || current.equals("wait")) return;
        Minecraft mc = Minecraft.getInstance();
        String name = current;
        if (w.impactTick() >= 0) {
            strike = null;
            current = "wait";
            int at = w.impactTick();
            Incoming.LOG.info("SCENARIO {} impact at tick {} (server {})", name, at, w.impactGameTime());
            for (int dt : new int[]{1, 2, 4, 7, 12, 20, 35, 60, 100, 160, 240, 320}) shot(Math.max(tick + 1, at + dt), name);
            at(Math.max(tick + 1, at + 340), this::nextFx);
            return;
        }
        if (tick - strikeTick > FX_IMPACT_LIMIT) {
            strike = null;
            current = "wait";
            Incoming.LOG.warn("SCENARIO {} no impact by tick {} — FAIL", name, tick);
            at(tick + 1, this::nextFx);
            return;
        }
        // the client’s entity disappears when tracking is lost and when it goes on a flight outside the world - search again using UUID
        if (watched != null && (watched.isRemoved() || !watched.getUUID().equals(w.projectile()))) watched = null;
        if (watched == null && w.projectile() != null) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile p && p.getUUID().equals(w.projectile())) watched = p;
            }
            if (watched != null && !reached) {
                reached = true;
                // server world time: before the strike - it ticks, the client’s clock does not repeat it (it catches up with the server in jerks)
                Incoming.LOG.info("SCENARIO {} reached client at tick {} (server {}), {} blocks from viewer, {} from target", name, tick,
                        mc.getSingleplayerServer().overworld().getGameTime(),
                        Math.round(watched.distanceTo(mc.player)), Math.round(watched.position().distanceTo(target)));
            }
        }
        if (watched != null && tick % 8 == 0 && (approach || flightShots < 6 && watched.distanceTo(mc.player) < 150)) {
            flightShots++;
            shot(tick + 1, name + "_flight");
        }
    }

    /** We wait for the projectile, film the approach, then the explosion - from tick, when the projectile disappears. */
    private void fxEvents() {
        if (fxTargets != null) {
            fxTargetEvents();
            return;
        }
        if (current == null || current.equals("icbm") || current.equals("wait")) return;
        Minecraft mc = Minecraft.getInstance();
        if (watched == null) {
            for (var e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile p && p.isActive()) watched = p;
            }
            // from this tick the client has the projectile (whether it is on the screen or not - watch the frames): to “impact” - how many ticks
            //  it can be seen
            if (watched != null) {
                Incoming.LOG.info("SCENARIO {} reached client at tick {}, {} blocks from viewer, {} from target", current, tick,
                        Math.round(watched.distanceTo(mc.player)), Math.round(watched.position().distanceTo(target)));
            }
            return;
        }
        if (!watched.isRemoved()) {
            if (tick % 8 == 0 && (approach || flightShots < 6 && watched.distanceTo(mc.player) < 150)) {
                flightShots++;
                shot(tick + 1, current + "_flight");
            }
            return;
        }
        String name = current;
        current = "wait";
        Incoming.LOG.info("SCENARIO {} impact at tick {}", name, tick);
        for (int dt : new int[]{1, 2, 4, 7, 12, 20, 35, 60, 100, 160, 240, 320}) shot(tick + dt, name);
        if (fxLate) lateFrames(name, tick);
        else at(tick + 340, this::nextFx);
    }

    /**
     * Column after the explosion ({@code fx-late}): through {@link #FX_LATE} tick the viewer along the same line from the target - halfway through the
     *  rendering (the fireball and particles there are drawn close, there is no distant image of the explosion yet) and at 1,1 drawing (behind its
     *  edge - only the distant one); The pillar is the same in both frames. How many billows of a pillar are in the frame - lines {@code SCENARIO far}.
     */
    private void lateFrames(String name, int impact) {
        double world = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        int t = impact + FX_LATE;
        for (double share : new double[]{0.5, 1.1}) {
            String label = String.format(java.util.Locale.ROOT, "%s_late%d", name, Math.round(share * 100));
            at(t, () -> {
                // along the ground from the target to the viewer, at his height; look at the pillar, not at the crater
                double dx = eye.x - target.x, dz = eye.z - target.z, len = Math.max(1e-6, Math.hypot(dx, dz)), d = share * world;
                double x = target.x + dx / len * d, z = target.z + dz / len * d;
                cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", x, eye.y, z, target.x, target.y + 60, target.z));
                Incoming.LOG.info("SCENARIO {}: spectator in {} blocks from the target ({} rendering)", label, Math.round(d), share);
            });
            shot(t + 40, label);
            t += 60;
        }
        at(t, this::nextFx);
    }

    /**
     * Close-up models: each projectile is a stationary client copy in the sky in the required phase (on a launcher with a
     *  accelerator, in flight with open wings, B-2 with an open and closed bomb bay); The camera flies around him and
     *  films from three sides. The server does not know about the copies - they do not fly or explode.
     */
    private void planModels() {
        record Pose(String name, java.util.function.Supplier<? extends net.minecraft.world.entity.EntityType<? extends StrikeProjectile>> type,
                    ua.zentix.incoming.entity.FlightPhase phase, double size, boolean aimHere) {}
        List<Pose> poses = List.of(
                new Pose("drone_ready", ua.zentix.incoming.registry.ModEntities.DRONE, ua.zentix.incoming.entity.FlightPhase.READY, 5, false),
                new Pose("drone_cruise", ua.zentix.incoming.registry.ModEntities.DRONE, ua.zentix.incoming.entity.FlightPhase.CRUISE, 5, false),
                new Pose("missile_boost", ua.zentix.incoming.registry.ModEntities.CRUISE_MISSILE, ua.zentix.incoming.entity.FlightPhase.BOOST, 7, false),
                new Pose("missile_cruise", ua.zentix.incoming.registry.ModEntities.CRUISE_MISSILE, ua.zentix.incoming.entity.FlightPhase.CRUISE, 7, false),
                new Pose("bomb", ua.zentix.incoming.registry.ModEntities.BUNKER_BUSTER, ua.zentix.incoming.entity.FlightPhase.TERMINAL, 7, false),
                new Pose("rocket", ua.zentix.incoming.registry.ModEntities.ROCKET, ua.zentix.incoming.entity.FlightPhase.CRUISE, 4, false),
                new Pose("icbm", ua.zentix.incoming.registry.ModEntities.ICBM, ua.zentix.incoming.entity.FlightPhase.CRUISE, 20, false),
                new Pose("b2_closed", ua.zentix.incoming.registry.ModEntities.BOMBER, ua.zentix.incoming.entity.FlightPhase.CRUISE, 42, false),
                new Pose("b2_open", ua.zentix.incoming.registry.ModEntities.BOMBER, ua.zentix.incoming.entity.FlightPhase.CRUISE, 42, true));
        at(40, () -> {
            cmd("time set 5000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            Minecraft.getInstance().options.hideGui = true;
            Minecraft.getInstance().options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            Minecraft.getInstance().player.getAbilities().flying = true;
            models = true;
        });
        int t = 300;
        for (Pose pz : poses) {
            int start = t;
            at(start, () -> {
                Minecraft mc = Minecraft.getInstance();
                mc.player.getAbilities().flying = true;
                if (watched != null) watched.discard();
                Vec3 m = new Vec3(0, 200, 0);
                StrikeProjectile e = pz.type().get().create(mc.level);
                e.moveTo(m.x, m.y, m.z, 0, 0);
                e.yRotO = 0;
                e.xRotO = 0;
                showPhase(e, pz.phase(), pz.aimHere() ? m : m.add(0, 0, 5000));
                mc.level.addEntity(e);
                watched = e;
            });
            // left front top, side, right rear bottom
            double d = pz.size();
            double[][] cams = {{-0.8, 0.35, 0.8}, {-1.0, 0.05, 0.0}, {0.7, -0.35, -0.8}};
            for (int k = 0; k < cams.length; k++) {
                double[] c = cams[k];
                at(start + 5 + k * 25, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f",
                        c[0] * d, 200 + c[1] * d - 1.62, c[2] * d, 0.0, 200.0, 0.0)));
                // the second time - the first teleportation after the player falls does not always get up exactly
                at(start + 15 + k * 25, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f",
                        c[0] * d, 200 + c[1] * d - 1.62, c[2] * d, 0.0, 200.0, 0.0)));
                at(start + 24 + k * 25, () -> Incoming.LOG.info("SCENARIO model {} at {} phase {}", pz.name(), watched.position(), watched.flightPhase()));
                shot(start + 25 + k * 25, pz.name() + "_" + k);
            }
            t += 95;
        }
        // launcher with a full package on the site, next to it is a resident (1.95 m) for scale
        at(t, () -> {
            watched.discard();
            cmd("fill -16 199 -16 16 199 16 minecraft:smooth_stone");
        });
        t += 20;
        for (WeaponType w : List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.LOITER)) {
            int start = t;
            List<net.minecraft.world.entity.Entity> shown = new java.util.ArrayList<>();
            at(start, () -> shown.addAll(showLauncher(w)));
            double[][] cams = {{-11, 4, 9}, {-13, 2.5, -1}, {9, 5, -11}};
            for (int k = 0; k < cams.length; k++) {
                double[] c = cams[k];
                for (int d : new int[]{5, 15}) {
                    at(start + d + k * 25, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f",
                            c[0], 200 + c[1], c[2], 0.0, 202.0, 0.0)));
                }
                shot(start + 25 + k * 25, "launcher_" + w.getSerializedName() + "_" + k);
            }
            at(start + 90, () -> shown.forEach(net.minecraft.world.entity.Entity::discard));
            t += 95;
        }
        at(t + 20, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /** Phase and aiming point of the client copy of the projectile (synchronous fields - only through reflection). */
    /** Client launcher with a raised package (0, 200, 0), shells in all cells and a resident nearby. */
    private static List<net.minecraft.world.entity.Entity> showLauncher(WeaponType w) {
        Minecraft mc = Minecraft.getInstance();
        List<net.minecraft.world.entity.Entity> out = new java.util.ArrayList<>();
        var l = ua.zentix.incoming.registry.ModEntities.LAUNCHER.get().create(mc.level);
        l.moveTo(0.5, 200, 0.5, 0, 0);
        l.yRotO = 0;
        try {
            for (String f : new String[]{"DATA_WEAPON", "DATA_DEPLOYED"}) {
                var field = ua.zentix.incoming.entity.LauncherEntity.class.getDeclaredField(f);
                field.setAccessible(true);
                if (f.equals("DATA_WEAPON")) {
                    @SuppressWarnings("unchecked")
                    var a = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) field.get(null);
                    l.getEntityData().set(a, (byte) w.id());
                } else {
                    @SuppressWarnings("unchecked")
                    var a = (net.minecraft.network.syncher.EntityDataAccessor<Long>) field.get(null);
                    l.getEntityData().set(a, mc.level.getGameTime() - 200);
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        mc.level.addEntity(l);
        out.add(l);
        var type = switch (w) {
            case MISSILE -> ua.zentix.incoming.registry.ModEntities.CRUISE_MISSILE.get();
            case ROCKET -> ua.zentix.incoming.registry.ModEntities.ROCKET.get();
            case LOITER -> ua.zentix.incoming.registry.ModEntities.LOITER.get();
            default -> ua.zentix.incoming.registry.ModEntities.DRONE.get();
        };
        for (int slot = 0; slot < ua.zentix.incoming.entity.LauncherEntity.slots(w); slot++) {
            Vec3 rail = l.railPoint(slot);
            StrikeProjectile e = type.create(mc.level);
            e.moveTo(rail.x, rail.y, rail.z, 0, -l.elevation());
            e.yRotO = 0;
            e.xRotO = -l.elevation();
            showPhase(e, ua.zentix.incoming.entity.FlightPhase.READY, rail.add(0, 0, 500));
            mc.level.addEntity(e);
            out.add(e);
        }
        var v = net.minecraft.world.entity.EntityType.VILLAGER.create(mc.level);
        v.moveTo(-2.6, 200, 3.0, 200, 0);
        v.yHeadRot = 200;
        v.yBodyRot = 200;
        mc.level.addEntity(v);
        out.add(v);
        Incoming.LOG.info("SCENARIO launcher {} slots {}", w.getSerializedName(), out.size() - 2);
        return out;
    }

    private static void showPhase(StrikeProjectile e, ua.zentix.incoming.entity.FlightPhase phase, Vec3 aim) {
        try {
            var phaseField = StrikeProjectile.class.getDeclaredField("DATA_PHASE");
            var aimField = StrikeProjectile.class.getDeclaredField("DATA_AIM");
            phaseField.setAccessible(true);
            aimField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ph = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) phaseField.get(null);
            @SuppressWarnings("unchecked")
            var am = (net.minecraft.network.syncher.EntityDataAccessor<org.joml.Vector3f>) aimField.get(null);
            e.getEntityData().set(ph, (byte) phase.ordinal());
            e.getEntityData().set(am, new org.joml.Vector3f((float) aim.x, (float) aim.y, (float) aim.z));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Projectile scenario far-models: weapon, B-2 is it, phase, pitch and roll (Grad), speed (blocks per tick). */
    private record FarShow(String name, WeaponType weapon, boolean bomber, ua.zentix.incoming.entity.FlightPhase phase,
                           float pitch, float roll, double speed) {}

    /**
     * Viewer's Eye far-models: Hangs at {@code incoming.far.eye} ("x,y,z" feet; default 0,220,0 - in the scenario world)
     *  and looks north.
     */
    private static final Vec3 FAR_EYE = farEye(System.getProperty("incoming.far.eye", "0,220,0"));
    /** Shells far-models fly east and towards the viewer (course −60°): three quarters are visible. */
    private static final float FAR_YAW = -60;

    /**
     * Models in the distance ({@code client.far.FarFlightView}, {@code client.render.FarModels}): the viewer in the sky looks north,
     *  projectiles - paths along the packets, as from the server (the client has no entities). First, pairs of frames on the same pose - entity
     *  ({@code match_*_entity}) and a path along packets ({@code match_*_far}): the same model near and far. Then a row of all shells
     *  on 300, 800 and 1500 blocks day and night ({@code far_day_*}, {@code far_night_*}) and in binoculars on 800, 1500 and 3000
     * ({@code far_scope_*}); Each frame has the line {@code SCENARIO far-models} in the log. Clouds are turned off: the background is clear sky.
     */
    private void planFarModels() {
        List<FarShow> row = List.of(
                new FarShow("drone", WeaponType.DRONE, false, ua.zentix.incoming.entity.FlightPhase.CRUISE, 0, 0, 2.1),
                new FarShow("missile", WeaponType.MISSILE, false, ua.zentix.incoming.entity.FlightPhase.CRUISE, 0, 25, 4),
                new FarShow("b2", WeaponType.BUNKER, true, ua.zentix.incoming.entity.FlightPhase.CRUISE, 0, 20, 12),
                new FarShow("bomb", WeaponType.BUNKER, false, ua.zentix.incoming.entity.FlightPhase.TERMINAL, 60, 0, 4),
                new FarShow("icbm", WeaponType.NUKE, false, ua.zentix.incoming.entity.FlightPhase.BOOST, -70, 0, 6),
                new FarShow("rocket", WeaponType.ROCKET, false, ua.zentix.incoming.entity.FlightPhase.BOOST, -40, 0, 6),
                new FarShow("loiter", WeaponType.LOITER, false, ua.zentix.incoming.entity.FlightPhase.CRUISE, 0, 15, 1.6));
        at(40, () -> {
            // player world (prod_client.py --world): only creative mode can fly and hold binoculars
            cmd("gamemode creative");
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("time set 6000");
            cmd("weather clear");
            Minecraft.getInstance().options.hideGui = true;
            Minecraft.getInstance().options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            hover = true;
        });
        int t = 300;
        // one pose - by entity and path by packages
        t = farMatch(t, row.get(0), 60);
        t = farMatch(t, row.get(1), 110);
        t = farMatch(t, row.get(2), 120); // an entity is visible only if its chunk has already been received by the client
        int pass = 0;
        for (String light : new String[]{"day", "night"}) {
            String time = light.equals("day") ? "time set 6000" : "time set 18000";
            at(t, () -> cmd(time));
            for (int d : new int[]{300, 800, 1500}) {
                farRow(t + 10, "far_" + light + "_" + d, row, ++pass, d, 36, 4);
                t += 90;
            }
        }
        at(t, () -> {
            cmd("time set 6000");
            cmd("give @s incoming:strike_designator");
            Minecraft.getInstance().options.keyUse.setDown(true);
        });
        t += 40;
        for (int d : new int[]{800, 1500, 3000}) {
            farRow(t, "far_scope_" + d, row, ++pass, d, 7, 1.5);
            t += 90;
        }
        at(t, () -> {
            Minecraft.getInstance().options.keyUse.setDown(false);
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Plume at the render-distance edge ({@code flame-edge}): Grad rocket and boosted missile at night and by day,
     * seen from the side on one line from the viewer; as entities (client copies) at 0.5, 0.85 and 0.97 render distance,
     * and via packet paths at 0.97, 1.1 and 1.4. Frames {@code flame_<light>_<projectile>_<render-distance hundredths>_<entity|far>}
     * (at 0.97, the same pose through both paths): plume looks the same on both sides of the boundary; the
     * {@code SCENARIO flame-edge} line gives its light (core, fraction, glint, veil).
     */
    private void planFlameEdge() {
        List<FarShow> show = List.of(
                new FarShow("rocket", WeaponType.ROCKET, false, ua.zentix.incoming.entity.FlightPhase.BOOST, -20, 0, 1),
                new FarShow("missile", WeaponType.MISSILE, false, ua.zentix.incoming.entity.FlightPhase.BOOST, -10, 0, 1));
        at(40, () -> {
            cmd("gamemode creative");
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("weather clear");
            Minecraft.getInstance().options.hideGui = true;
            Minecraft.getInstance().options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            hover = true;
        });
        int t = 300, pass = 0;
        for (String light : new String[]{"night", "day"}) {
            String time = light.equals("day") ? "time set 6000" : "time set 18000";
            at(t, () -> cmd(time));
            t += 20;
            for (FarShow s : show) {
                for (double frac : new double[]{0.5, 0.85, 0.97}) t = flameShot(t, s, light, frac, true, ++pass);
                for (double frac : new double[]{0.97, 1.1, 1.4}) t = flameShot(t, s, light, frac, false, ++pass);
            }
        }
        at(t, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Frame {@link #planFlameEdge}: the projectile s flies at a speed of s.speed along its course and at the tick of the frame stands directly in front of
     *  on frac rendering (horizontally, at 4° above the eye) - by the client copy of the entity or by the packet path. Returns
     *  tick after frame.
     */
    private int flameShot(int t, FarShow s, String light, double frac, boolean entity, int pass) {
        String name = String.format(java.util.Locale.ROOT, "flame_%s_%s_%03d_%s", light, s.name(), Math.round(frac * 100), entity ? "entity" : "far");
        int shotAt = t + 40;
        Vec3 dir = Vec3.directionFromRotation(s.pitch(), FAR_YAW), v = dir.scale(s.speed());
        // place in the tick of the frame - according to the drawing distance, known only in the game
        Vec3[] at = new Vec3[1];
        StrikeProjectile[] shown = new StrikeProjectile[1];
        java.util.UUID id = new java.util.UUID(0xF1AL, pass);
        farStand(t - 6);
        for (int k = t; k <= shotAt + 2; k++) {
            int tk = k;
            at(tk, () -> {
                Minecraft mc = Minecraft.getInstance();
                if (at[0] == null) {
                    double d = frac * mc.options.getEffectiveRenderDistance() * 16.0;
                    at[0] = FAR_EYE.add(0, d * Math.tan(Math.toRadians(4)), -d);
                }
                Vec3 p = at[0].add(v.scale(tk - shotAt));
                if (!entity) {
                    if ((tk - t) % ua.zentix.incoming.net.S2C.FarFlights.PERIOD == 0) {
                        ua.zentix.incoming.client.flight.FlightTracks.received(new ua.zentix.incoming.net.S2C.FarFlights(List.of(
                                farFlight(id, s, at[0], tk - shotAt, tk - t + 40))));
                    }
                    return;
                }
                if (shown[0] == null) {
                    StrikeProjectile e = (s.weapon() == WeaponType.ROCKET ? ua.zentix.incoming.registry.ModEntities.ROCKET.get()
                            : ua.zentix.incoming.registry.ModEntities.CRUISE_MISSILE.get()).create(mc.level);
                    showPhase(e, s.phase(), at[0].add(dir.scale(5000)));
                    shown[0] = e;
                    e.moveTo(p.x, p.y, p.z, FAR_YAW, s.pitch());
                    mc.level.addEntity(e);
                }
                StrikeProjectile e = shown[0];
                e.setDeltaMovement(v);
                e.moveTo(p.x, p.y, p.z, FAR_YAW, s.pitch());
                e.yRotO = FAR_YAW;
                e.xRotO = s.pitch();
            });
        }
        at(shotAt - 1, () -> Incoming.LOG.info("SCENARIO flame-edge {}: {}", name, FarRenderer.describe()));
        shot(shotAt, name);
        // the next frame is on the same line: the trail of this is shot along with the path
        at(shotAt + 4, () -> {
            if (shown[0] != null) shown[0].discard();
            ua.zentix.incoming.client.flight.FlightTracks.reset();
            FarRenderer.reset();
        });
        return shotAt + 10;
    }

    /**
     * A row of shells at a range of d: in a horizontal fan ±spread Grad, at elev Grad above the eye; packets - once
     *  in {@link ua.zentix.incoming.net.S2C.FarFlights#PERIOD} tick with tick start, frame name - through 60 tick, then
     *  each in its place. After the frame, the flights are repulsed: the trail does not move to the next row.
     */
    private void farRow(int start, String name, List<FarShow> row, int pass, double d, double spread, double elev) {
        int shotAt = start + 60, n = row.size();
        farStand(start - 6);
        List<java.util.UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) ids.add(new java.util.UUID(0xFA2L, pass * 16L + i));
        for (int k = start; k <= shotAt + 4; k += ua.zentix.incoming.net.S2C.FarFlights.PERIOD) {
            int tk = k;
            at(tk, () -> {
                List<ua.zentix.incoming.net.S2C.FarFlight> list = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    double side = Math.toRadians(-spread + 2 * spread * i / (n - 1)), up = Math.toRadians(elev);
                    Vec3 at = FAR_EYE.add(d * Math.sin(side) * Math.cos(up), d * Math.sin(up), -d * Math.cos(side) * Math.cos(up));
                    list.add(farFlight(ids.get(i), row.get(i), at, tk - shotAt, tk - start + 40));
                }
                ua.zentix.incoming.client.flight.FlightTracks.received(new ua.zentix.incoming.net.S2C.FarFlights(list));
            });
        }
        at(shotAt - 1, () -> Incoming.LOG.info("SCENARIO far-models {}: {}", name, FarRenderer.describe()));
        shot(shotAt, name);
        at(shotAt + 6, () -> ua.zentix.incoming.client.flight.FlightTracks.cancelled(ids));
    }

    /**
     * The projectile package s, which in the frame tick (dt = 0) stands at at: place - according to the {@link #FAR_YAW} heading, pitch and speed,
     *  target - 5 km ahead.
     */
    private static ua.zentix.incoming.net.S2C.FarFlight farFlight(java.util.UUID id, FarShow s, Vec3 at, int dt, int phaseAge) {
        Vec3 dir = Vec3.directionFromRotation(s.pitch(), FAR_YAW);
        Vec3 v = dir.scale(s.speed());
        return new ua.zentix.incoming.net.S2C.FarFlight(id, s.weapon().id(), s.bomber(), false, false, at.add(v.scale(dt)), v,
                FAR_YAW, s.pitch(), s.roll(), s.phase().ordinal(), phaseAge, at.add(dir.scale(5000)));
    }

    /**
     * A couple of frames of one pose at a distance of d are right ahead: the client copy of the entity ({@code match_<s>_entity}),
     * , then the path through packets without speed ({@code match_<s>_far}). Returns tick after a pair.
     */
    private int farMatch(int t, FarShow s, double d) {
        Vec3 at = FAR_EYE.add(0, d * Math.sin(Math.toRadians(4)), -d);
        Vec3 aim = at.add(Vec3.directionFromRotation(s.pitch(), FAR_YAW).scale(5000));
        StrikeProjectile[] shown = new StrikeProjectile[1];
        farStand(t - 6);
        at(t, () -> {
            Minecraft mc = Minecraft.getInstance();
            var type = switch (s.weapon()) {
                case MISSILE -> ua.zentix.incoming.registry.ModEntities.CRUISE_MISSILE.get();
                case BUNKER -> ua.zentix.incoming.registry.ModEntities.BOMBER.get();
                default -> ua.zentix.incoming.registry.ModEntities.DRONE.get();
            };
            StrikeProjectile e = type.create(mc.level);
            e.moveTo(at.x, at.y, at.z, FAR_YAW, s.pitch());
            e.yRotO = FAR_YAW;
            e.xRotO = s.pitch();
            showPhase(e, s.phase(), aim);
            showRoll(e, s.roll());
            mc.level.addEntity(e);
            shown[0] = e;
        });
        shot(t + 40, "match_" + s.name() + "_entity");
        at(t + 45, () -> shown[0].discard());
        java.util.UUID id = new java.util.UUID(0xFA1L, s.weapon().id() * 2L + (s.bomber() ? 1 : 0));
        for (int k = t + 46; k <= t + 96; k += ua.zentix.incoming.net.S2C.FarFlights.PERIOD) {
            int tk = k;
            at(tk, () -> ua.zentix.incoming.client.flight.FlightTracks.received(new ua.zentix.incoming.net.S2C.FarFlights(List.of(
                    farFlight(id, new FarShow(s.name(), s.weapon(), s.bomber(), s.phase(), s.pitch(), s.roll(), 0), at, 0, tk - t)))));
        }
        at(t + 89, () -> Incoming.LOG.info("SCENARIO far-models match_{}: {}", s.name(), FarRenderer.describe()));
        shot(t + 90, "match_" + s.name() + "_far");
        at(t + 100, () -> ua.zentix.incoming.client.flight.FlightTracks.cancelled(List.of(id)));
        return t + 110;
    }

    private static Vec3 farEye(String feet) {
        String[] c = feet.split(",");
        return new Vec3(Double.parseDouble(c[0].trim()), Double.parseDouble(c[1].trim()) + 1.62, Double.parseDouble(c[2].trim()));
    }

    /** Spectator - in place {@link #FAR_EYE}, looking north: each row anew (the flight could have moved it a little). */
    private void farStand(int t) {
        at(t, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.2f %.1f 180 0", FAR_EYE.x, FAR_EYE.y - 1.62, FAR_EYE.z)));
    }

    /** The roll of the client copy of the projectile (synchronous field - only through reflection). */
    private static void showRoll(StrikeProjectile e, float roll) {
        try {
            var field = StrikeProjectile.class.getDeclaredField("DATA_ROLL");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var a = (net.minecraft.network.syncher.EntityDataAccessor<Float>) field.get(null);
            e.getEntityData().set(a, roll);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Short flights from afar, without a launcher (for the strike scenario: the projectile arrives in ~5 s). */
    private static void quickFlights() {
        var c = ua.zentix.incoming.IncomingConfig.SERVER;
        c.launchNearPlayer.set(false);
        c.droneFlightTime.set(5);
        c.missileFlightTime.set(5);
        c.bomberFlightTime.set(5);
    }

    /**
     * Flying overhead: the viewer stands on the ground in the path of shells fired from afar (without a launcher nearby) - a salvo of
     *  MLRS from behind (the shells pass over him to the target in 200 blocks ahead), a cruise missile and Shahed (the last direct
     *  section of the route - also from behind). The projectiles fly for a long time outside the world and beyond the range of the entities: the sound from the first
     *  audible hundreds of blocks goes through the server packets ({@code s} in the sound lines), near - according to the entity ({@code e}).
     * Sound - in the log lines once in 5 tick and in audio.wav.
     */
    private void planFlyby() {
        soundEvery = 5;
        // INCOMING_FLIGHT_SPEED=<0..1> - world flight scale (preset CUSTOM) and frames: the viewer on the site in 30 blocks above the
        //  ground (above the crowns), in the morning (the sun is not at its zenith), looking at the target: shells are passing over them and go to her; “Grad” from afar on the scale above 0 flies ~80 s - the flight is longer
        String scale = System.getenv("INCOMING_FLIGHT_SPEED");
        boolean frames = scale != null;
        int end = frames && Double.parseDouble(scale) > 0 ? 2600 : 1750;
        at(40, () -> {
            cmd(frames ? "time set 1000" : "time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
        });
        at(200, () -> {
            var c = ua.zentix.incoming.IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            c.droneFlightTime.set(12);
            c.missileFlightTime.set(12);
            if (scale != null) {
                c.scalePreset.set(ua.zentix.incoming.IncomingConfig.ScalePreset.CUSTOM);
                c.flightSpeed.set(Double.parseDouble(scale));
            }
            aimAhead(200);
            Minecraft mc = Minecraft.getInstance();
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
            if (frames) {
                y += 30;
                cmd(String.format(java.util.Locale.ROOT, "fill -1 %d -1 1 %d 1 minecraft:glass", y - 1, y - 1));
            }
            cmd(String.format(java.util.Locale.ROOT, "tp @s 0.5 %d 0.5 facing %.1f %.1f %.1f", y, target.x, target.y, target.z));
        });
        at(240, () -> cmd(String.format(java.util.Locale.ROOT, "incoming salvo rocket 6 12 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        at(700, () -> cmd(String.format(java.util.Locale.ROOT, "incoming missile at %.1f %.1f %.1f", target.x, target.y, target.z)));
        at(1100, () -> cmd(String.format(java.util.Locale.ROOT, "incoming drone at %.1f %.1f %.1f", target.x, target.y, target.z)));
        if (end > 1750) {
            // on the real scale - another B-2 at the echelon 12 km: its target is 12 km ahead of the viewer, the drop line is ~10 km
            //  before it, and it passes over the viewer before the drop; the camera looks at him (distant view: body and footprint at height)
            at(1300, () -> cmd("incoming bunker at 0.5 12000.5"));
            for (int t = 1850; t <= 2100; t += 5) at(t, ClientScenario::lookAtBomber);
            at(2110, () -> cmd(String.format(java.util.Locale.ROOT, "tp @s ~ ~ ~ facing %.1f %.1f %.1f", target.x, target.y, target.z)));
        }
        for (int t = 250; t <= end - 50; t += 50) at(t, ClientScenario::dumpFlights);
        // flyby footage
        if (frames) for (int t = 250; t <= end - 50; t += 5) shot(t, "flyby");
        at(end, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * launch with the player's launcher: a platform in the sky (flat and firm), launcher Shahed turns behind; camera
     *  of the projectile from the launch itself - side view of the launcher, acceleration, accelerator separation, side, impact and flyby of the explosion;
     *  then the same for the cruise missile. The flights were shortened (Shahed 20 s, rocket 12 s) so that the scenario lasted minutes.
     */
    private void planLaunch() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            // the site is longer than the script's rendering range: without this, fill sometimes responds “not loaded”
            cmd("forceload add -32 -48 32 136");
        });
        at(200, () -> {
            var c = ua.zentix.incoming.IncomingConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(20);
            c.missileFlightTime.set(12);
            // platform in the sky: flat and solid under the launcher, the target is on it in 120 blocks ahead
            cmd("fill -30 199 -40 30 199 130 minecraft:smooth_stone");
            cmd("fill -2 200 118 2 202 122 minecraft:oak_planks");
            cmd("tp @s 0.5 200 0.5 0 5");
            target = new Vec3(0.5, 203, 120.5);
        });
        // launch, and immediately the camera: launch plan from the side, side, hit
        at(230, () -> cmd(String.format(java.util.Locale.ROOT, "incoming drone at %.1f %.1f %.1f", target.x, target.y, target.z)));
        // the server running llvmpipe is lagging behind: the projectile may not be on the list yet - try again
        for (int t = 236; t <= 266; t += 10) {
            at(t, () -> {
                if (!ua.zentix.incoming.client.cam.ProjectileCamera.isActive()) ua.zentix.incoming.client.cam.ProjectileCamera.cycle();
            });
        }
        for (int t = 240; t <= 1000; t += 8) shot(t, "drone");
        // repeat: another nuclear explosion in the recording (1 kt, behind the back in 700 blocks), its rewind is checked by ReplayCheck
        if (replay) at(1004, () -> cmd("incoming nuke now at 500 100 -500 1 air"));
        at(1010, () -> {
            ua.zentix.incoming.client.cam.ProjectileCamera.exit();
            cmd(String.format(java.util.Locale.ROOT, "incoming missile at %.1f %.1f %.1f", target.x, target.y, target.z));
        });
        at(1016, ua.zentix.incoming.client.cam.ProjectileCamera::cycle);
        for (int t = 1020; t <= 1500; t += 6) shot(t, "missile");
        at(1510, ua.zentix.incoming.client.cam.ProjectileCamera::exit);
        at(1520, () -> {
            if (replay) {
                new ReplayCheck(); // exit from the world, replay Flashback is saved and opened - then ReplayCheck
                return;
            }
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * HUD hits: ten shells at five targets at once - a stand with a name (a salvo from 4), a zombie (3), a pig (dies in flight:
     *  gray cross), a point next to the stand (the crosses merge) and a rocket to a far point. The viewer is above the launcher,
     *  looks at the targets: marks, dotted lines, signatures (neighboring ones do not overlap each other) and the list on the right; then - close.
     */
    private void planHud() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            cmd("forceload add -32 -48 32 136");
        });
        at(200, () -> {
            var c = ua.zentix.incoming.IncomingConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(30);
            c.missileFlightTime.set(20);
            cmd("fill -30 199 -40 30 199 130 minecraft:smooth_stone");
            cmd("tp @s 0.5 200 0.5 0 5");
            // peaceful mode removes hostile mobs: targets - a stand with a name, a resident and a pig (tags - tags hud)
            cmd("summon minecraft:armor_stand -11.5 200 105.5 {CustomName:'\"ENOTzRPG\"',Tags:[\"hud_named\"]}");
            cmd("summon minecraft:villager 12.5 200 112.5 {NoAI:1b,Tags:[\"hud_type\"]}");
            cmd("summon minecraft:pig 0.5 200 122.5 {NoAI:1b,Tags:[\"hud_lost\"]}");
            target = new Vec3(0.5, 200, 122.5);
        });
        at(215, () -> {
            cmd("incoming salvo drone 4 0 @e[tag=hud_named,limit=1]");
            cmd("incoming salvo drone 3 0 @e[tag=hud_type,limit=1]");
            cmd("incoming drone @e[tag=hud_lost,limit=1]");
            cmd("incoming drone at -9.5 200 106.5");
            cmd("incoming missile at 20.5 200 128.5");
        });
        // viewer above the launcher, behind the line of fire: both shells and targets are visible
        at(225, () -> cmd("tp @s 0.5 222 -14.5 0 9"));
        at(420, () -> cmd("kill @e[tag=hud_lost]"));
        for (int t = 240; t <= 900; t += 20) shot(t, "hud");
        // close to targets: signatures are larger on the screen
        at(905, () -> cmd("tp @s 0.5 214 70.5 0 20"));
        for (int t = 920; t <= 1100; t += 15) shot(t, "hud_near");
        at(1110, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * The projectile camera is further rendered (12 chunks, like Artem): a rocket on 800 blocks and three Shahed on 600 with a launcher
     *  nearby. Video - while the projectile is close, then the map is based on server telemetry; the missile disappears at the target - “target hit”
     *  on the map, then the camera switches to Shahed.
     */
    private void planMap() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            cmd("forceload add -32 -48 32 64");
        });
        at(200, () -> {
            var c = ua.zentix.incoming.IncomingConfig.SERVER;
            c.launchNearPlayer.set(true);
            c.droneFlightTime.set(30);
            c.missileFlightTime.set(20);
            cmd("fill -30 199 -40 30 199 60 minecraft:smooth_stone");
            cmd("tp @s 0.5 200 0.5 0 5");
        });
        at(215, () -> {
            cmd("incoming missile at 0.5 80 800.5");
            cmd("incoming salvo drone 3 8 at 300.5 80 520.5");
        });
        for (int t = 225; t <= 265; t += 10) {
            at(t, () -> {
                if (!ua.zentix.incoming.client.cam.ProjectileCamera.isActive()) ua.zentix.incoming.client.cam.ProjectileCamera.cycle();
            });
        }
        for (int t = 240; t <= 1400; t += 12) shot(t, "map");
        for (int t = 600; t <= 1400; t += 100) at(t, ClientScenario::dumpFlights);
        at(1410, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Targeting map: open from the remote control, zoom out with the wheel, select a location by clicking on 60 and 40 pixels from the center (northeast),
     *  fire on Enter - all through screen input, like a player. In the log - the selected location and target of the projectile according to server data
     *  (height - surface); frames target-map_* - map with relief, with a target mark, then a projectile on it.
     * Properties: {@code incoming.mapWeapon} - remote control weapon (missile by default), {@code incoming.mapAt=x,z} - location
     *  on the map is specified by a point, not a click (check hitting the known far roof), {@code incoming.mapFrom=x,y,z} -
     *  from where to hit (and in a copy of the player's world).
     */
    private void planTargetMap() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            // in a copy of the player’s world (prod_client.py --world) - where he stands
            String from = System.getProperty("incoming.mapFrom");
            if (from != null) {
                // the height of the ground there is not known in advance: from above, in creative - without damage from falling
                cmd("gamemode creative");
                cmd("tp @s " + from.replace(',', ' ') + " 0 30");
            }
            else if (System.getProperty("incoming.world") == null) cmd("tp @s 0.5 120 0.5 0 30");
            // new remote control in hand - default settings: one missile without spread, miss is measured from a point
            String weapon = System.getProperty("incoming.mapWeapon");
            cmd("item replace entity @s weapon.mainhand with incoming:strike_designator"
                    + (weapon == null ? "" : "[incoming:loadout={weapon:\"" + java.util.Objects.requireNonNull(WeaponType.parse(weapon), "incoming.mapWeapon: " + weapon).getSerializedName() + "\"}]"));
        });
        // the map is opened after playing: DH by this time has loaded his LOD around. While he is loading them, reading the terrain
        //  through his API is in line behind them (the I/O pool DH is lower in priority than loading LOD)
        for (int t = 300; t <= 300 + MAP_DH_WAIT; t += 20) {
            at(t, () -> {
                if (mapOpened < 0 && (dhIdle() || tick >= 300 + MAP_DH_WAIT)) mapBegin(tick);
            });
        }
        // hit - the first explosion on the server (built-in): where is it and how far from the selected point
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ExplosionEvent.Detonate e) -> {
            Vec3 at = e.getExplosion().center(), aim = mapTarget;
            if (aim == null || mapImpactTick >= 0 || e.getLevel().isClientSide()) return;
            double miss = Math.hypot(at.x - aim.x, at.z - aim.z);
            BlockPos hit = BlockPos.containing(at), target = BlockPos.containing(aim);
            WeaponType weapon = WeaponType.parse(System.getProperty("incoming.mapWeapon", "missile"));
            var shooter = e.getLevel().players().isEmpty() ? null : e.getLevel().players().getFirst();
            double range = shooter == null ? 0 : Math.hypot(shooter.getX() - aim.x, shooter.getZ() - aim.z);
            double allowed = allowedMiss(weapon, range);
            Incoming.LOG.info("SCENARIO map-target impact {} miss {} (approval {}) - {}; ground under explosion {} (with foliage {}), at the target {} (with foliage {})", at,
                    Math.round(miss), Math.round(allowed), miss <= allowed ? "OK" : "FAIL",
                    e.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, hit.getX(), hit.getZ()),
                    e.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, hit.getX(), hit.getZ()),
                    e.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ()),
                    e.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, target.getX(), target.getZ()));
            mapImpactTick = tick;
        });
    }

    /**
     * A salvo on the guidance map - frames salvo-map_* (dashed paths of projectiles to targets, scatter area) and the time during which the
     *  map opened, in the log (“Targeting map: view terrain ready in ..."): in the remote there are six Shahed with a spread of 40,
     *  place by clicking in ~130 blocks, fire on Enter, the map is again open for the entire flight. In the player's copy of the world
     * (prod_client.py --world) - where he stands; the card is opened when DH has loaded its LOD (as in target-map).
     */
    private void planSalvoMap() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            if (System.getProperty("incoming.world") == null) cmd("tp @s 0.5 120 0.5 0 30");
            cmd("item replace entity @s weapon.mainhand with incoming:strike_designator");
        });
        at(60, () -> {
            // remote control settings - both from its screen: to the item on the client and to the server
            var salvo = new ua.zentix.incoming.strike.Loadout(WeaponType.DRONE, 6, 40, ua.zentix.incoming.strike.TargetMode.MAP, "",
                    ua.zentix.incoming.strike.Loadout.DEFAULT.nuke());
            Minecraft.getInstance().player.getMainHandItem().set(ua.zentix.incoming.registry.ModDataComponents.LOADOUT.get(), salvo);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new ua.zentix.incoming.net.C2S.SetLoadout(net.minecraft.world.InteractionHand.MAIN_HAND, salvo));
        });
        for (int t = 300; t <= 300 + MAP_DH_WAIT; t += 20) {
            at(t, () -> {
                if (mapOpened < 0 && (dhIdle() || tick >= 300 + MAP_DH_WAIT)) salvoMapBegin(tick);
            });
        }
    }

    private void salvoMapBegin(int o) {
        mapOpened = o;
        Incoming.LOG.info("SCENARIO salvo-map open at tick {}", o);
        ua.zentix.incoming.IncomingConfig.SERVER.droneFlightTime.set(20);
        Minecraft.getInstance().setScreen(new ua.zentix.incoming.client.screen.MapScreen(new RemoteScreen()));
        shot(o + 10, "salvo-map");
        shot(o + 40, "salvo-map");
        at(o + 60, () -> {
            var screen = Minecraft.getInstance().screen;
            double x = screen.width / 2.0 + 120, y = screen.height / 2.0 - 50;
            screen.mouseClicked(x, y, 0);
            screen.mouseReleased(x, y, 0);
        });
        shot(o + 70, "salvo-map");
        at(o + 80, () -> Minecraft.getInstance().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
        at(o + 100, () -> Minecraft.getInstance().setScreen(new ua.zentix.incoming.client.screen.MapScreen(new RemoteScreen())));
        for (int t = o + 120; t <= o + 520; t += 40) shot(t, "salvo-map");
        at(o + 540, () -> {
            logFlights();
            Incoming.LOG.info("SCENARIO salvo-map terrain {}", ua.zentix.incoming.client.map.TerrainTiles.stats());
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Route on the guidance map ({@code route-map}): remote control with Shahed ({@code incoming.mapWeapon} - another weapon),
     *  the map is distant, the target is clicked, three route points (Shift+click without a window cannot be pressed: the points are placed by {@code MapTarget}
     *  in the same way as a click), the third is removed RMB, the second one is dragged by the mouse; the route is longer than the weapon's range - no fire is given;
     * fire at Enter. Server: the projectile passes the points in order - no further than two spec captures and a step like
     * {@code Route.update} - and hits the target (line {@code SCENARIO route-map impact … OK}). Frames route-map_*.
     * {@code incoming.mapFrom=x,y,z} - where to hit (and in a copy of the player’s world).
     * <p>
     * {@code launch-map} ({@code launch}) - the same with the launch location with maps: launch place button and click, the icon is dragged
     *  with the mouse (line {@code SCENARIO launch-map site … OK}), fire - from it: the server places a launcher there or, if there is no place under
     * , the projectile starts from the place itself ({@code SCENARIO launch-map start … OK}), the path is from the launch place through the points
     *  to the target. The embedded server host chooses the launch location without operator rights. Frames launch-map_*.
     */
    private void planRouteMap(boolean launch) {
        WeaponType weapon = java.util.Objects.requireNonNull(WeaponType.parse(System.getProperty("incoming.mapWeapon", "drone")),
                "incoming.mapWeapon");
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            String from = System.getProperty("incoming.mapFrom");
            if (from != null) {
                cmd("gamemode creative");
                cmd("tp @s " + from.replace(',', ' ') + " 0 30");
            }
            else if (System.getProperty("incoming.world") == null) cmd("tp @s 0.5 120 0.5 0 30");
            cmd("item replace entity @s weapon.mainhand with incoming:strike_designator[incoming:loadout={weapon:\"" + weapon.getSerializedName() + "\"}]");
        });
        for (int t = 300; t <= 300 + MAP_DH_WAIT; t += 20) {
            at(t, () -> {
                if (mapOpened < 0 && (dhIdle() || tick >= 300 + MAP_DH_WAIT)) routeMapBegin(tick, weapon, launch);
            });
        }
        // server (built-in): where the projectile started from, which route points it passed and where it exploded
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
            if (launch) checkLaunchSite(e.getServer().overworld(), weapon);
            ua.zentix.incoming.strike.Waypoints via = routeFired;
            if (via == null || routePassed >= via.size()) return;
            Vec3 wp = via.points().get(routePassed);
            double near = 2 * weapon.spec().capture() + weapon.spec().airframe().cruiseSpeed() + 1;
            var level = e.getServer().overworld();
            java.util.List<StrikeProjectile> flying = new ArrayList<>(ua.zentix.incoming.strike.VirtualFlights.get(level).flights());
            level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(StrikeProjectile.class), q -> !q.isRemoved()).forEach(flying::add);
            for (StrikeProjectile q : flying) {
                if (q.weapon() != weapon || Math.hypot(q.getX() - wp.x, q.getZ() - wp.z) > near) continue;
                routePassed++;
                Incoming.LOG.info("SCENARIO route-map waypoint {} passed at {}", routePassed, xyz(q.position()));
                break;
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ExplosionEvent.Detonate e) -> {
            Vec3 at = e.getExplosion().center(), aim = mapTarget;
            ua.zentix.incoming.strike.Waypoints via = routeFired;
            if (aim == null || via == null || mapImpactTick >= 0 || e.getLevel().isClientSide()) return;
            double miss = Math.hypot(at.x - aim.x, at.z - aim.z), allowed = allowedMiss(weapon, 0);
            boolean ok = miss <= allowed && routePassed == via.size();
            Incoming.LOG.info("SCENARIO route-map impact {} miss {} (tolerance {}), points passed {} from {} — {}", xyz(at), Math.round(miss), Math.round(allowed),
                    routePassed, via.size(), ok ? "OK" : "FAIL");
            mapImpactTick = tick;
        });
    }

    /** The route with which the order went (scenario route-map), and how many of its points the projectile passed; they are read by the server thread. */
    @org.jetbrains.annotations.Nullable
    private volatile ua.zentix.incoming.strike.Waypoints routeFired;
    private volatile int routePassed;
    /** Place of launch order (scenario launch-map) and tick of the server for which the start from it has not yet been found; they are read by the server thread. */
    @org.jetbrains.annotations.Nullable
    private volatile Vec3 launchSite;
    private long launchDeadline = -1;
    private boolean launchChecked;

    /**
     * Start from the launch site (scenario launch-map, server thread): launcher on it (in place or on a ring of sites around)
     *  with a projectile of this weapon, or the projectile was launched from the very place outside the world (there is no place for the launcher).
     */
    private void checkLaunchSite(net.minecraft.server.level.ServerLevel level, WeaponType weapon) {
        Vec3 site = launchSite;
        if (site == null || routeFired == null || launchChecked) return;
        if (launchDeadline < 0) launchDeadline = level.getGameTime() + 200;
        net.minecraft.world.phys.AABB around = new net.minecraft.world.phys.AABB(site.x - 48, level.getMinBuildHeight(), site.z - 48, site.x + 48, level.getMaxBuildHeight(), site.z + 48);
        for (var l : level.getEntitiesOfClass(ua.zentix.incoming.entity.LauncherEntity.class, around, ua.zentix.incoming.entity.LauncherEntity::ordered)) {
            launchChecked = true;
            Incoming.LOG.info("SCENARIO launch-map start: launcher at the launch site in {} from it, {} - OK", Math.round(Math.hypot(l.getX() - site.x, l.getZ() - site.z)),
                    xyz(l.position()));
            return;
        }
        for (StrikeProjectile q : ua.zentix.incoming.strike.VirtualFlights.get(level).flights()) {
            if (q.weapon() != weapon || Math.hypot(q.getX() - site.x, q.getZ() - site.z) > 48) continue;
            launchChecked = true;
            Incoming.LOG.info("SCENARIO launch-map start: without launcher, from an off-world launch location {} – OK", xyz(q.position()));
            return;
        }
        if (level.getGameTime() < launchDeadline) return;
        launchChecked = true;
        Incoming.LOG.warn("SCENARIO launch-map start: no launcher, no projectile at the launch site {} — FAIL", xyz(site));
    }

    private void routeMapBegin(int o, WeaponType weapon, boolean launch) {
        mapOpened = o;
        Incoming.LOG.info("SCENARIO route-map open at tick {}", o);
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new ua.zentix.incoming.client.screen.MapScreen(new RemoteScreen()));
        at(o + 20, () -> mc.screen.mouseScrolled(mc.screen.width / 2.0, mc.screen.height / 2.0, 0, -6));
        at(o + 40, () -> {
            Screen screen = mc.screen;
            double x = screen.width / 2.0 + 110, y = screen.height / 2.0 - 60;
            screen.mouseClicked(x, y, 0);
            screen.mouseReleased(x, y, 0);
            var place = ua.zentix.incoming.client.map.MapTarget.get(mc.level).orElseThrow();
            mapTarget = new Vec3(place.x(), 0, place.z());
            // bypass from the west, the third point is superfluous (it will be removed by RMB)
            Vec3 me = mc.player.position();
            ua.zentix.incoming.client.map.MapTarget.add(mc.level, new ua.zentix.incoming.client.map.MapTarget.Place(me.x - 320, me.z - 120));
            ua.zentix.incoming.client.map.MapTarget.add(mc.level, new ua.zentix.incoming.client.map.MapTarget.Place(me.x - 160, me.z - 480));
            ua.zentix.incoming.client.map.MapTarget.add(mc.level, new ua.zentix.incoming.client.map.MapTarget.Place(me.x + 200, me.z + 200));
            logRoute("three dots", weapon);
        });
        shot(o + 60, "route-map");
        at(o + 70, () -> {
            Screen screen = mc.screen;
            var route = ua.zentix.incoming.client.map.MapTarget.route(mc.level);
            int[] third = mapScreenAt(screen, route.get(2)), second = mapScreenAt(screen, route.get(1));
            screen.mouseClicked(third[0], third[1], 1);
            screen.mouseClicked(second[0], second[1], 0);
            screen.mouseDragged(second[0] + 20, second[1] - 10, 0, 20, -10);
            screen.mouseDragged(second[0] + 40, second[1] - 20, 0, 20, -10);
            screen.mouseReleased(second[0] + 40, second[1] - 20, 0);
            var edited = ua.zentix.incoming.client.map.MapTarget.route(mc.level);
            boolean ok = edited.size() == 2 && edited.get(0).equals(route.get(0)) && edited.get(1).x() > route.get(1).x() + 1
                    && edited.get(1).z() < route.get(1).z() - 1;
            Incoming.LOG.info("SCENARIO route-map edit (RMB removed the third, the second was dragged) - {}", ok ? "OK" : "FAIL");
            logRoute("after editing", weapon);
        });
        shot(o + 80, "route-map");
        at(o + 90, () -> {
            Vec3 me = mc.player.position();
            ua.zentix.incoming.client.map.MapTarget.add(mc.level, new ua.zentix.incoming.client.map.MapTarget.Place(me.x, me.z + 9_500));
            ua.zentix.incoming.client.map.MapTarget.add(mc.level, new ua.zentix.incoming.client.map.MapTarget.Place(me.x + 9_500, me.z + 9_500));
            boolean blocked = !mapCanFire(mc.screen);
            mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
            Incoming.LOG.info("SCENARIO route-map too long: fire is not given - {}", blocked && mc.screen instanceof ua.zentix.incoming.client.screen.MapScreen ? "OK" : "FAIL");
            logRoute("longer range", weapon);
        });
        shot(o + 100, "route-map");
        at(o + 110, () -> {
            ua.zentix.incoming.client.map.MapTarget.remove(mc.level, 3);
            ua.zentix.incoming.client.map.MapTarget.remove(mc.level, 2);
            if (launch) placeLaunchSite(mc.screen);
        });
        // launch location on the map: icon, path from it, line under the route
        int f = launch ? o + 130 : o + 110;
        if (launch) shot(o + 120, "launch-map");
        at(f, () -> {
            routeFired = ua.zentix.incoming.client.map.MapTarget.waypoints(mc.level);
            logRoute("fire", weapon);
            mc.screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        });
        at(f + 60, () -> {
            logFlights();
            mc.setScreen(new ua.zentix.incoming.client.screen.MapScreen(new RemoteScreen()));
        });
        String frames = launch ? "launch-map" : "route-map";
        for (int t = f + 80; t <= o + MAP_FLIGHT_WAIT; t += 120) shot(t, frames);
        for (int t = f + 80; t <= o + MAP_FLIGHT_WAIT; t += 20) {
            at(t, () -> {
                if (mapDone) return;
                if (mapImpactTick < 0 || tick < mapImpactTick + 60) {
                    if (tick < o + MAP_FLIGHT_WAIT) return;
                    Incoming.LOG.warn("SCENARIO route-map no impact by tick {}, points passed {} - FAIL", tick, routePassed);
                }
                mapDone = true;
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
        }
    }

    /** Route line on the map: points, length from the launch point (without it - from the player) to the target, whether the weapon is in range and whether fire is given. */
    private void logRoute(String stage, WeaponType weapon) {
        Minecraft mc = Minecraft.getInstance();
        var via = ua.zentix.incoming.client.map.MapTarget.waypoints(mc.level);
        Vec3 aim = mapTarget, me = ua.zentix.incoming.client.map.MapTarget.launchPoint(mc.level).orElse(mc.player.position());
        Incoming.LOG.info("SCENARIO route-map {}: points {} length {} in range {} fire {}", stage,
                via.points().stream().map(q -> Math.round(q.x) + " " + Math.round(q.z)).toList(),
                aim == null ? "—" : Math.round(via.length(me, aim)), aim != null && via.within(weapon, me, aim), mapCanFire(mc.screen));
    }

    /**
     * Place launch on the map as a player (scenario launch-map): launch place button and click on 300 blocks south of the player,
     *  then drag the icon with the mouse to 20 pixels to the west.
     */
    private void placeLaunchSite(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 me = mc.player.position();
        String pick = net.minecraft.network.chat.Component.translatable("incoming.map.launch.pick").getString();
        for (var c : screen.children()) {
            if (c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals(pick)) b.onPress();
        }
        int[] at = mapScreenAt(screen, new ua.zentix.incoming.client.map.MapTarget.Place(me.x + 40, me.z + 300));
        screen.mouseClicked(at[0], at[1], 0);
        screen.mouseReleased(at[0], at[1], 0);
        var placed = ua.zentix.incoming.client.map.MapTarget.launch(mc.level);
        screen.mouseClicked(at[0], at[1], 0);
        screen.mouseDragged(at[0] - 10, at[1], 0, -10, 0);
        screen.mouseDragged(at[0] - 20, at[1], 0, -10, 0);
        screen.mouseReleased(at[0] - 20, at[1], 0);
        var moved = ua.zentix.incoming.client.map.MapTarget.launch(mc.level);
        boolean ok = placed.isPresent() && Math.abs(placed.get().z() - (me.z + 300)) < 8 && moved.isPresent() && moved.get().x() < placed.get().x() - 1;
        Incoming.LOG.info("SCENARIO launch-map site (button and click, icon dragged) {} → {} - {}", placed.map(p -> Math.round(p.x()) + " " + Math.round(p.z())).orElse("—"),
                moved.map(p -> Math.round(p.x()) + " " + Math.round(p.z())).orElse("—"), ok ? "OK" : "FAIL");
        launchSite = moved.map(p -> new Vec3(p.x(), 0, p.z())).orElse(null);
    }

    /** The location of the world point on the guidance map (its projection is a closed screen method: for scenario only). */
    private static int[] mapScreenAt(Screen screen, ua.zentix.incoming.client.map.MapTarget.Place place) {
        try {
            var m = ua.zentix.incoming.client.screen.MapScreen.class.getDeclaredMethod("projection");
            m.setAccessible(true);
            return ((ua.zentix.incoming.client.map.MapProjection) m.invoke(screen)).at(place.x(), place.z());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Does the targeting map give fire (closed screen method: scenario only). */
    private static boolean mapCanFire(Screen screen) {
        try {
            var m = ua.zentix.incoming.client.screen.MapScreen.class.getDeclaredMethod("canFire");
            m.setAccessible(true);
            return (boolean) m.invoke(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Targeting map with tick {@code o}: move away, select a point in ~700 blocks, fire, wait for a hit. */
    private void mapBegin(int o) {
        mapOpened = o;
        Incoming.LOG.info("SCENARIO map-target open at tick {}", o);
        logCoverage();
        if (net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) logDhThreads();
        ua.zentix.incoming.IncomingConfig.SERVER.droneFlightTime.set(20);
        Minecraft.getInstance().setScreen(new ua.zentix.incoming.client.screen.MapScreen(new RemoteScreen()));
        shot(o + 40, "target-map");
        // zoom out on the map: the point is far beyond the drawing range - only DH has relief there
        at(o + 60, () -> {
            var screen = Minecraft.getInstance().screen;
            screen.mouseScrolled(screen.width / 2.0, screen.height / 2.0, 0, -8);
        });
        shot(o + 100, "target-map");
        at(o + 120, () -> {
            var screen = Minecraft.getInstance().screen;
            String at = System.getProperty("incoming.mapAt");
            if (at != null) {
                String[] xz = at.split(",");
                ua.zentix.incoming.client.map.MapTarget.set(Minecraft.getInstance().level, new ua.zentix.incoming.client.map.MapTarget.Place(
                        Double.parseDouble(xz[0].strip()) + 0.5, Double.parseDouble(xz[1].strip()) + 0.5));
            } else {
                double x = screen.width / 2.0 + 100, y = screen.height / 2.0 - 70;
                screen.mouseClicked(x, y, 0);
                screen.mouseReleased(x, y, 0);
            }
            var place = ua.zentix.incoming.client.map.MapTarget.get(Minecraft.getInstance().level).orElseThrow();
            mapTarget = new Vec3(place.x(), 0, place.z());
            Incoming.LOG.info("SCENARIO map-target selected X {} Z {} distance {} terrain height {} far {}", Math.round(place.x()), Math.round(place.z()),
                    Math.round(Math.hypot(place.x() - Minecraft.getInstance().player.getX(), place.z() - Minecraft.getInstance().player.getZ())),
                    ua.zentix.incoming.client.map.TerrainTiles.height((int) Math.floor(place.x()), (int) Math.floor(place.z())),
                    ua.zentix.incoming.client.map.TerrainTiles.farTerrain());
            Incoming.LOG.info("SCENARIO map-target terrain {}", ua.zentix.incoming.client.map.TerrainTiles.stats());
        });
        shot(o + 130, "target-map");
        at(o + 140, () -> {
            // height from the map that the order will take away (C2S.AimHint.mapSurface): the tile at the place could be completed after choosing
            Vec3 aim = mapTarget;
            Incoming.LOG.info("SCENARIO map-target order terrain height {}", aim == null ? "—"
                    : ua.zentix.incoming.client.map.TerrainTiles.height((int) Math.floor(aim.x), (int) Math.floor(aim.z)));
            Minecraft.getInstance().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        });
        at(o + 200, () -> {
            for (var f : ua.zentix.incoming.client.hud.ClientFlights.all()) {
                Incoming.LOG.info("SCENARIO map-target flight {} target {}", f.weapon(), f.target());
            }
            Minecraft.getInstance().setScreen(new ua.zentix.incoming.client.screen.MapScreen(new RemoteScreen()));
        });
        for (int t = o + 220; t <= o + 700; t += 80) shot(t, "target-map");
        for (int t = o + 300; t <= o + MAP_FLIGHT_WAIT; t += 200) {
            at(t, () -> {
                Incoming.LOG.info("SCENARIO map-target terrain {}", ua.zentix.incoming.client.map.TerrainTiles.stats());
                logCoverage();
                if (net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) logDhThreads();
            });
        }
        // end - in 3 s after the hit (card frames after the hit) or by deadline
        for (int t = o + 220; t <= o + MAP_FLIGHT_WAIT; t += 20) {
            at(t, () -> {
                if (mapDone) return;
                if (mapImpactTick < 0 || tick < mapImpactTick + 60) {
                    if (tick < o + MAP_FLIGHT_WAIT) return;
                    Incoming.LOG.warn("SCENARIO map-target no impact by tick {} — FAIL", tick);
                }
                mapDone = true;
                Incoming.LOG.info("SCENARIO map-target terrain {}", ua.zentix.incoming.client.map.TerrainTiles.stats());
                logCoverage();
                Incoming.LOG.info("SCENARIO done");
                Minecraft.getInstance().stop();
            });
        }
    }

    /**
     * How much terrain around the player is known by rays and sound ({@code TerrainTiles.height}, as {@code FarTerrain} further chunks):
     * fraction of grid points 33×33 squared ±r - for comparing sources (LOD Server Support, Distant Horizons).
     */
    private void logCoverage() {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        StringBuilder b = new StringBuilder();
        for (int r : new int[] {256, 512, 1024, 2048, 4096, 8192}) {
            int known = 0, all = 0;
            for (int i = -16; i <= 16; i++) {
                for (int j = -16; j <= 16; j++) {
                    all++;
                    if (ua.zentix.incoming.client.map.TerrainTiles.height(net.minecraft.util.Mth.floor(p.getX()) + i * r / 16, net.minecraft.util.Mth.floor(p.getZ()) + j * r / 16).isPresent()) known++;
                }
            }
            b.append(String.format(java.util.Locale.ROOT, " ±%d %.0f%%", r, 100.0 * known / all));
        }
        Incoming.LOG.info("SCENARIO map-target coverage (rays, sound) at tick {}:{}", tick, b);
    }

    /** DH is not worth it or has already downloaded its LOD around: the download queue is empty. */
    private static boolean dhIdle() {
        if (!net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) return true;
        try {
            Object ex = Class.forName("com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil").getMethod("getRenderLoadingExecutor").invoke(null);
            return ex == null || (int) ex.getClass().getMethod("getQueueSize").invoke(ex) == 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }

    /**
     * Thread queues Distant Horizons (its internal classes, only for parsing in the script): reading the relief through API
     *  goes to the I/O pool DH ({@code ThreadPoolUtil.getFileHandlerExecutor}).
     */
    private static void logDhThreads() {
        try {
            Class<?> util = Class.forName("com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil");
            StringBuilder b = new StringBuilder();
            for (String getter : new String[] {"getFileHandlerExecutor", "getRenderLoadingExecutor", "getChunkToLodBuilderExecutor",
                    "getUpdatePropagatorExecutor", "getWorldGenExecutor"}) {
                Object ex = util.getMethod(getter).invoke(null);
                if (ex == null) continue;
                Class<?> c = ex.getClass();
                b.append(String.format(java.util.Locale.ROOT, "%s: queue %s, going %s, done %s; ", getter.substring(3),
                        c.getMethod("getQueueSize").invoke(ex), c.getMethod("getRunningTaskCount").invoke(ex), c.getMethod("getCompletedTaskCount").invoke(ex)));
            }
            java.lang.reflect.Field picker = util.getDeclaredField("taskPicker");
            picker.setAccessible(true);
            Object tp = picker.get(null);
            java.lang.reflect.Field occupied = tp.getClass().getDeclaredField("occupiedThreadsRef");
            occupied.setAccessible(true);
            b.append("busy threads ").append(occupied.get(tp));
            Incoming.LOG.info("SCENARIO map-target dh threads {}", b);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Incoming.LOG.info("SCENARIO map-target dh threads unread: {}", e.toString());
        }
    }

    /** The script target-map waits for DH to load its LOD, no longer than that (tick), and hits no longer than the second. */
    private static final int MAP_DH_WAIT = 6000, MAP_FLIGHT_WAIT = 2100;
    /** Weapon without a miss in spec: explosion no further than this from the selected point (horizontally), blocks; with a miss - {@link #allowedMiss}. */
    private static final double MAP_MAX_MISS = 2;

    /**
     * Tolerance for a miss on the map: {@link #MAP_MAX_MISS} and three standard deviations of a miss - uncontrolled scattering (MLRS) on
     * {@code range} blocks from the shooter and a launch miss from the spec (for Shahed σ 3 blocks on each axis: hitting 3–4
     * blocks from the point - its usual spread). MLRS dispersion - {@link WeaponSpec.Ballistic#sigma}.
     */
    private static double allowedMiss(@org.jetbrains.annotations.Nullable WeaponType weapon, double range) {
        if (weapon == null) return MAP_MAX_MISS;
        WeaponSpec.Ballistic b = weapon.spec().striker().ballistic();
        return MAP_MAX_MISS + 3 * Math.hypot(b == null ? 0 : b.sigma(range), weapon.spec().route().error());
    }
    /** Point selected on the map (scenario target-map); the server thread also reads it. */
    @org.jetbrains.annotations.Nullable
    private volatile Vec3 mapTarget;
    private volatile int mapImpactTick = -1;
    private int mapOpened = -1;
    private boolean mapDone;

    /**
     * Commands from {@code incoming.commands} (semicolon-separated, except semicolons in brackets or quotes —
     * {@link ScenarioCommands}) in the open world, to check modpack mods respond (e.g. a recording copy:
     * {@code /dh pregen status}, {@code /chunky}); replies go to chat, chat to the client log.
     * Besides commands: {@code wait:N} adds N ticks (0…{@value CommandPlan#MAX_WAIT}); {@code wait:nuke} makes later steps
     * relative to client receipt of a new detonation packet (once per scenario; server may lag the client clock, so a
     * "N ticks after launch" frame would precede detonation); {@code shot:name} takes a screenshot {@code name_tick.png};
     * {@code hud:off}/{@code hud:on} hide/restore the interface (like F1: command-reply chat does not obscure the frame,
     * but goes to the client log as before). Steps run sequentially: 40 ticks after a command ({@code incoming.commands.gap},
     * from 1: count entities once per second), 20 after a screenshot, 1 after {@code hud:} (screenshot takes the already
     * rendered frame; on the same tick it would still have the interface), with {@code wait:N} added to these
     * ({@code cmd;wait:1200;shot:x} captures 1240 ticks after the command). Invalid {@code wait:} is logged and skipped:
     * an exception here would stop mod loading and prevent "SCENARIO done".
     */
    private void planCommands() {
        CommandPlan plan = CommandPlan.parse(System.getProperty("incoming.commands", ""),
                Math.max(1, Integer.getInteger("incoming.commands.gap", 40)));
        for (String w : plan.warnings()) Incoming.LOG.warn("SCENARIO commands: {}", w);
        if (plan.nukeGate() >= 0) nukeGate = plan.nukeGate();
        blastGates.addAll(plan.blastGates());
        if (!blastGates.isEmpty()) {
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ExplosionEvent.Start e) -> {
                if (!e.getLevel().isClientSide()) serverBlasts.incrementAndGet();
            });
        }
        for (CommandPlan.Step step : plan.steps()) {
            switch (step.kind()) {
                case SHOT -> shot(step.tick(), step.arg());
                case HUD_OFF, HUD_ON -> {
                    boolean hide = step.kind() == CommandPlan.Kind.HUD_OFF;
                    at(step.tick(), () -> Minecraft.getInstance().options.hideGui = hide);
                }
                case COMMAND -> at(step.tick(), () -> cmd(step.arg()));
            }
        }
        at(plan.end(), () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Video from the side of the rocket (projectile camera) with the gunner on land, then with the gunner under water (in a glass pool):
     * The picture from the side should not depend on where the player is standing; then - in the morning, when the trailer shot was taken (target
     *  to the east). Frames onboard-dry_*, onboard-wet_*, onboard-dawn_*; in the log - the environment of the camera and the player
     *  and how many particles are in front of the lens. The world moves slowly (/tick rate 5): video at the target - just a couple of dozen ticks.
     */
    private void planOnboard() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0.5 150 0.5 0 20");
            // the mod command tree is in place: help comes in the chat (and in the client log)
            cmd("incoming help");
        });
        at(200, () -> {
            ua.zentix.incoming.IncomingConfig.SERVER.launchNearPlayer.set(true);
            ua.zentix.incoming.IncomingConfig.SERVER.missileFlightTime.set(15);
            cmd("fill -3 149 -3 3 149 3 minecraft:smooth_stone");
            cmd("fill 37 145 -3 43 152 3 minecraft:glass");
            cmd("fill 38 146 -2 42 152 2 minecraft:water");
            cmd("tp @s 0.5 150 0.5 0 20");
            onboard = "dry";
        });
    }

    private void onboardEvents() {
        Minecraft mc = Minecraft.getInstance();
        if (onboardFired < 0) {
            if (tick < 240 || tick % 20 != 0) return;
            boolean dawn = "dawn".equals(onboard);
            double x = mc.player.getX() + (dawn ? 90 : 0), z = mc.player.getZ() + (dawn ? 0 : 90);
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
            cmd(String.format(java.util.Locale.ROOT, "incoming missile at %.1f %d %.1f", x, y, z));
            cmd("tick rate 5");
            onboardFired = tick;
            onboardFrames = 0;
            return;
        }
        if (!ua.zentix.incoming.client.cam.ProjectileCamera.isActive()) {
            if (tick - onboardFired < 200 && tick % 5 == 0) ua.zentix.incoming.client.cam.ProjectileCamera.cycle();
        }
        if (ua.zentix.incoming.client.cam.ProjectileCamera.isViewing() && tick % 2 == 0) {
            var camera = mc.gameRenderer.getMainCamera();
            Incoming.LOG.info("SCENARIO onboard {} frame={} camera={} fluid={} player={} underwater={} eyeLight={} lens={}", onboard, onboardFrames,
                    xyz(camera.getPosition()), camera.getFluidInCamera(), xyz(mc.player.position()), mc.player.isUnderWater(),
                    mc.level.getMaxLocalRawBrightness(mc.getCameraEntity().blockPosition()), lensMax);
            lensMax = 0;
            Screenshot.grab(mc.gameDirectory, String.format("onboard-%s_%04d.png", onboard, tick), mc.getMainRenderTarget(), c -> {});
            onboardFrames++;
        }
        boolean over = tick - onboardFired > 60 && ua.zentix.incoming.client.hud.ClientFlights.all().isEmpty()
                && !ua.zentix.incoming.client.cam.ProjectileCamera.isActive();
        if (!over && tick - onboardFired < 1500) return;
        Incoming.LOG.info("SCENARIO onboard {} done: {} frames", onboard, onboardFrames);
        cmd("tick rate 20");
        if ("dry".equals(onboard)) {
            onboard = "wet";
            onboardFired = -1;
            cmd("tp @s 40.5 146 0.5 0 20");
        } else if ("wet".equals(onboard)) {
            onboard = "dawn";
            onboardFired = -1;
            cmd("time set 1000");
            cmd("tp @s 0.5 150 0.5 0 20");
        } else {
            onboard = null;
            Incoming.LOG.info("SCENARIO done");
            mc.stop();
        }
    }

    /**
     * MLRS: salvo from 12 over an area of 12 blocks in 200 blocks ahead. First, the projectile chamber - the package has a plan for the entire
     *  queue; the second salvo - through the eyes of the shooter: arcs of smoky traces above the head and explosions at the target.
     */
    private void planRocket() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            cmd("forceload add -32 -48 32 240");
        });
        at(200, () -> {
            ua.zentix.incoming.IncomingConfig.SERVER.launchNearPlayer.set(true);
            cmd("fill -30 199 -40 30 199 230 minecraft:smooth_stone");
            cmd("fill -3 200 197 3 203 203 minecraft:oak_planks");
            cmd("tp @s 0.5 200 0.5 0 5");
            target = new Vec3(0.5, 204, 200.5);
        });
        at(230, () -> cmd(String.format(java.util.Locale.ROOT, "incoming salvo rocket 12 12 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        // the server running llvmpipe is lagging behind: the projectile may not be on the list yet - try again
        for (int t = 236; t <= 266; t += 10) {
            at(t, () -> {
                if (!ua.zentix.incoming.client.cam.ProjectileCamera.isActive()) ua.zentix.incoming.client.cam.ProjectileCamera.cycle();
            });
        }
        for (int t = 240; t <= 640; t += 6) shot(t, "rocket_cam");
        at(650, () -> {
            ua.zentix.incoming.client.cam.ProjectileCamera.exit();
            // we stand to the side of the line of fire, look at the target: you can see the packet, the arcs, and the gaps
            cmd("tp @s 24.5 200 50.5 12 -14");
        });
        at(1500, () -> cmd(String.format(java.util.Locale.ROOT, "incoming salvo rocket 12 12 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int t = 1510; t <= 1900; t += 8) shot(t, "rocket_view");
        // the third salvo is on the side of the launcher: the launch cloud (products while the jet hits the ground, and raised dust) and the beginning of the tracks
        at(1905, () -> {
            var mc = Minecraft.getInstance();
            var near = mc.level.getEntitiesOfClass(ua.zentix.incoming.entity.LauncherEntity.class, mc.player.getBoundingBox().inflate(200));
            Vec3 pad = near.isEmpty() ? new Vec3(13, 204, -19) : near.getFirst().position();
            Incoming.LOG.info("SCENARIO rocket pad at {}", xyz(pad));
            cmd(String.format(java.util.Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", pad.x - 32, pad.y + 6, pad.z - 14, pad.x, pad.y + 4, pad.z));
        });
        at(1925, () -> cmd(String.format(java.util.Locale.ROOT, "incoming salvo rocket 12 12 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int t = 1935; t <= 2335; t += 10) shot(t, "rocket_pad");
        at(2345, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Loitering: a swarm of three from a catapult at a target in 150 blocks (barrage 8 s). Camera one: catapult,
     *  approach, circle - the “operator” looks at the target; then from the side: circles over the target and dive in turn.
     */
    private void planLoiter() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("tp @s 0 200 0 0 0");
            cmd("forceload add -48 -48 48 240");
        });
        at(200, () -> {
            ua.zentix.incoming.IncomingConfig.SERVER.launchNearPlayer.set(true);
            ua.zentix.incoming.IncomingConfig.SERVER.loiterTime.set(8);
            cmd("fill -46 199 -40 46 199 230 minecraft:smooth_stone");
            cmd("fill -3 200 147 3 203 153 minecraft:oak_planks");
            cmd("tp @s 0.5 200 0.5 0 5");
            target = new Vec3(0.5, 204, 150.5);
        });
        at(230, () -> cmd(String.format(java.util.Locale.ROOT, "incoming salvo loiter 3 6 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int t = 236; t <= 266; t += 10) {
            at(t, () -> {
                if (!ua.zentix.incoming.client.cam.ProjectileCamera.isActive()) ua.zentix.incoming.client.cam.ProjectileCamera.cycle();
            });
        }
        for (int t = 240; t <= 700; t += 6) {
            at(t, () -> {
                // the operator on the circle looks at the target
                Minecraft mc = Minecraft.getInstance();
                if (mc.getCameraEntity() instanceof StrikeProjectile p && p.flightPhase() == ua.zentix.incoming.entity.FlightPhase.LOITER) {
                    Vec3 d = target.subtract(mc.gameRenderer.getMainCamera().getPosition());
                    mc.player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    mc.player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                }
            });
            shot(t, "loiter_cam");
        }
        at(710, () -> {
            ua.zentix.incoming.client.cam.ProjectileCamera.exit();
            cmd("tp @s 40.5 200 95.5 38 -32");
        });
        at(720, () -> cmd(String.format(java.util.Locale.ROOT, "incoming salvo loiter 3 6 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int t = 730; t <= 1400; t += 8) shot(t, "loiter_view");
        at(1410, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    /**
     * Effects behind the wall: the viewer in 40 blocks in front of the stone wall 60×14, behind it in 90 blocks there are large salvoes of MLRS
     *  and missiles. You can see smoke above the wall, nothing behind the wall. Then the camera moves its gaze left and right: the billows at the edge of the
     *  frame should not disappear. In the log - how many particles are in the engine (by layers).
     */
    private void planOcclusion() {
        at(40, () -> {
            cmd("time set 6000");
            cmd("weather clear");
            cmd("forceload add -64 -16 64 256");
            Minecraft.getInstance().options.hideGui = true;
            Minecraft.getInstance().player.getAbilities().flying = true;
        });
        // platform higher than any terrain (fill - no more than 32768 blocks at a time): viewer and target on the same plane
        at(200, () -> {
            cmd("fill -60 259 0 60 259 250 minecraft:smooth_stone");
            cmd("fill -30 260 80 30 273 81 minecraft:stone_bricks");
            cmd("tp @s 0.5 261 40.5 0 -8");
            target = new Vec3(0.5, 260, 170.5);
        });
        at(210, this::occlusionWhenReady);
    }

    /** The server under llvmpipe lags behind: volleys - when the viewer is already on the site and the wall is built. */
    private void occlusionWhenReady() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player.getY() < 259 || !mc.level.getBlockState(new net.minecraft.core.BlockPos(0, 265, 80)).is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)) {
            at(tick + 5, this::occlusionWhenReady);
            return;
        }
        int t0 = tick;
        Incoming.LOG.info("SCENARIO occlusion ready at tick {}", t0);
        at(t0 + 1, () -> cmd(String.format(java.util.Locale.ROOT, "incoming salvo rocket 40 20 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        at(t0 + 30, () -> cmd(String.format(java.util.Locale.ROOT, "incoming salvo missile 6 16 at %.1f %.1f %.1f", target.x, target.y, target.z)));
        for (int dt = 10; dt <= 1100; dt += 6) {
            int t = t0 + dt, d = dt;
            at(t, () -> {
                // with 550-th tick after the volley - we look at: ±35° for 3 s
                if (d >= 550) {
                    float yaw = (float) (35 * Math.sin((d - 550) / 60.0 * Math.PI));
                    cmd(String.format(java.util.Locale.ROOT, "tp @s 0.5 261 40.5 %.1f -8", yaw));
                }
                Incoming.LOG.info("SCENARIO particles t={} {}", d, particleLayers());
            });
            shot(t, "occlusion");
        }
        at(t0 + 1110, () -> {
            Incoming.LOG.info("SCENARIO done");
            Minecraft.getInstance().stop();
        });
    }

    private void onRenderLevel(net.neoforged.neoforge.client.event.RenderLevelStageEvent e) {
        if (onboard != null && e.getStage() == net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES
                && ua.zentix.incoming.client.cam.ProjectileCamera.isViewing()) {
            lensMax = Math.max(lensMax, particlesAtLens(e.getCamera()));
        }
    }

    /** Effect particles in front of the lens: closer to 12 blocks and in a cone 40° around the view - these cover the entire frame. */
    private static int particlesAtLens(net.minecraft.client.Camera camera) {
        var eye = camera.getPosition();
        var look = camera.getLookVector();
        return ua.zentix.incoming.client.fx.particle.FxPool.INSTANCE.near(eye.x, eye.y, eye.z, look.x(), look.y(), look.z(), 12, 0.766);
    }

    /**
     * Particles: vanilla by engine layer, effect particles by pool group; {@code cloud=live/slots, denied N}
     * (number of particles denied a slot in the group since startup).
     */
    private static String particleLayers() {
        StringBuilder sb = new StringBuilder("vanilla ").append(Minecraft.getInstance().particleEngine.countParticles()).append(';');
        var pool = ua.zentix.incoming.client.fx.particle.FxPool.INSTANCE;
        for (var b : ua.zentix.incoming.client.fx.particle.FxBudget.values()) {
            sb.append(' ').append(b.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(pool.live(b)).append('/').append(b.limit())
                    .append(", denied ").append(pool.refused(b)).append(';');
        }
        return sb.toString();
    }

    /** launch at a point on the ground ahead and frames every 10 tick while it flies and burns. */
    private void strike(int start, String weapon, int frames) {
        at(start, () -> {
            cmd(String.format(java.util.Locale.ROOT, "incoming %s at %.1f %.1f %.1f", weapon, target.x, target.y, target.z));
        });
        for (int t = start + 10; t <= start + frames; t += 10) shot(t, weapon);
    }

    private void aimAhead(double distance) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        double x = Math.floor(p.getX()) + 0.5, z = Math.floor(p.getZ() + distance) + 0.5;
        int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
        if (y <= mc.level.getMinBuildHeight()) {
            Incoming.LOG.warn("SCENARIO target chunk has not yet been loaded from the client");
            y = 64;
        }
        target = new Vec3(x, y, z);
    }

    /**
     * {@code wait:nuke}: until a packet of a new detonation has arrived to the client, all steps after this tick are shifted to tick.
     *  There is no detonation after {@value CommandPlan#MAX_WAIT} tick - a line in the log, and the steps go further.
     */
    private void holdForNuke() {
        var known = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations();
        if (tick == nukeGate) known.forEach(a -> nukeGateSeen.add(a.d.id()));
        boolean arrived = known.stream().anyMatch(a -> !nukeGateSeen.contains(a.d.id()));
        if (arrived || nukeGateWaited >= CommandPlan.MAX_WAIT) {
            if (arrived) Incoming.LOG.info("SCENARIO commands: the explosion came, the steps were waiting {} tick", nukeGateWaited);
            else Incoming.LOG.warn("SCENARIO commands: no detonation for {} tick - steps go further", nukeGateWaited);
            nukeGate = -1;
            return;
        }
        nukeGateWaited++;
        steps.replaceAll(s -> s.at >= tick ? new Step(s.at + 1, s.action) : s);
    }

    /**
     * {@code wait:blast}: Until a new explosion has started on the embedded server, all steps after this tick (and the following
     *  waiting points) are shifted by tick; the light of the explosion reaches the client with the next package. There is no explosion for
     * {@link #BLAST_WAIT} tick - a line in the log, and the steps move on.
     */
    private void holdForBlast() {
        int now = serverBlasts.get();
        if (blastGateSeen < 0) blastGateSeen = now;
        boolean arrived = now > blastGateSeen;
        if (arrived || blastGateWaited >= BLAST_WAIT) {
            if (arrived) Incoming.LOG.info("SCENARIO commands: explosion, steps were waiting {} tick", blastGateWaited);
            else Incoming.LOG.warn("SCENARIO commands: no explosion after {} tick - steps go further", blastGateWaited);
            blastGates.removeFirst();
            blastGateSeen = -1;
            blastGateWaited = 0;
            return;
        }
        blastGateWaited++;
        steps.replaceAll(s -> s.at >= tick ? new Step(s.at + 1, s.action) : s);
        int first = blastGates.removeFirst();
        java.util.List<Integer> rest = new ArrayList<>(blastGates);
        blastGates.clear();
        blastGates.add(first);
        for (int g : rest) blastGates.add(g + 1);
    }

    /** Longest wait for an explosion ({@code wait:blast}): Shahed's flight at 5 km, tick. */
    private static final int BLAST_WAIT = 3600;

    private void at(int t, Runnable r) {
        steps.add(new Step(t, r));
    }

    private void shot(int t, String name) {
        at(t, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (name.equals("nuke") || name.equals("fallout")) {
                var list = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations();
                Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
                String rain = list.isEmpty() ? "-" : String.format(java.util.Locale.ROOT, "trail=%b sky=%b",
                        list.getLast().d.blackRain(cam.x, cam.z, mc.level.getGameTime() - list.getLast().d.gameTime()),
                        ua.zentix.incoming.nuclear.Detonation.underOpenSky(mc.level, cam));
                Incoming.LOG.info("SCENARIO {} white={} rain={} {} fps={}", name, ua.zentix.incoming.client.nuclear.NukeFlash.whiteness(0),
                        ua.zentix.incoming.client.nuclear.NukeSky.blackRain(), rain, mc.getFps());
            }
            Screenshot.grab(mc.gameDirectory, String.format("%s_%04d.png", name, tick), mc.getMainRenderTarget(), c -> {});
        });
    }

    /** Turn your gaze to B-2 (by location on the server: in the world or outside it). */
    private static void lookAtBomber() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            java.util.List<StrikeProjectile> all = new ArrayList<>(level.getEntitiesOfClass(ua.zentix.incoming.entity.BomberEntity.class,
                    new net.minecraft.world.phys.AABB(-30_000_000, -1000, -30_000_000, 30_000_000, 40_000, 30_000_000)));
            for (StrikeProjectile p : ua.zentix.incoming.strike.VirtualFlights.get(level).flights()) {
                if (p instanceof ua.zentix.incoming.entity.BomberEntity) all.add(p);
            }
            if (all.isEmpty()) return;
            Vec3 at = all.getFirst().position();
            Minecraft.getInstance().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, at);
                Incoming.LOG.info("SCENARIO look B-2 at {} ({} blocks)", BlockPos.containing(at).toShortString(),
                        Math.round(at.distanceTo(mc.player.getEyePosition())));
            });
        });
    }

    /** Projectiles on the server: in the world and outside it, where, at what speed, whether their chunk is ticking. */
    private static void dumpFlights() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            java.util.List<StrikeProjectile> all = new ArrayList<>(level.getEntitiesOfClass(StrikeProjectile.class,
                    new net.minecraft.world.phys.AABB(-30_000_000, -1000, -30_000_000, 30_000_000, 40_000, 30_000_000)));
            all.addAll(ua.zentix.incoming.strike.VirtualFlights.get(level).flights());
            for (StrikeProjectile p : all) {
                var bp = p.blockPosition();
                Incoming.LOG.info("SCENARIO dump {} virtual={} phase={} pos={} speed={} age={} ticking={} loaded={}",
                        p.weapon(), p.isVirtual(), p.flightPhase(), bp.toShortString(), String.format(java.util.Locale.ROOT, "%.2f", p.speed()), p.age(),
                        level.isPositionEntityTicking(bp), level.getChunkSource().getChunkNow(bp.getX() >> 4, bp.getZ() >> 4) != null);
            }
        });
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Incoming.LOG.info("SCENARIO /{}", c);
    }

    /** Flights according to server data: weapon, phase, time before impact - they show that the launch and flight are going according to plan. */
    private static void logFlights() {
        StringBuilder sb = new StringBuilder();
        for (var f : ua.zentix.incoming.client.hud.ClientFlights.all()) {
            sb.append(String.format(java.util.Locale.ROOT, " %s#%d %s eta=%.0fs", f.weapon().getSerializedName(), f.number,
                    f.phase().getSerializedName(), f.etaSeconds(0)));
            if (f.entity() != null) sb.append(" at ").append(xyz(f.entity().position()));
            sb.append(';');
        }
        var cam = Minecraft.getInstance().getCameraEntity();
        Incoming.LOG.info("SCENARIO flights{} camera={} filming={} eye={}", sb, ua.zentix.incoming.client.cam.ProjectileCamera.isViewing(),
                ua.zentix.incoming.client.cam.ProjectileCamera.isFilming(), cam == null ? "-" : xyz(cam.position()));
    }

    private static String xyz(Vec3 v) {
        return String.format(java.util.Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
    }

    /** What is flying now and how loud/what tone it sounds (to check Doppler and log delay). */
    private void logSound() {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder sb = new StringBuilder();
        for (var e : mc.level.entitiesForRendering()) {
            if (e instanceof StrikeProjectile p) {
                sb.append(String.format(" %s d=%.0f v=%.1f ph=%s;", p.getType().toShortString(), p.distanceTo(mc.player), p.speed(), p.flightPhase().getSerializedName()));
            }
        }
        Incoming.LOG.info("SCENARIO t={} [{}] flying:{} engines:{}", tick, mc.getSoundManager().getDebugString(), sb, ClientSounds.describe());
    }
}
