package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Квадрат клуба — видимый край гаусса; цвет толстого слоя сажи — тёмно-серый, а не чёрная дыра. */
class SmokePuffsTest {
    /** Край — где толща падает до 0,05: у густого ~3,7σ; у тонкого (τ₀ меньше порога) — не NaN, а наименьший край. */
    @Test
    void edgeOfThinAndThickClouds() {
        assertEquals(Math.sqrt(2 * Math.log(42 / SmokePuffs.EDGE_TAU)), SmokePuffs.edge(42), 1e-9);
        for (double tau : new double[]{0, 0.004, 0.01, 0.049, 0.05}) {
            double e = SmokePuffs.edge(tau);
            assertTrue(Double.isFinite(e) && e == 1.2, "τ₀ " + tau + ": край " + e);
        }
    }

    /** Толстый слой сажи (ω' ≈ 0,2) отражает ~4 % — на экране ~0,24, а не 0: у Эддингтона отражение выходило отрицательным. */
    @Test
    void sootIsDarkGreyNotBlack() {
        float soot = SmokePuffs.thick(1, 0, 0, 0.9f);
        assertTrue(soot > 0.15 && soot < 0.35, "сажа " + soot);
    }

    /**
     * Тень карт клуба — в степени затухания света смеси против вещества запекания: белый дым окиси алюминия и капли почти
     * без тени (иначе белое облако старта ЗРК выходило тёмно-серым шаром), сажа — темнее запечённого, смесь как у
     * запекания — как есть; степень помещается в байт вершины ({@code FxQuads.SHADE_MAX}).
     */
    @Test
    void mapShadeFollowsAlbedo() {
        double white = SmokePuffs.shade(0, 0, 1, 0.9f) / SmokePuffs.BAKED_K, soot = SmokePuffs.shade(1, 0, 0, 0.9f) / SmokePuffs.BAKED_K;
        assertTrue(white < 0.05, "капли " + white);
        assertTrue(soot > 1 && soot < ua.zentix.incoming.client.fx.layer.FxQuads.SHADE_MAX, "сажа " + soot);
        assertEquals(1, SmokePuffs.twoStream(0.46, 0.355) / SmokePuffs.BAKED_K, 0.01, "вещество запекания");
        // брызги запечены каплями: у капель тень карт брызг — как есть
        assertEquals(1, SmokePuffs.shade(0, 0, 1, 0.9f) / SmokePuffs.SPRAY_K, 0.5, "капли на картах брызг");
    }

    /** Альбедо и асимметрия запекания — те же, что в скрипте запекания. */
    @Test
    void bakedAlbedoMatchesBakeScript() throws java.io.IOException {
        String bake = java.nio.file.Files.readString(java.nio.file.Path.of("..", "tools", "bake_fx", "bake.py"));
        assertTrue(bake.contains("PUFF_ALBEDO, PUFF_G = " + SmokePuffs.BAKED_W + ", " + SmokePuffs.BAKED_G),
                "tools/bake_fx/bake.py: PUFF_ALBEDO, PUFF_G не " + SmokePuffs.BAKED_W + ", " + SmokePuffs.BAKED_G);
        assertTrue(bake.contains("SPRAY_ALBEDO, SPRAY_G = " + SmokePuffs.SPRAY_W + ", " + SmokePuffs.SPRAY_G),
                "tools/bake_fx/bake.py: SPRAY_ALBEDO, SPRAY_G не " + SmokePuffs.SPRAY_W + ", " + SmokePuffs.SPRAY_G);
    }
}
