package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.util.Stability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One fire - one column: fires in chunks, whose trail merges, a trail of all the heat rises (complaint 22.10: the burning
 *  house at the junction of chunks gave a low streak - two to four weak trails instead of one).
 */
class FireMergeTest {
    /** A house in 160 fire blocks (~28 MW) at the junction of two chunks: two halves in 16 m - one fire; the distant hearth is yours. */
    @Test
    void houseOnAChunkSeamIsOneFire() {
        double wind = 4.9, cls = Stability.D, half = 80 * FirePlume.BLOCK_WATTS;
        double rise = new FirePlume(80, wind, cls).finalRise;
        double[] fire = FireSmoke.fires(new double[]{0, 16, 2000}, new double[]{0, 0, 0}, new double[]{half, half, half},
                new double[]{rise, rise, rise});
        assertEquals(2 * half, fire[0], 1e-6);
        assertEquals(2 * half, fire[1], 1e-6);
        assertEquals(half, fire[2], 1e-6, "center in 2 km - your own fire");
        // trail of the entire fire rises higher: final rise ∝ F^⅗ (neutral air, F > 55)
        FirePlume one = new FirePlume(160, wind, cls), part = new FirePlume(80, wind, cls);
        assertTrue(one.finalRise > 1.4 * part.finalRise, "the whole fire " + one.finalRise + " against half " + part.finalRise);
        assertTrue(one.rise(30) > 1.2 * part.rise(30), "at 30 m downwind");
    }

    /** Merger - by touching the trail on the final climb: d ≤ 0,6·(Δh₁ + Δh₂); small fires in 50 m - separately. */
    @Test
    void smallFiresApartStayApart() {
        double rise = new FirePlume(1, 4.9, Stability.D).finalRise;
        double[] fire = FireSmoke.fires(new double[]{0, 50}, new double[]{0, 0}, new double[]{1, 1}, new double[]{rise, rise});
        assertTrue(50 > 1.2 * rise, "raising the fire " + rise);
        assertEquals(1, fire[0], 1e-9);
        assertEquals(1, fire[1], 1e-9);
    }
}
