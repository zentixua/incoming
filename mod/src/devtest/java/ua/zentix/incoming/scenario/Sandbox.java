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
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.IncomingClient;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.net.ClientHooks;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.GroundMaterial;
import ua.zentix.incoming.warhead.Warheads;

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
 * Песочница эффектов (сценарий клиента {@code sandbox}, tools/sandbox.sh): клиент только с модом (по желанию — Iris и
 * шейдерпак хоста) в плоском мире; эффект — взрыв, пуск, полёт, пожар — играется командой за секунды, без полёта
 * через полкарты, а кадры на заданные секунды от его начала и звук пишутся в {@code fx/out/<имя>/}. Команды —
 * {@link SandboxCommand}: в чате {@code /fx …} (клиентская команда, есть только в этом сценарии) или файлами
 * {@code fx/inbox/*.cmd} от скрипта (итог — рядом, {@code *.done}). Клиент не выходит сам: код меняется горячей
 * заменой (агент {@code ua.zentix.hotswap.HotswapAgent}), ресурсы — {@code fx reload}.
 * <p>
 * Мир — плоский (коренная порода, 124 камня, 3 земли, трава; верх травы — {@link #GROUND}), равнины без построек, вечный
 * полдень без погоды и мобов: одинаковый каждый запуск, и место нового настоящего взрыва ({@code real}, {@code nuke}) —
 * та же земля, что у прежнего.
 */
final class Sandbox {
    static final String WORLD = "incoming_sandbox";
    /** Верх травы плоского мира: 1 + 124 + 3 + 1 слоёв от −64. */
    static final int GROUND = 64;
    /** Новое место настоящего взрыва — дальше прорисовки от прежнего (пожары и воронка не в кадре). */
    static final int REAL_STEP = 320, NUKE_STEP = 6000;
    /** Разрыв зенитной ракеты ({@code airburst}) — на столько блоков над землёй точки. */
    static final double AIRBURST_HEIGHT = 30;
    /** Пуск и пролёт: ждать снаряд не дольше, тиков. */
    static final int FLIGHT_LIMIT = 1800;
    /** Цель пуска — на столько блоков к северу от точки (−Z). */
    static final int LAUNCH_RANGE = 3000;

    private final Path dir = FMLPaths.GAMEDIR.get().resolve("fx");
    private final Path inbox = dir.resolve("inbox");
    private boolean started, ready;
    private int tick = -1;
    /** Точка эффектов, что землю не трогают (на земле), и точка эффекта сейчас. */
    private static final Vec3 HOME = new Vec3(0.5, GROUND + 1, 0.5);
    private Vec3 site = HOME;
    /** Настоящие взрывы и подрывы — каждый на нетронутой земле: столько блоков к +X от {@link #HOME} уже занято. */
    private int freshX;
    /** Камера: дальность, высота над землёй, азимут от точки, высота точки взгляда. */
    private double camDist = 60, camHeight = 10, camBearing = 90, camLook = 8;
    private boolean overlay;
    @Nullable
    private SandboxCommand lastEffect;
    /** Команда из файла, которая ещё идёт, и её файл итога. */
    @Nullable
    private Path running;
    /** Тик, когда команда без эффекта кончится сама ({@code wait}); −1 — нет. */
    private int waitUntil = -1;
    /** Эффект ждёт начала: что ждать (пуск, пролёт, сервер) и с какого тика. */
    @Nullable
    private Pending pending;
    /** Тик начала эффекта (−1 — нет) и снимок. */
    private int effectTick = -1;
    @Nullable
    private Capture armed, capture;

    private enum Pending { SERVER, LAUNCH, FLIGHT, SITE, NUKE }

    private int pendingSince;
    private SandboxCommand pendingCmd;
    /** Сервер выполнил задачу самого эффекта (взрыв, подрыв, пожар) — не камеры и не прочих команд. */
    private volatile boolean serverDone;
    /**
     * Ресурсы перезагружаются ({@code reload}): следующая команда — уже с новыми. Готово — когда загрузка кончилась,
     * экран загрузки ушёл и пауза снята: на время перезагрузки одиночная игра на паузе, а экран загрузки гаснет ещё
     * ~1 с после неё (эффект, начатый в это время, стоял бы, пока часы песочницы шли).
     */
    private boolean reloading;
    private volatile boolean reloaded;
    private long reloadStart;
    @Nullable
    private volatile String serverError;
    /** Пуск и пролёт: снаряд у клиента, где его увидели, ближе всего к точке. */
    @Nullable
    private StrikeProjectile projectile;
    /**
     * Ядерка: подрывы, что клиент знал до команды. Начало — пакет нового подрыва у клиента, а не задача сервера: место
     * подрыва, чья земля ещё грузится, сервер откладывает ({@code NuclearStrikes.detonateNow}).
     */
    private final java.util.Set<Integer> knownNukes = new java.util.HashSet<>();
    private Vec3 firstSeen = Vec3.ZERO;
    private double closest = Double.MAX_VALUE;

    /** Снимок эффекта: кадры на секунды {@code times}, звук {@code wavSeconds}. */
    private static final class Capture {
        final String name;
        final double[] times;
        final double wavSeconds;
        final Path out;
        int next;
        long audioStart = -1;
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
        try {
            Files.createDirectories(inbox);
            // команды прошлого запуска — не этому клиенту
            try (Stream<Path> old = Files.list(inbox)) {
                old.forEach(p -> p.toFile().delete());
            }
            Files.deleteIfExists(dir.resolve("ready"));
        } catch (IOException e) {
            Incoming.LOG.error("SANDBOX: no inbox", e);
        }
    }

    // ---------------------------------------------------------------- мир

    private void onScreen(ScreenEvent.Init.Post e) {
        // до мира — какой экран открыт: клиент без окна, который встал на чужом экране, иначе не понять
        if (!ready) Incoming.LOG.info("SANDBOX screen {}", e.getScreen().getClass().getName());
        if (started || !(e.getScreen() instanceof TitleScreen)) return;
        started = true;
        createWorld(e.getScreen());
    }

    /** Плоский мир заново: каждый запуск тот же (прежний удаляется). */
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

    // ---------------------------------------------------------------- тик

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (reloading && reloaded && mc.getOverlay() == null && !mc.isPaused()) {
            reloading = false;
            finish(String.format(Locale.ROOT, "ok reloaded in %.1f s", (System.nanoTime() - reloadStart) / 1e9));
        }
        // часы эффектов — тики мира: на паузе (перезагрузка ресурсов, меню) эффекты стоят, и кадры ждут
        if (mc.isPaused()) return;
        tick++;
        if (!ready) {
            if (tick == 1) setup();
            // земля под точкой и камерой у клиента
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

    /** Идёт команда (из файла или чата): ожидание, эффект и его кадры, перезагрузка — следующая ждёт. */
    private boolean busy() {
        return running != null || pending != null || reloading || waitUntil >= 0 || capture != null;
    }

    /** Итог команды из файла (у команды из чата — строка в чат). */
    private void finish(String result) {
        Incoming.LOG.info("SANDBOX > {}", result);
        if (running != null) write(running, result);
        else if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(Component.literal("fx: " + result), false);
        running = null;
    }

    // ---------------------------------------------------------------- команды

    private void onCommands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("fx").then(Commands.argument("line", StringArgumentType.greedyString()).executes(ctx -> {
            String line = StringArgumentType.getString(ctx, "line");
            if (busy()) {
                ctx.getSource().sendFailure(Component.literal("fx: занято"));
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
            case TIME -> {
                String t = c.args().getFirst();
                server("time set " + (t.equals("day") ? "6000" : t.equals("night") ? "18000" : t));
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
                if (lastEffect == null) finish("error эффектов ещё не было");
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

    /** Снять все эффекты: у клиента — как при выходе из мира, у сервера — снаряды и огонь у точки. */
    private void clear() {
        raid.clear();
        IncomingClient.resetSession();
        server("incoming clear");
        server("kill @e[type=!minecraft:player]");
        BlockPos s = BlockPos.containing(site);
        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air replace minecraft:fire", s.getX() - 20, GROUND, s.getZ() - 20,
                s.getX() + 20, GROUND + 6, s.getZ() + 20));
        // пожар ({@code fire}) — на незераке: земля снова трава
        server(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:grass_block replace minecraft:netherrack", s.getX() - 20, GROUND,
                s.getZ() - 20, s.getX() + 20, GROUND, s.getZ() + 20));
        effectTick = -1;
        capture = null;
    }

    /** Взрывы налёта ({@code raid}), что ещё впереди: тик, x, z (биты double), зерно. */
    private final List<long[]> raid = new ArrayList<>();

    /** Взрывы налёта, чей тик пришёл: пакет взрыва шахеда только у клиента, как {@code blast}. */
    private void raidNext(Minecraft mc) {
        while (!raid.isEmpty() && raid.getFirst()[0] <= tick) {
            long[] r = raid.removeFirst();
            Vec3 pos = new Vec3(Double.longBitsToDouble(r[1]), GROUND + 1, Double.longBitsToDouble(r[2]));
            GroundMaterial mat = GroundMaterial.sample(mc.level, BlockPos.containing(pos));
            ClientHooks.get().blast(new S2C.Blast(S2C.Blast.DRONE, pos, mat.ordinal(), (float) pos.y, r[3], Optional.empty(), WeaponType.DRONE.id(),
                    S2C.Blast.DOWN));
        }
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
        // воронка и пожары настоящего взрыва остаются на его месте, остальное — снова на нетронутой точке
        if (c.verb() != SandboxCommand.Verb.REAL && c.verb() != SandboxCommand.Verb.NUKE) site = HOME;
        switch (c.verb()) {
            case BLAST -> {
                placeCamera();
                WeaponType w = c.weapon();
                WeaponSpec spec = w.spec();
                if (w == WeaponType.BUNKER) throw new IllegalArgumentException("бомба без сервера не взрывается: flight bunker");
                int kind = switch (spec.blast()) {
                    case MISSILE -> S2C.Blast.MISSILE;
                    case ROCKET -> S2C.Blast.ROCKET;
                    case DRONE, NONE -> S2C.Blast.DRONE;
                };
                Vec3 ground = new Vec3(site.x, GROUND + 1, site.z), pos = ground.add(0, c.number(2, 0), 0);
                GroundMaterial mat = GroundMaterial.sample(level, BlockPos.containing(ground));
                long seed = c.args().size() > 1 ? c.seedAt(1) : 1;
                // тот же вход, что у пакета сервера (ClientHooks): картинка и звук взрыва — как в игре; земля — трава под точкой
                ClientHooks.get().blast(new S2C.Blast(kind, pos, mat.ordinal(), (float) ground.y, seed, Optional.empty(), w.id(), S2C.Blast.DOWN));
                begin();
            }
            case RAID -> {
                placeCamera();
                int n = (int) c.number(0, 16);
                double radius = c.number(1, 120), every = c.number(2, 3);
                java.util.Random rnd = new java.util.Random(7);
                raid.clear();
                for (int i = 0; i < n; i++) {
                    double a = 2 * Math.PI * rnd.nextDouble(), r = radius * Math.sqrt(rnd.nextDouble());
                    raid.add(new long[]{tick + Math.round(i * every * 20), Double.doubleToLongBits(site.x + r * Math.cos(a)),
                            Double.doubleToLongBits(site.z + r * Math.sin(a)), i + 1});
                }
                raidNext(mc);
                begin();
            }
            case AIRBURST -> {
                placeCamera();
                long seed = c.args().isEmpty() ? 1 : c.seed();
                ClientHooks.get().intercept(new S2C.Intercept(site.add(0, AIRBURST_HEIGHT, 0), false, Optional.empty(), seed));
                begin();
            }
            case REAL, NUKE -> {
                freshX += c.verb() == SandboxCommand.Verb.NUKE ? NUKE_STEP : REAL_STEP;
                site = HOME.add(freshX, 0, 0);
                placeCamera();
                pending = Pending.SITE;
            }
            case LAUNCH, FLIGHT -> {
                placeCamera();
                WeaponType w = c.weapon();
                int x = (int) Math.floor(site.x), z = (int) Math.floor(site.z);
                if (c.verb() == SandboxCommand.Verb.LAUNCH) {
                    server(String.format(Locale.ROOT, "incoming salvo %s 1 0 at %d %d from %d %d", w.getSerializedName(), x, z - LAUNCH_RANGE, x, z));
                } else {
                    int from = (int) c.number(1, 600);
                    server(String.format(Locale.ROOT, "incoming salvo %s 1 0 at %d %d from %d %d", w.getSerializedName(), x, z - from, x, z + from));
                }
                pending = c.verb() == SandboxCommand.Verb.LAUNCH ? Pending.LAUNCH : Pending.FLIGHT;
            }
            case FIRE -> {
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
            default -> throw new IllegalArgumentException("не эффект: " + c);
        }
    }

    /** Эффект начался в этом тике: часы кадров и звука. */
    private void begin() {
        pending = null;
        effectTick = tick;
        Incoming.LOG.info("SANDBOX effect {} at {} tick {} audio {}", pendingCmd, xyz(site), tick, audioBytes());
        capture = armed;
        armed = null;
        if (capture != null) capture.audioStart = audioBytes();
        else finish("ok " + pendingCmd);
    }

    private void advance(Minecraft mc) {
        if (waitUntil >= 0 && tick >= waitUntil) {
            waitUntil = -1;
            finish("ok");
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
                    if (ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().stream().anyMatch(a -> !knownNukes.contains(a.d.id()))) begin();
                }
                case LAUNCH, FLIGHT -> watchFlight(mc);
            }
            if (pending != null && tick - pendingSince > FLIGHT_LIMIT) {
                pending = null;
                finish("error не дождался начала эффекта за " + FLIGHT_LIMIT + " тиков");
            }
        }
        Capture cap = capture;
        if (cap != null && effectTick >= 0 && cap.next >= cap.times.length && tick >= effectTick + Math.round(cap.wavSeconds * 20) + 10) {
            capture = null;
            write(cap.out.resolve("capture.json"), json(cap));
            finish("ok " + cap.out);
        }
    }

    /** Новое место настоящего взрыва: подождать, пока земля у клиента и у сервера готова, — и взрыв или подрыв. */
    private void siteReady(Minecraft mc) {
        if (tick - pendingSince < 40 || !loaded(mc, mc.player.blockPosition())) return;
        // точка в прорисовке — и её земля у клиента; ядерку дальше прорисовки сервер грузит сам (NuclearTickets)
        BlockPos s = BlockPos.containing(site);
        if (mc.player.position().distanceTo(site) < mc.options.getEffectiveRenderDistance() * 16 - 32 && !loaded(mc, s)) return;
        SandboxCommand c = pendingCmd;
        pending = Pending.SERVER;
        knownNukes.clear();
        ua.zentix.incoming.client.nuclear.ClientNuclear.detonations().forEach(a -> knownNukes.add(a.d.id()));
        Vec3 at = new Vec3(site.x, GROUND + 1, site.z);
        onServer(true, server -> {
            if (c.verb() == SandboxCommand.Verb.REAL) Warheads.detonate(server.overworld(), c.weapon(), at, null, null);
            else run(server, String.format(Locale.ROOT, "incoming nuke now at %.1f %.1f %.1f %d %s", at.x, at.y, at.z, (int) c.number(0),
                    c.args().size() > 1 ? c.args().get(1) : "ground"));
        });
    }

    private static boolean loaded(Minecraft mc, BlockPos at) {
        return mc.level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4);
    }

    /** Пуск — с первого движения снаряда у точки; пролёт — в миг, когда он ближе всего к ней. */
    private void watchFlight(Minecraft mc) {
        if (projectile == null || projectile.isRemoved()) {
            projectile = null;
            double best = Double.MAX_VALUE;
            for (var e : mc.level.entitiesForRendering()) {
                if (!(e instanceof StrikeProjectile p) || p.weapon() != pendingCmd.weapon()) continue;
                double d = p.position().distanceToSqr(site);
                if (d < best) {
                    best = d;
                    projectile = p;
                }
            }
            if (projectile == null) return;
            firstSeen = projectile.position();
            Incoming.LOG.info("SANDBOX projectile seen at {}", xyz(firstSeen));
        }
        Vec3 pos = projectile.position();
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

    // ---------------------------------------------------------------- кадры

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
        Incoming.LOG.info("SANDBOX shot {} age {} s fps {}; layer: {}; smoke: {}", file, String.format(Locale.ROOT, "%.3f", age), mc.getFps(),
                ua.zentix.incoming.client.fx.layer.FxLayer.describeTiming(), ua.zentix.incoming.client.far.FarRenderer.describeSmoke());
        cap.next++;
    }

    /** Числа модели поверх кадра: последний взрыв, дальние взрывы и их звук, звук у уха, пожары, камера. */
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
        // проекция интерфейса ещё стоит, а сдвиг вида по z (−11000) ваниль уже сняла: без него текст вне глубины проекции
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

    // ---------------------------------------------------------------- камера и сервер

    /** Камера — на {@link #camDist} от точки по азимуту {@link #camBearing}, на {@link #camHeight} над землёй, взгляд — на точку. */
    private void placeCamera() {
        double a = Math.toRadians(camBearing);
        // азимут — как поворот игрока: 0 — к югу (+Z), 90 — к западу (−X)
        Vec3 eye = site.add(-Math.sin(a) * camDist, 0, Math.cos(a) * camDist);
        double y = GROUND + 1 + camHeight;
        String me = Minecraft.getInstance().player.getGameProfile().getName();
        server(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f facing %.2f %.2f %.2f", me, eye.x, y - 1.62, eye.z, site.x, GROUND + 1 + camLook, site.z));
    }

    private void server(String command) {
        onServer(false, s -> run(s, command));
    }

    /** Задача потоку сервера; {@code effect} — сам эффект: его конец ждёт {@link Pending#SERVER}. */
    private void onServer(boolean effect, java.util.function.Consumer<MinecraftServer> action) {
        MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
        if (s == null) throw new IllegalStateException("нет встроенного сервера");
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

    // ---------------------------------------------------------------- файлы

    private long audioBytes() {
        try {
            return Files.size(FMLPaths.GAMEDIR.get().resolve("audio.wav"));
        } catch (IOException e) {
            return -1;
        }
    }

    private String json(Capture c) {
        return String.format(Locale.ROOT, "{\"name\": \"%s\", \"effect\": \"%s\", \"site\": [%.1f, %.1f, %.1f], \"camera\": [%.1f, %.1f, %.1f, %.1f], "
                        + "\"audio\": {\"file\": \"%s\", \"start\": %d, \"seconds\": %.2f}, \"frames\": [%s]}\n",
                c.name, lastEffect, site.x, site.y, site.z, camDist, camHeight, camBearing, camLook,
                FMLPaths.GAMEDIR.get().resolve("audio.wav").toAbsolutePath(), c.audioStart, c.wavSeconds, String.join(", ", c.frames));
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
