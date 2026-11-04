package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DustGridTest {
    private static GlslFunction integral() throws Exception {
        try (var stream = DustGridTest.class.getResourceAsStream("/assets/incoming/shaders/include/dust_profile.glsl")) {
            assertNotNull(stream);
            return GlslFunction.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\\n]*", ""), "dustIntegral");
        }
    }

    @Test void turbulenceKeepsEveryAxisMassAtEveryPhaseAndCellAlignment() throws Exception {
        var f = integral();
        Random random = new Random(421);
        for (int trial = 0; trial < 80; trial++) {
            double cell = Math.pow(10, random.nextDouble()*2-2), shift = random.nextDouble()*cell;
            double phase = random.nextDouble()*2*Math.PI, frequency = trial%3+1;
            double sum = 0;
            for (double x = -4-shift; x < 4; x += cell) {
                double mass = f.call(Map.of("q0", x, "q1", x+cell, "phase", phase, "frequency", frequency));
                assertTrue(mass >= 0, "no negative density");
                sum += mass;
            }
            assertEquals(1, sum, 2e-7, "noise cannot delete or add optical mass");
        }
    }

    @Test void thinDustKeepsItsHeightAndMassInsteadOfTakingHorizontalCellSize() {
        DustGrid.begin(true, 0, 66, 40);
        DustGrid.add(0, 64.1, 0, 8, .1, 12, .9f, .85f, .8f, 7, 3);
        var frame = DustGrid.finish();
        assertEquals(1, frame.count);
        assertEquals(.1, frame.blobs[SmokeGrid.MAX_BLOBS*4], 1e-6);
        assertTrue(frame.dustCellY < .011);
        assertTrue(frame.corner[3] > .5);
        assertEquals(12*SmokeGrid.dustK(.9f,.85f,.8f), frame.blobs[SmokeGrid.MAX_BLOBS*4+2], .001);
        assertTrue(frame.layerStart[SmokeGrid.N] > 0);
        assertEquals(frame.layerStart[SmokeGrid.N], frame.layerStart[SmokeGrid.CASCADES*SmokeGrid.N]);
    }

    @Test void hundredStrikesAndOverflowKeepOpticalMassWithinOneFixedTable() {
        for (int strikes : new int[]{1, 100, 400}) {
            DustGrid.begin(true, 0, 66, 40);
            double mass = 0;
            for (int s = 0; s < strikes; s++) for (int k = 0; k < 80; k++) {
                double m = .1 + (k%7)*.03;
                DustGrid.add((s%10)*20 + k%8, 64 + k%3, (s/10)*20 + k/8, 3, .2, m, .9f, .85f, .8f, k+s*80, 6);
                mass += m*SmokeGrid.dustK(.9f,.85f,.8f);
            }
            var frame = DustGrid.finish();
            assertTrue(frame.count <= SmokeGrid.MAX_BLOBS);
            double sum = 0;
            for (int i = 0; i < frame.count; i++) sum += frame.blobs[(SmokeGrid.MAX_BLOBS+i)*4+2];
            assertEquals(mass, sum, mass*1e-6);
            assertTrue(frame.layerStart[SmokeGrid.CASCADES*SmokeGrid.N] <= SmokeGrid.MAX_BLOBS*SmokeGrid.N*12);
        }
    }

    @Test void mergeRetainsMassCentreAndSecondMoments() {
        double[] a = {1,2,3,4,.2,10,.9,.8,.7,0,0,1};
        double[] b = {3,4,5,2,.4,30,.8,.7,.6,0,0,3};
        DustGrid.merge(a,0,b,0);
        assertEquals(40,a[5],0);
        assertEquals(2.5,a[0],1e-12); assertEquals(3.5,a[1],1e-12);
        assertEquals(.25*16+.75*4+.75*.25*4,a[3]*a[3],1e-12);
        assertEquals(.25*.04+.75*.16+.75*.25*4,a[4]*a[4],1e-12);
        assertEquals(4,a[11],0);
    }
}
