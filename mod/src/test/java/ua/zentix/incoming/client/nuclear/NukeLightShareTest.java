package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sightline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ball's light follows the visible share of its disc, not one ray to its centre. Geometry of the Zearth audit
 * (09.11.2026): eye 3.78 km from a ground burst, tree crowns on a ridge ~2 km out cut the ray to the ground-level centre
 * while 94–98 % of the disc was open; the binary test dropped the light 3.5 times and flipped as the ball rose.
 */
class NukeLightShareTest {
    private static final double GROUND = 71;
    private static final Vec3 EYE = new Vec3(-1130.5, 91.62, -110.5);

    /** Flat ground at {@link #GROUND}, plus a crown {@code crown} blocks high on a 60-block band around x = 650 (2 km from the burst; wider than the 59-block sample step). */
    private static Sightline.Heights terrain(double crown) {
        return (x, z) -> x >= 620 && x <= 680 ? GROUND + crown : GROUND;
    }

    @Test
    void openGroundShowsTheWholeBall() {
        assertEquals(1, NukeLight.share(terrain(0), EYE, new Vec3(2650, GROUND + 50, 13), GROUND, 500), 1e-9);
    }

    @Test
    void crownsBelowMostOfTheDiscBarelyDimIt() {
        Vec3 centre = new Vec3(2650, GROUND + 2, 13);
        // the line from the eye over a 20-block crown passes the burst a few tens of blocks above its ground
        double share = NukeLight.share(terrain(20), EYE, centre, GROUND, 555);
        assertTrue(share > 0.9, "share " + share);
        assertTrue(share < 1, "share " + share);
    }

    @Test
    void aRidgeAboveTheWholeBallHidesIt() {
        Sightline.Heights hill = (x, z) -> x >= 0 && x <= 400 ? GROUND + 400 : GROUND;
        assertEquals(0, NukeLight.share(hill, EYE, new Vec3(2650, GROUND + 20, 13), GROUND, 100), 1e-9);
    }

    @Test
    void theShareGrowsSmoothlyAsTheBallRises() {
        double last = NukeLight.share(terrain(20), EYE, new Vec3(2650, GROUND, 13), GROUND, 300);
        for (int y = 1; y <= 400; y++) {
            double share = NukeLight.share(terrain(20), EYE, new Vec3(2650, GROUND + y, 13), GROUND, 300);
            assertTrue(share >= last - 1e-12 && share - last < 0.02, "jump at " + y + ": " + last + " -> " + share);
            last = share;
        }
    }
}
