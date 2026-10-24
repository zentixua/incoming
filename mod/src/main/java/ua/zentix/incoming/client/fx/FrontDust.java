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
    record Patch(double x, double y, double z, double slant, double arrival, double phase, double size,
                 double area, double contrast, double windX, double windZ, double stability) {}

    /** Подача: дискретизация кольца, не число частиц грунта; не более 24 × 64 клубов на взрыв. */
    private static final int RINGS = 24, SPOKES = 64;
    /**
     * Подача: высота перемешанного слоя, толща поднятого по вертикали (запас на площадь источника) и наибольшая толща
     * одного клуба. Не новая масса в GroundDust. Толща 0,5 — кольцо видно с 20–100 м и не стоит стеной у камеры
     * (0,08 — кольца не было видно вовсе, 2,8 на клуб в R2 — бурая стена во весь кадр). У бегущего края клуб ещё
     * сжат фронтом (его 4σ — за фронтом): весь запас площадки в нём был бы непрозрачной точкой, поэтому толща клуба
     * не больше {@code EDGE_TAU}; остальное площадка отдаёт, когда клуб вырос.
     */
    static final double HEIGHT = 1.3, COLUMN_TAU = 0.5, EDGE_TAU = 0.8;
    /** К этому остатку выпавшей крупной пыли источник уже невидим; не срок, связанный с фазой давления. */
    private static final double GONE = 1e-5;
    /**
     * Цвет толстого слоя крошки на экране (сухая светлая земля дёрна; подача) и альбедо вещества по нему: клуб берёт
     * альбедо ({@link SmokeGrid#dustAlbedo}), а не цвет экрана — иначе толстый слой выходил серым (0,6/0,55/0,45), и середина
     * клуба темнела против светлой каймы («бублики»).
     */
    static final float SHOWN_R = 0.82f, SHOWN_G = 0.76f, SHOWN_B = 0.62f;
    static final float ALBEDO_R = SmokeGrid.dustAlbedo(SHOWN_R), ALBEDO_G = SmokeGrid.dustAlbedo(SHOWN_G),
            ALBEDO_B = SmokeGrid.dustAlbedo(SHOWN_B);
    static final double SETTLE_SECONDS = -Math.log(GONE) * HEIGHT / BlastLitter.COVER_DUST_SETTLE;
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
        for (Patch p : patches) last = Math.max(last, p.arrival + p.phase + SETTLE_SECONDS);
        end = last;
    }

    static void spawn(ClientLevel level, Vec3 pos, ChemicalFront front, double inner, double outer, double hob) {
        List<Patch> patches = new ArrayList<>();
        Air air = ClientAir.now(); // За секунды жизни крошки — воздух в миг срыва (оценка постоянного ветра).
        double dr = (outer - inner) / RINGS;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int j = 0; j < RINGS; j++) {
            double r = inner + (outer - inner) * (j + 0.5) / RINGS;
            int spokes = Math.max(12, (int) Math.ceil(SPOKES * r / outer));
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
                double size = Math.max(0.45, Math.min(2.4, 2 * Math.PI * r / spokes));
                double contrast = Math.min(1, friction / DustModel.Surface.COVERED.threshold() - 1);
                patches.add(new Patch(x, y, z, slant, front.arrivalSeconds(slant),
                        BlastModel.chemicalPhaseSeconds(slant, front.tntKg, front.surface), size,
                        2 * Math.PI * r * dr / spokes, contrast, air.windX(y + HEIGHT / 2), air.windZ(y + HEIGHT / 2), air.stability()));
            }
        }
        if (!patches.isEmpty()) SmokeGrid.add(new FrontDust(level, front, patches));
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

    /**
     * Один конечный запас на площадь источника: расширение клуба не создаёт новую оптическую массу, у края — не больше
     * {@link #EDGE_TAU} на клуб.
     */
    static void emitPatch(Patch p, double elapsed, double radius, SmokeGrid.Sink out) {
        double age = elapsed - p.arrival, left = envelope(age, p.phase);
        double path = Math.max(1, Math.hypot(p.windX, p.windZ)) * Math.max(0, age);
        double spread = Stability.sigmaY(p.stability, path);
        double dx = p.windX * Math.max(0, age), dz = p.windZ * Math.max(0, age);
        // Клуб стоит на земле и растёт вверх вместе с собой: середина на высоте своего σ по вертикали (сорванное
        // начинает у земли), а не висит на полслоя, пока сам ещё в сантиметры. Весь клуб (середина, подъём и 4σ) — за
        // фронтом.
        double room = Math.max(0, radius - p.slant - Math.hypot(dx, dz));
        double s = Math.min(Math.hypot(1.2 * p.size, spread), room / 5);
        double v = Math.min(HEIGHT / 2, s), lift = v;
        double k = SmokeGrid.dustK(ALBEDO_R, ALBEDO_G, ALBEDO_B);
        double dust = s > 0 && v > 0 ? Math.min(COLUMN_TAU * p.area * p.contrast * left, EDGE_TAU * 2 * Math.PI * s * v) / k : 0;
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
