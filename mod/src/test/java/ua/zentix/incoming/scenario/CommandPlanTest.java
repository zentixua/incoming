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

    /**
     * Measuring steps (wave-color run): {@code site:} picks a fresh ground zero by biome (200 ticks), {@code wave:} and {@code pall:} set the world
     * wave speed and the client dust quality (1 tick), {@code pair:} shoots a frame and the same view with the pall off (20 ticks).
     */
    @Test
    void siteWavePallPair() {
        CommandPlan p = CommandPlan.parse("wave:0.35;pall:MEDIUM;site:#minecraft:is_forest,minecraft:desert;pair:front");
        assertTrue(p.warnings().isEmpty(), p.warnings().toString());
        assertEquals(java.util.List.of(CommandPlan.Kind.WAVE, CommandPlan.Kind.PALL, CommandPlan.Kind.SITE, CommandPlan.Kind.PAIR),
                p.steps().stream().map(CommandPlan.Step::kind).toList());
        assertEquals("0.35", p.steps().get(0).arg());
        assertEquals("#minecraft:is_forest,minecraft:desert", p.steps().get(2).arg());
        // wave 100 (+1), pall 101 (+1), site 102 (+200), pair 302 (+20)
        assertEquals(302, p.steps().get(3).tick());
        assertEquals(322 + CommandPlan.TAIL, p.end());
    }

    /** On a joined server (incoming.server) these steps would read the absent integrated server and crash the client. */
    @Test
    void ownServerStepsAreKnown() {
        CommandPlan p = CommandPlan.parse("burnable;site:#is_forest;skip:60;wait:blast;maxyield:1000;wave:0.35;say hi");
        java.util.Set<CommandPlan.Kind> own = java.util.EnumSet.noneOf(CommandPlan.Kind.class);
        for (CommandPlan.Step s : p.steps()) if (s.kind().ownServer()) own.add(s.kind());
        assertEquals(java.util.EnumSet.of(CommandPlan.Kind.BURNABLE, CommandPlan.Kind.SITE, CommandPlan.Kind.SKIP), own);
        assertEquals(1, p.blastGates().size());
    }
}
