package ua.zentix.incoming.strike;

import net.minecraft.core.BlockPos;
import org.slf4j.event.Level;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Unplanned flight end: one log line per group per tick. A 30-Shahed salvo targeting a dead player loses its
 * target in one tick; one line per projectile would produce dozens (logscan retains 40 recent per section). Group
 * by projectile type and event; dispersed salvo projectiles have distinct points, so line gives center and
 * spread. Each projectile UUID is logged at DEBUG where event occurs. Written at world tick end ({@link
 * StrikeWorld}).
 */
public final class FlightLog {
    /** What happened to the flight. */
    public enum Event {
        /**
         * Target vanished (died, logged out, changed dimension, craft disassembled): projectile goes to its last
         * point.
         */
        LOST_GONE(Level.INFO, "lost the target (gone), heading to its last position"),
        /** Target moved beyond chase budget. */
        LOST_OUT_OF_REACH(Level.INFO, "lost the target (beyond pursuit range), heading to its last position"),
        /** Range exhausted in the world: self-destruct. */
        EXPIRED(Level.INFO, "ran out of endurance and self-destructed, target"),
        /** Range exhausted outside the world: removed without explosion. */
        EXPIRED_VIRTUAL(Level.WARN, "ran out of endurance off-world and were removed, target"),
        /** Collision before fuze arming (at launch): crash without warhead detonation. */
        CRASHED(Level.WARN, "crashed before fuze arming at"),
        /** Shot down by damage before arming (detail: damage type): crash without warhead detonation. */
        SHOT_DOWN(Level.WARN, "shot down before fuze arming at"),
        /** Removed externally (command, another mod's entity cleaner; detail: by whom). */
        REMOVED(Level.WARN, "removed externally at"),
        /** Shot down by interceptor (detail: which SAM): warhead did not detonate, casing fell as debris. */
        INTERCEPTED(Level.INFO, "shot down by a SAM at");

        private final Level level;
        private final String text;

        Event(Level level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    /** Log line: level and text. */
    record Line(Level level, String text) {}

    private record Key(String weapon, Event event, boolean targetLost, String detail) {}

    /** Projectile count in group, largest remaining range (seconds at cruise speed), and bounds of their points. */
    private static final class Group {
        int count;
        int seconds;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        void add(BlockPos p, int s) {
            count++;
            seconds = Math.max(seconds, s);
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }

        /** Bounds center and horizontal half-size, “x y z (±r)”; no spread for a single point. */
        String where() {
            int r = (Math.max(maxX - minX, maxZ - minZ) + 1) / 2;
            String at = Math.floorDiv(minX + maxX, 2) + " " + Math.floorDiv(minY + maxY, 2) + " " + Math.floorDiv(minZ + maxZ, 2);
            return r == 0 ? at : at + " (±" + r + ")";
        }
    }

    private final Map<Key, Group> groups = new LinkedHashMap<>();
    /** All-time projectile counts by event (tests). */
    private final int[] totals = new int[Event.values().length];

    /**
     * Record a projectile.
     *
     * @param weapon projectile type (entity type id)
     * @param point last target point (on loss) or destination point
     * @param targetLost target already lost
     * @param seconds remaining seconds of flight at cruise speed (for target loss; otherwise 0)
     */
    public void note(String weapon, Event event, BlockPos point, boolean targetLost, int seconds) {
        note(weapon, event, point, targetLost, seconds, "");
    }

    /** Same with detail (heading, damage type, remover): separate line for each. */
    public void note(String weapon, Event event, BlockPos point, boolean targetLost, int seconds, String detail) {
        groups.computeIfAbsent(new Key(weapon, event, targetLost, detail), k -> new Group()).add(point, seconds);
        totals[event.ordinal()]++;
    }

    /** Projectiles recorded for {@code event} during this world's lifetime in memory. */
    public int total(Event event) {
        return totals[event.ordinal()];
    }

    /** Tick lines, one per group; recorded entries are forgotten. */
    List<Line> drain() {
        List<Line> lines = new ArrayList<>(groups.size());
        groups.forEach((k, g) -> {
            String tail = switch (k.event) {
                case LOST_GONE, LOST_OUT_OF_REACH -> ", endurance ≤ " + g.seconds + " s";
                case EXPIRED, EXPIRED_VIRTUAL -> k.targetLost ? " (lost)" : "";
                case CRASHED -> k.detail.isEmpty() ? "" : ", " + k.detail;
                case SHOT_DOWN -> ", damage " + k.detail;
                case REMOVED -> ", by: " + k.detail;
                case INTERCEPTED -> ", " + k.detail;
            };
            lines.add(new Line(k.event.level, String.format(Locale.ROOT, "Projectiles: %d × %s %s %s%s",
                    g.count, k.weapon, k.event.text, g.where(), tail)));
        });
        groups.clear();
        return lines;
    }

    /** Log at world tick end. */
    void flush() {
        if (groups.isEmpty()) return;
        for (Line line : drain()) Incoming.LOG.atLevel(line.level()).log(line.text());
    }
}
