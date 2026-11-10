package ua.zentix.incoming.client;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.hud.Countdown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickTimeTest {
    /** Zearth's game time on 10.11.2026: about 2³¹ ticks, where a float steps by 128 ticks. */
    private static final long OLD_WORLD = 2_040_453_081L;

    @Test
    void partialTickSurvivesAnOldWorldsGameTime() {
        assertEquals(OLD_WORLD + 0.5, TickTime.at(OLD_WORLD, 0.5f));
        assertEquals(OLD_WORLD + 0.25, TickTime.at(OLD_WORLD, 0.25f));
        float lossy = OLD_WORLD + 0.5f;
        assertEquals(2_040_453_120.0, lossy, "long + float is float arithmetic: the old HUD clock stood here");
    }

    /**
     * The nuclear HUD's detonation countdown in an old world steps once a second, every second: in 3.12.0 it stood for
     * 6.4 s and then jumped (frame probe, Zearth copy, 10.11.2026).
     */
    @Test
    void detonationCountdownStepsEverySecondInAnOldWorld() {
        long detonate = OLD_WORLD + 60 * 20;
        Countdown countdown = new Countdown();
        List<String> shown = new ArrayList<>();
        for (long tick = OLD_WORLD; tick < OLD_WORLD + 20 * 20; tick++) {
            for (float partial = 0; partial < 1; partial += 0.25f) {
                double now = TickTime.at(tick, partial);
                String s = Countdown.seconds(countdown.next(Math.max(0, (detonate - now) / 20.0)));
                if (shown.isEmpty() || !shown.getLast().equals(s)) shown.add(s);
            }
        }
        List<String> expected = new ArrayList<>();
        for (int s = 60; s > 40; s--) expected.add(Integer.toString(s));
        assertEquals(expected, shown);
    }

    /** Game time and tick counters take the partial tick only through {@link TickTime}: a bare sum is float arithmetic. */
    @Test
    void noBarePartialTickSums() throws IOException {
        Pattern bare = Pattern.compile("(getGameTime\\(\\)|ticks\\(\\)|\\bticks)\\s*\\+\\s*(partial\\w*|pt\\b|view\\.partial\\(\\)|\\w+\\.getGameTimeDeltaPartialTick\\()");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.endsWith("TickTime.java")).toList()) {
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    if (bare.matcher(lines.get(i)).find()) found.add(f + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }
        assertEquals(List.of(), found);
    }
}
