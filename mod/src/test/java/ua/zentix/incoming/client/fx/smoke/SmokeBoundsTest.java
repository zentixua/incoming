package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;
import static org.junit.jupiter.api.Assertions.*;

class SmokeBoundsTest {
    static void contains(SmokeBounds b, double x, double y, double z, double sh, double sv, String at) {
        double r = SmokeBounds.PUFF * Math.max(sh, sv), eps = 1e-6;
        assertTrue(x-r >= b.minX-eps && x+r <= b.maxX+eps && y-r >= b.minY-eps && y+r <= b.maxY+eps
                && z-r >= b.minZ-eps && z+r <= b.maxZ+eps, at + ": puff " + x + "," + y + "," + z + " ± " + r
                + " outside " + b.minX + "," + b.minY + "," + b.minZ + " — " + b.maxX + "," + b.maxY + "," + b.maxZ);
    }

    @Test void cloudEnvelopeContainsEveryEmittedPartWithoutTerrainQueries() {
        for (var weapon : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE, WeaponType.ROCKET, WeaponType.LOITER}) {
            for (int terrain = 0; terrain < 6; terrain++) {
                int kind = terrain % 3; int[] reads = {0};
                var ground = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0),
                        GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), (x,z) -> {
                            reads[0]++;
                            return kind == 0 ? 0 : kind == 1 ? (Math.abs(x) < 5 ? 0 : 20) : 20 * Math.sin(x / 40) * Math.cos(z / 40);
                        });
                var site = terrain < 3 ? ua.zentix.incoming.util.LocalWind.OPEN
                        : new ua.zentix.incoming.util.LocalWind(0, 15, 20, 10, 1, 2, .6, 30);
                var air = new CloudModel.Air(0.01, terrain == 0 ? 0 : 5, kind == 2 ? -8 : 0, 0.2, terrain < 3 ? 3 : 5, 0.6, site);
                var model = new CloudModel(BlastSource.of(weapon, false), air, false, 0, ground);
                var bound = new SmokeBounds();
                for (double t = .01; t < model.life; t = t * 1.23 + .01) {
                    double at = t; String label = weapon + " terrain " + terrain + " age " + t;
                    int before = reads[0]; bound.clear(); model.bounds(t, -64, 320, bound);
                    assertEquals(before, reads[0], "bounds must not touch any terrain backend");
                    model.blobs(t, (x,y,z,sh,sv,s,d,w,k,rise) -> contains(bound,x,y,z,sh,sv,label));
                    // Compare again after a large invisible-time jump, allowing cached path catch-up.
                    if (at > 100) for (int n = 0; n < 3; n++) model.blobs(t,
                            (x,y,z,sh,sv,s,d,w,k,rise) -> contains(bound,x,y,z,sh,sv,label));
                }
                bound.clear(); model.bounds(model.life, -64, 320, bound); assertTrue(bound.empty());
            }
        }
    }

    @Test void waterEnvelopeIncludesBallisticSegmentsAndLongLivedMist() {
        for (double wind : new double[]{0, 10}) {
            var water = new WaterBlast(500, 0, 100, new CloudModel.Air(.01, wind, -wind, .2, 3, .6));
            for (double t = .01; t < Math.max(water.mistLife(), water.waterLife()); t = t * 1.2 + .01) {
                var bound = new SmokeBounds(); water.bounds(t, bound, 0, 0, 0); String at = "water " + wind + " age " + t;
                water.blobs(t, (x,y,z,ax,ay,az,sh,sv,s,d,w,r,g,b,k,rise) -> {
                    contains(bound,x-ax,y-ay,z-az,sh,sv,at); contains(bound,x+ax,y+ay,z+az,sh,sv,at);
                }, 0, 0, 0);
            }
        }
    }
}
