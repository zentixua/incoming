package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

/**
 * Two-step nuclear launch (DESIGN-nuke §8): the first press "arms" it — a 3 s countdown on screen; another press
 * cancels it. No accidental nuclear strike from a misplaced finger. The server still checks permissions.
 */
public final class NukeArming {
    private static final int DELAY = 60;

    @Nullable
    private static Runnable pending;
    private static int left;

    private NukeArming() {}

    /** Arm (launch in 3 s), or cancel if already armed. */
    public static void toggle(Runnable launch) {
        Minecraft mc = Minecraft.getInstance();
        if (pending != null) {
            cancel();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 0.6f));
            return;
        }
        pending = launch;
        left = DELAY;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT, 0.5f));
    }

    public static void cancel() {
        pending = null;
        left = 0;
    }

    public static boolean armed() {
        return pending != null;
    }

    public static double ticksLeft(float partialTick) {
        return Math.max(0, left - partialTick);
    }

    public static void tick() {
        if (pending == null) return;
        left--;
        if (left % 20 == 0 && left > 0) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT, 0.5f + (DELAY - left) / 120f));
        }
        if (left <= 0) {
            Runnable r = pending;
            pending = null;
            r.run();
        }
    }
}
