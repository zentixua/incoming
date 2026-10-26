package ua.zentix.incoming.client.hud;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.sound.Sources;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.strike.WeaponType;

/**
 * Alert for those under attack: sirens on both sides of the city and flashing text above the hotbar.
 * Each player has their own siren with 26 s memory (recording length): no second siren over it, but text updates.
 * Also the salvo progress line for the player who launched it.
 */
public final class Alerts {
    private static final int SIREN_MEMORY = 530;
    private static final int BANNER_TICKS = 100;

    private static long ticks;
    private static long lastSiren = -SIREN_MEMORY;
    private static int bannerLeft;
    private static Component banner = Component.empty();

    private static int salvoFired, salvoTotal, salvoWeapon, salvoLeft;

    private Alerts() {}

    public static void siren(S2C.Siren p) {
        boolean missile = p.kind() == S2C.Siren.MISSILE;
        banner = Component.translatable(missile ? "incoming.alert.missile" : "incoming.alert.air_raid").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
        bannerLeft = BANNER_TICKS;
        if (ticks - lastSiren < SIREN_MEMORY) return;
        lastSiren = ticks;
        // Two sirens on opposite sides of the target, with slightly different pitch, as on city streets.
        Vec3 c = p.pos();
        Vec3 a = missile ? c.add(125, 15, -65) : c.add(115, 15, 80);
        Vec3 b = missile ? c.add(-95, 15, 115) : c.add(-130, 15, -70);
        // Volume follows street siren power and path to the ear (Sources), arriving at the speed of sound.
        Sources.play(ModSounds.SIREN.get(), a, Sources.SIREN, 1.0f);
        Sources.play(ModSounds.SIREN.get(), b, Sources.SIREN, 0.97f);
    }

    public static void salvo(S2C.SalvoStatus p) {
        salvoWeapon = p.weapon();
        salvoFired = p.fired();
        salvoTotal = p.total();
        salvoLeft = p.fired() >= p.total() ? 60 : 20 * 20;
    }

    public static void tick() {
        ticks++;
        if (bannerLeft > 0) bannerLeft--;
        if (salvoLeft > 0) salvoLeft--;
    }

    public static void reset() {
        bannerLeft = salvoLeft = 0;
        lastSiren = ticks - SIREN_MEMORY;
    }

    /** Own abort: clear salvo progress; alert remains, since hostile strikes may still be flying over the player. */
    public static void salvoCancelled() {
        salvoLeft = 0;
    }

    /** Alert text (flashing) and salvo progress above the hotbar, at actionbar height. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        Font font = mc.font;
        int cx = g.guiWidth() / 2;
        int y = g.guiHeight() - 72;
        if (bannerLeft > 0) {
            float t = ticks + delta.getGameTimeDeltaPartialTick(false);
            float pulse = 0.55f + 0.45f * Mth.sin(t * 0.45f);
            int alpha = (int) (255 * Math.min(1, bannerLeft / 20f) * pulse);
            if (alpha > 8) {
                g.pose().pushPose();
                g.pose().translate(cx, y, 0);
                g.pose().scale(1.5f, 1.5f, 1);
                g.drawString(font, banner, -font.width(banner) / 2, 0, (alpha << 24) | 0xFF3030);
                g.pose().popPose();
            }
            y -= 16;
        }
        if (salvoLeft > 0 && salvoTotal > 0) {
            Component line = salvoFired >= salvoTotal
                    ? Component.translatable("incoming.salvo.done").withStyle(ChatFormatting.GRAY)
                    : Component.translatable("incoming.hud.salvo", weaponName(), salvoFired, salvoTotal).withStyle(ChatFormatting.YELLOW);
            g.drawString(font, line, cx - font.width(line) / 2, y, 0xFFFFFFFF);
        }
    }

    private static Component weaponName() {
        WeaponType[] w = WeaponType.values();
        return salvoWeapon >= 0 && salvoWeapon < w.length ? w[salvoWeapon].displayName() : Component.empty();
    }
}
