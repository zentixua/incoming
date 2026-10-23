package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Propagation;

/**
 * Громкость в игре по уровню звука у уха — одна мерка на весь звук моторов мода и звуки мира по закону источника.
 * <ul>
 * <li>Громкость — половина децибел уровня (10<sup>ΔL/40</sup>), как у далёких взрывов ({@code Outdoor}) и ближних
 * ({@code BlastMix}: −3 дБ на удвоение расстояния): у колонок дома ~60 дБ от шума комнаты до громкого, а снаружи —
 * ~110 дБ от тихой ночи до рёва ракеты на старте. Это отступление от физики — перенос в колонки, а не закон
 * распространения: само распространение ({@link Propagation}) считается в полных децибелах.</li>
 * <li>Потолок — {@link #CEILING_LUFS}, громкость ванильного TNT: громче в игре нет ничего, и самый громкий звук мода не
 * тише самого громкого звука игры. Файлы моторов и стартов собраны на потолке ({@link #MOTOR_LUFS}), поэтому до него
 * громкость растёт с уровнем, а не упирается в громкость файла: в 3.5.0 файлы были на {@link #ENGINE_LUFS}, и всё
 * громче {@link #REF} дБА звучало одинаково.</li>
 * <li>Верх шкалы — окно громкости ({@link Window}, подача): у потолка звучит самый громкий звук, что слышно сейчас, но не
 * громче взрыва с 1 psi ({@link #CEILING_LEVEL}) и не тише {@link Window#FLOOR}; остальное — ниже на половину децибел
 * разницы уровней. Так сводят звук в играх (HDR-звук: Frostbite, Wwise): неподвижная шкала с потолком у ближнего взрыва
 * играла мотор шахеда в 54 м (~90 дБА) на −37 LUFS — тише ванильного дождя, хотя громче него вокруг ничего нет. Окно
 * фактов не меняет: что громче, то и в игре громче, слышно ли звук — по фону, как прежде.</li>
 * <li>Звук тонет в фоне за последние {@link Propagation#FADE} дБ над ним в своей октаве (частичная маскировка): днём и
 * в дождь фон выше, и тот же звук слышно ближе.</li>
 * </ul>
 * Громкость {@link #gain} — против {@link #ENGINE_LUFS} (по ней же выбирает слои {@link VoiceBudget}); громкость, которую
 * получает Minecraft, — {@link #volume}: она же, приведённая к громкости файла. Звук ядерного удара — на прежней
 * неподвижной шкале с потолком {@link #ENGINE_LUFS} (её звук не меняется).
 */
final class Loudness {
    /**
     * Мерка громкости, LUFS (наибольшая мгновенная): так звучит звук с уровнем {@link #REF} у уха на шкале ядерки — то же
     * число, что {@code ENGINE_LUFS} в {@code tools/build_sounds.py} ({@code LoudnessTest} сверяет); фон тихой ночи
     * ({@link Propagation#NIGHT}) на ней — громкостью {@link VoiceBudget#AUDIBLE}.
     */
    static final double ENGINE_LUFS = -19;
    /**
     * Потолок громкости в игре, LUFS: громкость ванильного TNT (наибольшая мгновенная у {@code random/explode1–4} —
     * −10,4, −9,6, −9,7, −11,0, замер 23.10.2026). До 23.10 потолок был у файла ближнего ракурса взрыва (−13,5) — на
     * 3,5 дБ тише ванильного взрыва, и взрыв с 1 psi, старт и пролёт звучали тише 3.4.1 (прогон RV2).
     */
    static final double CEILING_LUFS = -10;
    /**
     * Громкость файлов петель моторов и разовых звуков старта, LUFS ({@code MOTOR_LUFS} в {@code tools/build_sounds.py},
     * {@code SoundAssetsTest} меряет сами файлы): на потолке — громкость в игре не упирается в файл раньше потолка.
     */
    static final double MOTOR_LUFS = CEILING_LUFS;
    /**
     * Громкость файлов схода снаряда РСЗО, LUFS ({@code ROCKET_LAUNCH_LUFS} в сборке): короткий удар на потолок не свести
     * без пережатия — на 2 дБ ниже. Хлопок отделения ускорителя и катапульта — на {@link #ENGINE_LUFS}.
     */
    static final double ROCKET_LAUNCH_LUFS = MOTOR_LUFS - 2;
    /**
     * Громкость файлов поджига и рёва ускорителя, LUFS ({@code LAUNCH_LUFS} в сборке): удар и плотный рёв РДТТ
     * ограничитель не глубже 6 дБ доводит только до −11 — на 1 дБ ниже потолка.
     */
    static final double LAUNCH_LUFS = MOTOR_LUFS - 1;
    /** Громкость петли бурения бомбы, LUFS ({@code DRILL_LUFS} в сборке): скрежет с ударами — так же на 1 дБ ниже потолка. */
    static final double DRILL_LUFS = MOTOR_LUFS - 1;

