package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightLogTest {
    private static final String DRONE = "entity.incoming.drone";

    /**
     * A volley from 30 Shahed at a player with a spread loses the target in one tick: each has its own last point (the spread of the volley),
     *  and in the log there is one line with a middle and a spread, and not 30.
     */
    @Test
    void salvoWithSpreadIsOneLine() {
        FlightLog log = new FlightLog();
        for (int i = 0; i < 30; i++) {
            log.note(DRONE, FlightLog.Event.LOST_GONE, new BlockPos(-300 + i * 2, 70, 100 - i), true, 10 + i % 3);
        }
        List<FlightLog.Line> lines = log.drain();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.getFirst().level());
        String text = lines.getFirst().text();
        assertTrue(text.startsWith("Projectiles: 30 × " + DRONE + " lost the target (gone)"), text);
        assertTrue(text.endsWith(" -271 70 85 (±29), endurance ≤ 12 s"), text);
        assertTrue(log.drain().isEmpty(), "lines behind tick are not forgotten");
    }

    /** Different events and types - different lines; one point - no scatter. */
    @Test
    void eventsAndWeaponsAreSeparate() {
        FlightLog log = new FlightLog();
        log.note(DRONE, FlightLog.Event.LOST_GONE, new BlockPos(0, 64, 0), true, 5);
        log.note(DRONE, FlightLog.Event.EXPIRED_VIRTUAL, new BlockPos(10, 64, 10), true, 0);
        log.note("entity.incoming.cruise_missile", FlightLog.Event.LOST_GONE, new BlockPos(0, 64, 0), true, 3);
        List<FlightLog.Line> lines = log.drain();
        assertEquals(3, lines.size(), lines.toString());
        assertTrue(lines.get(0).text().endsWith(" 0 64 0, endurance ≤ 5 s"), lines.get(0).text());
        assertEquals(Level.WARN, lines.get(1).level());
        assertTrue(lines.get(1).text().endsWith(" 10 64 10 (lost)"), lines.get(1).text());
    }

    /** Crashed before cocking: WARN with space (middle and spread), the event score is not reset by tick lines. */
    @Test
    void crashedIsWarnedWithPlace() {
        FlightLog log = new FlightLog();
        for (int i = 0; i < 30; i++) log.note(DRONE, FlightLog.Event.CRASHED, new BlockPos(-250 + i % 3, 90, -1140), false, 0, "heading 90°, phase boost");
        List<FlightLog.Line> lines = log.drain();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.WARN, lines.get(0).level());
        assertTrue(lines.get(0).text().startsWith("Projectiles: 30 × "), lines.get(0).text());
        assertTrue(lines.get(0).text().endsWith(" crashed before fuze arming at -249 90 -1140 (±1), heading 90°, phase boost"), lines.get(0).text());
        assertEquals(30, log.total(FlightLog.Event.CRASHED));
    }

    /** Knocked down before cocking: line for damage type. */
    @Test
    void shotDownSplitsByDamage() {
        FlightLog log = new FlightLog();
        log.note(DRONE, FlightLog.Event.SHOT_DOWN, new BlockPos(0, 80, 0), false, 0, "onFire");
        log.note(DRONE, FlightLog.Event.SHOT_DOWN, new BlockPos(2, 80, 0), false, 0, "onFire");
        log.note(DRONE, FlightLog.Event.SHOT_DOWN, new BlockPos(0, 80, 0), false, 0, "player");
        List<FlightLog.Line> lines = log.drain();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).text().startsWith("Projectiles: 2 × "), lines.get(0).text());
        assertTrue(lines.get(0).text().endsWith(" shot down before fuze arming at 1 80 0 (±1), damage onFire"), lines.get(0).text());
        assertEquals(Level.WARN, lines.get(1).level());
    }

    /** Shot down by an anti-aircraft missile: standard outcome (INFO), in the line - which SAM. */
    @Test
    void interceptedNamesLauncher() {
        FlightLog log = new FlightLog();
        log.note(DRONE, FlightLog.Event.INTERCEPTED, new BlockPos(500, 120, -40), false, 0, "SAM 10, 64, -3");
        List<FlightLog.Line> lines = log.drain();
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.getFirst().level());
        assertTrue(lines.getFirst().text().endsWith(" shot down by a SAM at 500 120 -40, SAM 10, 64, -3"), lines.getFirst().text());
        assertEquals(1, log.total(FlightLog.Event.INTERCEPTED));
    }
}
