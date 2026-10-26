package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Propagation;

/**
 * Громкость в игре по уровню звука у уха — одна мерка на весь звук моторов мода и звуки мира по закону источника.
 * <ul>
 * <li>Громкость — половина децибел уровня (10<sup>ΔL/40</sup>), как у далёких взрывов ({@code Outdoor}) и ближних
 * ({@code BlastMix}: −3 дБ на удвоение расстояния): у колонок дома ~60 дБ от шума комнаты до громкого, а снаружи —
 * ~110 дБ от тихой ночи до рёва ракеты на старте. Это отступление от физики — перенос в колонки, а не закон
 * распространения: само распространение ({@link Propagation}) считается в полных децибелах.</li>
 * <li>Мерка — фон тихой ночи ({@link Propagation#NIGHT}): звук на уровне фона звучит громкостью {@link VoiceBudget#AUDIBLE}
 * против {@link #ENGINE_LUFS} — порог слышимости в игре там же, где в жизни; отсюда {@link #ENGINE_LUFS} — у
 * {@link #REF} дБА.</li>
 * <li>Потолок — {@link #CEILING_LUFS}: так громко звучит ближний взрыв с 1 psi ({@code BlastMix.NEAR_LUFS}), громче в игре
 * нет ничего; мотор доходит до него на {@link #CEILING_LEVEL} дБА. Файлы моторов и стартов собраны на потолке
 * ({@link #MOTOR_LUFS}), поэтому до него громкость растёт с уровнем, а не упирается в громкость файла: в 3.5.0 файлы
 * были на {@link #ENGINE_LUFS}, и всё громче {@link #REF} дБА звучало одинаково: удар поджига ракеты и ЗРК — ближе ~45 м
 * к пусковой, рёв ускорителя — ближе ~20 м.</li>
 * <li>Звук тонет в фоне за последние {@link Propagation#FADE} дБ над ним в своей октаве (частичная маскировка): днём и
 * в дождь фон выше, и тот же звук слышно ближе.</li>
 * </ul>
 * Громкость {@link #gain} — против {@link #ENGINE_LUFS} (по ней же выбирает слои {@link VoiceBudget}); громкость, которую
 * получает Minecraft, — {@link #volume}: она же, приведённая к громкости файла.
 */
final class Loudness {
    /**
     * Мерка громкости, LUFS (наибольшая мгновенная): так звучит звук с уровнем {@link #REF} у уха — то же число, что
     * {@code ENGINE_LUFS} в {@code tools/build_sounds.py} ({@code LoudnessTest} сверяет). Подача: мерка сведения, не SPL.
     */
    static final double ENGINE_LUFS = -19;
    /** Потолок громкости в игре, LUFS: ближний взрыв с 1 psi ({@code BlastMix.NEAR_LUFS}, {@code LoudnessTest} сверяет; подача). */
    static final double CEILING_LUFS = -13.5;
    /**
     * Громкость файлов петель моторов и разовых звуков старта, LUFS ({@code MOTOR_LUFS} в {@code tools/build_sounds.py},
     * {@code SoundAssetsTest} меряет сами файлы): на потолке — громкость в игре не упирается в файл раньше потолка.
     */
    static final double MOTOR_LUFS = CEILING_LUFS;
    /**
     * Громкость файлов схода снаряда РСЗО, LUFS ({@code ROCKET_LAUNCH_LUFS} в сборке; подача): короткий удар на потолок не свести
     * без пережатия — на 2 дБ ниже, упирается в файл с {@code REF + 2·(−15,5 − ENGINE_LUFS)} ≈ 133 дБА у уха. Хлопок
     * отделения ускорителя и катапульта — на {@link #ENGINE_LUFS}: громче {@link #REF} их нет и в метре ({@code LoudnessTest}).
     */
    static final double ROCKET_LAUNCH_LUFS = MOTOR_LUFS - 2;

    /** A-уровень у уха, который звучит громкостью {@link #ENGINE_LUFS}, дБА. */
    static final double REF = Propagation.NIGHT + 40 * Math.log10(1 / VoiceBudget.AUDIBLE);
    /** A-уровень у уха, с которого звук звучит на потолке {@link #CEILING_LUFS}, дБА. */
    static final double CEILING_LEVEL = REF + 2 * (CEILING_LUFS - ENGINE_LUFS);
    /** Громкость потолка против {@link #ENGINE_LUFS}. */
    static final double TOP = Math.pow(10, (CEILING_LUFS - ENGINE_LUFS) / 20);

    private Loudness() {}

    /**
     * Множитель громкости файла громкостью {@code lufs}, приводящий его к {@link #ENGINE_LUFS}: громкость в игре задаёт
     * уровень у уха, а не то, как громко собран файл.
     */
    static double fileGain(double lufs) {
        return Math.pow(10, (ENGINE_LUFS - lufs) / 20);
    }

    /**
     * Громкость в игре звука с A-уровнем {@code level} у уха, который громче фона на {@code margin} дБ в самой выгодной
     * октаве ({@link Propagation#margin}), — против {@link #ENGINE_LUFS}, не больше потолка {@link #TOP}.
     */
    static double gain(double level, double margin) {
        return gain(level, margin, CEILING_LUFS);
    }

    /** То же с потолком {@code ceilingLufs} (у ядерки — прежний, {@link #ENGINE_LUFS}: её звук не меняется). */
    static double gain(double level, double margin, double ceilingLufs) {
        if (!(margin > 0)) return 0;
        double top = Math.pow(10, (ceilingLufs - ENGINE_LUFS) / 20);
        return Math.min(top, Math.pow(10, (level - REF) / 40)) * Propagation.smoothstep(0, Propagation.FADE, margin);
    }

    /** Громкость для Minecraft (0..1) громкости {@code gain} ({@link #gain}) у файла громкостью {@code fileLufs}. */
    static float volume(double gain, double fileLufs) {
        return (float) Math.min(1, gain * fileGain(fileLufs));
    }

    /** Громкость для Minecraft громкости {@code gain} у файла мотора или старта ({@link #MOTOR_LUFS}). */
    static float motor(double gain) {
        return volume(gain, MOTOR_LUFS);
    }
}