    /** A-уровень у уха, который на шкале ядерки звучит громкостью {@link #ENGINE_LUFS}, дБА. */
    static final double REF = Propagation.NIGHT + 40 * Math.log10(1 / VoiceBudget.AUDIBLE);
    /**
     * A-уровень у уха, который звучит в игре как взрыв с 1 psi, дБА: выше окно не поднимается. Оценка: у шахеда в 50 м
     * (8,7 кПа, фаза ~10 мс) энергия скачка Фридлендера на окне громкости 0,4 с — ~150 дБ без взвешивания, ~135 дБА
     * (спектр — у 1/t₊ ≈ 100 Гц); прежняя шкала (потолок −13,5 LUFS от мерки) давала 137 — число оставлено.
     */
    static final double CEILING_LEVEL = 137;
    /** Громкость потолка против {@link #ENGINE_LUFS}. */
    static final double TOP = Math.pow(10, (CEILING_LUFS - ENGINE_LUFS) / 20);
    /** Окно громкости звуков обычного оружия (клиент один — окно одно). */
    static final Window WINDOW = new Window();

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
     * октаве ({@link Propagation#margin}), — против {@link #ENGINE_LUFS}, не больше потолка {@link #TOP}; звук слышно —
     * он в окне ({@link Window#hear}).
     */
    static double gain(double level, double margin) {
        return gain(level, margin, CEILING_LUFS);
    }

    /**
     * То же с потолком {@code ceilingLufs}: у звука обычного оружия ({@link #CEILING_LUFS}) — в окне громкости, у ядерки
     * ({@link #ENGINE_LUFS}) — прежняя неподвижная шкала от {@link #REF}: её звук не меняется.
     */
    static double gain(double level, double margin, double ceilingLufs) {
        if (!(margin > 0)) return 0;
        if (ceilingLufs != CEILING_LUFS) {
            double top = Math.pow(10, (ceilingLufs - ENGINE_LUFS) / 20);
            return Math.min(top, Math.pow(10, (level - REF) / 40)) * Propagation.smoothstep(0, Propagation.FADE, margin);
        }
        return windowed(level, WINDOW.hear(level)) * Propagation.smoothstep(0, Propagation.FADE, margin);
    }

    /** Громкость против {@link #ENGINE_LUFS} звука с уровнем {@code level} при верхе окна {@code top}, не громче потолка. */
    static double windowed(double level, double top) {
        return TOP * Math.min(1, Math.pow(10, (level - top) / 40));
    }

    /** A-уровень, равный в игре звуку громкостью {@code lufs} при окне у {@link #CEILING_LEVEL} (взрывы: {@code BlastMix}). */
    static double level(double lufs) {
        return CEILING_LEVEL + 2 * (lufs - CEILING_LUFS);
    }

    /** Громкость для Minecraft (0..1) громкости {@code gain} ({@link #gain}) у файла громкостью {@code fileLufs}. */
    static float volume(double gain, double fileLufs) {
        return (float) Math.min(1, gain * fileGain(fileLufs));
    }

    /** Громкость для Minecraft громкости {@code gain} у файла мотора или старта ({@link #MOTOR_LUFS}). */
    static float motor(double gain) {
        return volume(gain, MOTOR_LUFS);
    }

    /**
     * Окно громкости (подача, как HDR-звук в играх): верх окна — самый громкий звук обычного оружия, что слышно сейчас
     * (моторы, старты, звуки мира — по уровню каждый тик и при запуске, взрывы — в миг прихода фронта), не ниже
     * {@link #FLOOR} и не выше {@link #CEILING_LEVEL}. Громкий звук поднимает окно сразу, тише — окно держится
     * {@link #HOLD} тиков и опускается на {@link #RELEASE} дБ за тик: моторы под взрывом уходят вниз на всю разницу уровней
     * (прежний {@code Ducking} — только на 8 дБ) и возвращаются за секунды, а звук, что стихает быстрее окна (ракета
     * уходит от пусковой, взрыв гаснет), стихает и в игре.
     */
    static final class Window {
        /**
         * Ниже окно не опускается, дБА (подача): так ревёт мотор или старт в десятках метров. Звук громче — у потолка, тише —
         * ниже на половину децибел разницы: шахед в 54 м (~90 дБА) звучит на 7,5 дБ ниже потолка, а не на 23,5, как на
         * неподвижной шкале; в миг прихода взрыва с 1 psi окно встаёт к {@link #CEILING_LEVEL}, и мотор под ним — на 23,5 дБ
         * ниже потолка, как прежде.
         */
        static final double FLOOR = 105;
        /** Тиков, что окно держится после самого громкого звука. */
        static final int HOLD = 4;
        /**
         * Окно опускается на столько дБ уровня за тик (подача): 6 дБ/с уровня — 3 дБ/с громкости, от взрыва с 1 psi до
         * {@link #FLOOR} — за ~5 с. Быстрее — окно шло бы за уходящим звуком, и уходящая ракета не стихала бы.
         */
        static final double RELEASE = 0.3;

        private double top = FLOOR;
        private int hold;

        /** Звук с уровнем {@code level} слышно в этот тик: верх окна после него. */
        double hear(double level) {
            double l = Math.min(CEILING_LEVEL, level);
            if (l >= top) {
                top = l;
                hold = HOLD;
            }
            return top;
        }

        /** До уха дошёл взрыв, который на неподвижной шкале (окно у {@link #CEILING_LEVEL}) звучит громкостью {@code lufs}. */
        void blast(double lufs) {
            hear(level(lufs));
        }

        /**
         * Насколько громче неподвижной шкалы звучит взрыв громкостью {@code lufs} (на ней), дБ: окно — с ним самим
         * ({@link #blast} — когда он зазвучит), поэтому громче потолка он не выходит. Подъём держится до конца записи
         * взрыва: хвост не разрастается, пока окно опускается.
         */
        double lift(double lufs) {
            return (CEILING_LEVEL - Math.max(top, Math.min(CEILING_LEVEL, level(lufs)))) / 2;
        }

        double top() {
            return top;
        }

        /** Раз в тик. */
        void tick() {
            if (hold > 0) hold--;
            else top = Math.max(FLOOR, top - RELEASE);
        }

        void reset() {
            top = FLOOR;
            hold = 0;
        }
    }
}
