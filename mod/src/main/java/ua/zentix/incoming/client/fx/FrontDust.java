package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.model.BlastLitter;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Stability;

import java.util.ArrayList;
import java.util.List;

/**
 * Крупная пыль с сорванного дёрна: фронт поднимает её один раз, затем она сносится и выпадает из перемешанного слоя.
 * Это детали срыва FrontLitter, а не второй физический слой GroundDust (его масса и оседание не меняются).
 * Место и момент срыва — тот же DustModel и ChemicalFront; контраст, размер клубов и подчёркнутый подъём — подача.
 * В отличие от задержки частиц в целых тиках, источник проверяет приход с долей тика: кольцо не возникает целиком.
 */
final class FrontDust implements SmokeGrid.Source {
    /**
     * Площадка срыва: место, расстояние до взрыва, приход фронта и положительная фаза, σ выросшего клуба по горизонтали,
     * площадь, контраст срыва, ветер и устойчивость; {@code until} — с какого возраста взрыва её клуб уже не виден.
     */
    record Patch(double x, double y, double z, double slant, double arrival, double phase, double sigma,
                 double area, double contrast, double windX, double windZ, double stability, double until) {
        Patch(double x, double y, double z, double slant, double arrival, double phase, double sigma,
              double area, double contrast, double windX, double windZ, double stability) {
            this(x, y, z, slant, arrival, phase, sigma, area, contrast, windX, windZ, stability,
                    seenUntil(arrival, phase, fullTau(area, contrast, sigma)));
        }
    }

    /**
     * Подача: сетка площадок — дискретизация кольца, не число частиц грунта. Шаг один вдоль и поперёк кольца,
     * {@link #STEP} м, у большого заряда шире — площадок не больше {@link #PATCHES} (у залпа — {@link #share}); клуб
     * выросшей площадки — σ = {@link #SPREAD} шага: соседи перекрываются, слой без бус. Цена: клубы источника
     * {@code SmokePuffs} каждый кадр сливает попарно; прежняя сетка 24 × 64 (у шахеда 864 клуба, шаг поперёк кольца
     * 2,2 м против 5,6 вдоль) стоила ~1 мс кадра на взрыв, залп — кратно.
     */
    static final double STEP = 5.6, SPREAD = 0.6;
    static final int PATCHES = 400;
    /**
     * Подача: видимых площадок у всех взрывов сразу не больше {@link #ALL_PATCHES} ({@link #share}): сверх него
     * прореживаются взрывы (сначала те, чья доля больше равной, из них старшие, затем дальние) — площадки группы сливаются
     * в одну с той же массой и шире, кольцо остаётся покрытым ({@link #thin}). Без общего предела залп из восьми
     * шахедов в одном месте рисовал восемь наложенных слоёв по ~360 клубов — слой эффектов +12 мс на кадр против main.
     */
    static final int ALL_PATCHES = 800;
    /** Взрывы, чьи площадки ещё видны (для общего предела). */
    private static final List<FrontDust> LIVE = new ArrayList<>();
    /** Уровней прореживания не больше: каждый вдвое реже по дуге или по кольцам. */
    static final int LEVELS = 24;
    /** Клуб прозрачнее этого {@code SmokePuffs} не рисует (непрозрачность середины 0,004): площадка дальше не нужна. */
    static final double FAINTEST = 0.004;
    /**
     * Подача: высота перемешанного слоя, толща поднятого по вертикали (запас на площадь источника) и наибольшая толща
     * одного клуба. Не новая масса в GroundDust. Толща 0,5 — кольцо видно с 20–100 м и не стоит стеной у камеры
     * (0,08 — кольца не было видно вовсе, 2,8 на клуб в R2 — бурая стена во весь кадр). У бегущего края клуб ещё
     * сжат фронтом (его 4σ — за фронтом): весь запас площадки в нём был бы непрозрачной точкой, поэтому толща клуба
     * не больше толщи выросшего и {@code EDGE_TAU}; остальное площадка отдаёт, когда клуб вырос.
     */
    static final double HEIGHT = 1.3, COLUMN_TAU = 0.5, EDGE_TAU = 0.8;
    /**
     * Цвет толстого слоя крошки на экране — сухая светлая пыль почвы: ω' ≈ 0,9 в красном (у минеральной пыли в видимом
     * 0,85–0,95, ⚠ оценка для крошки дёрна) — и альбедо по нему ({@link SmokeGrid#dustAlbedo}). Клуб берёт альбедо, а не
     * цвет экрана: цвет экрана вместо альбедо давал серый толстый слой, а у тёмной пыли (ω' ≈ 0,8) клуб в 0,8 толщи
     * при солнце сверху темнел серединой против светлой просвечивающей каймы («бублики»).
     */
    static final float SHOWN_R = 0.72f, SHOWN_G = 0.65f, SHOWN_B = 0.54f;
    static final float ALBEDO_R = SmokeGrid.dustAlbedo(SHOWN_R), ALBEDO_G = SmokeGrid.dustAlbedo(SHOWN_G),
            ALBEDO_B = SmokeGrid.dustAlbedo(SHOWN_B);
    @Nullable
    private final ClientLevel level;
    private final long born;
    private final ChemicalFront front;
    /** Место взрыва. */
    private final double cx, cy, cz;
    /** Площадки по кольцам изнутри наружу; у каждой — её кольцо; у колец — радиус и число мест. */
    private final Patch[] patches;
    private final int[] ring, spokes;
    private final double[] radius;
    private final double dr;
    /** Уровень прореживания и его площадки по номеру исходной: у первой в группе — слитая, у остальных — null. */
    private int thinned;
    private Patch[] shown;
    private double end;
    /** Групп на уровне (−1 — ещё не считали). */
    private final int[] groupCount = new int[LEVELS + 2];

