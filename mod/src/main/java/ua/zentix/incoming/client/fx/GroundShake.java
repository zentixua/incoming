package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.nuclear.model.GroundShock;

import java.util.Locale;

/**
 * Волна грунта у игрока: удар бомбы о землю и взрыв в грунте приходят к нему по земле — толчок тела и гул земли с её
 * приходом R/c ({@link GroundShock}: скорость волны и пиковая скорость грунта по среде у источника), а не с пакетом. Где
 * грунт быстрее фронта в воздухе (скала, влажная глина), земля дрогнет и загудит раньше хлопка; в рыхлом грунте — после.
 * <p>
 * Толчок камеры — {@link CameraShake#ground}: сила — ощутимость человеком ({@link GroundShock.Sample#perceptibility}:
 * порог восприятия — 0, не камера и не урон), длина — пока грунт ходит ({@link GroundShock#velocityMps}). Гул —
 * {@code BOMB_QUAKE}, громкость — давление, которое излучает земля, ходящая со скоростью грунта
 * ({@link BlastSounds#quake(double)}).
 */
public final class GroundShake {
    /** Шаг поиска конца движения грунта, с, и дальше скольких секунд после прихода не искать. */
    static final double SCAN_STEP_S = 0.01, SCAN_MAX_S = 20;
    /** Грунт «стоит», когда скорость меньше этой доли пика. */
    static final double STILL = 1e-3;
    /** Последняя волна грунта у игрока (строка сценария клиента). */
    private static String last = "-";

    private GroundShake() {}

    public static String describe() {
        return last;
    }

    /**
     * Источник волны грунта {@code source} в {@code pos} случился в тик игры {@code sourceTick} (сервера): толчок и гул —
     * когда волна дойдёт до ног игрока; {@code seed} — узор толчка.
     */
    public static void schedule(Vec3 pos, GroundShock.Source source, long sourceTick, long seed) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;
        double range = player.position().distanceTo(pos);
        GroundShock.Sample s = GroundShock.sample(source, range);
        if (!(s.perceptibility() > 0) && !(s.peakVelocityMps() > 0)) return;
        double seconds = seconds(s);
        // ждать прихода: тики с источника уже прошли, пока шёл пакет (и работа подрыва на сервере)
        double wait = 20 * s.arrivalSeconds() - (level.getGameTime() - sourceTick);
        last = String.format(Locale.ROOT, "ground r %.0f m arrival %.2f s ppv %.2f mm/s %.2f Hz felt %.2f for %.2f s, in %.1f ticks",
                range, s.arrivalSeconds(), 1000 * s.peakVelocityMps(), s.frequencyHz(), s.perceptibility(), seconds, wait);
        // редкое событие (удар бомбы о землю, взрыв в грунте): строка — для разбора лога после игры
        Incoming.LOG.info("Ground wave at the player: {}", last);
        Effects.add((l, t) -> {
            if (t < wait) return true;
            arrive(s, seconds, Math.min(1, t - wait), seed);
            return false;
        });
    }

    /** Волна грунта дошла до ног {@code late} тиков назад (0..1). */
    private static void arrive(GroundShock.Sample s, double seconds, double late, long seed) {
        CameraShake.ground(s.perceptibility(), seconds, late, seed);
        BlastSounds.quake(s.peakVelocityMps());
    }

    /** Сколько секунд после прихода грунт ходит заметно ({@link #STILL} пика): по сигналу скорости модели. */
    static double seconds(GroundShock.Sample s) {
        double peak = s.peakVelocityMps(), end = 0;
        if (!(peak > 0)) return 0;
        for (double t = 0; t <= SCAN_MAX_S; t += SCAN_STEP_S) {
            if (Math.abs(GroundShock.velocityMps(s, s.arrivalSeconds() + t)) > STILL * peak) end = t;
        }
        return end;
    }
}
