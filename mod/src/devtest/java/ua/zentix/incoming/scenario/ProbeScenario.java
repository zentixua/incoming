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
 * Проба картинки и звука после 3.5.0 (handoff P): одни и те же сцены в любой версии мода — только ваниль, команды
 * {@code /incoming} и {@link StrikeProjectile}, которые есть и в 3.4.1, и в 3.5.0. Класс один и тот же во всех деревьях
 * пробы, поэтому места, время суток и зерно у вариантов совпадают.
 * <p>
 * Сцены ({@code incoming.probe.cases}, через запятую; без свойства — все): {@code drone-day-50}, {@code drone-day-300},
 * {@code drone-night-50}, {@code drone-night-300} — шахед в землю, зритель в 50 и 300 блоках, кадры через 0,1–30 с;
 * {@code grad-day}, {@code grad-night} — залп «Града» 40 снарядов с разбросом 15 в 30 блоках, кадры до минуты;
 * {@code launch-behind}, {@code launch-side}, {@code launch-night-behind} — старт ракеты с пусковой, камера за ракетой
 * по её оси и сбоку; {@code flyby} — шахед проходит над зрителем (звук). У каждой сцены своё место на равнине.
 * <p>
 * В лог — строки {@code SCENARIO probe …}: взрыв или старт (тик клиента, игровое время, байт в audio.wav — по нему
 * режется звук), снимки. Время каждого кадра — logs/probe-frames.txt: «сцена игровое_время мс».
 */
final class ProbeScenario {
    private record Step(int at, Runnable action) {}

    private static final List<String> ALL = List.of("drone-day-50", "drone-day-300", "drone-night-50", "drone-night-300",
            "grad-day", "grad-night", "launch-behind", "launch-side", "launch-night-behind", "flyby");
    /** Кадры шахеда, секунды после взрыва. */
    private static final double[] DRONE_SHOTS = {0.1, 0.5, 1, 3, 10, 30};
    private static final double[] GRAD_SHOTS = {0.1, 0.5, 1, 3, 6, 12, 20, 30, 45, 60};
    private static final double[] LAUNCH_SHOTS = {0.5, 1, 1.5, 2, 3, 4};
    /** Сколько ждать взрыва после команды, тиков. */
    private static final int BLAST_LIMIT = 2400;

    private final List<Step> steps = new ArrayList<>();
    private final List<String> cases;
    private final Writer frames;
    private int tick = -1;
    private int caseIndex = -1;
    private String current = "-";
    private Vec3 target = Vec3.ZERO;
    /** Места сцен на равнине (x, z), ищет сервер в начале. */
    private volatile List<int[]> sites;
    private volatile boolean armed;
    private final AtomicInteger blasts = new AtomicInteger();
    private int seenBlasts, firedAt = -1, blastTick = -1;
    private boolean hover;
    /** Старт ракеты: снаряд у клиента, тик старта, где он был, сбоку или сзади. */
    private StrikeProjectile missile;
    private int launchTick = -1;
    private Vec3 launchFrom = Vec3.ZERO, lastPos = Vec3.ZERO, axis = new Vec3(0, 0, 1);
    private boolean behind;
    /** Пролёт: ближе всего к зрителю. */
    private StrikeProjectile drone;
    private double closest = Double.MAX_VALUE;

