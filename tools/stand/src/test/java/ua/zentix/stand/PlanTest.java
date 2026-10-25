package ua.zentix.stand;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** План, как его пишет stand.py (plan.py): все виды шагов читаются, ошибки — сразу, а не посреди прогона. */
class PlanTest {
    private static final String PLAN = """
            {"seed": 20261025, "day_time": 6000, "settle_s": 10, "limit_s": 1500,
             "projectiles": ["incoming:drone"], "warheads": ["incoming:strike"], "probes": ["a.B#c"], "once": ["d.E#f"],
             "steps": [
              {"do": "tp", "eye": [800.0, 95, 0.0], "look": [1000.0, 80, 0.0], "settle_s": 8},
              {"do": "salvo", "id": "100", "x": 1000.0, "z": 0.0, "radius": 160, "expect": 60,
               "cmds": ["incoming salvo drone 30 120 at 1000 0 from 1200 346.4", "incoming salvo drone 30 120 at 1000 0 from 800 346.4"],
               "delays": [0, 20]},
              {"do": "await", "salvo": "100", "timeout_s": 300, "quiet_s": 30},
              {"do": "shot", "name": "100_edge"},
              {"do": "wait", "s": 0.5},
              {"do": "window", "name": "100/edge", "s": 10},
              {"do": "impact_window", "name": "one", "salvo": "one", "n": 1, "s": 15, "shots": [0.5, 3, 15], "prefix": "one", "timeout_s": 300},
              {"do": "cmd", "cmd": "time set 6000"},
              {"do": "mark", "name": "end"}
             ]}
            """;

    private static Plan read(String json) throws IOException {
        Path f = Files.createTempFile("stand-plan", ".json");
        try {
            Files.writeString(f, json);
            return Plan.read(f);
        } finally {
            Files.delete(f);
        }
    }

    @Test
    void readsEveryStep() throws IOException {
        Plan p = read(PLAN);
        assertEquals(20261025L, p.seed());
        assertTrue(p.projectiles().contains("incoming:drone"));
        assertTrue(p.warheads().contains("incoming:strike"));
        assertEquals(9, p.steps().size());
        Plan.Tp tp = assertInstanceOf(Plan.Tp.class, p.steps().get(0));
        assertArrayEquals(new double[]{800, 95, 0}, tp.eye());
        Plan.Salvo s = assertInstanceOf(Plan.Salvo.class, p.steps().get(1));
        assertEquals(60, s.expect());
        assertEquals(2, s.commands().size());
        assertArrayEquals(new double[]{0, 20}, s.delays());
        Plan.AwaitSalvo a = assertInstanceOf(Plan.AwaitSalvo.class, p.steps().get(2));
        assertEquals(30, a.quietS());
        Plan.ImpactWindow w = assertInstanceOf(Plan.ImpactWindow.class, p.steps().get(6));
        assertArrayEquals(new double[]{0.5, 3, 15}, w.shots());
        assertInstanceOf(Plan.Mark.class, p.steps().get(8));
    }

    @Test
    void delaysMustMatchCommands() {
        String bad = PLAN.replace("\"delays\": [0, 20]", "\"delays\": [0]");
        assertThrows(IllegalArgumentException.class, () -> read(bad));
    }

    @Test
    void unknownStepFails() {
        String bad = PLAN.replace("\"do\": \"mark\"", "\"do\": \"dance\"");
        assertThrows(IllegalArgumentException.class, () -> read(bad));
    }
}
