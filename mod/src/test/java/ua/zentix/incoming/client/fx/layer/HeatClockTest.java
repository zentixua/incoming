package ua.zentix.incoming.client.fx.layer;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Heat shimmer runs on the client level's tick, not the wall clock (time.md §8.3 rule 9). */
class HeatClockTest {
    /** The same level time gives the same shimmer: a frozen level, a paused game or replay show a still pattern. */
    @Test
    void sameLevelTimeSamePattern() {
        Vector4f a = HeatPass.clock(123.45, new Vector4f()), b = HeatPass.clock(123.45, new Vector4f());
        assertEquals(a, b);
    }

    /**
     * One advection cycle per {@link HeatPass#HAZE_CYCLE} seconds of level time: at {@code /tick rate 10} a real second
     * is half a game second and the shimmer moves half as far; an exported video's second of replay is one game second.
     */
    @Test
    void cyclesFollowLevelSeconds() {
        Vector4f v = new Vector4f();
        HeatPass.clock(0, v);
        assertEquals(0, v.x, 1e-6);
        assertEquals(0.5, v.z, 1e-6, "second layer half a cycle ahead");
        HeatPass.clock(HeatPass.HAZE_CYCLE / 2, v);
        assertEquals(0.5, v.x, 1e-6);
        assertEquals(0, v.y, 0, "still the first cycle");
        assertEquals(0, v.z, 1e-6);
        assertEquals(1.5, v.w, 0, "second layer in its next cycle");
        HeatPass.clock(HeatPass.HAZE_CYCLE * 1000.25, v);
        assertEquals(0.25, v.x, 1e-5);
        assertEquals(1000 % HeatPass.CYCLE_SEEDS, v.y, 0);
    }

    /** No wall clock in the shimmer: that clock ran under pause, freeze and replay pause, and at the wrong pace in export. */
    @Test
    void noWallClock() throws IOException {
        String src = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/fx/layer/HeatPass.java"));
        for (String wall : new String[] {"System.nanoTime", "System.currentTimeMillis", "Util.getMillis", "Util.getNanos"})
            assertFalse(src.contains(wall), wall);
    }
}
