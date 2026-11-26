package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastTime;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.net.S2C;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The air's readers are wired to the motion age (rule 2). A client detonation cannot be built without a running game, so
 * the readers' own lines are held here: each air reader takes M ({@code Active.airSeconds}, {@code airTicks},
 * {@code motionTicksAt}), and the clock packet's counted night reaches the conventional air through {@code CountedJump}.
 */
class AirWiringTest {
    private static final String ROOT = "src/main/java/ua/zentix/incoming/client/";

    /** The body of the first method whose declaration starts with {@code signature} in {@code file}. */
    private static String body(String file, String signature) throws IOException {
        String s = Files.readString(Path.of(ROOT + file));
        int at = s.indexOf(signature);
        assertTrue(at >= 0, file + ": no " + signature);
        int open = s.indexOf('{', at), depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(open, i + 1);
        }
        throw new AssertionError(file + ": unbalanced " + signature);
    }

    private static void reads(String file, String signature, String m, String... notReal) throws IOException {
        String b = body(file, signature);
        assertTrue(b.contains(m), file + " " + signature + " must read the motion age: " + m);
        for (String r : notReal) assertFalse(b.contains(r), file + " " + signature + " reads the real age: " + r);
    }

    @Test
    void theAirReadsTheMotionAge() throws IOException {
        reads("nuclear/MushroomField.java", "static MushroomModel.Shape shape(ClientNuclear.Active a, float partial)", "a.airSeconds(partial)", "a.seconds(");
        reads("nuclear/DustSources.java", "private static double seconds(ClientNuclear.Active a, float partial)", "a.airSeconds(partial)", "a.seconds(");
        reads("nuclear/CloudPuffs.java", "boolean pallLightNow(ClientNuclear.Active a", "a.airSeconds(0)", "a.seconds(");
        reads("nuclear/BlastDust.java", "public boolean emit(double seconds", "a.motionTicksAt(shown)", "a.ticks(0) + (");
        reads("nuclear/BlastDust.java", "public double puffGrowth(double seconds)", "a.airTicks(0)", "a.ticks(0) + (");
        String nuke = Files.readString(Path.of(ROOT + "nuclear/ClientNuclear.java"));
        assertTrue(nuke.contains("this.pall.motion(this::motionTicksAt);"), "the pall's dust is not on M");
        assertTrue(nuke.contains("current.motionTicksAt(current.clock.at(partial))"), "scorch smoke is not on M");
        assertTrue(nuke.contains("if (a.airTicks(0) > lifeTicks(a.d)"), "the detonation retires on its real age");
        String jump = body("fx/CountedJump.java", "public static void apply(long ticks)");
        for (String c : List.of("BlastTime.jump(ticks)", "SmokeGrid.jump(ticks)", "FireSmoke.jump(ticks)", "FarFlightView.jump(ticks)", "BlastSounds.jump(ticks)"))
            assertTrue(jump.contains(c), "CountedJump misses " + c);
    }

    private static S2C.NukeClock packet(double counted, double unseen) {
        return new S2C.NukeClock(1000, List.of(), new S2C.NukeClock.Clocks(900, 72, counted, unseen, List.of()));
    }

    /** The clock packet's counted night moves the conventional air's clocks once; a night someone saw and another level do not. */
    @Test
    void aCountedNightInThePacketMovesTheConventionalAir() {
        Object level = new Object();
        ClientNuclear.clock(packet(100, 0), level);
        long blast = BlastTime.ticks();
        double grid = SmokeGrid.seconds(0);
        ClientNuclear.clock(packet(100, 8000), level);
        assertEquals(blast, BlastTime.ticks(), "a night someone saw moved the clouds");
        ClientNuclear.clock(packet(8100, 8000), level);
        assertEquals(blast + 576_000, BlastTime.ticks(), "the blast clock with an 8-hour night");
        assertEquals(grid + 28_800, SmokeGrid.seconds(0), 1e-9, "the smoke grid with an 8-hour night");
        ClientNuclear.clock(packet(0, 0), new Object());
        assertEquals(blast + 576_000, BlastTime.ticks(), "another level's count is no jump");
        ClientNuclear.clock(new S2C.NukeClock(1000, List.of(), S2C.NukeClock.Clocks.NONE), new Object());
    }
}
