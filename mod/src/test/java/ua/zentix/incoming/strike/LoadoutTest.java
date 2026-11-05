package ua.zentix.incoming.strike;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadoutTest {
    @Test
    void supportedNuclearCarriersClampAndRoundTripTheFullYieldRange() {
        for (WeaponType weapon : new WeaponType[]{WeaponType.NUKE, WeaponType.MISSILE, WeaponType.BUNKER}) {
            for (int input : new int[]{-1, 1, 15, 100, 500, 1000, 1001}) {
                int expected = Math.clamp(input, 1, 1000);
                Loadout loadout = Loadout.DEFAULT.withWeapon(weapon)
                        .withNuke(new Loadout.Nuke(input, false, true));
                assertTrue(loadout.nuclear());
                assertEquals(expected, loadout.nuke().yieldKt());
                var encoded = Loadout.CODEC.encodeStart(NbtOps.INSTANCE, loadout).getOrThrow();
                assertEquals(loadout, Loadout.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
                var buffer = io.netty.buffer.Unpooled.buffer();
                try {
                    Loadout.STREAM_CODEC.encode(buffer, loadout);
                    assertEquals(loadout, Loadout.STREAM_CODEC.decode(buffer));
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void newAndMissingDesignatorSettingsUseGroundBursts() {
        assertFalse(Loadout.Nuke.DEFAULT.airBurst());
        assertFalse(Loadout.DEFAULT.withWeapon(WeaponType.NUKE).nuke().airBurst());
        assertFalse(Loadout.Nuke.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow().airBurst());
        assertFalse(Loadout.CODEC.parse(NbtOps.INSTANCE, new CompoundTag()).getOrThrow().nuke().airBurst());
        assertFalse(new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", null).nuke().airBurst());
    }

    @Test
    void explicitSavedAirRemainsAvailableAndBothModesRoundTrip() {
        CompoundTag savedAir = new CompoundTag();
        savedAir.putBoolean("air_burst", true);
        Loadout.Nuke air = Loadout.Nuke.CODEC.parse(NbtOps.INSTANCE, savedAir).getOrThrow();
        assertTrue(air.airBurst());
        for (boolean burst : new boolean[]{false, true}) {
            Loadout.Nuke nuke = new Loadout.Nuke(6, burst, true);
            var encoded = Loadout.Nuke.CODEC.encodeStart(NbtOps.INSTANCE, nuke).getOrThrow();
            assertEquals(nuke, Loadout.Nuke.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
        }
    }
}
