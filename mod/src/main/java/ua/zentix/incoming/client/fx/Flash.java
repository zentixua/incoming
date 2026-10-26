package ua.zentix.incoming.client.fx;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import ua.zentix.incoming.IncomingConfig;

/**
 * Blast flash on screen: light arrives instantaneously,
 * brightness depends on distance and line of sight, fades over a few ticks. Vanilla Hide Lightning Flashes
 * (accessibility) caps it at {@link #CALM} — as for the nuclear flash ({@code NukeFlash}).
 */
public final class Flash {
    /** Below this strength no flash appears on screen (presentation). */
    public static final float FLOOR = 0.01f;
    /** Screen flash cap with vanilla Hide Lightning Flashes: shared by conventional and nuclear flashes (player convenience). */
    public static final float CALM = 0.4f;
    private static float intensity;
    private static float decay = 0.75f;
    private static int color = 0xFFF4D0;

    private Flash() {}

    /** @param strength 0..1, @param decayPerTick per-tick multiplier (0.75 — quick flash, 0.97 — long glow) */
    public static void trigger(float strength, float decayPerTick, int rgb) {
        float s = (float) (strength * IncomingConfig.CLIENT.flash.get());
        if (calm()) s = Math.min(s, CALM);
        if (s > intensity) {
            intensity = Math.min(1, s);
            decay = decayPerTick;
            color = rgb;
        }
    }

    /** Vanilla Hide Lightning Flashes is enabled (accessibility): the flash does not dazzle. */
    public static boolean calm() {
        return Minecraft.getInstance().options.hideLightningFlash().get();
    }

    public static void tick() {
        intensity *= decay;
        if (intensity < FLOOR) intensity = 0;
    }

    public static void reset() {
        intensity = 0;
    }

    public static float intensity() {
        return intensity;
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        if (intensity <= 0) return;
        int a = Math.min(255, (int) (intensity * 255));
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | color);
    }
}
