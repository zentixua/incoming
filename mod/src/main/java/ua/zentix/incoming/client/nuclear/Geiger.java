package ua.zentix.incoming.client.nuclear;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import ua.zentix.incoming.item.GeigerCounterItem;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.radiation.GeigerFormat;
import ua.zentix.incoming.registry.ModSounds;

import java.util.Locale;

/**
 * Handheld Geiger counter: clicks at a Poisson rate based on dose rate (background 0.15 μSv/h — a click every
 * couple of seconds; near ground zero — a continuous crackle), and readings above the hotbar: dose rate and accumulated dose.
 */
public final class Geiger {
    /** Clicks per second per 1 μSv/h (of order unity for household counters). */
    private static final double CPS_PER_USV = 2.5;
    /** Above this the counter saturates: the crackle gets no faster. */
    private static final double MAX_CPS = 400;
    private static final RandomSource RANDOM = RandomSource.create();

    private Geiger() {}

    private static boolean holding(LocalPlayer p) {
        return p.getMainHandItem().getItem() instanceof GeigerCounterItem || p.getOffhandItem().getItem() instanceof GeigerCounterItem;
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || !holding(p)) return;
        S2C.Radiation r = ClientNuclear.radiationState();
        double usv = GeigerFormat.microSievertsPerHour(r == null ? 0 : r.rate());
        double lambda = Math.min(MAX_CPS, usv * CPS_PER_USV) / 20;
        int clicks = poisson(lambda);
        // the ear cannot distinguish more than three clicks per tick
        for (int i = 0; i < Math.min(3, clicks); i++) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.GEIGER_CLICK.get(), 0.9f + RANDOM.nextFloat() * 0.2f, 0.5f));
        }
    }

    private static int poisson(double lambda) {
        if (lambda <= 0) return 0;
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do {
            k++;
            p *= RANDOM.nextDouble();
        } while (p > l && k < 50);
        return k - 1;
    }

    /** Readings above the hotbar on the right — only with the counter in hand. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.options.hideGui || !holding(p)) return;
        S2C.Radiation r = ClientNuclear.radiationState();
        double rate = r == null ? 0 : r.rate();
        double usv = GeigerFormat.microSievertsPerHour(rate);
        ChatFormatting color = usv < 1 ? ChatFormatting.GREEN : usv < 100 ? ChatFormatting.YELLOW : usv < 10_000 ? ChatFormatting.GOLD : ChatFormatting.RED;
        Component line1 = Component.translatable("incoming.geiger.rate", GeigerFormat.rate(rate)).withStyle(color);
        Component line2 = Component.translatable("incoming.geiger.dose", String.format(Locale.ROOT, "%.2f", r == null ? 0 : r.doseGy()))
                .withStyle(ChatFormatting.GRAY);
        Font font = mc.font;
        int x = g.guiWidth() / 2 + 98, y = g.guiHeight() - 30;
        g.drawString(font, line1, x, y, 0xFFFFFFFF);
        g.drawString(font, line2, x, y + 10, 0xFFFFFFFF);
    }
}
