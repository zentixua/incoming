package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.Hearing;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Звуки мира, которые не моторы и не взрывы, — по закону источника, как моторы ({@code sound/Noise}): у источника —
 * мощность звука (A-уровень L<sub>WA</sub>, дБА) из его паспорта ниже, у записи — октавный спектр и громкость самого
 * файла ({@link Spectra}, меряет {@code tools/build_sounds.py}); до уха — путь ISO 9613 ({@link Propagation}: разлёт,
 * воздух, земля, тень днём, фон по октавам), громкость в игре — {@link Loudness}, файл приведён к одной мерке
 * {@link Loudness#ENGINE_LUFS}. Ручных дальностей нет: докуда слышно, решают мощность, путь и фон. Звук приходит со
 * скоростью звука ({@link #play}).
 * <p>
 * Числа — с источником; где замеров нет — оценка порядка с отметкой «О».
 */
public final class Sources {
    /**
     * Источник: A-уровень мощности звука, дБА; спектр записи и её громкость, LUFS; потолок громкости в игре, LUFS
     * ({@link Loudness#CEILING_LUFS}; у звуков ядерного удара — прежний {@link Loudness#ENGINE_LUFS}: они не меняются).
     */
    public record Source(double lwA, Spectrum spectrum, double fileLufs, double ceilingLufs) {
        Source(double lwA, Spectrum spectrum, double fileLufs) {
            this(lwA, spectrum, fileLufs, Loudness.CEILING_LUFS);
        }

        /** Тот же источник другой мощности (закон источника считает её из своих чисел). */
        public Source power(double lwA) {
            return new Source(lwA, spectrum, fileLufs, ceilingLufs);
        }
    }

    /**
     * Сирена гражданской обороны (электромеханическая, на улице): уличные сирены оповещения — 100–130 дБ(C) в 100 футах
     * (30,5 м) на оси (FEMA CPG 1-17, «Outdoor Warning Systems Guide», 1980); взято 115 дБ(C) — середина класса больших
     * городских, у тона сирены (400–600 Гц) A ≈ C − 3: L<sub>WA</sub> = 112 + 20·lg 30,5 + 11 ≈ 153 дБА (О: тип сирен в
     * городе мода не задан). Ротор вертится — на оси громче, в стороне тише; здесь — по оси, среднее не считается.
     */
    public static final Source SIREN = new Source(153, Spectra.SIREN, Spectra.SIREN_LUFS);
    /** Сирена ядерной тревоги — та же уличная сирена ({@link #SIREN}), своя запись. */
    public static final Source NUKE_ALARM = new Source(153, Spectra.NUKE_ALARM, Spectra.NUKE_ALARM_LUFS, Loudness.ENGINE_LUFS);
    /**
     * Короткое замыкание с дугой в подстанции: О ~140 дБА (~120 дБА в 10 м; замеров звука дуговых пробоев
     * распределительных подстанций в открытом доступе не нашлось — порядок удара грома вблизи, на запись которого он
     * и собран).
     */
    public static final Source GRID_FAIL = new Source(140, Spectra.GRID_FAIL, Spectra.GRID_FAIL_LUFS);
    /** Треск дуги выбитой подстанции: О ~115 дБА (~95 дБА в 1 м). */
    public static final Source GRID_SPARK = new Source(115, Spectra.GRID_SPARK, Spectra.GRID_SPARK_LUFS);
    /**
     * Гул работающего трансформатора подстанции: силовой трансформатор ~10 МВА — около 68 дБА средним по поверхности
     * в 0,3 м (NEMA TR 1, таблица уровней; ⚠ по памяти), площадь поверхности замера ~40 м²: L<sub>WA</sub> = 68 + 10·lg 40
     * ≈ 84 дБА.
     */
    public static final Source GRID_HUM = new Source(84, Spectra.GRID_HUM, Spectra.GRID_HUM_LUFS);
    /** Квартал гаснет или загорается — щелчок автомата и гул, что глохнет (или включается): О ~100 дБА у его середины. */
    public static final Source GRID_POWER_DOWN = new Source(100, Spectra.GRID_POWER_DOWN, Spectra.GRID_POWER_DOWN_LUFS);
    public static final Source GRID_POWER_UP = new Source(100, Spectra.GRID_POWER_UP, Spectra.GRID_POWER_UP_LUFS);
    /** Обрушение дома в руинах ядерного удара: О ~125 дБА (снос зданий — ~100–110 дБА в 100 м вокруг). */
    public static final Source NUKE_COLLAPSE = new Source(125, Spectra.NUKE_COLLAPSE, Spectra.NUKE_COLLAPSE_LUFS, Loudness.ENGINE_LUFS);
    /** Осыпь обломков в руинах: О ~110 дБА. */
    public static final Source RUIN_DEBRIS = new Source(110, Spectra.DEBRIS_FALL, Spectra.DEBRIS_FALL_LUFS, Loudness.ENGINE_LUFS);
    /**
     * Пуск МБР — запись старта со стола; мощность — закон РДТТ первой ступени из паспорта
     * ({@link Noise.Rocket#power}, {@code WeaponSpec.ICBM_NOISE}), здесь — только запись.
     */
    public static final Source NUKE_LAUNCH = new Source(Double.NaN, Spectra.NUKE_LAUNCH, Spectra.NUKE_LAUNCH_LUFS, Loudness.ENGINE_LUFS);

    /**
     * Шум пламени: доля тепловыделения открытого турбулентного пламени, что уходит звуком, — 10⁻⁶–10⁻⁵ (Strahle W. C.,
     * «Combustion noise», Prog. Energy Combust. Sci. 4, 1978; ⚠ по памяти): нижний край, оценка.
     */
    public static final double FLAME_EFFICIENCY = 1e-6;
    /** Гул огня в воронке: запись пожара, мощность — от тепла лужи ({@link #flame}). */
    public static final Source BLAST_FIRE = new Source(Double.NaN, Spectra.BLAST_FIRE, Spectra.BLAST_FIRE_LUFS);
    /**
     * Звуком уходит такая доля энергии падения обломков и грунта: удары камней о землю и друг о друга — О 10⁻⁴ (у ударов
     * твёрдых тел 10⁻⁴–10⁻³, порядок).
     */
    public static final double IMPACT_EFFICIENCY = 1e-4;
    /** Осыпь выброса воронки: запись, мощность — от массы и высоты падения ({@link #fall}). */
    public static final Source EJECTA = new Source(Double.NaN, Spectra.DEBRIS_FALL, Spectra.DEBRIS_FALL_LUFS);
    /**
     * Стёкла, которые выбила ядерная волна у слушателя: запись — окна вылетают по улице, мощность — от энергии
     * осколков ({@link #impact}, {@link Clatter}). Потолок — ядерки ({@link Loudness#ENGINE_LUFS}).
     */
    public static final Source RUIN_GLASS = new Source(Double.NaN, Spectra.NUKE_GLASS, Spectra.NUKE_GLASS_LUFS, Loudness.ENGINE_LUFS);
    /** Обломки, что снесла ядерная волна у слушателя: запись осыпи, мощность — от энергии обломков ({@link #impact}). */
    public static final Source RUIN_CLATTER = new Source(Double.NaN, Spectra.DEBRIS_FALL, Spectra.DEBRIS_FALL_LUFS, Loudness.ENGINE_LUFS);
    /**
     * Гул горящего города: запись большого пожара, мощность — от тепла горящего ({@link #flame}: шторм модели и огонь в
     * своих чанках, {@code NukeAfter}).
     */
    public static final Source NUKE_FIRE = new Source(Double.NaN, Spectra.NUKE_FIRE, Spectra.NUKE_FIRE_LUFS, Loudness.ENGINE_LUFS);

    /** Звук, который ещё идёт к уху: событие, место, тон, источник, сколько тиков в пути. */
    private record Pending(SoundEvent event, Vec3 at, float pitch, Source source, ClientLevel level, long born) {}

    private static final List<Pending> PENDING = new ArrayList<>();
    private static long ticks;

    private Sources() {}

    /** A-уровень мощности пламени тепловыделением {@code watts}, Вт ({@link #FLAME_EFFICIENCY}), дБА по спектру записи. */
    public static double flame(double watts, Spectrum spectrum) {
        return lw(FLAME_EFFICIENCY * watts) + spectrum.aWeighting();
    }

    /**
     * A-уровень мощности осыпи: {@code kg} массы падает с {@code height} м за {@code seconds} с
     * ({@link #IMPACT_EFFICIENCY}), дБА по спектру записи.
     */
    public static double fall(double kg, double height, double seconds, Spectrum spectrum) {
        return lw(IMPACT_EFFICIENCY * kg * 9.81 * height / Math.max(seconds, 0.1)) + spectrum.aWeighting();
    }

    /**
     * Уровень мощности звука удара (не A), дБ: звуком уходит {@link #IMPACT_EFFICIENCY} энергии {@code joules}, Дж, за
     * {@code seconds} с — пока падает и бьётся сорванное.
     */
    public static double impact(double joules, double seconds) {
        return lw(IMPACT_EFFICIENCY * joules / Math.max(seconds, 0.05));
    }

    /** Уровень мощности, дБ отн. 1 пВт. */
    public static double lw(double watts) {
        return watts > 0 ? 10 * Math.log10(watts / 1e-12) : Noise.SILENT;
    }

    /**
     * Громкость в игре источника {@code s} в {@code at} у уха {@code ear}: уровень у уха по пути ISO 9613 против фона
     * ({@link Loudness#gain}, не громче потолка источника), файл — к мерке {@link Loudness#ENGINE_LUFS}. Преграды — не
     * здесь (их ставит звук сам).
     */
    public static float volume(Source s, Vec3 at, Vec3 ear, Propagation.Weather weather) {
        if (Double.isNaN(s.lwA) || s.lwA == Noise.SILENT) return 0;
        double[] bands = new double[Propagation.BANDS];
        Propagation.Path path = Propagation.Path.over(at.distanceTo(ear), Hearing.height(at, ear), weather);
        double level = Propagation.level(s.lwA - s.spectrum.aWeighting(), s.spectrum, path, bands);
        return Loudness.volume(Loudness.gain(level, Propagation.margin(bands, weather), s.ceilingLufs), s.fileLufs);
    }

    /** Громкость у уха камеры сейчас (0 — мира нет). */
    public static float volume(Source s, Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        return volume(s, at, mc.gameRenderer.getMainCamera().getPosition(), Propagation.Weather.of(mc.level));
    }

    /**
     * Разовый звук источника {@code s} в {@code at}: зазвучит, когда до уха дойдёт его фронт (скорость звука
     * {@link Noise#SOUND}), громкостью по пути в тот миг; тише порога слышимости — не играется.
     */
    public static void play(SoundEvent event, Vec3 at, Source s, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PENDING.add(new Pending(event, at, pitch, s, mc.level, ticks));
        tick(mc);
    }

    /** Раз в тик клиента ({@link ClientSounds#tick}): звуки, чей фронт дошёл до уха. */
    static void tick() {
        ticks++;
        tick(Minecraft.getInstance());
    }

    private static void tick(Minecraft mc) {
        if (PENDING.isEmpty()) return;
        if (mc.level == null) {
            PENDING.clear();
            return;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        Propagation.Weather weather = Propagation.Weather.of(mc.level);
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            double d = ear.distanceTo(p.at), path = (ticks - p.born) * Noise.SOUND / 20;
            // мир сменился или звук ушёл дальше, чем клиент помнит путь звука, — забыть
            if (p.level != mc.level || path > Hearing.RANGE) {
                it.remove();
                continue;
            }
            if (path < d) continue;
            it.remove();
            float v = volume(p.source, p.at, ear, weather);
            if (v >= VoiceBudget.AUDIBLE) ClientSounds.atEar(p.event, p.at, v, p.pitch);
        }
    }

    /** Выход из мира, перемотка: звуков в пути нет. */
    static void reset() {
        PENDING.clear();
    }
}
