package ua.zentix.incoming.strike;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A refused nuclear order names its own reason: the designator's rights, nuclear weapons in the world, operator rights,
 * carrier warheads, the yield limit; never the designator's rights for a nuclear-only limit. The rights come from the
 * one policy ({@link Authority#decide}); the default handler's lines are the switches' own.
 */
class NuclearRefusalTest {
    private static final Loadout ICBM = new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, false, false));
    private static final Loadout CARRIER = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, false, true));

    @Test
    void eachRefusalNamesItsCause() {
        Set<PermissionNode<Boolean>> all = Set.of(Authority.LAUNCH, Authority.NUCLEAR, Authority.weapon(WeaponType.NUKE), Authority.weapon(WeaponType.MISSILE));
        Set<PermissionNode<Boolean>> noDesignator = Set.of(Authority.NUCLEAR, Authority.weapon(WeaponType.NUKE), Authority.weapon(WeaponType.MISSILE));
        Set<PermissionNode<Boolean>> noNuclear = Set.of(Authority.LAUNCH, Authority.weapon(WeaponType.NUKE), Authority.weapon(WeaponType.MISSILE));
        assertEquals("incoming.no_permission", refusal(ICBM, noDesignator, true, true, 100));
        assertEquals("incoming.nuke.disabled", refusal(ICBM, all, false, true, 100));
        assertEquals("incoming.nuke.ops_only", refusal(ICBM, noNuclear, true, true, 100));
        assertEquals("incoming.nuke.carrier_off", refusal(CARRIER, all, true, false, 100));
        assertEquals("incoming.nuke.over_cap", refusal(ICBM, all, true, true, 10));
        assertNull(refusal(ICBM, all, true, true, 15));
        // several causes at once: the first one the player can act on, in the order of the checks
        assertEquals("incoming.nuke.disabled", refusal(CARRIER, noNuclear, false, false, 10));
        assertEquals("incoming.nuke.ops_only", refusal(CARRIER, noNuclear, true, false, 10));
        assertEquals("incoming.nuke.carrier_off", refusal(CARRIER, all, true, false, 10));
        // the ICBM always carries its warhead: the carrier setting does not apply to it
        assertNull(refusal(ICBM, all, true, false, 100));
    }

    /** The refusal as the order sees it, with the default handler's lines. */
    @Nullable
    private static String refusal(Loadout l, Set<PermissionNode<Boolean>> granted, boolean enabled, boolean carrierNukes, int cap) {
        Authority.Gate gate = Authority.decide(Authority.Action.launch(l.weapon(), true), enabled, granted::contains);
        if (gate != null) return key(new Authority.Denial(gate, l.weapon(), true).line());
        return key(ServerActions.nuclearRefusal(l, carrierNukes, cap));
    }

    @Nullable
    static String key(@Nullable Component c) {
        return c == null ? null : ((TranslatableContents) c.getContents()).getKey();
    }
}
