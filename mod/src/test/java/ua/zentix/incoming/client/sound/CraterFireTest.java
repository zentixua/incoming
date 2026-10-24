package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraterFireTest {
    private static CloudModel model(WeaponType weapon) {
        return new CloudModel(BlastSource.of(weapon, false), CloudModel.Air.STANDARD);
    }

    @Test
    void burningFuelDoesNotFadeOrGoSilentAtTheOldRecordingBoundaries() {
        for (WeaponType weapon : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = model(weapon);
            assertTrue(m.poolSeconds > 30);
            // The old recording faded after 9 s and restarted every 13 s, with weapon-dependent gaps/overlaps.
            for (double t : new double[]{1, 9, 12.9, 13, 13.1, 26, 30}) {
                assertEquals(m.poolWatts, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), t, 0), 1e-6, weapon + " at " + t);
            }
        }
    }

    @Test
    void envelopeArrivesAtSoundSpeedAndEndsWithTheFuel() {
        CloudModel m = model(WeaponType.DRONE);
        double distance = 2 * Noise.SOUND;
        assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 1.9, distance));
        assertEquals(m.poolWatts, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 2.1, distance), 1e-6);
        assertEquals(m.poolWatts / 2, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 0.9 * m.poolSeconds + 2, distance), 1e-6);
        assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), m.poolSeconds + 2, distance));
    }

    @Test
    void noFuelOrWaterMeansNoFireSound() {
        CloudModel dry = model(WeaponType.ROCKET);
        CloudModel water = new CloudModel(BlastSource.of(WeaponType.DRONE, false), CloudModel.Air.STANDARD, true);
        for (CloudModel m : new CloudModel[]{dry, water}) {
            assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 1, 0));
            assertEquals(0, CraterFire.heat(ua.zentix.incoming.client.fx.FuelBurn.at(m.pool, net.minecraft.core.BlockPos.ZERO), 30, 0));
        }
    }
}
