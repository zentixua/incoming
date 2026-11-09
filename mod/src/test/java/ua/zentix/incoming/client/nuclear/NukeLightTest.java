package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.model.FlashLight;

import static org.junit.jupiter.api.Assertions.*;

class NukeLightTest {
    @Test
    void oneOwnerFollowsTheSumAndResetsForSeekOrWorldChange() {
        NukeLight.reset();
        NukeLight.update(0, 0.17, 0);
        double before = NukeLight.exposure();
        NukeLight.update(0.2, 0.17, 100 + 200);
        double expected = FlashLight.follow(before, Sight.exposure(0.17, 300), 0.2, NukeExposure.ADAPT_S);
        assertEquals(expected, NukeLight.exposure(), 1e-12);
        assertEquals(before, NukeLight.bodyExposure(), 1e-12, "body film does not get camera adaptation");
        NukeLight.update(0.2, 0.17, 100 + 200);
        assertEquals(expected, NukeLight.exposure(), "paused/repeated frame cannot advance exposure");
        NukeLight.update(0.4, 0.17, 0);
        assertTrue(NukeLight.exposure() > expected, "entering shelter returns toward ambient");
        NukeLight.reset();
        NukeLight.update(30, 0.17, 5);
        assertEquals(Sight.exposure(0.17, 5), NukeLight.exposure(), "seek/late entry reconstructs adapted state");
        NukeLight.reset();
        NukeLight.update(0, 1, 0);
        assertEquals(1, NukeLight.exposure(), "new world carries no old exposure");
        NukeLight.reset();
    }
}
