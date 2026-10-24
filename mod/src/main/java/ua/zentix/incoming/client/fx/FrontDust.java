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
     * {@link #STEP} м, у большого заряда шире — площадок не больше {@link #PATCHES} (у залпа — {@link #budget}); клуб
     * выросшей площадки — σ = {@link #SPREAD} шага: соседи перекрываются, слой без бус. Цена: клубы источника
     * {@code SmokePuffs} каждый кадр сливает попарно; прежняя сетка 24 × 64 (у шахеда 864 клуба, шаг поперёк кольца
     * 2,2 м против 5,6 вдоль) стоила ~1 мс кадра на взрыв, залп — кратно.
     */
    static final double STEP = 5.6, SPREAD = 0.6;
    static final int PATCHES = 400;
    /**
     * Подача: видимых площадок у всех взрывов сразу не больше {@link #ALL_PATCHES}: новый берёт свободное, но не меньше
     * четверти своих {@link #PATCHES} (шаг сетки тогда шире). Без общего предела залп из восьми шахедов в одном месте
     * рисовал восемь наложенных слоёв по ~360 клубов — слой эффектов +12 мс на кадр против main.
     */
    static final int ALL_PATCHES = 800;
    /** Взрывы, чьи площадки ещё видны (для общего предела). */
    private static final List<FrontDust> LIVE = new ArrayList<>();
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
    private final ClientLevel level;
    private final long born;
    private final double end;
    private final ChemicalFront front;
    private final List<Patch> patches;

    private FrontDust(ClientLevel level, ChemicalFront front, List<Patch> patches) {
        this.front = front;
        this.level = level;
        this.patches = patches;
        born = BlastTime.ticks();
        double last = 0;
        for (Patch p : patches) last = Math.max(last, p.until);
        end = last;
    }

    static void spawn(ClientLevel level, Vec3 pos, ChemicalFront front, double inner, double outer, double hob) {
        List<Patch> patches = new ArrayList<>();
        Air air = ClientAir.now(); // За секунды жизни крошки — воздух в миг срыва (оценка постоянного ветра).
        int budget = budget(), rings = rings(inner, outer, budget);
        double dr = (outer - inner) / rings, arc = arc(inner, outer, budget);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int j = 0; j < rings; j++) {
            double r = inner + dr * (j + 0.5);
            int spokes = spokes(r, arc);
            for (int i = 0; i < spokes; i++) {
                double a = 2 * Math.PI * (i + 0.5 * (j % 2)) / spokes;
                double x = pos.x + r * Math.cos(a), z = pos.z + r * Math.sin(a);
                int bx = Mth.floor(x), bz = Mth.floor(z);
                LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
                if (chunk == null) continue;
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15);
                var cover = chunk.getBlockState(m.set(bx, top, bz));
                // Только сорванный покров; голый грунт, вода, камень и песок остаются у GroundDust.
                if (!cover.is(Blocks.GRASS_BLOCK) && !cover.is(BlockTags.LEAVES)) continue;
                double y = top + 1.05, slant = Math.sqrt(r * r + (y - pos.y) * (y - pos.y));
                double along = cover.is(BlockTags.LEAVES) || y > pos.y - Math.max(0, hob) + 2
                        ? 1 : FrontLitter.along(r, Math.hypot(r, hob));
                double friction = BlastLitter.friction(front.kpa(slant), along);
                if (!BlastLitter.tearsCover(front.kpa(slant), along)) continue;
                double contrast = Math.min(1, friction / DustModel.Surface.COVERED.threshold() - 1);
                patches.add(new Patch(x, y, z, slant, front.arrivalSeconds(slant),
                        BlastModel.chemicalPhaseSeconds(slant, front.tntKg, front.surface), SPREAD * Math.max(dr, 2 * Math.PI * r / spokes),
                        2 * Math.PI * r * dr / spokes, contrast, air.windX(y + HEIGHT / 2), air.windZ(y + HEIGHT / 2), air.stability()));
            }
        }
        if (patches.isEmpty()) return;
        FrontDust dust = new FrontDust(level, front, patches);
        LIVE.add(dust);
        SmokeGrid.add(dust);
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
    static int rings(double inner, double outer, int budget) {
        double step = Math.max(STEP, Math.sqrt(Math.PI * (outer * outer - inner * inner) / budget));
        return Math.max(1, (int) Math.ceil((outer - inner) / step));
    }

    /**
     * Шаг площадок вдоль кольца, м: {@link #STEP}, а у большого кольца — такой, чтобы с округлением по кольцам площадок было не
     * больше {@code budget}: Σ⌈2πr/шаг⌉ ≤ π(R² − r²)/(dr·шаг) + колец (середины колец: Σ r·dr = (R² − r²)/2).
     */
    static double arc(double inner, double outer, int budget) {
        int rings = rings(inner, outer, budget);
        double dr = (outer - inner) / rings;
        return Math.max(STEP, Math.PI * (outer * outer - inner * inner) / (dr * Math.max(1, budget - rings)));
    }

    /** Площадок новому взрыву: {@link #PATCHES}, у залпа — свободное из {@link #ALL_PATCHES}, но не меньше четверти. */
    static int budget() {
        LIVE.removeIf(FrontDust::ended);
        int seen = 0;
        for (FrontDust d : LIVE) seen += d.visible();
        return budget(seen);
    }

    static int budget(int seen) {
        return Math.min(PATCHES, Math.max(PATCHES / 4, ALL_PATCHES - seen));
    }

    /** Площадок, чей клуб ещё виден. */
    private int visible() {
        double elapsed = BlastTime.seconds(BlastTime.ticks(), born, 0);
        int n = 0;
        for (Patch p : patches) if (p.until > elapsed) n++;
        return n;
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
        for (Patch p : patches) emitPatch(p, elapsed, radius, out);
        return true;
    }

    @Override
    public boolean ended() {
        return level != Minecraft.getInstance().level || BlastTime.seconds(BlastTime.ticks(), born, 0) >= end;
    }
}
