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
     * Скорость грунта, с которой толчок начинает расти, и во сколько раз больше неё он уже полный, м/с: 0,1 мм/с — «не
     * ощутимо» и ×1000, 100 мм/с — «очень сильно, пугает» (шкала восприятия по пиковой скорости из DIN 4150-2: 0,1 — не
     * ощутимо, 0,3 — едва, 1 — ощутимо, 3 — ясно, 10 — сильно). Сила толчка — доля по логарифму, как у дрожи удара по
     * давлению ({@link CameraShake#tremor}): ощутимость модели (KB с весом по частоте) насыщается уже у порога и силы не
     * различает. Подача.
     */
    static final double QUIET_MPS = 1e-4, FULL_OVER_THRESHOLD = 1000;
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
        // сколько тиков с источника уже прошло у сервера, пока шёл пакет (и работа подрыва на сервере)
        double age = Math.max(0, level.getGameTime() - sourceTick);
        last = String.format(Locale.ROOT, "ground r %.0f m arrival %.2f s ppv %.2f mm/s %.2f Hz felt %.2f jolt %.2f for %.2f s, %.0f ticks after the source",
                range, s.arrivalSeconds(), 1000 * s.peakVelocityMps(), s.frequencyHz(), s.perceptibility(), strength(s), seconds, age);
        // редкое событие (удар бомбы о землю, взрыв в грунте): строка — для разбора лога после игры
        Incoming.LOG.info("Ground wave at the player: {}", last);
        Effects.add(new Arrival(20 * s.arrivalSeconds(), 20 * seconds, age, BlastTime.ticks(), late -> arrive(s, seconds, late, seed)));
    }

    /**
     * Ожидание волны грунта у ног. Возраст — тики с источника по монотонным часам клиента ({@link BlastTime}), а не по
     * счётчику вызовов {@link Effects}: тот в тик рождения зовёт эффект дважды (сразу и в тике очереди), и толчок пришёл бы
     * на тик раньше волны. Начало отсчёта — сколько у сервера уже прошло с источника ({@code ageAtBirth}). Волна дошла —
     * {@code arrive} с тем, на сколько тиков позже прихода (с долей тика: приход R/c дробный); пакет опоздал на весь толчок
     * (вошёл позже, сеть встала) — ничего: прошедший толчок не играется заново с начала.
     *
     * @param arrivalTicks приход волны, тики с источника
     * @param lengthTicks  сколько после прихода земля и тело ходят, тики
     * @param born         {@link BlastTime#ticks()} в миг, когда пакет пришёл
     */
    record Arrival(double arrivalTicks, double lengthTicks, double ageAtBirth, long born, java.util.function.DoubleConsumer arrive)
            implements Effects.Effect {
        @Override
        public boolean tick(ClientLevel level, int t) {
            double age = ageAtBirth + (BlastTime.ticks() - born);
            if (age < arrivalTicks) return true;
            double late = age - arrivalTicks;
            if (late < lengthTicks) arrive.accept(late);
            return false;
        }
    }

    /**
     * Сила толчка 0..1 у волны грунта {@code s}: ощутимость модели × доля по логарифму пиковой скорости грунта от
     * {@link #QUIET_MPS} до {@link #FULL_OVER_THRESHOLD} раз больше.
     */
    static double strength(GroundShock.Sample s) {
        double over = Math.log10(Math.max(s.peakVelocityMps(), 1e-12) / QUIET_MPS) / Math.log10(FULL_OVER_THRESHOLD);
        return s.perceptibility() * Math.clamp(over, 0, 1);
    }

    /** Волна грунта дошла до ног {@code late} тиков назад: толчок идёт с того места, где он уже был бы, гул — сейчас. */
    private static void arrive(GroundShock.Sample s, double seconds, double late, long seed) {
        CameraShake.ground(strength(s), seconds, late, seed);
        BlastSounds.quake(s.peakVelocityMps());
    }
}
