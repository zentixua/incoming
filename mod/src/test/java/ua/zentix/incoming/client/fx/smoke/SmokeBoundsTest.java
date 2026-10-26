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
                    // A repeated sample at the same absolute age must stay inside the same support.
                    if (at > 100) for (int n = 0; n < 3; n++) model.blobs(t,
                            (x,y,z,sh,sv,s,d,w,k,rise) -> contains(bound,x,y,z,sh,sv,label));
                }
                bound.clear(); model.bounds(model.life, -64, 320, bound); assertTrue(bound.empty());
            }
        }
    }

    @Test void airUndergroundWaterAndFragmentedTargetsStayInsideTheirBounds() {
        var air = new CloudModel.Air(.01, 8, -5, .2, 3, .9);
        var base = BlastSource.of(WeaponType.MISSILE, false);
        var sources = new BlastSource[]{new BlastSource(base.weapon(), base.chargeKg(), base.fuelKg(), false, false),
                BlastSource.of(WeaponType.BUNKER, true), base, base};
        var materials = ua.zentix.incoming.warhead.GroundMaterial.values();
        for (int kind = 0; kind < sources.length; kind++) {
            double hob = kind == 0 ? 80 : 0;
            var target = kind == 2 ? BlastTarget.of(ua.zentix.incoming.warhead.GroundMaterial.WATER, base.chargeKg(), 0)
                    : kind == 3 ? BlastTarget.of(ua.zentix.incoming.warhead.GroundMaterial.STONE, false, 1, 3)
                    : BlastTarget.of(materials[0], sources[kind].chargeKg(), hob);
            var model = new CloudModel(sources[kind], air, target, hob, new GroundDust.Ground(GroundDust.Ground.filled(kind == 2 ? 0 : 1), GroundDust.Ground.filled(0),
                    GroundDust.Ground.filled(0), GroundDust.Ground.filled(-hob), (x,z) -> -hob));
            for (double t = .001; t < model.life; t = t * 1.17 + .001) {
                var bounds = new SmokeBounds();
                model.bounds(t, -64 - hob, 320 - hob, bounds);
                model.condensationBounds(t, bounds);
                String label = "target=" + kind + " age=" + t;
                model.blobs(t, (x,y,z,sh,sv,s,d,w,k,rise) -> contains(bounds,x,y,z,sh,sv,label));
                model.condensation(t, (x,y,z,sh,sv,s,d,w,k,rise) -> contains(bounds,x,y,z,sh,sv,label));
            }
        }
    }

    @Test void youngEjectaAreBoundedBeforeEmission() {
        for (double radius : new double[]{1, 4, 10}) {
            var ejecta = new Ejecta(radius, 1000, .5);
            for (double t = .01; t < ejecta.life; t = t * 1.15 + .01) {
                var bounds = new SmokeBounds(); ejecta.bounds(t, bounds);
                String label = "ejecta " + radius + " age " + t;
                ejecta.jets(t, 42, (x,y,z,ax,ay,az,sigma,mass) -> {
                    contains(bounds,x-ax,y-ay,z-az,sigma,sigma,label);
                    contains(bounds,x+ax,y+ay,z+az,sigma,sigma,label);
                });
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
