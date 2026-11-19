package ua.zentix.incoming.client.nuclear;

import java.lang.reflect.Field;

/**
 * Measurement only (wave-proposal, not for merge): the shown detonation age and its clock state by reflection, so the same
 * file compiles before #668 (own client ticks, {@code age}) and after it ({@code AgeClock}); and a hold of the shown age for
 * frozen-time frames.
 */
public final class AgeProbe {
    private static Field age, clock, anchor, since, shown, framed, streamed;
    private static boolean ready, hasClock;

    private AgeProbe() {}

    private static void init() {
        if (ready) return;
        ready = true;
        try {
            age = ClientNuclear.Active.class.getDeclaredField("age");
            age.setAccessible(true);
        } catch (ReflectiveOperationException ignored) {
            // absent
        }
        try {
            clock = ClientNuclear.Active.class.getDeclaredField("clock");
            clock.setAccessible(true);
            Class<?> c = clock.getType();
            anchor = c.getDeclaredField("anchor");
            since = c.getDeclaredField("since");
            shown = c.getDeclaredField("shown");
            framed = c.getDeclaredField("framed");
            streamed = c.getDeclaredField("streamed");
            for (Field f : new Field[]{anchor, since, shown, framed, streamed}) f.setAccessible(true);
            hasClock = true;
        } catch (ReflectiveOperationException ignored) {
            hasClock = false;
        }
    }

    public static boolean anchored() {
        init();
        return hasClock;
    }

    /** "anchor since shown framed streamed ownAge" (after #668) or "- - - - - ownAge" (before). */
    public static String state(ClientNuclear.Active a) {
        init();
        try {
            long own = age == null ? -1 : age.getLong(a);
            if (!hasClock) return "- - - - - " + own;
            Object c = clock.get(a);
            return anchor.getLong(c) + " " + since.getLong(c) + " " + shown.getLong(c) + " " + String.format(java.util.Locale.ROOT, "%.3f", framed.getDouble(c))
                    + " " + (streamed.getBoolean(c) ? 1 : 0) + " " + own;
        } catch (ReflectiveOperationException e) {
            return "? " + e;
        }
    }

    /** Largest age returned to a frame (after #668), NaN before. */
    public static double framed(ClientNuclear.Active a) {
        init();
        if (!hasClock) return Double.NaN;
        try {
            return framed.getDouble(clock.get(a));
        } catch (ReflectiveOperationException e) {
            return Double.NaN;
        }
    }

    /** Whole shown ticks now. */
    public static long shownTicks(ClientNuclear.Active a) {
        init();
        try {
            return hasClock ? shown.getLong(clock.get(a)) : age.getLong(a);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Hold the shown age at {@code h} whole ticks (call every tick and frame while held). */
    public static void hold(ClientNuclear.Active a, long h) {
        init();
        try {
            if (hasClock) {
                Object c = clock.get(a);
                anchor.setLong(c, h - 1);
                since.setLong(c, 0);
                shown.setLong(c, h);
                framed.setDouble(c, h);
                streamed.setBoolean(c, true);
            } else {
                // before #668: ticks = before + age (+ partial); own age only
                age.setLong(a, h - beforeOf(a));
            }
        } catch (ReflectiveOperationException ignored) {
            // measurement
        }
    }

    private static long beforeOf(ClientNuclear.Active a) throws ReflectiveOperationException {
        Field b = ClientNuclear.Active.class.getDeclaredField("before");
        b.setAccessible(true);
        return b.getLong(a);
    }
}
