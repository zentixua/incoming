package ua.zentix.incoming.client.sound;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Какие взрывы держат голоса движка — разовые звуки: ракурсы, удар в грудь, раскат вдали, звуки бомбы (без Minecraft,
 * проверяется юнит-тестом). Как {@link VoiceBudget} у моторов, но разовый звук нельзя отпустить и взять снова: место
 * уступают целиком, и уступивший взрыв дальше играет гул ({@link Rumble}) с той же мощностью в смеси ({@link Mix}).
 * <p>
 * Каналы — общий ресурс: когда статические кончаются, движок молча не играет новый звук ({@code SoundEngine.play}: «Failed
 * to create new sound handle» — только в среде разработки), в том числе ближний взрыв. Сколько их, решает устройство
 * ({@code Library.init}: моно-источники OpenAL минус потоковые clamp(√моно, 2, 8)); у OpenAL Soft, которого везёт
 * Minecraft 1.21.1, 255 моно и 1 стерео — 247 статических и 8 потоковых. Из тех же 256 источников OpenAL берёт свои
 * {@link OwnSources#MAX} (эхо, удар у уха), поэтому статических на деле — не больше 256 − 8 − 12 = 236. Из них:
 * <ul>
 * <li>моторы снарядов — {@link VoiceBudget#CAP};</li>
 * <li>гул взрывов — {@link Rumble#QUADRANTS} петли;</li>
 * <li>ядерка ({@link #NUKE}): четыре петли гула пожаров и ветер в огонь ({@code NukeAfter}), звон в ушах, две сирены;</li>
 * <li>остальной мир ({@link #WORLD}, О): ваниль (дождь, мобы, шаги, блоки), другие моды (машины Create), наши звуки мира
 * ({@link Sources}: осыпь выброса, руины, подстанции), пожары воронок ({@link CraterFire}) — их числа никто не
 * ограничивает, поэтому сверх оценки — проверка свободных каналов движка в миг удара ({@link #admit}, {@code free});</li>
 * <li>уступившие голоса стихают {@link #FADE} тика и всё это время держат каналы — {@link #HEADROOM};</li>
 * <li>остальное — разовым звукам взрывов: {@link #CAP}, ~100 (25 ближних взрывов по 4 голоса разом).</li>
 * </ul>
 * Места нет — голос у того, чья громкость в смеси выше (Q_b, {@link Mix.Blast#heard}): уступает самый тихий, и обычно
 * это хвост (атака громче своего хвоста), а хвост вдали и есть гул. Атаку — первые {@link #ATTACK} тиков, окно мгновенной
 * громкости BS.1770, — новый вытесняет, только если он громче её в {@link #KEEP} раза: слышимая атака, оборванная в гул,
 * слышна как сбой, хвост — нет. Тише всех звучащих — новый сразу в гул. Ближний (громче всех) голос получает всегда.
 * Это виртуализация каналов, а не закон слуха: порог «удар или гул» по слышимости атаки над фоном (временная маскировка)
 * не считается — пока каналов хватает, удар остаётся ударом, даже самый тихий.
 */
final class BlastVoices {
    /** Источников OpenAL у OpenAL Soft по умолчанию (alsoft {@code sources}): 255 моно и 1 стерео. */
    static final int MONO = 255, STEREO = 1;
    /** Каналы ядерки: четыре петли гула пожаров, ветер в огонь, звон в ушах, две сирены. */
    static final int NUKE = 4 + 1 + 1 + 2;
    /**
     * Остальной мир, О: ваниль в бою — дождь (новый звук каждые несколько тиков, по 1–2 с), шаги и мобы; машины Create;
     * наши звуки мира и пожары воронок. Оценка, не замер: сверх неё держит проверка свободных каналов.
     */
    static final int WORLD = 64;
    /** Уступившие голоса стихают {@link #FADE} тиков, держа каналы: три ближних взрыва по 4 голоса. */
    static final int HEADROOM = 12;
    /** Сколько свободных каналов движка оставить миру в миг удара (его звуки этого тика). */
    static final int SPARE = 8;
    /** Тиков стихает уступивший голос (без щелчка). */
    static final int FADE = 3;
    /** Тиков у взрыва атака — окно мгновенной громкости (BS.1770, 400 мс). */
    static final int ATTACK = 8;
    /** Во столько раз новый должен быть громче атаки, чтобы занять её место. */
    static final double KEEP = 2;
    /** Каналов разовым звукам взрывов при OpenAL Soft по умолчанию. */
    static final int CAP = cap(MONO, STEREO);

    /** Разовые звуки одного взрыва в каналах движка. */
    interface Sound {
        /** Стихнуть за {@link #FADE} тиков и уйти. */
        void fade();

        /** Замолчать сейчас. */
        void cut();

        /** Отзвучал или движок его снял. */
        boolean over();
    }

    /** Взрыв с голосами: его звуки и что о нём знать гулу, если уступит. */
    static final class Voice {
        final Mix.Blast blast;
        final List<? extends Sound> sounds;
        final int channels;
        final double born, azimuth;
        final float pitch, gain, highs;
        /** С этого тика стихает (уступил); NaN — звучит. */
        double fadeFrom = Double.NaN;
        Rumble.Member member;

        Voice(Mix.Blast blast, List<? extends Sound> sounds, double born, double azimuth, float pitch, float gain, float highs) {
            this.blast = blast;
            this.sounds = sounds;
            this.channels = sounds.size();
            this.born = born;
            this.azimuth = azimuth;
            this.pitch = pitch;
            this.gain = gain;
            this.highs = highs;
        }

        boolean fading() {
            return !Double.isNaN(fadeFrom);
        }
    }

    private final List<Voice> voices = new ArrayList<>();
    private final Rumble rumble;
    private int cap = CAP;
    /** Счётчики для строки сценария: голосов просили, движок взял, отказал; взрывов сразу в гул, уступили. */
    int requested, started, refused, toRumble, yielded;

    BlastVoices(Rumble rumble) {
        this(rumble, CAP);
    }

    /** {@code cap} — каналов разовым звукам взрывов (проверки с теснотой). */
    BlastVoices(Rumble rumble, int cap) {
        this.rumble = rumble;
        this.cap = cap;
    }

    /**
     * Каналов разовым звукам взрывов у устройства с {@code mono} моно- и {@code stereo} стерео-источниками: статические
     * движка ({@code Library.init}), сколько из них даст OpenAL рядом с потоковыми и своими источниками, минус моторы, гул,
     * ядерка, мир и запас на стихающие.
     */
    static int cap(int mono, int stereo) {
        return Math.max(4, reach(mono, stereo) - VoiceBudget.CAP - Rumble.QUADRANTS - NUKE - WORLD - HEADROOM);
    }

    /**
     * Статических каналов движка, которые на деле даст OpenAL: пул {@code Library.init} (моно минус потоковые
     * clamp(√моно, 2, 8), от 8 до 255), но не больше источников устройства без потоковых и своих ({@link OwnSources#MAX}).
     */
    static int reach(int mono, int stereo) {
        int streaming = Math.max(2, Math.min(8, (int) Math.sqrt(mono))), pool = Math.max(8, Math.min(255, mono - streaming));
        return Math.min(pool, mono + stereo - streaming - OwnSources.MAX);
    }

    /** Устройство звука сменилось (перезапуск движка): потолок — по его источникам. */
    void device(int mono, int stereo) {
        cap = cap(mono, stereo);
    }

    int cap() {
        return cap;
    }

    /**
     * Новый взрыв {@code blast} (слои уже в смеси), которому нужно столько голосов, сколько звуков {@code sounds}: голос —
     * или гул. Места нет — уступают самые тихие (стихают {@link #FADE} тиков, их мощность переходит в гул); не уступает
     * никто — новый сразу в гул.
     *
     * @param azimuth откуда взрыв от уха, atan2(dz, dx)
     * @param pitch   тон его записей
     * @param gain    доля мощности, что проходит преграду у уха (у голоса — фильтр EFX)
     * @param highs   доля верхов на пути
     * @param free    свободных каналов у движка сейчас (вместе со звуками мира); {@link Integer#MAX_VALUE} — не знаем
     * @return голос — играть его звуки; null — взрыв звучит в гуле
     */
    Voice admit(Mix.Blast blast, List<? extends Sound> sounds, double now, double azimuth, float pitch, float gain, float highs, int free) {
        prune(now);
        int need = sounds.size(), steady = 0, held = 0;
        for (Voice v : voices) {
            if (v.fading()) held += v.channels;
            else steady += v.channels;
        }
        long ours = steady + held;
        double fresh = blast.heard();
        // кто уступит: самые тихие, атака — с запасом KEEP
        List<Voice> evict = new ArrayList<>();
        if (steady + need > cap) {
            List<Voice> quieter = new ArrayList<>();
            for (Voice v : voices) if (!v.fading() && priority(v, now) < fresh) quieter.add(v);
            quieter.sort(Comparator.comparingDouble(v -> priority(v, now)));
            for (Voice v : quieter) {
                if (steady + need <= cap) break;
                evict.add(v);
                steady -= v.channels;
            }
        }
        if (steady + need > cap) return rumble(blast, now, azimuth, pitch, gain, highs);
        // каналы на деле, пока уступившие стихают: сверх запаса или свободных у движка — стихающие замолкают сразу
        long hard = Math.min((long) cap + HEADROOM, ours + free - SPARE);
        long total = (long) steady + need + held + sum(evict);
        List<Voice> cut = new ArrayList<>();
        List<Voice> order = new ArrayList<>();
        for (Voice v : voices) if (v.fading()) order.add(v);
        order.sort(Comparator.comparingDouble(v -> v.fadeFrom));
        order.addAll(evict);
        for (Voice v : order) {
            if (total <= hard) break;
            cut.add(v);
            total -= v.channels;
        }
        if (total > hard) return rumble(blast, now, azimuth, pitch, gain, highs);
        for (Voice v : evict) give(v, now);
        for (Voice v : cut) cut(v, now);
        Voice v = new Voice(blast, sounds, now, azimuth, pitch, gain, highs);
        voices.add(v);
        requested += need;
        return v;
    }

    private static long sum(List<Voice> vs) {
        long s = 0;
        for (Voice v : vs) s += v.channels;
        return s;
    }

    /** Громкость в смеси, с которой голос держит место. */
    private static double priority(Voice v, double now) {
        return v.blast.heard() * (now - v.born < ATTACK ? KEEP : 1);
    }

    private Voice rumble(Mix.Blast blast, double now, double azimuth, float pitch, float gain, float highs) {
        rumble.add(blast, azimuth, pitch, gain, highs, now, 0);
        toRumble++;
        return null;
    }

    /** Движок взял {@code taken} звуков голоса {@code v}; не все — голос уступает, взрыв — в гул. */
    void started(Voice v, int taken, double now) {
        started += taken;
        refused += v.channels - taken;
        if (taken < v.channels) give(v, now);
    }

    /** Голос уступает: звуки стихают, мощность взрыва переходит в гул за то же время. */
    void give(Voice v, double now) {
        if (v.fading()) return;
        v.fadeFrom = now;
        v.sounds.forEach(Sound::fade);
        v.member = rumble.add(v.blast, v.azimuth, v.pitch, v.gain, v.highs, now, FADE);
        yielded++;
    }

    /** Стихающий голос замолкает сразу (каналы нужны сейчас): вся его мощность — в гуле с этого тика. */
    private void cut(Voice v, double now) {
        if (!v.fading()) give(v, now);
        v.sounds.forEach(Sound::cut);
        v.member.settle(now);
        voices.remove(v);
    }

    /** Тик: отзвучавшие и стихшие — прочь. */
    void prune(double now) {
        voices.removeIf(v -> v.fading() && now - v.fadeFrom >= FADE || v.sounds.stream().allMatch(Sound::over));
    }

    /** Каналов держат голоса взрывов сейчас: звучащие и стихающие. */
    int channels() {
        int n = 0;
        for (Voice v : voices) n += v.channels;
        return n;
    }

    /** Каналов у звучащих (не стихающих). */
    int steady() {
        int n = 0;
        for (Voice v : voices) if (!v.fading()) n += v.channels;
        return n;
    }

    List<Voice> voices() {
        return voices;
    }

    void reset() {
        voices.forEach(v -> v.sounds.forEach(Sound::cut));
        voices.clear();
        requested = started = refused = toRumble = yielded = 0;
    }
}
