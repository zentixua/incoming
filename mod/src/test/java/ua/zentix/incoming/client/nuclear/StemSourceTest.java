package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.StemDust;

import static org.junit.jupiter.api.Assertions.*;

class StemSourceTest {
    @Test
    void footUsesTheSupplyingLayersClockForBothBurstModesAndScales() {
        for (boolean ground : new boolean[]{false, true}) {
            for (float scale : new float[]{0.25f, 1, 2.5f}) {
                for (float wave : new float[]{0.25f, 1}) {
                    double y = 15, hob = ground ? 0 : 592;
                    Detonation d = new Detonation(1, new Vec3(0, 64 + hob * scale, 0), 64, y, ground,
                            0, 0, 0, 20_000, 7, scale, false, wave);
                    PallField pall = new PallField(d, 6000);
                    StemDust dust = StemDust.of(pall.stemSource);
                    for (double t : new double[]{30, 60}) {
                        MushroomModel.Shape shape = MushroomModel.shape(t, y, hob, ground, 0, dust);
                        double wall = MushroomModel.stemRadius(0, shape.stemR(), MushroomModel.dustFlare(shape));
                        double from = d.arrivalTicks(d.slantOf(d.blocks(wall))) / (20 * scale)
                                + DustModel.stemFrom(hob, y, FireballModel.maxRadius(y, ground));
                        double[] foot = new double[5];
                        MushroomField.foot(d, pall, PallField.Water.DRY, shape, t * 20 * scale, foot);
                        assertTrue(foot[1] > 0 && foot[2] > 0, "positive dry layer and stem required");
                        assertEquals(d.blocks(MushroomModel.stemRise(t, hob, y, ground)
                                - MushroomModel.stemRise(from, hob, y, ground)), foot[3], 1e-8);
                    }
                }
            }
        }
    }
}
