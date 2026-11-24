package ua.zentix.incoming.compat.mekanism;

import mekanism.api.radiation.capability.IRadiationShielding;
import net.neoforged.neoforge.capabilities.ItemCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mekanism adapter against Mekanism's API (Mekanism itself is not in the GameTest runtime): the capability looked up
 * under Mekanism's name and type is one instance, so it is the one Mekanism registers its items under
 * ({@code Capabilities.RADIATION_SHIELDING = ItemCapability.createVoid(Mekanism.rl("radiation_shielding"), IRadiationShielding.class)});
 * a piece's value is Mekanism's, and an item without the capability has none (the data map or nothing decides).
 */
class MekanismShieldingTest {
    @Test
    void capabilityIsMekanismsByName() {
        assertEquals("mekanism:radiation_shielding", MekanismShielding.NAME.toString());
        assertSame(MekanismShielding.CAPABILITY, ItemCapability.createVoid(MekanismShielding.NAME, IRadiationShielding.class));
    }

    @Test
    void valueIsMekanismsShielding() {
        // Mekanism's hazmat suit, head to feet (ItemHazmatSuitArmor.getShieldingByArmor)
        double[] hazmat = {0.25, 0.4, 0.2, 0.15};
        for (double v : hazmat) assertEquals(v, MekanismShielding.value(() -> v));
        assertTrue(Double.isNaN(MekanismShielding.value(null)), "no capability: no value");
        // summed in WornShielding's slot order (feet, legs, chest, head), as Mekanism sums them: exactly full protection
        assertEquals(1.0, hazmat[3] + hazmat[2] + hazmat[1] + hazmat[0]);
        IRadiationShielding broken = () -> 7;
        assertEquals(7, MekanismShielding.value(broken), "the adapter passes the value on; WornShielding limits it");
    }
}
