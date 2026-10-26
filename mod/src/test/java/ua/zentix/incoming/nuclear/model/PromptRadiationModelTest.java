package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptRadiationModelTest {
    /**
     * Free air kerma off Nagasaki (21 kt, 503 m; RERF): gamma 7,8 / 0,89 / 0,13 / 0,021 Gr at 1 / 1,5 / 2 /
     * 2,5 km from the epicenter and neutrons - up to 10% from above. Tolerance 25 %.
     */
    @ParameterizedTest
    @CsvSource({"1000, 7.8", "1500, 0.89", "2000, 0.13", "2500, 0.021"})
    void nagasakiKerma(double groundM, double gammaGy) {
        double gy = PromptRadiationModel.doseRem(Math.hypot(groundM, 503), 21) / PromptRadiationModel.REM_PER_GY;
        assertEquals(gammaGy * 1.05, gy, gammaGy * 0.25);
    }

    @Test
    void smallYieldRadiationOutrangesBlast() {
        // 1 kt: lethal dose further than 5 psi (465 m); for 1 Mt - vice versa
        double ld50 = SicknessModel.LD50 * PromptRadiationModel.REM_PER_GY;
        assertTrue(PromptRadiationModel.doseRem(HobBlast.rangeFor(5, 0, 1), 1) > ld50);
        assertTrue(PromptRadiationModel.doseRem(HobBlast.rangeFor(5, 0, 1000), 1000) < ld50);
    }

    /** G&D tab. 8.41: half a meter of concrete dampens the radiation of the first minute by 10 times, the range of precipitation by almost 1000. */
    @Test
    void transmissionByMass() {
        assertEquals(1, PromptRadiationModel.transmission(0, PromptRadiationModel.HALF_PROMPT), 0);
        assertEquals(0.5, PromptRadiationModel.transmission(PromptRadiationModel.HALF_PROMPT, PromptRadiationModel.HALF_PROMPT), 1e-12);
        double concrete = 0.5 * 2300;
        assertEquals(0.1, PromptRadiationModel.transmission(concrete, PromptRadiationModel.HALF_PROMPT), 0.03);
        assertEquals(0.002, PromptRadiationModel.transmission(concrete, PromptRadiationModel.HALF_FALLOUT), 0.001);
    }
}
