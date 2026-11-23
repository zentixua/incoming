package ua.zentix.incoming.strike;

import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The policy's gates ({@link Authority#decide}) and the nodes a permission mod sees: one per right, one per weapon under
 * its command name; a refused weapon leaves the others; the second key asks only nuclear rights; linking asks the
 * designator first.
 */
class AuthorityTest {
    @Test
    void nodesAreNamedForPermissionMods() {
        List<String> names = Authority.nodes().stream().map(PermissionNode::getNodeName).toList();
        assertEquals(List.of("incoming.launch", "incoming.launch.drone", "incoming.launch.missile", "incoming.launch.bunker", "incoming.launch.nuke",
                "incoming.launch.rocket", "incoming.launch.loiter", "incoming.nuclear", "incoming.launcher.link"), names);
        assertEquals(names.size(), new HashSet<>(names).size(), "node names repeat");
    }

    @Test
    void aRefusedWeaponLeavesTheOthers() {
        Set<PermissionNode<Boolean>> granted = new HashSet<>(Authority.nodes());
        granted.remove(Authority.weapon(WeaponType.ROCKET));
        for (WeaponType w : WeaponType.values()) {
            Authority.Gate gate = Authority.decide(Authority.Action.launch(w, false), true, granted::contains);
            if (w == WeaponType.ROCKET) assertEquals(Authority.Gate.WEAPON, gate);
            else assertNull(gate, w + " refused");
        }
        // the weapon of a nuclear order is asked before nuclear rights
        assertEquals(Authority.Gate.WEAPON, Authority.decide(Authority.Action.launch(WeaponType.ROCKET, true), true, granted::contains));
        // a nuclear command without a weapon of its own asks the designator, the flag and nuclear rights
        assertNull(Authority.decide(Authority.Action.launch(null, true), true, granted::contains));
        assertEquals("incoming.no_permission.weapon", NuclearRefusalTest.key(new Authority.Denial(Authority.Gate.WEAPON, WeaponType.ROCKET, false).line()));
    }

    @Test
    void gatesComeInOrder() {
        Set<PermissionNode<Boolean>> none = Set.of();
        assertEquals(Authority.Gate.USE, Authority.decide(Authority.Action.USE, true, none::contains));
        assertEquals(Authority.Gate.USE, Authority.decide(Authority.Action.LINK, true, none::contains));
        assertEquals(Authority.Gate.USE, Authority.decide(Authority.Action.launch(WeaponType.NUKE, true), false, none::contains));
        Set<PermissionNode<Boolean>> designator = Set.of(Authority.LAUNCH);
        assertEquals(Authority.Gate.LINK, Authority.decide(Authority.Action.LINK, true, designator::contains));
        assertNull(Authority.decide(Authority.Action.USE, true, designator::contains));
        // the second key: the flag is a hard gate above the node; the designator itself is not asked
        Set<PermissionNode<Boolean>> nuclear = Set.of(Authority.NUCLEAR);
        assertNull(Authority.decide(Authority.Action.SECOND_KEY, true, nuclear::contains));
        assertEquals(Authority.Gate.NUCLEAR_OFF, Authority.decide(Authority.Action.SECOND_KEY, false, nuclear::contains));
        assertEquals(Authority.Gate.NUCLEAR, Authority.decide(Authority.Action.SECOND_KEY, true, none::contains));
        // a granted nuclear node never opens a world with nuclear weapons off
        Set<PermissionNode<Boolean>> all = new HashSet<>(Authority.nodes());
        assertEquals(Authority.Gate.NUCLEAR_OFF, Authority.decide(Authority.Action.launch(WeaponType.NUKE, true), false, all::contains));
    }

    @Test
    void handlerRefusalsDoNotBlameTheSettings() {
        // the default handler's lines point to the settings that refused; a permission mod's say only what is refused
        assertEquals("incoming.no_permission", NuclearRefusalTest.key(new Authority.Denial(Authority.Gate.USE, null, true).line()));
        assertEquals("incoming.no_permission.handler", NuclearRefusalTest.key(new Authority.Denial(Authority.Gate.USE, null, false).line()));
        assertEquals("incoming.nuke.ops_only", NuclearRefusalTest.key(new Authority.Denial(Authority.Gate.NUCLEAR, null, true).line()));
        assertEquals("incoming.no_permission.nuclear", NuclearRefusalTest.key(new Authority.Denial(Authority.Gate.NUCLEAR, null, false).line()));
        assertTrue(Authority.nodes().contains(Authority.LINK));
    }
}
