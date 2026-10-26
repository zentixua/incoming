package ua.zentix.incoming.scenario;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Analysis of the commands scenario: {@code wait:nuke} is the detonation waiting point, and not the missing “wait:N”. */
class CommandPlanTest {
    @Test
    void waitNukeSetsGate() {
        CommandPlan p = CommandPlan.parse("hud:off;incoming nuke at 0 64 0 15 air;wait:nuke;wait:10;shot:flash");
        // hud:off - tick 100 (+1), command - 101 (+40), wait:nuke - on 141, wait:10 - snapshot on 151
        assertEquals(141, p.nukeGate());
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
        CommandPlan.Step shot = p.steps().get(2);
        assertEquals(CommandPlan.Kind.SHOT, shot.kind());
        assertEquals("flash", shot.arg());
        assertEquals(151, shot.tick());
        assertEquals(171 + CommandPlan.TAIL, p.end());
    }

    @Test
    void gateOnlyOnceAndBadWaitsSkipped() {
        CommandPlan p = CommandPlan.parse("wait:nuke;wait:nuke;wait:abc;wait:-5;wait:72001;wait:  7 ;say hi");
        assertEquals(CommandPlan.START, p.nukeGate());
        assertEquals(4, p.warnings().size(), p.warnings().toString());
        assertEquals(CommandPlan.START + 7, p.steps().get(0).tick());
        assertEquals(CommandPlan.Kind.COMMAND, p.steps().get(0).kind());
    }

    @Test
    void emptyAndNoGate() {
        CommandPlan p = CommandPlan.parse(" ; ;");
        assertEquals(-1, p.nukeGate());
        assertTrue(p.steps().isEmpty());
        assertEquals(CommandPlan.START + CommandPlan.TAIL, p.end());
    }

    @Test
    void waitBlastGatesEveryTime() {
        CommandPlan p = CommandPlan.parse("incoming missile at 0 64 0;wait:blast;shot:flash;incoming drone at 0 64 0;wait:blast;wait:20;shot:column");
        // command - 100 (+40), wait:blast - 140, snapshot 140 (+20), command 160 (+40), wait:blast - 200, snapshot on 220
        assertEquals(java.util.List.of(140, 200), p.blastGates());
        assertEquals(-1, p.nukeGate());
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
        assertEquals(220, p.steps().get(3).tick());
    }
}
