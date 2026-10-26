package ua.zentix.incoming.scenario.trailer;

import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;

/**
 * Client clock by video frames: while recording is in progress, the game between frames advances exactly one frame step (1/60 s video),
 * , no matter how many frames are drawn. The tick fraction for drawing is set exactly, the tick number is set after the “past time” of the timer
 *  (how many milliseconds supposedly passed since the last frame). Timer fields are closed - only for this entry, by reflection.
 */
final class FrameClock {
    private static final Field LAST_MS = field("lastMs");
    private static final Field RESIDUAL = field("deltaTickResidual");

    private FrameClock() {}

    private static Field field(String name) {
        try {
            Field f = DeltaTracker.Timer.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("DeltaTracker.Timer." + name, e);
        }
    }

    private static DeltaTracker.Timer timer() {
        return (DeltaTracker.Timer) Minecraft.getInstance().getTimer();
    }

    /** The tick fraction with which this frame will be drawn. */
    static void setPartial(float partial) {
        try {
            RESIDUAL.setFloat(timer(), partial);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** By the next frame the game will advance by {@code ticks} tick (at the current client tick length). */
    static void advanceNext(double ticks) {
        Minecraft mc = Minecraft.getInstance();
        float mspt = Math.max(50f, mc.level == null ? 50f : mc.level.tickRateManager().millisecondsPerTick());
        try {
            LAST_MS.setLong(timer(), Util.getMillis() - Math.round(ticks * mspt));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