    ProbeScenario() {
        String prop = System.getProperty("incoming.probe.cases");
        cases = prop == null || prop.isBlank() ? ALL : List.of(prop.split(","));
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
            cmd("weather clear");
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doFireTick true");
            Minecraft.getInstance().options.hideGui = true;
            var server = Minecraft.getInstance().getSingleplayerServer();
            // поиск мест — в своём потоке: высоты генератора считаются шумом, на потоке сервера это минуты без тиков
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                var level = server.overworld();
                List<int[]> found = new ArrayList<>();
                for (int k = 0; k < cases.size(); k++) {
                    // место — по сцене, а не по её номеру в выборке: у сцены всегда одна и та же площадка
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

    private void nextCase() {
        if (sites == null) {
            at(tick + 20, this::nextCase);
            return;
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
        int[] site = sites.get(caseIndex);
        armed = false;
        firedAt = -1;
        missile = null;
        drone = null;
        launchTick = -1;
        blastTick = -1;
        closest = Double.MAX_VALUE;
        Incoming.LOG.info("SCENARIO probe case {} site {} {}", current, site[0], site[1]);
        boolean night = current.contains("night");
        cmd(night ? "time set 18000" : "time set 6000");
        // зритель взрывов — наблюдатель: волна залпа отбрасывает игрока в творческом режиме (кадры из-под воды)
        cmd(current.startsWith("launch") || current.equals("flyby") ? "gamemode creative" : "gamemode spectator");
        hover = true;
        Minecraft.getInstance().player.getAbilities().flying = true;
        // сначала над целью: чанки и высота цели у сервера и у клиента
        cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 0 60", site[0], 230, site[1]));
        int s = tick;
        if (current.startsWith("launch")) planLaunch(s, site, current.contains("behind"));
        else if (current.equals("flyby")) planFlyby(s, site);
        else planBlast(s, site);
    }

    /**
     * Ровная суша у (x, z) по высотам генератора: квадрат ±40 блоков без воды и с перепадом не больше 4, и путь зрителя
     * на 300 блоков к −Z не выше цели на 12; ищется кольцами до 1,5 км, не нашлось — сама точка.
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

    /** Шахед или залп «Града» в землю: зритель висит перед целью, кадры от первого взрыва у цели. */
    private void planBlast(int s, int[] site) {
        boolean grad = current.startsWith("grad");
        int dist = grad ? 30 : current.endsWith("-300") ? 300 : 50;
        at(s + 120, () -> {
            Minecraft mc = Minecraft.getInstance();
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site[0], site[1]);
            // чанк цели мог ещё не прийти клиенту (высота −27 в прогоне B): расходится с генератором — генератор
            int gen = groundAt(site[0], site[1]);
            if (Math.abs(y - gen) > 12) y = gen;
            target = new Vec3(site[0] + 0.5, y, site[1] + 0.5);
            // зритель — выше рельефа на всём пути к цели (по генератору), не ниже заданной высоты над целью
            int above = grad ? 10 : dist == 50 ? 18 : 40;
            double eyeY = target.y + above;
            // деревья генератор не знает: над рельефом пути — с запасом на крону
            for (int d = 0; d <= dist; d += 8) eyeY = Math.max(eyeY, groundAt(site[0], site[1] - d) + 14);
            Vec3 eye = new Vec3(target.x, eyeY, target.z - dist);
            double lookUp = grad ? 6 : dist == 50 ? 8 : 15;
            cmd(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, target.x, target.y + lookUp, target.z));
            Incoming.LOG.info("SCENARIO probe {} target {} eye {}", current, xyz(target), xyz(eye));
        });
        at(s + 420, () -> {
            var c = IncomingConfig.SERVER;
            c.launchNearPlayer.set(false);
            c.droneFlightTime.set(5);
            seenBlasts = blasts.get();
            armed = true;
            firedAt = tick;
            if (grad) cmd(String.format(Locale.ROOT, "incoming salvo rocket 40 15 at %.1f %.1f %.1f", target.x, target.y, target.z));
            else cmd(String.format(Locale.ROOT, "incoming drone at %.1f %.1f %.1f", target.x, target.y, target.z));
            Incoming.LOG.info("SCENARIO probe {} fire tick {} audio {}", current, tick, audioBytes());
        });
    }

    /** Взрыв пришёл: кадры по списку сцены, потом следующая сцена. */
    private void onBlast() {
        blastTick = tick;
        Minecraft mc = Minecraft.getInstance();
        Incoming.LOG.info("SCENARIO probe {} blast tick {} game {} audio {} wall {}", current, tick, mc.level.getGameTime(), audioBytes(),
                System.currentTimeMillis());
        double[] times = current.startsWith("grad") ? GRAD_SHOTS : DRONE_SHOTS;
        int last = 0;
        for (double t : times) {
            int dt = Math.max(1, (int) Math.round(t * 20));
            last = dt;
            shot(tick + dt, label(t));
        }
        at(tick + last + 40, this::nextCase);
    }

    /** Старт ракеты с пусковой у зрителя; камера — за ракетой по её оси или сбоку. */
    private void planLaunch(int s, int[] site, boolean fromBehind) {
        behind = fromBehind;
        int y = 200;
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
        // камера: строго сзади по оси (не ниже площадки) или сбоку поперёк оси на высоте ракеты
        Vec3 cam;
        if (behind) {
            cam = pos.subtract(axis.scale(24));
            if (cam.y < 201.6) cam = new Vec3(cam.x, 201.6, cam.z);
        } else {
            Vec3 side = new Vec3(axis.z, 0, -axis.x);
            if (side.lengthSqr() < 1e-4) side = new Vec3(1, 0, 0);
            cam = pos.add(side.normalize().scale(30));
        }
        hover = true;
        p.getAbilities().flying = true;
        Vec3 d = pos.subtract(cam);
        float yaw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
        p.moveTo(cam.x, cam.y - p.getEyeHeight(), cam.z, yaw, pitch);
        p.setDeltaMovement(Vec3.ZERO);
        if (launchTick >= 0 && (tick - launchTick) % 10 == 0) {
            Incoming.LOG.info("SCENARIO probe {} t={}s missile {} axis {} cam {} angle-to-axis {}°", current,
                    String.format(Locale.ROOT, "%.1f", (tick - launchTick) / 20.0), xyz(pos),
                    String.format(Locale.ROOT, "%.2f %.2f %.2f", axis.x, axis.y, axis.z), xyz(cam),
                    Math.round(Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, d.normalize().dot(axis)))))));
        }
    }

    /** Шахед проходит над зрителем к цели в 200 блоках впереди (последний прямой участок — из-за спины). */
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
            if (tick % 10 == 0) Incoming.LOG.info("SCENARIO probe flyby at {} d={} v={}", xyz(drone.position()), Math.round(d),
                    String.format(Locale.ROOT, "%.2f", drone.speed()));
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
        if (current.startsWith("launch") && firedAt >= 0) launchEvents();
        if (current.equals("flyby") && firedAt >= 0) flybyEvents();
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
        if (frames != null && last != 0 && mc.level != null) {
            try {
                frames.write(current + " " + tick + " " + (now - last) / 1000 / 1000.0 + "\n");
                if (tick % 200 == 0) frames.flush();
            } catch (IOException ignored) {
                // замер, не игра
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
        });
    }

    private void at(int t, Runnable r) {
        steps.add(new Step(t, r));
    }

    /** Байт в audio.wav (драйвер «wave» OpenAL Soft пишет в реальном времени): место события в звуке. */
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
