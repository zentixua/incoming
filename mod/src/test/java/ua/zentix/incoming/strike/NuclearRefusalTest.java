package ua.zentix.incoming.strike;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A refused nuclear order names its own reason (a new player who is not an operator was told "the designator is for
 * operators only", though the designator worked and only nuclear weapons needed rights).
 */
class NuclearRefusalTest {
    private static final Loadout ICBM = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, false, false));
    private static final Loadout CARRIER = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, false, true));

    @Test
    void eachRefusalNamesItsCause() {
        assertEquals("incoming.no_permission", key(NuclearReview.refusal(ICBM, false, true, false, true, 100)));
        assertEquals("incoming.nuke.disabled", key(NuclearReview.refusal(ICBM, true, false, false, true, 100)));
        assertEquals("incoming.nuke.ops_only", key(NuclearReview.refusal(ICBM, true, true, true, true, 100)));
        assertEquals("incoming.nuke.carrier_off", key(NuclearReview.refusal(CARRIER, true, true, false, false, 100)));
        assertEquals("incoming.nuke.over_cap", key(NuclearReview.refusal(ICBM, true, true, false, true, 10)));
        assertNull(NuclearReview.refusal(ICBM, true, true, false, true, 15));
        // the ICBM always carries its warhead: the carrier setting does not apply to it
        assertNull(NuclearReview.refusal(ICBM, true, true, false, false, 100));
    }

    private static String key(Component c) {
        return c == null ? null : ((TranslatableContents) c.getContents()).getKey();
    }
}
