package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.util.Stability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Один пожар — один столб: очаги по чанкам, чьи шлейфы сливаются, поднимаются шлейфом всего тепла (жалоба 22.10: горящий
 * дом на стыке чанков давал низкую полосу — два-четыре слабых шлейфа вместо одного).
 */
class FireMergeTest {
    /** Дом в 160 блоков огня (~28 МВт) на стыке двух чанков: две половины в 16 м — один пожар; дальний очаг — свой. */
    @Test
    void houseOnAChunkSeamIsOneFire() {
        double wind = 4.9, cls = Stability.D, half = 80 * FirePlume.BLOCK_WATTS;
        double rise = new FirePlume(80, wind, cls).finalRise;
        double[] fire = FireSmoke.fires(new double[]{0, 16, 2000}, new double[]{0, 0, 0}, new double[]{half, half, half},
                new double[]{rise, rise, rise});
        assertEquals(2 * half, fire[0], 1e-6);
        assertEquals(2 * half, fire[1], 1e-6);
        assertEquals(half, fire[2], 1e-6, "очаг в 2 км — свой пожар");
        // шлейф всего пожара поднимается выше: конечный подъём ∝ F^⅗ (нейтральный воздух, F > 55)
        FirePlume one = new FirePlume(160, wind, cls), part = new FirePlume(80, wind, cls);
        assertTrue(one.finalRise > 1.4 * part.finalRise, "весь пожар " + one.finalRise + " против половины " + part.finalRise);
        assertTrue(one.rise(30) > 1.2 * part.rise(30), "на 30 м по ветру");
    }

    /** Слияние — по касанию шлейфов на конечном подъёме: d ≤ 0,6·(Δh₁ + Δh₂); маленькие костры в 50 м — порознь. */
    @Test
    void smallFiresApartStayApart() {
        double rise = new FirePlume(1, 4.9, Stability.D).finalRise;
        double[] fire = FireSmoke.fires(new double[]{0, 50}, new double[]{0, 0}, new double[]{1, 1}, new double[]{rise, rise});
        assertTrue(50 > 1.2 * rise, "подъём костра " + rise);
        assertEquals(1, fire[0], 1e-9);
        assertEquals(1, fire[1], 1e-9);
    }
}
