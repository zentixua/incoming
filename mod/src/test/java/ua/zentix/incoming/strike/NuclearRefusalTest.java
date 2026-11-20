package ua.zentix.incoming.strike;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A refused nuclear order names its own reason: the designator's rights, nuclear weapons in the world, operator rights,
 * carrier warheads, the yield limit; never the designator's rights for a nuclear-only limit.
 */
class NuclearRefusalTest {
    private static final Loadout ICBM = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, false, false));
    private static final Loadout CARRIER = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, false, true));

    @Test
    void eachRefusalNamesItsCause() {
        assertEquals("incoming.no_permission", key(ServerActions.nuclearRefusal(ICBM, false, true, false, true, 100)));
        assertEquals("incoming.nuke.disabled", key(ServerActions.nuclearRefusal(ICBM, true, false, false, true, 100)));
        assertEquals("incoming.nuke.ops_only", key(ServerActions.nuclearRefusal(ICBM, true, true, true, true, 100)));
        assertEquals("incoming.nuke.carrier_off", key(ServerActions.nuclearRefusal(CARRIER, true, true, false, false, 100)));
        assertEquals("incoming.nuke.over_cap", key(ServerActions.nuclearRefusal(ICBM, true, true, false, true, 10)));
        assertNull(ServerActions.nuclearRefusal(ICBM, true, true, false, true, 15));
        // several causes at once: the first one the player can act on, in the order of the checks
        assertEquals("incoming.nuke.disabled", key(ServerActions.nuclearRefusal(CARRIER, true, false, true, false, 10)));
        assertEquals("incoming.nuke.ops_only", key(ServerActions.nuclearRefusal(CARRIER, true, true, true, false, 10)));
        assertEquals("incoming.nuke.carrier_off", key(ServerActions.nuclearRefusal(CARRIER, true, true, false, false, 10)));
        // the ICBM always carries its warhead: the carrier setting does not apply to it
        assertNull(ServerActions.nuclearRefusal(ICBM, true, true, false, false, 100));
    }

    private static String key(Component c) {
        return c == null ? null : ((TranslatableContents) c.getContents()).getKey();
    }
}
