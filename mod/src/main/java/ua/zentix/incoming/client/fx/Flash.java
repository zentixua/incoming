package ua.zentix.incoming.client.fx;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import ua.zentix.incoming.IncomingConfig;

/**
 * Вспышка взрыва на экране: свет приходит мгновенно,
 * яркость зависит от расстояния и прямой видимости, затухает за несколько тиков. Ванильное «без вспышек молний»
 * (доступность) держит её не ярче {@link #CALM} — как и ядерную ({@code NukeFlash}).
 */
public final class Flash {
    /** Слабее этого вспышки на экране нет. */
    public static final float FLOOR = 0.01f;
    /** Предел силы вспышки на экране при ванильном «без вспышек молний»: у обычной и у ядерной одна. */
    public static final float CALM = 0.4f;
    private static float intensity;
    private static float decay = 0.75f;
    private static int color = 0xFFF4D0;

    private Flash() {}

    /** @param strength 0..1, @param decayPerTick множитель за тик (0.75 — быстрая вспышка, 0.97 — долгое сияние) */
    public static void trigger(float strength, float decayPerTick, int rgb) {
        float s = (float) (strength * IncomingConfig.CLIENT.flash.get());
        if (calm()) s = Math.min(s, CALM);
        if (s > intensity) {
            intensity = Math.min(1, s);
            decay = decayPerTick;
            color = rgb;
        }
    }

    /** Включено ванильное «без вспышек молний» (доступность): вспышка не слепит. */
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
