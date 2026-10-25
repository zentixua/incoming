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
 * порог восприятия — 0) и скорость грунта по логарифму ({@link #strength}), длина — импульс грунта ({@link GroundShock.Sample#durationSeconds}) и
 * затухание тела после него ({@link #BODY_RING_S}). Гул —
 * {@code BOMB_QUAKE}, громкость — давление, которое излучает земля, ходящая со скоростью грунта
 * ({@link BlastSounds#quake(double)}).
 */
public final class GroundShake {
    /**
     * Сколько тело ещё качается после толчка, с: стоящий человек по вертикали — звено с резонансом 4–6 Гц и затуханием
     * 0,3–0,5 от критического (ISO 2631-1; Griffin, «Handbook of Human Vibration», 1990), толчок короче периода он
     * отрабатывает за 2–3 периода — ⚠ оценка. Без этого импульс скалы в 70 мс у камеры был бы короче четверти кивка.
     */
    static final double BODY_RING_S = 0.4;
    /**
     * Во сколько раз выше порога ощутимости ({@link GroundShock#PERCEPTION_START_MPS}, 0,1 мм/с) толчок уже полный: ×1000,
     * 100 мм/с — «очень сильно, пугает» (шкала восприятия DIN 4150-2: 0,1 — не ощутимо, 0,3 — едва, 1 — ощутимо, 3 —
     * ясно, 10 — сильно). Сила толчка — доля по логарифму, как у дрожи удара по давлению ({@link CameraShake#tremor}):
     * ощутимость модели насыщается уже на 0,3 мм/с и силы не различает. Подача.
     */
    static final double FULL_OVER_THRESHOLD = 1000;
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
        double seconds = s.durationSeconds() + BODY_RING_S;
        // ждать прихода: тики с источника уже прошли, пока шёл пакет (и работа подрыва на сервере)
        double wait = 20 * s.arrivalSeconds() - (level.getGameTime() - sourceTick);
        last = String.format(Locale.ROOT, "ground r %.0f m arrival %.2f s ppv %.2f mm/s %.2f Hz felt %.2f jolt %.2f for %.2f s, in %.1f ticks",
                range, s.arrivalSeconds(), 1000 * s.peakVelocityMps(), s.frequencyHz(), s.perceptibility(), strength(s), seconds, wait);
        // редкое событие (удар бомбы о землю, взрыв в грунте): строка — для разбора лога после игры
        Incoming.LOG.info("Ground wave at the player: {}", last);
        Effects.add((l, t) -> {
            if (t < wait) return true;
            arrive(s, seconds, Math.min(1, t - wait), seed);
            return false;
        });
    }

    /**
     * Сила толчка 0..1 у волны грунта {@code s}: ощутимость модели × доля по логарифму пиковой скорости грунта от порога
     * ощутимости до {@link #FULL_OVER_THRESHOLD} порогов.
     */
    static double strength(GroundShock.Sample s) {
        double over = Math.log10(Math.max(s.peakVelocityMps(), 1e-12) / GroundShock.PERCEPTION_START_MPS) / Math.log10(FULL_OVER_THRESHOLD);
        return s.perceptibility() * Math.clamp(over, 0, 1);
    }

    /** Волна грунта дошла до ног {@code late} тиков назад (0..1). */
    private static void arrive(GroundShock.Sample s, double seconds, double late, long seed) {
        CameraShake.ground(strength(s), seconds, late, seed);
        BlastSounds.quake(s.peakVelocityMps());
    }
}
