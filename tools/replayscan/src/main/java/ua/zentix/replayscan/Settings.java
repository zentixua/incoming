package ua.zentix.replayscan;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Scan settings from JVM properties {@code replayscan.*} (Gradle {@code -P<name>}, README.md).
 *
 * @param replay Flashback replay (zip); without it mod does nothing
 * @param out table directory (default {@code replayscan/<replay name>} under game directory)
 * @param speed replay ticks per second (20 normal); scan speed caps it
 * @param viewers visibility viewpoints: {@code recorder} or {@code players} (all players)
 * @param every entity visibility sampling interval, ticks (events/distant projectiles on every packet)
 * @param range maximum visibility distance, blocks
 * @param subjects entity type IDs to track (regex; players always included)
 * @param ask camera file (JSON, README.md), visibility from these points
 * @param from first tick to write, earlier ticks seek without tables
 * @param to last tick inclusive, 0 to end
 */
record Settings(Path replay, Path out, float speed, String viewers, int every, double range, Pattern subjects, Path ask, int from, int to) {

    static Settings read(Path gameDir) {
        String replay = System.getProperty("replayscan.replay");
        if (replay == null || replay.isBlank()) return null;
        Path zip = Path.of(replay).toAbsolutePath();
        String name = zip.getFileName().toString().replaceFirst("\\.zip$", "");
        String out = System.getProperty("replayscan.out");
        String ask = System.getProperty("replayscan.ask");
        return new Settings(zip, out == null ? gameDir.resolve("replayscan").resolve(name) : Path.of(out).toAbsolutePath(),
                Float.parseFloat(System.getProperty("replayscan.speed", "200")),
                System.getProperty("replayscan.viewers", "recorder"),
                Math.max(1, Integer.parseInt(System.getProperty("replayscan.every", "2"))),
                Double.parseDouble(System.getProperty("replayscan.range", "1500")),
                Pattern.compile(System.getProperty("replayscan.subjects", "(incoming|airstrike):(?!debris$).*")),
                ask == null || ask.isBlank() ? null : Path.of(ask).toAbsolutePath(),
                Integer.parseInt(System.getProperty("replayscan.from", "0")),
                Integer.parseInt(System.getProperty("replayscan.to", "0")));
    }
}
