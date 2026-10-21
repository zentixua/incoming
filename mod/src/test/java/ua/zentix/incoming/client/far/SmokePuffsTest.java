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
}
