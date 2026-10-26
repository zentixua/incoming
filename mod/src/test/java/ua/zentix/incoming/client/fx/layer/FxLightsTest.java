package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Light of the fireballs of the frame: the most noticeable ones go into the shader. */
class FxLightsTest {
    @Test
    void keepsTheBrightestBalls() {
        FxLights lights = new FxLights();
        lights.begin();
        // six balls of radius 1..6 in 100 blocks: larger ones are more noticeable
        for (int r = 1; r <= 6; r++) lights.add(100, 0, 0, r, 8, 1, 1, 0.6f, 0.2f);
        double sum = 0;
        for (int i = 0; i < FxLights.MAX; i++) sum += lights.radius(i);
        assertEquals(3 + 4 + 5 + 6, sum, 1e-6, "there are four large ones left");
        // a ball closer to the eye is more noticeable than the same one at a distance
        lights.add(10, 0, 0, 2, 8, 1, 1, 0.6f, 0.2f);
        sum = 0;
        for (int i = 0; i < FxLights.MAX; i++) sum += lights.radius(i);
        assertEquals(2 + 4 + 5 + 6, sum, 1e-6, "the neighbor supplanted the weakest");
        lights.begin();
        for (int i = 0; i < FxLights.MAX; i++) assertEquals(0, lights.radius(i), "new frame - without balls");
    }
}
