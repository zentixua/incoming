package ua.zentix.incoming.strike;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadoutTest {
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

    /** Wheel past the Grad and back: the missile stays a single shot (a new player got a 12-missile order). */
    @Test
    void scrollingPastBurstWeaponRestoresSingleShot() {
        Loadout grad = Loadout.DEFAULT.withWeapon(WeaponType.ROCKET);
        assertTrue(grad.count() > 1);
        Loadout back = grad.withWeapon(WeaponType.MISSILE);
        assertEquals(1, back.count());
        assertEquals(0, back.spread());
        // a burst the player set on purpose stays
        Loadout chosen = grad.withCount(5).withWeapon(WeaponType.MISSILE);
        assertEquals(5, chosen.count());
    }
}
