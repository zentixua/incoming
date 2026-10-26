package ua.zentix.incoming.gametest.scenario;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

/**
 * Script running mode (system properties of tasks Gradle).
 * <ul>
 *     <li>{@code incoming.scenario.only} ({@code -PscenarioOnly=…}) - only scripts whose name matches
 *      regular expression (without backslash: argument file MDG doubles it): this is how one fall is repeated; </li>
 *     <li>{@code incoming.scenario.realChunks} ({@code -PscenarioRealChunks}) - real loading of chunks: without
 *     {@link InstantChunks}, server at the pace of the game (20 tick/s), the properties are the same, the reference is not checked (the flight depends on how fast the machine generates).</li>
 * </ul>
 */
final class ScenarioMode {
    static final boolean REAL_CHUNKS = Boolean.getBoolean("incoming.scenario.realChunks");
    private static final Pattern ONLY = compile(System.getProperty("incoming.scenario.only", ""));

    private static boolean paced;
    private static long lastTick;

    private ScenarioMode() {}

    private static Pattern compile(String regex) {
        return regex.isBlank() ? null : Pattern.compile(regex);
    }

    static boolean selected(Scenario s) {
        return ONLY == null || ONLY.matcher(s.id()).find();
    }

    /** How to repeat the scenario in one run - for each crash message. */
    static String reproduce(Scenario s) {
        // do not escape dots: argument file MDG doubles backslash, and dot already matches dot
        return "repeat: ./gradlew runScenarioSweep -PscenarioOnly='^" + s.id() + "$' -PscenarioSeeds="
                + (s.seed() + 1) + (REAL_CHUNKS ? " -PscenarioRealChunks" : "");
    }

    /**
     * The pace of the game is for the entire server at once, and not for a test: batch scenarios run simultaneously, and a pause in the tick of each
     *  would add up. The GameTest server otherwise ticks without pauses, and the background generation never keeps up with the flight.
     */
    static synchronized void paceServer() {
        if (paced) return;
        paced = true;
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            // parkNanos wakes up earlier (chunk tasks wake up the server thread): wait until the deadline in a loop
            long deadline = lastTick + 50_000_000L, left;
            while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
            lastTick = System.nanoTime();
        });
    }
}
