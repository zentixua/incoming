package ua.zentix.incoming.scenario;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.IncomingClient;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.net.ClientHooks;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Effects sandbox (client scenario {@code sandbox}, tools/sandbox.sh): a client with only the mod (optionally Iris and
 * the host's shaderpack) in a flat world; an effect — blast, launch, flight, fire — is played by a command in seconds,
 * without flying across half the map; frames at set seconds from its start, and sound, are written to {@code
 * fx/out/<name>/}. Commands — {@link SandboxCommand}: in chat {@code /fx …} (a client command, present only in this
 * scenario) or as files {@code fx/inbox/*.cmd} from a script (the result — next to it, {@code *.done}). The client does
 * not exit by itself: code changes by hot swap (agent {@code ua.zentix.hotswap.HotswapAgent}), resources — {@code fx
 * reload}.
 * <p>
 * The world is flat (bedrock, 124 stone, 3 dirt, grass; top of the grass — {@link #GROUND}), plains without buildings,
 * eternal noon without weather or mobs: the same on every run, and the site of a new real blast ({@code real}, {@code
 * nuke}) — the same ground as the previous one.
 * <p>
 * The nuke near the camera ({@code nuke <kt> [air|ground] near [m]}) — what the {@code debris} scenario
 * (ClientScenario) films, in seconds: houses and oaks of the same layout at a new site, detonation {@code m} north,
 * effect timing — from the front's arrival at the houses. The wave is the same as in client scenarios (1.0 speed of
 * sound, {@code TestScale}): at 15 kt the front reaches houses 2 km away in ~4 s of game time. Differences from the
 * scenario: oaks — even crowns of blocks, not of the random shape of {@code place feature}; ground — flat grass, not a
 * generator plain. The site at each run is new, but the spread of block thresholds ({@code BlockResponse.jitterAt}, the
 * site's hash) there is the same ({@link #nearSite}), the detonation number is the same ({@link #NEAR_ID}: the
 * scenario's detonation seed — by it), and the game time at the moment of detonation is the same ({@link
 * #NEAR_GAME_TIME}: the world's wind — by it, {@code Wind.at}) — the same blocks break in the same wind; other
 * randomness of ruins and trails by world site (trail noise, debris sampling) is per site, as for two scenario runs on
 * different maps.
 */
final class Sandbox {
    static final String WORLD = "incoming_sandbox";
    /** Top of the grass of the flat world: 1 + 124 + 3 + 1 layers from −64. */
    static final int GROUND = 64;
    /**
     * A new site of a real blast — beyond draw distance from the previous one (fires and the crater are not in frame).
     */
    static final int REAL_STEP = 320, NUKE_STEP = 6000;
    /** Burst of an air-defence missile ({@code airburst}) — this many blocks above the ground at the point. */
    static final double AIRBURST_HEIGHT = 30;
    /** Launch and flyby: wait for the projectile no longer than this many ticks. */
    static final int FLIGHT_LIMIT = 1800;
    /**
     * {@code sam}: the Shahed comes from this many blocks north of the SAM and aims this far south (as the {@code
     * sam-side} probe scene).
     */
    static final int SAM_FROM = 1400, SAM_PAST = 250;
    /**
     * The nuke near the camera: the site of the k-th run — (X0 + k·DX, Z0 + k·DZ). The site hash {@code
     * Long.hashCode(BlockPos.asLong())} — the high 32 bits (x << 6 and up) XOR the low bits (z << 12 | y): shifting x
     * by k·2¹⁶ and z by k·2¹⁰ puts k into both words at the same spot (bit 22), and XOR cancels it: for a house with x
     * in [X0 − 2¹⁵, X0 + 2¹⁵) and z in [Z0 − 2⁹, Z0 + 2⁹) the hash, and with it the spread of thresholds, is the same
     * at any site. k up to 450: x no farther than the world edge (3·10⁷).
     */
    static final int NEAR_X0 = 1 << 15, NEAR_Z0 = 1 << 9, NEAR_DX = 1 << 16, NEAR_DZ = 1 << 10, NEAR_RUNS = 450;
    /**
     * Detonation number near the camera: the scenario's detonation seed — by number ({@code NuclearWarhead}), the same
     * for every run.
     */
    static final int NEAR_ID = 9000;
    /**
     * Game time at the moment of detonation near the camera: the world's wind — a function of game time ({@code
     * Wind.at}), the same for every run.
     */
    static final long NEAR_GAME_TIME = 6000;

    private final Path dir = FMLPaths.GAMEDIR.get().resolve("fx");
    private final Path inbox = dir.resolve("inbox");
    private boolean started, ready;
    private int tick = -1;
    /** Point of effects that do not touch the ground (on the ground), and the current effect's point. */
    private static final Vec3 HOME = new Vec3(0.5, GROUND + 1, 0.5);
    private Vec3 site = HOME;
    /**
     * Real blasts and detonations — each on untouched ground: this many blocks toward +X from {@link #HOME} are already
     * taken.
     */
    private int freshX;
    /** Camera: distance, height above ground, bearing from the point, height of the look point. */
    private double camDist = 60, camHeight = 10, camBearing = 90, camLook = 8;
    private boolean overlay;
    /** Zoom ×k ({@code zoom}): 1 — none. */
    private double zoom = 1;
    /** Look follows the effect's projectile ({@code track}). */
    private boolean track;
    /**
     * {@code turn <deg/s> [from s]}: from that effect second the view turns at this rate, by the effect clock (the same
     * yaw in every run at the same effect instant); 0 — none. Effects and {@code clear} keep it; the start yaw is the
     * view's at the effect's first frame.
     */
    private double turn, turnFrom;
    private float turnYaw = Float.NaN;
    @Nullable
    private SandboxCommand lastEffect;
    /** Command from a file that is still running, and its result file. */
    @Nullable
    private Path running;
    /** Tick when a command without an effect ends by itself ({@code wait}); −1 — none. */
    private int waitUntil = -1;
    /**
     * Time of day (0–23999) that the {@code time} command waits for at the client; −1 — none; from which tick it waits.
     */
    private long timeWait = -1;
    private int timeSince;
    /** Effect waits to start: what to wait for (launch, flyby, server) and from which tick. */
    @Nullable
    private Pending pending;
    /** Tick the effect started (−1 — none) and a snapshot. */
    private int effectTick = -1;
    @Nullable
    private Capture armed, capture;

    private enum Pending { SERVER, LAUNCH, FLIGHT, SITE, NUKE, SAM, POND }

    private int pendingSince;
    private SandboxCommand pendingCmd;
    /** The server ran the effect's own task (blast, detonation, fire) — not the camera's or other commands'. */
    private volatile boolean serverDone;
    /**
     * Resources reload ({@code reload}): the next command runs on the new ones. Done — when loading has ended, the
     * loading screen is gone and pause lifted: during a reload the single-player game is paused, and the loading screen
     * fades out ~1 s after it (an effect started at that time would stall while the sandbox clock keeps running).
     */
    private boolean reloading;
    private volatile boolean reloaded;
    private long reloadStart;
    @Nullable
    private volatile String serverError;
    /** Launch and flyby: the projectile at the client, where it was seen, closest to the point. */
    @Nullable
    /**
     * Path of the effect's projectile: by entity nearby and by server packets far away, where the client has no entity.
     */
    private FlightTrack projectile;
    /**
     * Nuke: detonations the client knew before the command. The start is the packet of a new detonation at the client,
     * not a server task: the detonation site, whose ground is still loading, the server defers ({@code
     * NuclearStrikes.detonateNow}).
     */
    private final java.util.Set<Integer> knownNukes = new java.util.HashSet<>();
    private Vec3 firstSeen = Vec3.ZERO;
    private double closest = Double.MAX_VALUE;
    /** Nuke near the camera: how many runs there were (site — {@link #nearSite}). */
    private int nearRuns;

    /** Effect snapshot: frames at the seconds {@code times}, sound {@code wavSeconds}. */
    private static final class Capture {
        final String name;
        final double[] times;
        final double wavSeconds;
        final Path out;
        int next;
        long audioStart = -1;
        /** Tick sound starts: the first frame, if it is before the effect's start, otherwise the effect's start. */
        int audioTick;
        final List<String> frames = new ArrayList<>();

        Capture(String name, double[] times, double wavSeconds, Path out) {
            this.name = name;
            this.times = times;
            this.wavSeconds = wavSeconds;
            this.out = out;
        }
    }

    Sandbox() {
        NeoForge.EVENT_BUS.addListener(this::onScreen);
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onFrame);
        NeoForge.EVENT_BUS.addListener(this::onCommands);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onFov);
        NeoForge.EVENT_BUS.addListener(this::onFrameStart);
        try {
            Files.createDirectories(inbox);
            // commands of a previous run — not for this client
            try (Stream<Path> old = Files.list(inbox)) {
                old.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir.resolve("ready"));
        } catch (IOException e) {
            Incoming.LOG.error("SANDBOX: no inbox", e);
        }
    }

    // ---------------------------------------------------------------- world

    private void onScreen(ScreenEvent.Init.Post e) {
        // before the world — which screen is open: a client without a window that stopped on someone else's screen,
        // otherwise it would be impossible to tell
        if (!ready) Incoming.LOG.info("SANDBOX screen {}", e.getScreen().getClass().getName());
        if (started || !(e.getScreen() instanceof TitleScreen)) return;
        started = true;
        createWorld(e.getScreen());
    }

    /** Flat world anew: every run is the same (the previous one is deleted). */
    private static void createWorld(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        Path old = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.isDirectory(old)) {
            try (Stream<Path> walk = Files.walk(old)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ex) {
                Incoming.LOG.warn("SANDBOX: old world not deleted", ex);
            }
        }
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261022L, false, false), registries -> {
            var biomes = registries.lookupOrThrow(Registries.BIOME);
            FlatLevelGeneratorSettings flat = FlatLevelGeneratorSettings.getDefault(biomes, registries.lookupOrThrow(Registries.STRUCTURE_SET),
                    registries.lookupOrThrow(Registries.PLACED_FEATURE)).withBiomeAndLayers(List.of(new FlatLayerInfo(1, Blocks.BEDROCK),
                    new FlatLayerInfo(124, Blocks.STONE), new FlatLayerInfo(3, Blocks.DIRT), new FlatLayerInfo(1, Blocks.GRASS_BLOCK)),
                    Optional.empty(), biomes.getOrThrow(Biomes.PLAINS));
            WorldDimensions dims = WorldPresets.createNormalWorldDimensions(registries);
            return dims.replaceOverworldGenerator(registries, new FlatLevelSource(flat));
        }, parent);
    }

    // ---------------------------------------------------------------- tick

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (reloading && reloaded && mc.getOverlay() == null && !mc.isPaused()) {
            reloading = false;
            finish(String.format(Locale.ROOT, "ok reloaded in %.1f s", (System.nanoTime() - reloadStart) / 1e9));
        }
        // effect clocks are world ticks: paused (resource reload, menu), effects stand still and frames wait
        if (mc.isPaused()) return;
        tick++;
        if (!ready) {
            if (tick == 1) setup();
            // ground under the point and the camera at the client
            if (tick < 20 || !mc.level.getChunkSource().hasChunk(BlockPos.containing(site).getX() >> 4, BlockPos.containing(site).getZ() >> 4)) return;
            ready = true;
            double up = (System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime()) / 1000.0;
            Incoming.LOG.info("SANDBOX ready {} s after JVM start", String.format(Locale.ROOT, "%.1f", up));
            write(dir.resolve("ready"), String.format(Locale.ROOT, "%.1f", up));
        }
        try {
            raidNext(mc);
            advance(mc);
            if (!busy()) nextFromInbox();
        } catch (RuntimeException ex) {
            Incoming.LOG.error("SANDBOX failed", ex);
            finish("error " + ex);
        }
    }

    private void setup() {
        String me = Minecraft.getInstance().player.getGameProfile().getName();
        server("gamemode spectator " + me);
        server("time set 6000");
        server("weather clear 1000000");
        server("gamerule doFireTick true");
        Minecraft.getInstance().options.hideGui = true;
        placeCamera();
    }

    private void nextFromInbox() {
        Path cmd;
        try (Stream<Path> files = Files.list(inbox)) {
            cmd = files.filter(p -> p.getFileName().toString().endsWith(".cmd")).sorted().findFirst().orElse(null);
        } catch (IOException e) {
            return;
        }
        if (cmd == null) return;
        String line;
        try {
            line = Files.readString(cmd, StandardCharsets.UTF_8);
            Files.delete(cmd);
        } catch (IOException e) {
            return;
        }
        String name = cmd.getFileName().toString();
        running = cmd.resolveSibling(name.substring(0, name.length() - ".cmd".length()) + ".done");
        Incoming.LOG.info("SANDBOX < {}", line.strip());
        try {
            run(SandboxCommand.parse(line));
        } catch (IllegalArgumentException ex) {
            finish("error " + ex.getMessage());
        }
    }

    /** A command is running (from a file or chat): waiting, the effect and its frames, reload — the next one waits. */
    private boolean busy() {
        return running != null || pending != null || reloading || waitUntil >= 0 || timeWait >= 0 || capture != null;
    }

    /** Result of a command from a file (for a command from chat — a line in chat). */
    private void finish(String result) {
        Incoming.LOG.info("SANDBOX > {}", result);
        if (running != null) write(running, result);
        else if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Component.literal("fx: " + result), false);
        running = null;
    }

    // ---------------------------------------------------------------- commands

    private void onCommands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("fx").then(Commands.argument("line", StringArgumentType.greedyString()).executes(ctx -> {
            String line = StringArgumentType.getString(ctx, "line");
            if (busy()) {
                ctx.getSource().sendFailure(Component.literal("fx: busy"));
                return 0;
            }
            try {
                run(SandboxCommand.parse(line));
            } catch (IllegalArgumentException ex) {
                ctx.getSource().sendFailure(Component.literal("fx: " + ex.getMessage()));
                return 0;
            }
            return 1;
        })));
    }

    /**
     * Zoom ×k: the world's field of view is k times narrower by the tangent of the half-angle, last of all who change
     * it — as Ok Zoomer changes it at the output of {@code getFov} (the world frame and its matrix — with zoom).
     */
    private void onFov(ViewportEvent.ComputeFov e) {
        if (zoom <= 1 || !e.usedConfiguredFov()) return;
        e.setFOV(2 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(e.getFOV() / 2)) / zoom)));
    }

    private void run(SandboxCommand c) {
        Minecraft mc = Minecraft.getInstance();
        if (c.effect()) {
            lastEffect = c;
            startEffect(c);
            return;
        }
        switch (c.verb()) {
            case CAM -> {
                camDist = c.number(0);
                camHeight = c.number(1, camHeight);
                camBearing = c.number(2, camBearing);
                camLook = c.number(3, camLook);
                placeCamera();
                finish("ok");
            }
            case STANCE -> {
                boolean stand = c.args().getFirst().equals("stand");
                // Only the player in their own dev sandbox: in spectator they fly, and the body's leg sway is not
                // visible.
                var id = mc.player.getUUID();
                onServer(false, s -> {
                    var p = s.getPlayerList().getPlayer(id);
                    if (p == null) return;
                    p.setGameMode(stand ? GameType.CREATIVE : GameType.SPECTATOR);
                    p.getAbilities().flying = !stand;
                    p.onUpdateAbilities();
                });
                finish("ok");
            }
            case WEATHER -> {
                boolean rain = c.args().getFirst().equals("rain");
                onServer(false, s -> s.overworld().setWeatherParameters(rain ? 0 : 6000, rain ? 6000 : 0, rain, false));
                finish("ok");
            }
            case TIME -> {
                String t = c.args().getFirst();
                long day = t.equals("day") ? 6000 : t.equals("night") ? 18000 : (long) c.number(0);
                // the server sends the time to players every 20 ticks: the packet — at once, and the command ends when
                // the time has reached
                // the client — otherwise the effect after it ran its first second in the previous light (frames at 0.1
                // and 0.5 s of “night” were by day)
                onServer(false, s -> {
                    run(s, "time set " + day);
                    var level = s.overworld();
                    s.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(level.getGameTime(),
                            level.getDayTime(), level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
                });
                timeWait = Math.floorMod(day, 24000L);
                timeSince = tick;
            }
            case SCENERY -> {
                // Deterministic surfaces for the same dust-source comparison on both versions. Only this test world.
                BlockPos s = BlockPos.containing(HOME);
                for (int y = GROUND + 1; y <= GROUND + 9; y += 3) {
                    server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", s.getX() - 48, y, s.getZ() - 48,
                            s.getX() + 48, y + 2, s.getZ() + 48));
                }
                String kind = c.args().getFirst();
                server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:%s", s.getX() - 48, GROUND, s.getZ() - 48,
                        s.getX() + 48, GROUND, s.getZ() + 48, kind.equals("paved") ? "stone" : "grass_block"));
                if (kind.equals("roof")) {
                    // room 21×21: a box of stone brick (in hollow both floor and ceiling are stone), the ceiling
                    // replaced
                    // by a metre of planks at 5 m height (top — 6 m above the grass), under it — a 3 m void: strike
                    // blast <weapon> 1 5 — into
                    // the roof over the void
                    int x0 = s.getX() - 10, z0 = s.getZ() - 10, x1 = s.getX() + 10, z1 = s.getZ() + 10;
                    server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks hollow", x0, GROUND + 1, z0, x1, GROUND + 5, z1));
                    server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_planks", x0, GROUND + 5, z0, x1, GROUND + 5, z1));
                }
                if (kind.equals("forest")) {
                    for (int x = -40; x <= 40; x += 8) for (int z = -40; z <= 40; z += 8) {
                        if (x * x + z * z < 64) continue;
                        int tx = s.getX() + x, tz = s.getZ() + z;
                        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_log", tx, GROUND + 1, tz, tx, GROUND + 5, tz));
                        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_leaves[persistent=true]", tx - 3, GROUND + 4, tz - 3,
                                tx + 3, GROUND + 6, tz + 3));
                        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:oak_leaves[persistent=true]", tx - 2, GROUND + 7, tz - 2,
                                tx + 2, GROUND + 7, tz + 2));
                    }
                }
                finish("ok");
            }
            case HOUSE -> {
                // the previous house — gone, the new one — solid stone brick from the grass to the height (the roof is
                // on it)
                BlockPos s = BlockPos.containing(HOME);
                if (house[0] > 0) {
                    server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", s.getX() - house[1], GROUND + 1, s.getZ() - house[1],
                            s.getX() + house[1], GROUND + house[0], s.getZ() + house[1]));
                }
                house[0] = (int) c.number(0);
                house[1] = (int) c.number(1, 10);
                if (house[0] > 0) {
                    server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", s.getX() - house[1], GROUND + 1, s.getZ() - house[1],
                            s.getX() + house[1], GROUND + house[0], s.getZ() + house[1]));
                    if (c.args().size() > 2) {
                        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:%s", s.getX() - house[1], GROUND + house[0], s.getZ() - house[1],
                                s.getX() + house[1], GROUND + house[0], s.getZ() + house[1], c.args().get(2)));
                    }
                }
                finish("ok");
            }
            case HUD -> {
                mc.options.hideGui = c.args().getFirst().equals("off");
                finish("ok");
            }
            case OVERLAY -> {
                overlay = c.args().getFirst().equals("on");
                finish("ok");
            }
            case TRACK -> {
                track = c.args().getFirst().equals("on");
                finish("ok");
            }
            case ZOOM -> {
                zoom = c.number(0);
                finish("ok");
            }
            case TURN -> {
                turn = c.number(0);
                turnFrom = c.number(1, 0);
                turnYaw = Float.NaN;
                finish("ok");
            }
            case SHOTS -> {
                Path out = dir.resolve("out").resolve(c.args().getFirst());
                try {
                    if (Files.isDirectory(out)) {
                        try (Stream<Path> walk = Files.walk(out)) {
                            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                        }
                    }
                    Files.createDirectories(out);
                } catch (IOException ex) {
                    finish("error " + ex);
                    return;
                }
                armed = new Capture(c.args().getFirst(), c.times(), c.number(2, 0), out);
                finish("ok armed " + out);
            }
            case AGAIN -> {
                if (lastEffect == null) finish("error no effects yet");
                else startEffect(lastEffect);
            }
            case CLEAR -> {
                clear();
                finish("ok");
            }
            case RELOAD -> {
                reloadStart = System.nanoTime();
                reloading = true;
                reloaded = false;
                mc.reloadResourcePacks().thenRun(() -> reloaded = true);
            }
            case WAIT -> waitUntil = tick + (int) c.number(0);
            default -> finish("error " + c);
        }
    }

    /**
     * Clear all effects: at the client — as on leaving the world, at the server — projectiles and fire at the point.
     */
    private void clear() {
        raid.clear();
        turnYaw = Float.NaN;
        IncomingClient.resetSession();
        server("incoming clear");
        server("kill @e[type=!minecraft:player]");
        BlockPos s = BlockPos.containing(site);
        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air replace minecraft:fire", s.getX() - 20, GROUND, s.getZ() - 20,
                s.getX() + 20, GROUND + 6, s.getZ() + 20));
        // SAM ({@code sam}) — gone: loaded, it would shoot down the projectiles of the next effects
        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air replace incoming:sam", s.getX() - 20, GROUND + 1, s.getZ() - 20,
                s.getX() + 20, GROUND + 1, s.getZ() + 20));
        // fire ({@code fire}) — on netherrack: the ground is grass again
        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:grass_block replace minecraft:netherrack", s.getX() - 20, GROUND,
                s.getZ() - 20, s.getX() + 20, GROUND, s.getZ() + 20));
        // pond ({@code blast … water}, always at the source point) — ground and grass again; the bridge bed {@code real
        // … deck} — at
        // its place, its water stays
        BlockPos p = BlockPos.containing(HOME);
        int half = SandboxCommand.POND / 2;
        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:dirt replace minecraft:water", p.getX() - half, GROUND - SandboxCommand.POND_DEPTH + 1,
                p.getZ() - half, p.getX() + half, GROUND - 1, p.getZ() + half));
        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:grass_block replace minecraft:water", p.getX() - half, GROUND, p.getZ() - half,
                p.getX() + half, GROUND, p.getZ() + half));
        effectTick = -1;
        capture = null;
    }

    /** House under the point ({@code house}): height and half-width, in blocks; 0 — no house. */
    private final int[] house = new int[2];
    /** Tick when the paving under {@code real … masonry} is already placed; −1 — not yet. */
    private int paved = -1;

    /** Raid blasts ({@code raid}) still ahead: tick, x, z (double bits), seed. */
    private final List<long[]> raid = new ArrayList<>();
    /** Raid weapon. */
    private WeaponType raidWeapon = WeaponType.DRONE;

    /** Raid blasts whose tick has come: a blast packet only at the client, as {@code blast}. */
    private void raidNext(Minecraft mc) {
        while (!raid.isEmpty() && raid.getFirst()[0] <= tick) {
            long[] r = raid.removeFirst();
            Vec3 pos = new Vec3(Double.longBitsToDouble(r[1]), GROUND + 1, Double.longBitsToDouble(r[2]));
            GroundMaterial mat = GroundMaterial.sample(mc.level, BlockPos.containing(pos));
            ClientHooks.get().blast(new S2C.Blast(blastKind(raidWeapon), pos, mat.ordinal(), (float) pos.y, r[3], Optional.empty(), raidWeapon.id(),
                    S2C.Blast.DOWN));
        }
    }

    /** Blast packet kind by weapon. */
    private static int blastKind(WeaponType w) {
        return switch (w.spec().blast()) {
            case MISSILE -> S2C.Blast.MISSILE;
            case ROCKET -> S2C.Blast.ROCKET;
            case DRONE, NONE -> S2C.Blast.DRONE;
        };
    }

    /** Raid ({@code raid}): blasts in a circle at an interval, the first one now. */
    private void raidStart(Minecraft mc, SandboxCommand c) {
        int n = (int) c.number(0, 16);
        double radius = c.number(1, 120), every = c.number(2, 3);
        java.util.Random rnd = new java.util.Random(7);
        raid.clear();
        raidWeapon = c.raidWeapon();
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * rnd.nextDouble(), r = radius * Math.sqrt(rnd.nextDouble());
            raid.add(new long[]{tick + Math.round(i * every * 20), Double.doubleToLongBits(site.x + r * Math.cos(a)),
                    Double.doubleToLongBits(site.z + r * Math.sin(a)), i + 1});
        }
        raidNext(mc);
        begin();
    }

    /**
     * Pond at the point ({@code blast … water}, {@code raid … water}) — at the server; the effect — when the client
     * sees water under the point.
     */
    private void pond() {
        BlockPos s = BlockPos.containing(site);
        int half = SandboxCommand.POND / 2;
        onServer(false, server -> run(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water", s.getX() - half,
                GROUND - SandboxCommand.POND_DEPTH + 1, s.getZ() - half, s.getX() + half, GROUND, s.getZ() + half)));
        pending = Pending.POND;
    }

    private void startEffect(SandboxCommand c) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        pendingCmd = c;
        pendingSince = tick;
        serverDone = false;
        serverError = null;
        projectile = null;
        closest = Double.MAX_VALUE;
        // the crater and fires of a real blast stay in place, the rest — again on an untouched point
        if (c.verb() != SandboxCommand.Verb.REAL && c.verb() != SandboxCommand.Verb.NUKE && c.verb() != SandboxCommand.Verb.SURFACE) site = HOME;
        // frames before the effect's start — only where the start is known in advance (the front's arrival at the
        // houses)
        if (armed != null && armed.times[0] < 0 && !c.near()) throw new IllegalArgumentException("frames before the effect's start — only for nuke … near");
        switch (c.verb()) {
            case BLAST -> {
                sameAir(mc);
                placeCamera();
                WeaponType w = c.weapon();
                if (w == WeaponType.BUNKER) throw new IllegalArgumentException("a bomb without a server does not detonate: flight bunker");
                // pond — at the server (water reaches the client with the chunk), blast — when the client sees water
                // under the point
                if (c.water()) pond();
                else blast(level, c);
            }
            case RAID -> {
                sameAir(mc);
                placeCamera();
                if (c.water()) pond();
                else raidStart(mc, c);
            }
            case AIRBURST -> {
                placeCamera();
                long seed = c.args().isEmpty() ? 1 : c.seed();
                ClientHooks.get().intercept(new S2C.Intercept(site.add(0, AIRBURST_HEIGHT, 0), false, Optional.empty(), seed));
                begin();
            }
            case REAL, NUKE, SURFACE -> {
                if (c.near()) {
                    // the previous detonation near the camera is forgotten: this one's number and game time are the
                    // same ({@link #NEAR_ID})
                    clear();
                    nearRuns = nearRuns % NEAR_RUNS + 1;
                    site = nearSite(nearRuns);
                } else {
                    freshX += c.verb() == SandboxCommand.Verb.NUKE ? NUKE_STEP : REAL_STEP;
                    site = HOME.add(freshX, 0, 0);
                }
                placeCamera();
                pending = Pending.SITE;
            }
            case LAUNCH, FLIGHT -> {
                placeCamera();
                // own launch — on behalf of the player: the server takes the owner from the command's source
                String as = c.own() ? "execute as " + mc.player.getGameProfile().getName() + " run " : "";
                server(as + c.order((int) Math.floor(site.x), (int) Math.floor(site.z)));
                pending = c.verb() == SandboxCommand.Verb.LAUNCH ? Pending.LAUNCH : Pending.FLIGHT;
            }
            case SAM -> {
                // One air for comparing different branches: Wind depends on gameTime, and /time changes only dayTime.
                onServer(false, s -> {
                    var world = s.overworld();
                    ((net.minecraft.world.level.storage.ServerLevelData) world.getLevelData()).setGameTime(NEAR_GAME_TIME);
                    s.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(world.getGameTime(),
                            world.getDayTime(), world.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
                });
                placeCamera();
                int x = (int) Math.floor(site.x), z = (int) Math.floor(site.z);
                // SAM loaded (4 missiles), the Shahed comes from the north through the point to the south: the radar
                // takes it, the missile launches at the point
                // anew (again without clear): at the site of the previous SAM, setblock would not have changed the
                // block, and the missiles would not have been refilled
                server(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x, GROUND + 1, z));
                server(String.format(Locale.ROOT, "setblock %d %d %d incoming:sam{ready:4}", x, GROUND + 1, z));
                server(String.format(Locale.ROOT, "incoming salvo drone 1 0 at %d %d from %d %d", x, z + SAM_PAST, x, z - SAM_FROM));
                pending = Pending.SAM;
            }
            case SPENT -> {
                placeCamera();
                WeaponType w = c.weapon();
                Vec3 at = new Vec3(site.x, GROUND + 1 + c.number(1, 20), site.z);
                // /summon is closed for the accelerator (the entity is not summoned): by hand, as the projectile
                // separates it
                onServer(true, server -> {
                    net.minecraft.server.level.ServerLevel l = server.overworld();
                    var e = new ua.zentix.incoming.entity.SpentBoosterEntity(ua.zentix.incoming.registry.ModEntities.SPENT_BOOSTER.get(), l);
                    net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                    tag.putByte("weapon", (byte) w.id());
                    e.load(tag);
                    e.moveTo(at.x, at.y, at.z, 0, 0);
                    l.addFreshEntity(e);
                });
                pending = Pending.SERVER;
            }
            case FIRE -> {
                sameAir(mc);
                placeCamera();
                int half = (int) c.number(0, 6) / 2;
                BlockPos s = BlockPos.containing(site);
                onServer(true, server -> {
                    run(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:netherrack", s.getX() - half, GROUND, s.getZ() - half,
                            s.getX() + half, GROUND, s.getZ() + half));
                    run(server, String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:fire", s.getX() - half, GROUND + 1, s.getZ() - half,
                            s.getX() + half, GROUND + 1, s.getZ() + half));
                });
                pending = Pending.SERVER;
            }
            default -> throw new IllegalArgumentException("not an effect: " + c);
        }
    }

    /**
     * One air for {@code blast}, {@code raid} and {@code fire} in any run and on any branch: wind and stability — a
     * function of game time ({@code Wind.at}, at the client — {@code ClientAir}), and {@code time} changes only the
     * time of day. The client takes {@link #NEAR_GAME_TIME} at once (the blast packet — in this same tick), the server
     * — with its own task, and sends it to all.
     */
    private void sameAir(Minecraft mc) {
        mc.level.setGameTime(NEAR_GAME_TIME);
        onServer(false, s -> {
            var world = s.overworld();
            ((net.minecraft.world.level.storage.ServerLevelData) world.getLevelData()).setGameTime(NEAR_GAME_TIME);
            s.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(world.getGameTime(),
                    world.getDayTime(), world.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        });
    }

    /**
     * Blast packet {@code blast} only at the client, through the same entry as from the server; ground — whatever is
     * under the point (grass or pond).
     */
    private void blast(ClientLevel level, SandboxCommand c) {
        WeaponType w = c.weapon();
        int kind = blastKind(w);
        int n = c.args().size() - (c.water() ? 1 : 0);
        Vec3 ground = new Vec3(site.x, GROUND + 1, site.z), pos = ground.add(0, n > 2 ? c.number(2) : 0, 0);
        // material — as at the server: at the strike point (roof, paving), by the server's world (the client has no far
        // chunk — the
        // server has it, and the packet carries its material)
        GroundMaterial mat = sampleOnServer(level, BlockPos.containing(pos));
        long seed = n > 1 ? c.seedAt(1) : 1;
        // the same entry as the server's packet (ClientHooks): the blast picture and sound — as in the game
        ClientHooks.get().blast(new S2C.Blast(kind, pos, mat.ordinal(), (float) ground.y, seed, Optional.empty(), w.id(), S2C.Blast.DOWN, false,
                supportOnServer(pos, w.spec().charge())));
        begin();
    }

    /**
     * Support along the strike axis, as the server sends it in the blast packet ({@code Warheads.support}: a roof over
     * a void — a slab): at the integrated server — its task; without it — none (picture by material).
     */
    private static Optional<S2C.Blast.Support> supportOnServer(Vec3 pos, double tnt) {
        net.minecraft.client.server.IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !(tnt > 0)) return Optional.empty();
        try {
            return server.submit(() -> ua.zentix.incoming.warhead.Warheads.support(server.overworld(), pos, tnt, S2C.Blast.DOWN, null))
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Material under the point, as the server takes it ({@code GroundMaterial.sample} in the server thread): at the
     * integrated server — its task (the client waits a tick); without it — by the client's world.
     */
    private static GroundMaterial sampleOnServer(ClientLevel level, BlockPos at) {
        net.minecraft.client.server.IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return GroundMaterial.sample(level, at);
        try {
            return server.submit(() -> GroundMaterial.sample(server.overworld(), at)).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return GroundMaterial.sample(level, at);
        }
    }

    /** Effect started in this tick: frame and sound clocks. */
    private void begin() {
        begin(tick);
    }

    /** Effect starts at tick {@code at} (not before it): frame and sound clocks from it. */
    private void begin(int at) {
        pending = null;
        effectTick = at;
        Incoming.LOG.info("SANDBOX effect {} at {} tick {} (now {}) audio {}", pendingCmd, xyz(site), at, tick, audioBytes());
        capture = armed;
        armed = null;
        if (capture != null) {
            // the first frame is already in the past (the detonation is closer than the frames before the front) —
            // sound from this tick, frames — now (their
            // real time — age in capture.json)
            int first = at + (int) Math.round(Math.min(0, capture.times[0]) * 20);
            if (first < tick) {
                Incoming.LOG.warn("SANDBOX frames before {} s are already past (the front comes in {} s): taken now, see age",
                        String.format(Locale.ROOT, "%.2f", (tick - at) / 20.0), String.format(Locale.ROOT, "%.2f", (at - tick) / 20.0));
            }
            capture.audioTick = Math.max(tick, first);
            if (tick >= capture.audioTick) capture.audioStart = audioBytes();
        } else {
            finish("ok " + pendingCmd);
        }
    }

    private void advance(Minecraft mc) {
        if (waitUntil >= 0 && tick >= waitUntil) {
            waitUntil = -1;
            finish("ok");
        }
        // time has come (a packet without a day change writes 0 as 1 — tolerance of a tick)
        if (timeWait >= 0 && Math.floorMod(mc.level.getDayTime() - timeWait, 24000L) <= 1) {
            timeWait = -1;
            finish("ok");
        } else if (timeWait >= 0 && tick - timeSince > 40) {
            // the packet takes a tick or two; not arrived within 2 s — the day change was switched on or the server did
            // not accept the command
            timeWait = -1;
            finish("error time did not reach the client within 2 s: " + mc.level.getDayTime() % 24000);
        }
        if (pending != null) {
            if (serverError != null) {
                pending = null;
                finish("error " + serverError);
                return;
            }
            switch (pending) {
                case SITE -> siteReady(mc);
                case SERVER -> {
                    if (serverDone) {
                        if (pendingCmd.verb() == SandboxCommand.Verb.NUKE) pending = Pending.NUKE;
                        else begin();
                    }
                }
                case NUKE -> {
                    var fresh = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().stream().filter(a -> !knownNukes.contains(a.d.id())).findFirst();
                    if (fresh.isPresent()) {
                        if (pendingCmd.near()) frontAtHouses(fresh.get());
                        else begin();
                    }
                }
                case LAUNCH, FLIGHT -> watchFlight(mc);
                case SAM -> watchSam();
                case POND -> {
                    if (mc.level.getFluidState(BlockPos.containing(site).atY(GROUND)).isSource()) {
                        if (pendingCmd.verb() == SandboxCommand.Verb.RAID) raidStart(mc, pendingCmd);
                        else blast(mc.level, pendingCmd);
                    }
                }
            }
            if (pending != null && tick - pendingSince > FLIGHT_LIMIT) {
                pending = null;
                finish("error did not wait for the effect to start within " + FLIGHT_LIMIT + " ticks");
            }
        }
        Capture cap = capture;
        if (cap != null && cap.audioStart < 0 && effectTick >= 0 && tick >= cap.audioTick) cap.audioStart = audioBytes();
        if (cap != null && effectTick >= 0 && cap.next >= cap.times.length && tick >= cap.audioTick + Math.round(cap.wavSeconds * 20) + 10) {
            capture = null;
            write(cap.out.resolve("capture.json"), json(cap));
            finish("ok " + cap.out);
        }
    }

    /**
     * New site of a real blast: wait until the ground is ready at the client and at the server — then the blast or
     * detonation.
     */
    private void siteReady(Minecraft mc) {
        if (tick - pendingSince < 40 || !aroundLoaded(mc)) return;
        // the point is in draw distance — and its ground is at the client; the nuke beyond draw distance the server
        // loads itself (NuclearTickets)
        BlockPos s = BlockPos.containing(site);
        if (mc.player.position().distanceTo(site) < mc.options.getEffectiveRenderDistance() * 16 - 32 && !loaded(mc, s)) return;
        SandboxCommand c = pendingCmd;
        if ((c.masonry() || c.town()) && paved < 0) {
            // city: paving around, not lawn; the client must get it before the blast packet (dust ground — at the
            // client)
            paved = tick;
            server("fill %d %d %d %d %d %d minecraft:gray_concrete".formatted(s.getX() - 48, GROUND, s.getZ() - 48, s.getX() + 48, GROUND, s.getZ() + 48));
            return;
        }
        if ((c.masonry() || c.town()) && tick - paved < 20) return;
        paved = -1;
        pending = Pending.SERVER;
        knownNukes.clear();
        ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().forEach(a -> knownNukes.add(a.d.id()));
        Vec3 at = c.near() ? new Vec3(site.x, GROUND + 1, site.z - c.nearRange()) : new Vec3(site.x, GROUND + 1, site.z);
        onServer(true, server -> {
            if (c.verb() == SandboxCommand.Verb.SURFACE) {
                BlockPos floor = new BlockPos((int) Math.floor(site.x), GROUND, (int) Math.floor(site.z));
                String material = c.args().size() > 1 && c.args().get(1).equals("gray") ? "gray_concrete" : "black_concrete";
                run(server, "fill %d %d %d %d %d %d minecraft:%s".formatted(
                        floor.getX() - 16, GROUND, floor.getZ() - 5, floor.getX() + 16, GROUND, floor.getZ() + 5, material));
                var world = server.overworld();
                ua.zentix.incoming.warhead.SurfaceDamage.mark(world, floor, world.getBlockState(floor), c.number(0));
                return;
            }
            if (c.verb() == SandboxCommand.Verb.REAL) {
                if (c.deck() > 0) buildBridge(server, BlockPos.containing(site), c.deck(), c.concrete() ? "gray_concrete" : "stone");
                if (c.paved()) {
                    BlockPos o = BlockPos.containing(site);
                    run(server, "fill %d %d %d %d %d %d minecraft:black_concrete".formatted(
                            o.getX() - 16, GROUND, o.getZ() - 5, o.getX() + 16, GROUND, o.getZ() + 5));
                }
                // at the plank house: a strike into the ground at its wall facing the camera (house — from x + 4 to x +
                // 12, z ± 3, roof — planks at
                // 5 above the ground) or into the middle of its roof (top of the planks — 6 above the ground)
                // or into the middle of the roof of the stone brick house (x − 12 … x − 4, z ± 4), the roof there is a
                // concrete slab one metre above
                // the room's void (the stone ceiling of the house is removed: with it the roof is 2 m, and the Shahed
                // does not break through it), top — 8 above the ground
                if (c.houses() || c.roof() || c.masonry()) buildHouses(server, BlockPos.containing(site));
                if (c.town()) buildTown(server, BlockPos.containing(site));
                if (c.masonry()) {
                    BlockPos o = BlockPos.containing(site);
                    run(server, "fill %d %d %d %d %d %d minecraft:air".formatted(o.getX() - 11, o.getY() + 6, o.getZ() - 3,
                            o.getX() - 5, o.getY() + 6, o.getZ() + 3));
                    run(server, "fill %d %d %d %d %d %d minecraft:gray_concrete".formatted(o.getX() - 12, o.getY() + 7, o.getZ() - 4,
                            o.getX() - 4, o.getY() + 7, o.getZ() + 4));
                }
                SandboxStrikes.detonate(server.overworld(), c.weapon(),
                        c.houses() ? at.add(8, 0, 5) : c.roof() ? at.add(8, 6, 0) : c.masonry() ? at.add(-8, 8, 0) : at);
                return;
            }
            if (c.near()) {
                buildHouses(server, BlockPos.containing(site));
                sameDetonationNumber(server);
                sameGameTime(server, at);
            }
            run(server, String.format(Locale.ROOT, "incoming nuke now at %.1f %.1f %.1f %d %s", at.x, at.y, at.z, (int) c.number(0),
                    c.air() ? "air" : "ground"));
        });
    }

    /**
     * Bridge for {@code real … deck}: a bed along X (13 m wide, water on the bottom) and across it a deck of stone,
     * thickness {@code deck} m, and 9 m wide flush with the ground; the strike — into the middle of the deck (the blast
     * point — on its top face).
     */
    private static void buildBridge(MinecraftServer server, BlockPos o, int deck, String material) {
        int x = o.getX(), y = GROUND, z = o.getZ();
        for (String c : List.of(
                "fill %d %d %d %d %d %d minecraft:air".formatted(x - 30, y - deck - 6, z - 6, x + 30, y, z + 6),
                "fill %d %d %d %d %d %d minecraft:water".formatted(x - 30, y - deck - 6, z - 6, x + 30, y - deck - 5, z + 6),
                "fill %d %d %d %d %d %d minecraft:%s".formatted(x - 4, y - deck + 1, z - 7, x + 4, y, z + 7, material))) {
            run(server, c);
        }
    }

    /**
     * Site of the k-th run of the nuke near the camera (point — midway between the houses, on the ground): the
     * threshold spread there is the same.
     */
    static Vec3 nearSite(int k) {
        return new Vec3(NEAR_X0 + (long) k * NEAR_DX + 0.5, GROUND + 1, NEAR_Z0 + (long) k * NEAR_DZ + 0.5);
    }

    /**
     * Houses and oaks of the {@code debris} scene (ClientScenario.debrisEvents): a stone brick house with glass panels
     * facing the viewer and the blast, and a plank roof; a plank house with glass; oaks — even crowns (in the scenario
     * — {@code place feature}: random shape).
     */
    private static void buildHouses(MinecraftServer server, BlockPos o) {
        int x = o.getX(), y = o.getY(), z = o.getZ();
        List<String> build = new ArrayList<>(List.of(
                "fill %d %d %d %d %d %d minecraft:stone_bricks hollow".formatted(x - 12, y, z - 4, x - 4, y + 6, z + 4),
                "fill %d %d %d %d %d %d minecraft:glass_pane".formatted(x - 10, y + 2, z + 4, x - 6, y + 3, z + 4),
                "fill %d %d %d %d %d %d minecraft:glass_pane".formatted(x - 10, y + 2, z - 4, x - 6, y + 3, z - 4),
                "fill %d %d %d %d %d %d minecraft:oak_planks".formatted(x - 12, y + 7, z - 4, x - 4, y + 7, z + 4),
                "fill %d %d %d %d %d %d minecraft:oak_planks hollow".formatted(x + 4, y, z - 3, x + 12, y + 5, z + 3),
                "fill %d %d %d %d %d %d minecraft:glass".formatted(x + 6, y + 2, z + 3, x + 10, y + 3, z + 3),
                "fill %d %d %d %d %d %d minecraft:glass".formatted(x + 6, y + 2, z - 3, x + 10, y + 3, z - 3)));
        for (int[] t : new int[][]{{x - 8, z + 12}, {x + 8, z + 12}, {x, z - 14}}) build.addAll(oak(t[0], y, t[1]));
        for (String c : build) run(server, c);
    }

    /**
     * Block {@code real … town}: stone brick houses 10×10 m and 12 m high, streets 6 m wide along X and Z, the point —
     * at the crossroads (streets — at ±3 m from it), ±48 m.
     */
    private static void buildTown(MinecraftServer server, BlockPos o) {
        int y = GROUND + 1;
        for (int i = -3; i <= 2; i++) {
            for (int j = -3; j <= 2; j++) {
                int x0 = o.getX() + 3 + 16 * i, z0 = o.getZ() + 3 + 16 * j;
                run(server, "fill %d %d %d %d %d %d minecraft:stone_bricks".formatted(x0, y, z0, x0 + 9, y + 11, z0 + 9));
            }
        }
    }

    /**
     * Oak of 5 logs: two layers of 5×5 crown without corners on the 3rd and 4th log, on top 3×3 and a cross — as the
     * vanilla oak.
     */
    private static List<String> oak(int x, int y, int z) {
        String leaves = "minecraft:oak_leaves[persistent=true]";
        return List.of(
                "fill %d %d %d %d %d %d %s".formatted(x - 2, y + 3, z - 1, x + 2, y + 4, z + 1, leaves),
                "fill %d %d %d %d %d %d %s".formatted(x - 1, y + 3, z - 2, x + 1, y + 4, z + 2, leaves),
                "fill %d %d %d %d %d %d %s".formatted(x - 1, y + 5, z - 1, x + 1, y + 5, z + 1, leaves),
                "fill %d %d %d %d %d %d %s".formatted(x - 1, y + 6, z, x + 1, y + 6, z, leaves),
                "fill %d %d %d %d %d %d %s".formatted(x, y + 6, z - 1, x, y + 6, z + 1, leaves),
                "fill %d %d %d %d %d %d minecraft:oak_log".formatted(x, y, z, x, y + 4, z));
    }

    /**
     * Number of the next detonation — {@link #NEAR_ID}: wind and the scenario's detonation seed — by number, the same
     * for every run. The counter is closed ({@code NuclearEvents.nextId}, the game does not need a foreign number) —
     * only here, by reflection; past detonations {@code clear} has already forgotten ({@code incoming clear}), so the
     * number will not match anyone's.
     */
    private static void sameDetonationNumber(MinecraftServer server) {
        try {
            var f = ua.zentix.incoming.nuclear.NuclearEvents.class.getDeclaredField("nextId");
            f.setAccessible(true);
            f.setInt(ua.zentix.incoming.nuclear.NuclearEvents.get(server.overworld()), NEAR_ID);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("detonation number not set", e);
        }
    }

    /**
     * Game time — {@link #NEAR_GAME_TIME} (to all players — a time packet, as in the {@code firestorm} scenario skip),
     * and the ground of the epicentre — ready now (flat world, milliseconds; a sandbox, not the game): the detonation
     * runs in this same tick ({@code NuclearStrikes.detonateNow}), not deferred until the ground loads — and the wind
     * at the moment of detonation is the same.
     */
    private static void sameGameTime(MinecraftServer server, Vec3 burst) {
        var level = server.overworld();
        level.getChunk(BlockPos.containing(burst));
        ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(NEAR_GAME_TIME);
        server.getPlayerList().broadcastAll(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(),
                level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
    }

    /**
     * The detonation near the camera has come: the effect's start — the front's arrival at the houses by the client's
     * detonation clock, as in the {@code debris} scenario.
     */
    private void frontAtHouses(ua.zentix.incoming.client.nuclear.ClientNuclear.Active a) {
        Vec3 houses = site.add(0, 2, 0);
        double wait = a.d.arrivalTicks(houses.distanceTo(a.d.burst())) - a.ticks(0);
        Incoming.LOG.info("SANDBOX near: burst {}, wave {} of sound, front at the houses in {} ticks", xyz(a.d.burst()), a.d.wave(), Math.round(wait));
        begin(tick + (int) Math.round(wait));
    }

    private static boolean loaded(Minecraft mc, BlockPos at) {
        return mc.level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4);
    }

    /**
     * The ground in draw distance around the camera has reached the client (at a new site chunks take a second or two):
     * otherwise the first frames of the effect — without ground, in fog colour (nuke frames at 0.5–1 s).
     */
    private static boolean aroundLoaded(Minecraft mc) {
        BlockPos at = mc.player.blockPosition();
        int r = Math.max(1, mc.options.getEffectiveRenderDistance() - 1);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= r * r && !mc.level.getChunkSource().hasChunk((at.getX() >> 4) + dx, (at.getZ() >> 4) + dz)) return false;
            }
        }
        return true;
    }

    /** Launch of the SAM air-defence missile ({@code sam}): from its first movement; {@code track on} follows it. */
    private void watchSam() {
        double[] at = new double[3];
        if (projectile == null) {
            for (FlightTrack t : FlightTracks.interceptors()) {
                if (t.isDead() || !t.predict(FlightTracks.now() - 1, at)) continue;
                projectile = t;
                firstSeen = new Vec3(at[0], at[1], at[2]);
                Incoming.LOG.info("SANDBOX interceptor seen at {}", xyz(firstSeen));
                break;
            }
            return;
        }
        if (projectile.predict(FlightTracks.now() - 1, at) && firstSeen.distanceTo(new Vec3(at[0], at[1], at[2])) > 0.3) begin();
    }

    /** Launch — from the projectile's first movement at the point; flyby — at the moment it is closest to it. */
    private void watchFlight(Minecraft mc) {
        double[] at = new double[3];
        if (projectile == null || projectile.isDead()) {
            projectile = null;
            double best = Double.MAX_VALUE;
            for (FlightTrack t : FlightTracks.all()) {
                if (t.weapon != pendingCmd.weapon() || t.isDead() || !t.predict(FlightTracks.now() - 1, at)) continue;
                double d = site.distanceToSqr(at[0], at[1], at[2]);
                if (d < best) {
                    best = d;
                    projectile = t;
                }
            }
            if (projectile == null || !projectile.predict(FlightTracks.now() - 1, at)) return;
            firstSeen = new Vec3(at[0], at[1], at[2]);
            Incoming.LOG.info("SANDBOX projectile seen at {}", xyz(firstSeen));
        }
        if (!projectile.predict(FlightTracks.now() - 1, at)) return;
        Vec3 pos = new Vec3(at[0], at[1], at[2]);
        if (pending == Pending.LAUNCH) {
            if (pos.distanceTo(firstSeen) > 0.3) begin();
            return;
        }
        double d = pos.distanceTo(site);
        if (d > closest + 0.5 && closest < 400) {
            Incoming.LOG.info("SANDBOX closest {} blocks", String.format(Locale.ROOT, "%.0f", closest));
            begin();
        }
        closest = Math.min(closest, d);
    }

    // ---------------------------------------------------------------- frames

    /**
     * {@code track on}: before a frame the eye looks at the effect's projectile where it is on its path in this frame.
     */
    private void onFrameStart(RenderFrameEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        if (turn != 0 && effectTick >= 0 && mc.player != null) {
            if (Float.isNaN(turnYaw)) turnYaw = mc.player.getYRot();
            double t = (tick - effectTick + e.getPartialTick().getGameTimeDeltaPartialTick(true)) / 20.0;
            float yaw = (float) (turnYaw + turn * Math.max(0, t - turnFrom));
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
        }
        FlightTrack p = projectile;
        if (!track || p == null || mc.player == null) return;
        double[] at = new double[3];
        if (!p.predict(FlightTracks.now() - 1 + e.getPartialTick().getGameTimeDeltaPartialTick(true), at)) return;
        Vec3 d = new Vec3(at[0], at[1], at[2]).subtract(mc.gameRenderer.getMainCamera().getPosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
    }

    private void onFrame(RenderFrameEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ready) return;
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(true);
        if (overlay) drawOverlay(mc, partial);
        Capture cap = capture;
        if (cap == null || effectTick < 0 || cap.next >= cap.times.length) return;
        double t = cap.times[cap.next];
        if (tick < effectTick + Math.round(t * 20)) return;
        double age = (tick - effectTick + partial) / 20.0;
        String file = String.format(Locale.ROOT, "%s_%06.2fs.png", cap.name, t);
        NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        Path to = cap.out.resolve(file);
        Util.ioPool().execute(() -> {
            try (image) {
                image.writeToFile(to);
            } catch (IOException ex) {
                Incoming.LOG.error("SANDBOX frame {} not written", to, ex);
            }
        });
        cap.frames.add(String.format(Locale.ROOT, "{\"t\": %.2f, \"age\": %.3f, \"file\": \"%s\", \"fps\": %d}", t, age, file, mc.getFps()));
        Incoming.LOG.info("SANDBOX shot {} age {} s fps {}; layer: {}; smoke: {}; flight: {}; air: {}", file, String.format(Locale.ROOT, "%.3f", age), mc.getFps(),
                ua.zentix.incoming.client.fx.layer.FxLayer.describeTiming(), ua.zentix.incoming.client.far.FarRenderer.describeSmoke(),
                ua.zentix.incoming.client.far.FarFlightView.describe(), ua.zentix.incoming.client.fx.ClientAir.now());
        cap.next++;
    }

    /** Model numbers over the frame: last blast, distant blasts and their sound, sound at the ear, fires, camera. */
    private void drawOverlay(Minecraft mc, float partial) {
        List<String> lines = new ArrayList<>();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        lines.add(String.format(Locale.ROOT, "t %.2f s  eye %.0f m from point  fps %d", effectTick < 0 ? 0 : (tick - effectTick + partial) / 20.0,
                eye.distanceTo(site), mc.getFps()));
        lines.add("blast: " + ua.zentix.incoming.client.far.FarBlasts.describeLast());
        String far = ua.zentix.incoming.client.far.FarBlasts.describe();
        if (!far.isEmpty()) lines.add("far: " + far);
        String sound = ua.zentix.incoming.client.sound.BlastSounds.describe();
        if (!sound.isEmpty()) lines.add("sound: " + sound);
        String fire = ua.zentix.incoming.client.fx.FireSmoke.describe();
        if (!fire.isEmpty()) lines.add("fire: " + fire);
        lines.add("smoke: " + ua.zentix.incoming.client.far.FarRenderer.describeSmoke());
        var nukes = ua.zentix.incoming.client.nuclear.ClientNuclear.detonations();
        if (!nukes.isEmpty()) {
            var a = nukes.getLast();
            double front = a.d.frontRadius(a.ticks(partial));
            lines.add(String.format(Locale.ROOT, "nuke: front %.0f m past the point, %.0f m past the eye; %.2f psi at the point", front - site.distanceTo(a.d.burst()),
                    front - eye.distanceTo(a.d.burst()), a.d.overpressureKpa(site) / 6.895));
            lines.add("near: " + ua.zentix.incoming.client.nuclear.NukeDust.describe());
        }
        // the interface projection is still set, but vanilla has already removed the view shift along z (−11000):
        // without it the text is outside the projection depth
        var view = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.translation(0, 0, -11000);
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        int y = 4, width = mc.getWindow().getGuiScaledWidth() - 8;
        for (String line : lines) {
            for (var part : mc.font.split(Component.literal(line), width)) {
                g.fill(2, y - 1, 6 + mc.font.width(part), y + 9, 0x90000000);
                g.drawString(mc.font, part, 4, y, 0xFFFFFF, false);
                y += 10;
            }
        }
        g.flush();
        view.popMatrix();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }

    // ---------------------------------------------------------------- camera and server

    /**
     * Camera — at {@link #camDist} from the point at bearing {@link #camBearing}, at {@link #camHeight} above the
     * ground, looking at the point.
     */
    private void placeCamera() {
        double a = Math.toRadians(camBearing);
        // bearing — as the player's turn: 0 — south (+Z), 90 — west (−X)
        Vec3 eye = site.add(-Math.sin(a) * camDist, 0, Math.cos(a) * camDist);
        double y = GROUND + 1 + camHeight;
        String me = Minecraft.getInstance().player.getGameProfile().getName();
        server(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f", me, eye.x, y - 1.62, eye.z, site.x, GROUND + 1 + camLook, site.z));
    }

    private void server(String command) {
        onServer(false, s -> run(s, command));
    }

    /** Task for the server thread; {@code effect} — the effect itself: its end awaits {@link Pending#SERVER}. */
    private void onServer(boolean effect, java.util.function.Consumer<MinecraftServer> action) {
        MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
        if (s == null) throw new IllegalStateException("no integrated server");
        s.execute(() -> {
            try {
                action.accept(s);
                if (effect) serverDone = true;
            } catch (RuntimeException ex) {
                Incoming.LOG.error("SANDBOX server task failed", ex);
                serverError = ex.toString();
            }
        });
    }

    private static void run(MinecraftServer s, String command) {
        Incoming.LOG.info("SANDBOX /{}", command);
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
    }

    // ---------------------------------------------------------------- files

    private long audioBytes() {
        try {
            return Files.size(FMLPaths.GAMEDIR.get().resolve("audio.wav"));
        } catch (IOException e) {
            return -1;
        }
    }

    private String json(Capture c) {
        return String.format(Locale.ROOT, "{\"name\": \"%s\", \"effect\": \"%s\", \"site\": [%.1f, %.1f, %.1f], \"camera\": [%.1f, %.1f, %.1f, %.1f], "
                        + "\"audio\": {\"file\": \"%s\", \"start\": %d, \"from\": %.2f, \"seconds\": %.2f}, \"frames\": [%s]}\n",
                c.name, lastEffect, site.x, site.y, site.z, camDist, camHeight, camBearing, camLook,
                FMLPaths.GAMEDIR.get().resolve("audio.wav").toAbsolutePath(), c.audioStart, (c.audioTick - effectTick) / 20.0, c.wavSeconds,
                String.join(", ", c.frames));
    }

    private static void write(Path file, String text) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text.endsWith("\n") ? text : text + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Incoming.LOG.error("SANDBOX: {} not written", file, e);
        }
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
    }
}
