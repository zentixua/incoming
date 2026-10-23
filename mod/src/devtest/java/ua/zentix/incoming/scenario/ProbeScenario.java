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
 * над струёй ускорителя ({@link #ABOVE_JET}) и сбоку; {@code flyby} — шахед проходит над зрителем (звук);
 * {@code water-day-50} — шахед в пруд 41×41×4 (взрыв на воде), {@code debris-night-50} — шахед в крышу деревянного
 * дома 9×5×9 (горящие обломки, свет огня на мир, дрожание воздуха), {@code substation-night-50} — шахед в подстанцию
 * (дуга, горящее масло, гул и треск); {@code bomb-day-50}, {@code bomb-night-50} — бомба B-2 в грунт равнины, зритель в 50
 * блоках ({@code bomb-day-150}, {@code bomb-night-150}, {@code bomb-day-300} — в 150 и 300: у прорыва ~1 т в воздухе слой пыли
 * у земли — десятки метров, и в 50 блоках зритель внутри него), кадры от первого взрыва бомбы (у 3.4.1 — взрыв входа в
 * грунт, дальше — подрыв); {@code sam-side}, {@code sam-behind}, {@code sam-night-side} — ЗРК пускает ракету по шахеду,
 * зритель сбоку от ЗРК и за ним (дым схода, след, звук мотора); {@code sam-burst}, {@code sam-night-burst} — ЗРК
 * заряжается, когда шахед в ~180 блоках, разрыв — в ~70 блоках сбоку от зрителя. У каждой сцены своё место на равнине.
 * <p>
 * В лог — строки {@code SCENARIO probe …}: взрыв или старт (тик клиента, игровое время, байт в audio.wav — по нему
 * режется звук), снимки. Время каждого кадра — logs/probe-frames.txt: «сцена игровое_время мс».
 */
final class ProbeScenario {
    private record Step(int at, Runnable action) {}

    private static final List<String> ALL = List.of("drone-day-50", "drone-day-300", "drone-night-50", "drone-night-300",
            "grad-day", "grad-night", "launch-behind", "launch-side", "launch-night-behind", "flyby",
            // S8 — в конце: места прежних сцен — по их номеру в этом списке
            "water-day-50", "debris-night-50", "substation-night-50",
            // S5
            "bomb-day-50", "bomb-night-50", "bomb-day-150", "bomb-night-150", "bomb-day-300",
            // S6
            "sam-side", "sam-behind", "sam-night-side", "sam-burst", "sam-night-burst");
    /** Кадры сцен с пожаром и подстанцией: дольше — дым горящего масла и пожара встаёт десятками секунд. */
    private static final double[] FIRE_SHOTS = {0.1, 0.5, 1, 3, 10, 30, 60};
    /** Кадры ЗРК: секунды после схода ракеты и после разрыва. */
    private static final double[] SAM_SHOTS = {0.25, 0.5, 1, 1.5, 2, 3, 5, 8, 15};
    private static final double[] BURST_SHOTS = {0.1, 0.25, 0.5, 1, 2, 3, 6, 12};
    /** Дольше полёта ракеты ЗРК с запасом разрыва не ждать, тиков. */
    private static final int SPEC_FLIGHT = 400;
    /** Кадры шахеда, секунды после взрыва. */
    private static final double[] DRONE_SHOTS = {0.1, 0.5, 1, 3, 10, 30};
    private static final double[] GRAD_SHOTS = {0.1, 0.5, 1, 3, 6, 12, 20, 30, 45, 60};
    private static final double[] LAUNCH_SHOTS = {0.5, 1, 1.5, 2, 3, 4};
    /**
     * Камера сзади — на столько блоков за ракетой по её оси и на столько над осью. Прямо на оси она стояла бы в струе
     * ускорителя: струя идёт от сопла по оси назад, в 24 м её полуширина по скорости ~2,3 м (0,094 длины, Pope, «Turbulent
     * Flows», табл. 5.1; по примеси — чуть шире), а толща её окиси алюминия по оси до сопла — сотни (кадр сплошь серый, как
     * в тумане; 3.4.1 рисовал след плоской лентой и видел сквозь него). Настоящий вид старта сзади — снаружи струи: над
     * осью на ~2,5 её полуширины, ~14° от оси; что ракета и струя оттуда видны, показывают кадры шлагбаума.
     */
    private static final double BEHIND = 24, ABOVE_JET = 6;
    /**
     * Площадка старта: верх её каменного пола — y этого числа (пол — блоки на y − 1). Камера, поставленная ногами ниже
     * верха пола, — в блоке: сервер считает это новым столкновением (`ServerGamePacketListenerImpl.handleMovePlayer`,
     * `isPlayerCollidingWithAnythingNew`) и возвращает игрока на прежнее место с новым поворотом. Так камера сзади
     * стояла до 23.10: глаз на 201,6 при росте глаза 1,62 — ноги на 199,98, и до ~1 с (пока ракета не поднимет камеру выше)
     * кадр снимался с места игрока до старта, а смотрел туда, где ракета, — без пусковой и облака в кадре (при уходе
     * ракеты к −X сдвиг ~25 блоков, к +X — ~3, и было незаметно).
     */
    private static final int LAUNCH_FLOOR = 200;
    /** Камера, сдвинутая от места, куда её поставила сцена, на столько блоков к кадру, — кадр не с той точки. */
    static final double CAMERA_SLIP = 1;
    /**
     * Время игры, с которого сцена начинается, — как в эталонном прогоне всех сцен подряд (сравнение 22.10, compare-final:
     * начало сцены в v3.4.1 и main — в пределах 200 тиков). Ветер мира и его класс устойчивости зависят только от времени
     * игры ({@code util.Wind}, {@code util.Air}): ночью у границы классов D и E ветер у земли — 1 или 5 м/с, и шлейф пожара
     * то встаёт столбом, то стелется. Выборка сцен без этого шла бы в другом воздухе, чем эталон; сцена, до которой игра
     * не дошла, догоняет своё время ускоренными тиками ({@code /tick sprint}). Назад время не идёт, поэтому сцены выборки
     * идут в порядке эталона ({@link #ALL}); сцена, начавшаяся позже (прошлая сцена шла дольше: сцены старта ждут схода
     * ракеты), годна, пока её воздух тот же, что в эталонное время ({@link #air}: ветер меняется за игровые сутки, а не за
     * сотни тиков, но у границы классов хватает и малого), иначе — строка {@code SCENARIO probe FAIL} и выход (её же ловит
     * {@code tools/look_gate/check.py}). У пролёта время — по прогону main 22.10 (Cmain: между стартом ночью и взрывом на
     * воде, как у 3.4.1).
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
     * Тот же воздух: ветер у земли — не дальше этого, м/с, его направление — градусов, класс устойчивости (непрерывный,
     * {@code util.Stability}) — долей класса (⚠ оценка: снос дыма на минуту — в пределах метров и градусов).
     */
    static final double SAME_WIND = 0.3, SAME_DIR = 3, SAME_CLASS = 0.1;
    /** Время игры, в которое нынешняя сцена началась; −1 — ещё ни одна. */
    private long startedAt = -1;
    /** Сцена уже догнала своё время игры; ускорение до него уже заказано. */
    private boolean onTime, sprinting;

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
    /** Где сцена поставила глаз в прошлом тике (кадр снимка нарисован с него); null — камера сцены не двигает. */
    private Vec3 placed;
    /** ЗРК: где стоит, ракета у клиента, тик схода, заряжен ли (сцена разрыва), тик разрыва. */
    private BlockPos sam;
    private net.minecraft.world.entity.Entity interceptor;
    private int samLaunch = -1, samBurst = -1;
    private boolean samLoaded;
    /** Пролёт: ближе всего к зрителю. */
    private StrikeProjectile drone;
    private double closest = Double.MAX_VALUE;

    ProbeScenario() {
        String prop = System.getProperty("incoming.probe.cases");
        // выборка — в порядке эталона: время игры догоняется только вперёд
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
            // сравнение звука — без оглушения: сцены идут подряд одним игроком, и глухота от взрыва в 50 м перешла бы в
            // следующие сцены (прогоны F2b7, F2b8 22.10 шли со слухом, и хвосты взрывов в них заглушены)
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

    /**
     * Тот же ли воздух мира в тик {@code now}, что в тик {@code reference}, при солнце сцены (день — в зените, ночь — под
     * горизонтом), без дождя: «same», «differs: …» или «none» — у этой версии мода воздуха мира нет (3.4.1: дым и звук от
     * времени игры не зависят). Отражением: класс один в деревьях всех версий.
     */
    static String sameAir(long now, long reference, boolean night) {
        double[] a, b;
        try {
            a = air(now, night);
            b = air(reference, night);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // воздух в этой версии есть, а прочитать его нельзя (класс переделан): прогон не годится, а не проходит молча
            return "differs: air unreadable (" + e + ")";
        }
        if (a == null || b == null) return "none";
        double dir = Math.abs(Math.toDegrees(Math.atan2(Math.sin(a[1] - b[1]), Math.cos(a[1] - b[1]))));
        String numbers = String.format(Locale.ROOT, "wind %.2f/%.2f m/s, direction %.1f deg apart, class %.2f/%.2f", a[0], b[0], dir, a[2], b[2]);
        boolean same = Math.abs(a[0] - b[0]) <= SAME_WIND && dir <= SAME_DIR && Math.abs(a[2] - b[2]) <= SAME_CLASS;
        return (same ? "same: " : "differs: ") + numbers;
    }

    /**
     * Ветер в 10 м над уровнем моря, м/с, его направление, рад, и класс устойчивости в тик {@code gameTime}; null —
     * класса воздуха в этой версии нет. Имена — те же, что проверяет {@code AirTest.probeReadsTheAirByTheseNames}.
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

    /** Сцены выборки в порядке эталона; незнакомая сцена — ошибка (место и время у неё неизвестны). */
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
     * Прогон не годится для сравнения: строка {@code SCENARIO probe FAIL} и выход — сразу, а не после часа съёмки
     * ({@code tools/look_gate/check.py} ловит её и сам).
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
        // сцена кончилась: и в конце воздух тот же, что у эталона на том же месте его сцены (сцены идут до минуты, а
        // начатая позже могла к концу перейти границу класса)
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

    /** Начало сцены {@link #current}: сперва — её время игры ({@link #START}), потом — место и план. */
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
        drone = null;
        launchTick = -1;
        blastTick = -1;
        closest = Double.MAX_VALUE;
        // ЗРК прошлой сцены — прочь: заряженный, в загруженных чанках, он бил бы шахед следующей сцены в 1,5 км
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
        // зритель взрывов — наблюдатель: волна залпа отбрасывает игрока в творческом режиме (кадры из-под воды)
        cmd(current.startsWith("launch") || current.equals("flyby") ? "gamemode creative" : "gamemode spectator");
        if (current.startsWith("sam")) {
            planSam(tick, site, current.endsWith("burst"));
            return;
        }
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
        boolean grad = current.startsWith("grad"), bomb = current.startsWith("bomb");
        int dist = grad ? 30 : current.endsWith("-300") ? 300 : current.endsWith("-150") ? 150 : 50;
        at(s + 120, () -> {
            Minecraft mc = Minecraft.getInstance();
            int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, site[0], site[1]);
            // чанк цели мог ещё не прийти клиенту (высота −27 в прогоне B): расходится с генератором — генератор
            int gen = groundAt(site[0], site[1]);
            if (Math.abs(y - gen) > 12) y = gen;
            target = new Vec3(site[0] + 0.5, y, site[1] + 0.5);
            // зритель — выше рельефа на всём пути к цели (по генератору), не ниже заданной высоты над целью
            int above = grad ? 10 : dist == 50 ? 18 : dist == 150 ? 30 : 40;
            double eyeY = target.y + above;
            // деревья генератор не знает: над рельефом пути — с запасом на крону
            for (int d = 0; d <= dist; d += 8) eyeY = Math.max(eyeY, groundAt(site[0], site[1] - d) + 14);
            Vec3 eye = new Vec3(target.x, eyeY, target.z - dist);
            double lookUp = grad ? 6 : dist == 50 ? 8 : dist == 150 ? 20 : 15;
            cmd(String.format(Locale.ROOT, "tp @s %.1f %.1f %.1f facing %.1f %.1f %.1f", eye.x, eye.y, eye.z, target.x, target.y + lookUp, target.z));
            Incoming.LOG.info("SCENARIO probe {} target {} eye {}", current, xyz(target), xyz(eye));
        });
        // постройки сцен S8 — до пуска, на земле цели (ванильные команды: те же в любой версии мода)
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

    /** Взрыв пришёл: кадры по списку сцены, потом следующая сцена. */
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

    /** Старт ракеты с пусковой у зрителя; камера — за ракетой по её оси или сбоку. */
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
            // клиент снял ракету до схода (сервер встал на загрузке чанков, сущность пришла заново): та же ракета —
            // новый объект, старый не двигается, и старт не засекался никогда (прогон F2c 22.10)
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
        // камера: сзади, над струёй, или сбоку поперёк оси на высоте ракеты
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
        // ногами не ниже верха пола площадки (LAUNCH_FLOOR): иначе сервер вернёт игрока назад
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
            // слои мотора у уха — громкость против мерки и тон ({@code ClientSounds.describe}): сверка записи с моделью
            if (tick % 10 == 0) Incoming.LOG.info("SCENARIO probe flyby at {} d={} v={} engines:{}", xyz(drone.position()), Math.round(d),
                    String.format(Locale.ROOT, "%.2f", drone.speed()), ua.zentix.incoming.client.sound.ClientSounds.describe());
        }
    }

    /**
     * ЗРК на площадке в 25 блоках над землёй (вокруг расчищено небо), шахед идёт на него из-за 1400 блоков (разрыв — из-за 700) к точке
     * за ним. Старт: ЗРК заряжен сразу, зритель сбоку в 45 блоках или за ним в 30 на линии пуска. Разрыв: ЗРК пуст, заряжается,
     * когда шахед у клиента ближе 180 блоков по линии, зритель — в 70 блоках сбоку от места встречи.
     */
    private void planSam(int s, int[] site, boolean burst) {
        at(s + 2, () -> cmd(String.format(Locale.ROOT, "forceload add %d %d %d %d", site[0] - 32, site[1] - 48, site[0] + 32, site[1] + 48)));
        at(s + 120, () -> {
            // площадка — над лесом и холмами вокруг: у сцены с рельефом, закрывающим шахед, пуск и встреча — в другом месте
            // у каждой версии (в 3.4.1 рельеф радар не закрывает), и сравнивать нечего
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
        // цена слоя эффектов раз в секунду после взрыва (бюджет: залп «Града» — не больше 2 мс видеокарты)
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
            // взрыв у кадра, а не в миг события сервера: пакет взрыва приходит позже счётчика взрывов
            Incoming.LOG.info("SCENARIO probe shot {} blast {}", name, ua.zentix.incoming.client.far.FarBlasts.describeLast());
            // пожары у кадра: блоков огня, подъём шлейфа и ветер (дом, подстанция, огонь от шара топлива)
            Incoming.LOG.info("SCENARIO probe shot {} smoke {}", name, ua.zentix.incoming.client.fx.FireSmoke.describe());
            // кадр — с той точки, куда сцена поставила глаз (сервер мог вернуть игрока назад): иначе check.py бракует прогон
            if (placed != null) {
                Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
                double off = eye.distanceTo(placed);
                Incoming.LOG.info("SCENARIO probe shot {} camera {} off {}", name, xyz(eye),
                        String.format(Locale.ROOT, "%.2f", off));
            }
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
