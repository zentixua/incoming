package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.nuclear.Deafness;
import ua.zentix.incoming.client.sound.BlastMix;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.Outdoor;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.Locale;

/**
 * Фронт обычного взрыва у тела игрока — тем же законам, что у ядерной волны ({@code NukeSounds.Schedule.hit}): слух по
 * энергии самой волны у уха ({@link HearingModel#exposure(double, double)}: у обычного взрыва фаза — миллисекунды,
 * слышна вся волна) и толчок тела ({@link CameraShake#sway}: удар обтекания и ветер за фронтом по положительной фазе,
 * {@code BodySway}) — с головой на шее: камера качается головой, а не только наклоном тела. Давление и фаза — Кинни–Грэхем ({@link BlastModel}) у тела, а не у камеры: камера снаряда облетает
 * взрыв, игрок стоит в стороне.
 */
public final class BlastHit {
    /** Последний фронт у тела (строка сценария клиента). */
    private static String last = "-";

    private BlastHit() {}

    /** Последний фронт обычного взрыва у тела: давление, фаза, энергия у уха, сдвиг порога, наклон. */
    public static String describe() {
        return last;
    }

    /**
     * @param late сколько тиков назад (0..1) фронт прошёл тело
     */
    static void hit(BlastSource s, Vec3 pos, GroundMaterial ground, long seed, LocalPlayer player, Vec3 body, double late) {
        double d = Math.max(0.5, body.distanceTo(pos)), pa = BlastMix.pressure(s, d), tPlus = BlastMix.phase(s, d);
        double exposure = HearingModel.exposure(pa, tPlus);
        if (IncomingConfig.CLIENT.nukeTinnitus.get()) hurtEars(pos, ground, body, d, pa, tPlus, seed);
        // дрожь камеры в миг удара — подача, по давлению и импульсу волны у тела (и верхом, и вплавь: удар чувствуется)
        double tremor = CameraShake.tremor(pa, BlastModel.chemicalImpulse(d, s.chargeKg(), s.surface()), late, seed);
        double lean = 0, neck = 0;
        float open = 0;
        if (!CameraShake.carried(player)) {
            // за стеной, домом или холмом в сторону взрыва — в его ветровой тени (лучи у тела)
            open = SoundFilters.openRays(body, Math.atan2(pos.z - body.z, pos.x - body.x));
            BodySway.Motion m = CameraShake.sway(pa, 1000 * BlastModel.dynamicPressureKpa(pa / 1000), open, tPlus, tPlus, late,
                    body.subtract(pos), player.getYRot(), true);
            lean = Math.toDegrees(BodySway.peak(m.body(), 0));
            neck = Math.toDegrees(BodySway.peak(m.neck(), 0));
        }
        last = String.format(Locale.ROOT, "body d %.0f p %.0f Pa phase %.1f ms LAE %.1f dB shift %.1f dB open %.2f lean %.2f deg neck %.2f deg tremor %.2f",
                d, pa, 1000 * tPlus, exposure, HearingModel.shift(exposure), open, lean, neck, tremor);
    }

    /**
     * Слух: энергия волны у уха за вычетом того, что съел путь (холм и зона тени гасят давление и сильнее верха, а
     * A-энергия — на 1–3 кГц), перепонки — по давлению с потерей пути; как у ядерной волны. Путь — {@link Outdoor}:
     * кромка рельефа по лучу от взрыва ({@link Sightline}).
     */
    private static void hurtEars(Vec3 pos, GroundMaterial ground, Vec3 body, double d, double pa, double tPlus, long seed) {
        ClientLevel level = Minecraft.getInstance().level;
        double lost = 0, highs = 1;
        if (level != null) {
            double z = Sightline.trace(FarTerrain.of(level), pos.x, pos.y + 1, pos.z, body.x, body.y, body.z).pathDifference();
            Outdoor.Path path = BlastSounds.path(d, z, 1, ground.porosity);
            if (path != null) {
                lost = Outdoor.pressureLoss(path);
                highs = Outdoor.pressureHighs(path);
            }
        }
        Deafness.blast(HearingModel.exposure(pa, tPlus) - lost + 20 * Math.log10(Math.max(1e-3, highs)), pa / 1000 * Math.pow(10, -lost / 20), seed);
    }
}
