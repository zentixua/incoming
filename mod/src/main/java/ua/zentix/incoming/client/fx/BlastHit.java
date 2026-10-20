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
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.warhead.GroundMaterial;

/**
 * Фронт обычного взрыва у тела игрока — тем же законам, что у ядерной волны ({@code NukeSounds.Schedule.hit}): слух по
 * энергии самой волны у уха ({@link HearingModel#exposure(double, double)}: у обычного взрыва фаза — миллисекунды,
 * слышна вся волна) и толчок тела ({@link CameraShake#sway}: удар обтекания и ветер за фронтом по положительной фазе,
 * {@code BodySway}). Давление и фаза — Кинни–Грэхем ({@link BlastModel}) у тела, а не у камеры: камера снаряда облетает
 * взрыв, игрок стоит в стороне.
 */
final class BlastHit {
    private BlastHit() {}

    /**
     * @param late сколько тиков назад (0..1) фронт прошёл тело
     */
    static void hit(BlastSource s, Vec3 pos, GroundMaterial ground, long seed, LocalPlayer player, Vec3 body, double late) {
        double d = Math.max(0.5, body.distanceTo(pos)), pa = BlastMix.pressure(s, d), tPlus = BlastMix.phase(s, d);
        if (IncomingConfig.CLIENT.nukeTinnitus.get()) hurtEars(pos, ground, body, d, pa, tPlus, seed);
        if (CameraShake.carried(player)) return;
        // за стеной, домом или холмом в сторону взрыва — в его ветровой тени (лучи у тела)
        float open = SoundFilters.openRays(body, Math.atan2(pos.z - body.z, pos.x - body.x));
        CameraShake.sway(pa, 1000 * BlastModel.dynamicPressureKpa(pa / 1000), open, tPlus, late, body.subtract(pos), player.getYRot());
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
