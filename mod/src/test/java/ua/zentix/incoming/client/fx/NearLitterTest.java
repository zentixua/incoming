package ua.zentix.incoming.client.fx;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.LocalLitter;
import ua.zentix.incoming.nuclear.model.ShockAtBody;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сор у камеры ({@link NearLitter}): какая поверхность у места — та же, что у пыли волны ({@code FarBlasts.ground}). */
class NearLitterTest {
    private static BlockState AIR;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
    }

    /** Трава на дёрне — клок травы, держит до 3 м/с (дёрн); голая земля, песок и гравий — крошка, рыхлое. */
    @Test
    void groundCoverPicksTheSurfaceOfThePlace() {
        NearLitter.Cover grass = NearLitter.cover(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState());
        assertSame(DustModel.Surface.COVERED, grass.surface());
        assertEquals(Fragments.Kind.LEAF, grass.kind());
        assertTrue(grass.torn().is(Blocks.SHORT_GRASS), "травинка, а не кубик земли");
        NearLitter.Cover turf = NearLitter.cover(Blocks.GRASS_BLOCK.defaultBlockState(), AIR);
        assertSame(DustModel.Surface.COVERED, turf.surface());
        assertTrue(turf.torn().is(Blocks.SHORT_GRASS), "дёрн рвётся травой, не землёй");
        // (песок и листва — по тегам, а теги в юнит-тесте не загружены: только блоки, что узнаются по себе)
        for (BlockState loose : new BlockState[]{Blocks.GRAVEL.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState()}) {
            NearLitter.Cover c = NearLitter.cover(loose, AIR);
            assertSame(DustModel.Surface.FIELD, c.surface(), loose.toString());
            assertEquals(Fragments.Kind.CHUNK, c.kind());
            assertSame(loose, c.torn(), "крошка своего блока");
        }
    }

    /** Камень и кирпич — только осевшая пыль (кусков нет); вода и воздух — ничего. */
    @Test
    void hardAndWater() {
        for (BlockState hard : new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.BRICKS.defaultBlockState()}) {
            NearLitter.Cover c = NearLitter.cover(hard, AIR);
            assertSame(DustModel.Surface.HARD, c.surface(), hard.toString());
            assertFalse(c.pieces(), "с твёрдого — только пыль");
        }
        assertNull(NearLitter.cover(Blocks.WATER.defaultBlockState(), AIR));
        assertNull(NearLitter.cover(AIR, AIR));
    }

    /**
     * Куски — только где фронт их бросил: шахед в 30 м рвёт траву и бросает крошку, «Град» в 200 м срывает долю рыхлого
     * без броска — кусков нет (на экране они бы просто появились), томагавк в 100 м траву не рвёт.
     */
    @Test
    void piecesOnlyWhereTheFrontThrowsThem() {
        ShockAtBody shahed = ShockAtBody.chemical(50, true, 30), grad = ShockAtBody.chemical(6.4, true, 200), missile = ShockAtBody.chemical(150, true, 100);
        double leaf = Fragments.terminal(Fragments.Kind.LEAF, 0, 0), grain = Fragments.terminal(Fragments.Kind.CHUNK, NearLitter.GRAIN_DENSITY, NearLitter.CRUMB_M);
        assertTrue(NearLitter.pieces(LocalLitter.lift(shahed, DustModel.Surface.COVERED, 1, leaf)));
        assertTrue(NearLitter.pieces(LocalLitter.lift(shahed, DustModel.Surface.FIELD, 1, grain)));
        assertFalse(NearLitter.pieces(LocalLitter.lift(grad, DustModel.Surface.FIELD, 1, grain)));
        assertFalse(NearLitter.pieces(LocalLitter.lift(missile, DustModel.Surface.COVERED, 1, leaf)));
    }

    /**
     * Оседание тонкой пыли — то, с которым тает её ослабление ({@link DustModel#extinctionAloft}: наклон в начале —
     * Σ k·доля·v / Σ k·доля), а не скорость падения брошенной крошки.
     */
    @Test
    void fineDustSettlesAsItsExtinction() {
        double eps = 1e-3, slope = (1 - DustModel.extinctionAloft(eps)) / eps;
        assertEquals(slope, NearLitter.DUST_SETTLE_MPS, 0.002, "наклон ослабления " + slope);
    }

    /**
     * Сор у камеры перебирает колонки, только если фронт в круге поднимет что-то видимое: шахед в 30 м — да, «Град» в
     * 200 м и ракета в 300 м — нет (цена: закон на рыхлом грунте — микросекунды на место, мест — до тысячи).
     */
    @Test
    void nothingVisibleNothingSampled() {
        assertTrue(NearLitter.visible(50, true, 30 - NearLitter.REACH));
        assertTrue(NearLitter.visible(3, true, 10 - NearLitter.REACH < 0.5 ? 0.5 : 10 - NearLitter.REACH));
        assertFalse(NearLitter.visible(6.4, true, 200 - NearLitter.REACH));
        assertFalse(NearLitter.visible(150, true, 300 - NearLitter.REACH));
    }

    /** Колонка: блоки по высоте, остальное — воздух. */
    private static java.util.function.IntFunction<BlockState> column(java.util.Map<Integer, BlockState> blocks) {
        return y -> blocks.getOrDefault(y, AIR);
    }

    /**
     * Под кроной (листва 68–70) трава у ног (64) — тоже открытое место, а не только верх колонки; крыша в 25 блоках над
     * глазом — вне шара выборки: снизу колонки не берётся, что выше глаза на {@link NearLitter#REACH}.
     */
    @Test
    void grassUnderTheCrownAndNotTheRoof() {
        java.util.Map<Integer, BlockState> b = new java.util.HashMap<>();
        b.put(63, Blocks.DIRT.defaultBlockState());
        b.put(64, Blocks.GRASS_BLOCK.defaultBlockState());
        b.put(65, Blocks.SHORT_GRASS.defaultBlockState());
        for (int y = 68; y <= 70; y++) b.put(y, Blocks.OAK_LEAVES.defaultBlockState());
        b.put(90, Blocks.STONE.defaultBlockState());
        double eyeY = 65.62;
        int high = (int) Math.floor(eyeY + NearLitter.REACH), low = (int) Math.floor(eyeY - NearLitter.REACH);
        int[] open = NearLitter.exposed(column(b), Math.min(high, 90), low);
        assertArrayEquals(new int[]{70, 64}, open, "крона и трава под ней, без крыши: " + java.util.Arrays.toString(open));
        // крыша над головой в шаре: её верх открыт, пол под ней — тоже
        b.put(75, Blocks.STONE.defaultBlockState());
        assertArrayEquals(new int[]{75, 70, 64}, NearLitter.exposed(column(b), 75, low));
    }

    /**
     * Кусок летит тем телом, которым его бросил закон: скорость падения, что дала {@link LocalLitter#lift} (комочек почвы
     * 2 мм, а не нарисованные 6–12 см), — та, к которой {@link Fragments#step} с его сопротивлением сходится.
     */
    @Test
    void piecesFlyAsTheBodyTheLawThrew() {
        ShockAtBody shahed = ShockAtBody.chemical(50, true, 30);
        for (Fragments.Kind kind : new Fragments.Kind[]{Fragments.Kind.CHUNK, Fragments.Kind.LEAF}) {
            LocalLitter.Lift lift = LocalLitter.lift(shahed, kind == Fragments.Kind.LEAF ? DustModel.Surface.COVERED : DustModel.Surface.FIELD, 1,
                    NearLitter.terminal(kind));
            assertEquals(NearLitter.terminal(kind), lift.settlingMps(), 1e-12);
            double[] v = {0, 0, 0};
            for (int t = 0; t < 400; t++) Fragments.step(v, 0, 0, 0, Fragments.drag(lift.settlingMps()));
            // шаг Fragments у листа (установление за 3 тика) держит скорость падения с точностью ~1 %
            assertEquals(-lift.settlingMps() / 20, v[1], 0.02 * lift.settlingMps() / 20, kind + ": падает не со своей скоростью");
        }
        double drawn = Fragments.terminal(Fragments.Kind.CHUNK, NearLitter.GRAIN_DENSITY, NearLitter.GRAIN_MIN);
        assertTrue(drawn > 4 * NearLitter.terminal(Fragments.Kind.CHUNK), "нарисованный ком падал бы в разы быстрее: " + drawn);
    }
}