    private FrontDust(@Nullable ClientLevel level, ChemicalFront front, Vec3 pos, long born, List<Patch> patches, List<Integer> rings,
                      int[] spokes, double[] radius, double dr) {
        this.front = front;
        this.level = level;
        this.born = born;
        cx = pos.x;
        cy = pos.y;
        cz = pos.z;
        this.patches = patches.toArray(Patch[]::new);
        ring = rings.stream().mapToInt(Integer::intValue).toArray();
        this.spokes = spokes;
        this.radius = radius;
        this.dr = dr;
        java.util.Arrays.fill(groupCount, -1);
        show(this.patches);
    }

    /** Покров места: высота верха и листва ли. */
    record Cover(int top, boolean leaves) {}

    /** Сорванный покров в столбе (bx, bz); null — срывать нечего (голый грунт, вода, камень, песок) или чанка нет. */
    interface Ground {
        @Nullable
        Cover at(int bx, int bz);
    }

    static void spawn(ClientLevel level, Vec3 pos, ChemicalFront front, double inner, double outer, double hob) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // За секунды жизни крошки — воздух в миг срыва (оценка постоянного ветра).
        FrontDust dust = layout(level, pos, front, inner, outer, hob, ClientAir.now(), (bx, bz) -> {
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null) return null;
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
            var cover = chunk.getBlockState(m.set(bx, top, bz));
            // Только сорванный покров; голый грунт, вода, камень и песок остаются у GroundDust.
            boolean leaves = cover.is(BlockTags.LEAVES);
            return leaves || cover.is(Blocks.GRASS_BLOCK) ? new Cover(top, leaves) : null;
        }, BlastTime.ticks());
        if (dust == null) return;
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        LIVE.removeIf(FrontDust::ended);
        share(LIVE, dust, BlastTime.ticks(), eye);
        SmokeGrid.add(dust);
    }

    /** Площадки срыва вокруг {@code pos} в кольце от {@code inner} до {@code outer} м; null — нигде не сорвано. */
    @Nullable
    static FrontDust layout(@Nullable ClientLevel level, Vec3 pos, ChemicalFront front, double inner, double outer, double hob, Air air,
                            Ground ground, long born) {
        List<Patch> patches = new ArrayList<>();
        List<Integer> places = new ArrayList<>();
        int rings = rings(inner, outer);
        double dr = (outer - inner) / rings, arc = arc(inner, outer);
        int[] counts = new int[rings];
        double[] radii = new double[rings];
        for (int j = 0; j < rings; j++) {
            double r = inner + dr * (j + 0.5);
            int spokes = spokes(r, arc);
            counts[j] = spokes;
            radii[j] = r;
            for (int i = 0; i < spokes; i++) {
                double a = 2 * Math.PI * (i + 0.5 * (j % 2)) / spokes;
                double x = pos.x + r * Math.cos(a), z = pos.z + r * Math.sin(a);
                Cover cover = ground.at(Mth.floor(x), Mth.floor(z));
                if (cover == null) continue;
                double y = cover.top() + 1.05, slant = Math.sqrt(r * r + (y - pos.y) * (y - pos.y));
                double along = cover.leaves() || y > pos.y - Math.max(0, hob) + 2 ? 1 : FrontLitter.along(r, Math.hypot(r, hob));
                double friction = BlastLitter.friction(front.kpa(slant), along);
                if (!BlastLitter.tearsCover(front.kpa(slant), along)) continue;
                double contrast = Math.min(1, friction / DustModel.Surface.COVERED.threshold() - 1);
                patches.add(new Patch(x, y, z, slant, front.arrivalSeconds(slant),
                        BlastModel.chemicalPhaseSeconds(slant, front.tntKg, front.surface), SPREAD * Math.max(dr, 2 * Math.PI * r / spokes),
                        2 * Math.PI * r * dr / spokes, contrast, air.windX(y + HEIGHT / 2), air.windZ(y + HEIGHT / 2), air.stability()));
                places.add(j);
            }
        }
        return patches.isEmpty() ? null : new FrontDust(level, front, pos, born, patches, places, counts, radii, dr);
    }

    /**
     * Подъём за импульс напора, затем выпадение крупной корзины: dm/dt = −v_settle m / h, как в DustModel.
     * Перемешанный слой постоянной высоты — приближение на секунды. Яркость уходит только вместе со взвесью;
     * конец положительной фазы прекращает подачу, но не удаляет поднятую пыль.
     */
    static double envelope(double age, double phase) {
        if (age <= 0 || phase <= 0) return 0;
        return DustModel.lifted(age / phase) * Math.exp(-BlastLitter.COVER_DUST_SETTLE * Math.max(0, age - phase) / HEIGHT);
    }

    /** Колец площадок в кольце срыва от {@code inner} до {@code outer} м: шаг поперёк — {@link #STEP}, у большого кольца шире. */
    static int rings(double inner, double outer) {
        double step = Math.max(STEP, Math.sqrt(Math.PI * (outer * outer - inner * inner) / PATCHES));
        return Math.max(1, (int) Math.ceil((outer - inner) / step));
    }

    /**
     * Шаг площадок вдоль кольца, м: {@link #STEP}, а у большого кольца — такой, чтобы с округлением по кольцам площадок было не
     * больше {@link #PATCHES}: Σ⌈2πr/шаг⌉ ≤ π(R² − r²)/(dr·шаг) + колец (середины колец: Σ r·dr = (R² − r²)/2).
     */
    static double arc(double inner, double outer) {
        int rings = rings(inner, outer);
        double dr = (outer - inner) / rings;
        return Math.max(STEP, Math.PI * (outer * outer - inner * inner) / (dr * Math.max(1, PATCHES - rings)));
    }

    /**
     * Общий предел ({@link #ALL_PATCHES}): {@code fresh} входит в {@code live}; пока видимых площадок у всех в миг
     * {@code now} (тики) больше предела — прореживается взрыв, чья доля больше равной (нет таких — любой, кого ещё можно),
     * из них старший, при равных — дальний от глаза. Прореживать некого — новый пыли не рисует: прежние уже в пределе
     * (видимых со временем только меньше).
     */
    static void share(List<FrontDust> live, FrontDust fresh, long now, Vec3 eye) {
        live.add(fresh);
        while (true) {
            int total = 0;
            for (FrontDust d : live) total += d.visible(now);
            if (total <= ALL_PATCHES) return;
            double fair = (double) ALL_PATCHES / live.size();
            FrontDust pick = null;
            boolean pickAbove = false;
            for (FrontDust d : live) {
                if (!d.canThin()) continue;
                boolean above = d.visible(now) > fair;
                if (pick == null || above && !pickAbove || above == pickAbove && (d.born < pick.born
                        || d.born == pick.born && d.distanceSqr(eye) > pick.distanceSqr(eye))) {
                    pick = d;
                    pickAbove = above;
                }
            }
            if (pick == null) {
                fresh.show(new Patch[fresh.patches.length]);
                return;
            }
            pick.thin();
        }
    }

    /** Видимых площадок в миг {@code now} (тики). */
    int visible(long now) {
        double elapsed = BlastTime.seconds(now, born, 0);
        int n = 0;
        for (Patch p : shown) if (p != null && p.until > elapsed) n++;
        return n;
    }

    /** Площадки, что рисуются сейчас (у первой в группе — слитая; остальные — null). */
    Patch[] shown() {
        return shown;
    }

    private double distanceSqr(Vec3 eye) {
        return (cx - eye.x) * (cx - eye.x) + (cy - eye.y) * (cy - eye.y) + (cz - eye.z) * (cz - eye.z);
    }

    /** Следующий уровень даёт меньше групп. */
    boolean canThin() {
        return thinned < LEVELS && groups(thinned + 1) < groups(thinned);
    }

    /** Вдвое реже: следующий уровень прореживания. */
    void thin() {
        thinned++;
        show(aggregate(thinned));
    }

    private void show(Patch[] shown) {
        this.shown = shown;
        double last = 0;
        for (Patch p : shown) if (p != null) last = Math.max(last, p.until);
        end = last;
    }

    /** Шаг уровня {@code thinned} по дуге (a) и по кольцам (b): попеременно вдвое. */
    private static int arcStride(int thinned) {
        return 1 << ((thinned + 1) / 2);
    }

    private static int ringStride(int thinned) {
        return 1 << (thinned / 2);
    }

    /** Наибольший шаг по дуге кольца из {@code spokes} площадок: на кольце остаётся хотя бы три. */
    private static int arcMost(int spokes) {
        return Integer.highestOneBit(Math.max(1, spokes / 3));
    }

    /**
     * Группа площадки {@code i} на уровне {@code thinned}: кольца — блоками по b от внутреннего, по дуге — номер места
     * первого кольца блока под этим углом, делённый на шаг по дуге (на кольце не меньше трёх групп).
     */
    private long group(int i, int thinned) {
        int b = ringStride(thinned), j0 = ring[i] / b * b, s0 = spokes[j0], a = Math.min(arcStride(thinned), arcMost(s0));
        double turn = Math.atan2(patches[i].z - cz, patches[i].x - cx) / (2 * Math.PI);
        int k = Math.floorMod((int) Math.floor((turn - Math.floor(turn)) * s0 - 0.5 * (j0 % 2) + 1e-9), s0);
        return (long) j0 << 32 | (k / a);
    }

    private int groups(int thinned) {
        if (groupCount[thinned] < 0) {
            java.util.HashSet<Long> seen = new java.util.HashSet<>();
            for (int i = 0; i < patches.length; i++) seen.add(group(i, thinned));
            groupCount[thinned] = seen.size();
        }
        return groupCount[thinned];
    }

    /**
     * Площадки уровня {@code thinned}: группа — одна площадка у первой по номеру, с той же массой (сумма площади ×
     * контраст), в середине масс группы, σ — шаг группы × {@link #SPREAD}; приход и фаза — у её места. Зерно клуба у
     * первой в группе прежнее: при прореживании клубы не перерождаются, а сливаются.
     */
    private Patch[] aggregate(int thinned) {
        Patch[] out = new Patch[patches.length];
        java.util.Map<Long, List<Integer>> members = new java.util.LinkedHashMap<>();
        for (int i = 0; i < patches.length; i++) members.computeIfAbsent(group(i, thinned), g -> new ArrayList<>()).add(i);
        int b = ringStride(thinned);
        for (List<Integer> g : members.values()) {
            int first = g.getFirst(), j0 = ring[first] / b * b, s0 = spokes[j0], a = Math.min(arcStride(thinned), arcMost(s0));
            double area = 0, mass = 0, x = 0, y = 0, z = 0;
            for (int i : g) {
                Patch p = patches[i];
                area += p.area;
                mass += p.area * p.contrast;
                x += p.area * p.x;
                y += p.area * p.y;
                z += p.area * p.z;
            }
            x /= area;
            y /= area;
            z /= area;
            double slant = Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz));
            Patch head = patches[first];
            double sigma = SPREAD * Math.max(Math.min(b, spokes.length - j0) * dr, a * 2 * Math.PI * radius[j0] / s0);
            out[first] = new Patch(x, y, z, slant, front.arrivalSeconds(slant), BlastModel.chemicalPhaseSeconds(slant, front.tntKg, front.surface),
                    sigma, area, mass / area, head.windX, head.windZ, head.stability);
        }
        return out;
    }

    /** Выход из мира. */
    static void reset() {
        LIVE.clear();
    }

    /** Площадок на кольце радиуса {@code r} при шаге {@code arc}. */
    static int spokes(double r, double arc) {
        return Math.max(6, (int) Math.ceil(2 * Math.PI * r / arc));
    }

    /** Толща выросшего клуба площадки поперёк середины (до оседания). */
    static double fullTau(double area, double contrast, double sigma) {
        return COLUMN_TAU * area * contrast / (2 * Math.PI * sigma * Math.min(HEIGHT / 2, sigma));
    }

    /**
     * С какого возраста взрыва клуб площадки, выпадая, прозрачнее {@link #FAINTEST}: его толща — не больше
     * max({@code tau}, {@link #EDGE_TAU}) и дальше гаснет как оседание (расплывание только разбавляет).
     */
    static double seenUntil(double arrival, double phase, double tau) {
        double most = Math.max(tau, EDGE_TAU);
        return arrival + phase + HEIGHT / BlastLitter.COVER_DUST_SETTLE * Math.log(Math.max(1, most / FAINTEST));
    }

    /**
     * Один конечный запас на площадь источника: расширение клуба не создаёт новую оптическую массу, у края — не толще
     * выросшего клуба и не меньше {@link #EDGE_TAU}. Невидимая площадка ({@link Patch#until}) отдаёт пустой клуб: её
     * место в источнике (зерно клуба) остаётся, а слияние и кадр её не считают.
     */
    static void emitPatch(Patch p, double elapsed, double radius, SmokeGrid.Sink out) {
        if (elapsed >= p.until) {
            out.blob(p.x, p.y, p.z, 0, 0, 0, 0, 0, ALBEDO_R, ALBEDO_G, ALBEDO_B, CloudModel.T_AIR_K, 0);
            return;
        }
        double age = elapsed - p.arrival, left = envelope(age, p.phase);
        double path = Math.max(1, Math.hypot(p.windX, p.windZ)) * Math.max(0, age);
        double spread = Stability.sigmaY(p.stability, path);
        double dx = p.windX * Math.max(0, age), dz = p.windZ * Math.max(0, age);
        // Клуб стоит на земле и растёт вверх вместе с собой: середина на высоте своего σ по вертикали (сорванное
        // начинает у земли), а не висит на полслоя, пока сам ещё в сантиметры. Весь клуб (середина, подъём и 4σ) — за
        // фронтом.
        double room = Math.max(0, radius - p.slant - Math.hypot(dx, dz));
        double s = Math.min(Math.hypot(p.sigma, spread), room / 5);
        double v = Math.min(HEIGHT / 2, s), lift = v;
        double k = SmokeGrid.dustK(ALBEDO_R, ALBEDO_G, ALBEDO_B), edge = Math.max(EDGE_TAU, fullTau(p.area, p.contrast, p.sigma));
        double dust = s > 0 && v > 0 ? Math.min(COLUMN_TAU * p.area * p.contrast * left, edge * 2 * Math.PI * s * v) / k : 0;
        out.blob(p.x + dx, p.y + lift, p.z + dz,
                s, v, 0, dust, 0, ALBEDO_R, ALBEDO_G, ALBEDO_B, CloudModel.T_AIR_K, 0);
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        double elapsed = BlastTime.seconds(BlastTime.ticks(), born, 20 * (seconds - SmokeGrid.seconds(0)));
        if (ended() || elapsed >= end) return false;
        double radius = front.radiusAt(elapsed);
        for (Patch p : shown) {
            // не рисуемая в прореженной группе — пустой клуб: номера клубов источника (их зерно) прежние
            if (p == null) out.blob(cx, cy, cz, 0, 0, 0, 0, 0, ALBEDO_R, ALBEDO_G, ALBEDO_B, CloudModel.T_AIR_K, 0);
            else emitPatch(p, elapsed, radius, out);
        }
        return true;
    }

    @Override
    public boolean ended() {
        return level != Minecraft.getInstance().level || BlastTime.seconds(BlastTime.ticks(), born, 0) >= end;
    }
}
