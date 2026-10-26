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
 * <li>гул взрывов — {@link Rumble#QUADRANTS} петли, гул пожаров в воронках — {@link Quarters#N} (сумма всех пожаров,
 * {@link CraterFire});</li>
 * <li>ядерка ({@link #NUKE}): четыре петли гула пожаров и ветер в огонь ({@code NukeAfter}), звон в ушах, две сирены;</li>
 * <li>остальной мир ({@link #WORLD}, О): ваниль (дождь, мобы, шаги, блоки), другие моды (машины Create), наши звуки мира
 * без бюджета ({@link Sources}: руины, подстанции, сирены) — их числа никто не ограничивает, поэтому сверх оценки —
 * свободные каналы движка в миг удара ({@link #admit}, {@code free}) и запас на самый громкий ({@link #reserve}). Осыпь
 * выброса воронки — разовый звук взрыва, она в этом бюджете;</li>
 * <li>уступившие голоса держат каналы, пока стихают, и ещё тик, пока движок их не вернёт, — {@link #HEADROOM};</li>
 * <li>остальное — разовым звукам взрывов: {@link #CAP}, ~96 (24 ближних взрыва по 4 голоса разом).</li>
 * </ul>
 * Канал голоса занят, пока его держит движок ({@link Sound#held}): {@code stop()} у звука — только флаг, канал движок
 * останавливает в своём следующем тике и возвращает пулу задачей звукового потока после него
 * ({@code SoundEngine.tickNonPaused}, {@code ChannelAccess.scheduleTick}). Поэтому вытеснение места сейчас не даёт: новый
 * звучит в тех каналах, что свободны у движка уже сейчас, а уступившие вернут свои позже — и вернут миру его запас
 * {@link #SPARE}.
 * <p>
 * Места нет — голос у того, чья громкость в смеси выше (Q_b, {@link Mix.Blast#heard}): уступает самый тихий, и обычно это
 * хвост (атака громче своего хвоста), а хвост вдали и есть гул. Атаку — первые {@link #ATTACK} тиков, окно мгновенной
 * громкости BS.1770, — новый вытесняет, только если он громче её в {@link #KEEP} раза: слышимая атака, оборванная в гул,
 * слышна как сбой, хвост — нет. Новый получает голос — даже самый громкий — только если сразу выполнены три условия:
 * <ol>
 * <li>у движка сейчас свободно не меньше каналов, чем ему нужно (вытеснение их в этот тик не освободит), — у нового, который
 * тише кого-то из звучащих, сверх запаса {@link #GROUP};</li>
 * <li>с ним каналы взрывов, считая уступивших, что ещё не вернули свои, не выйдут за {@link #cap} + {@link #HEADROOM}
 * (остатка запаса хватает на его группу);</li>
 * <li>уступками тех, кто тише него (атака — тише в {@link #KEEP} раза), восстанавливаются и свой потолок, и запас миру
 * {@link #SPARE}, когда уступившие вернут каналы, — а у нового, который тише кого-то из звучащих, ещё и запас
 * {@link #GROUP} на самый громкий.</li>
 * </ol>
 * Запас {@link #GROUP} свободных каналов — самому громкому: он может его взять, а запас вернут тише его (как уступили бы
 * они его приёму). Тише кого-то из звучащих его не берёт: иначе запас тут же отнял бы у него голос внутри атаки, хотя мир
 * ничего не взял (ревью R7); такой новый звучит в гуле сразу. Запас тратит только мир сверх своей оценки — тогда
 * {@link #reserve} уступает заранее тех, кого вытеснил бы самый громкий.
 * Иначе он звучит в гуле с той же мощностью. Это
 * виртуализация каналов, а не закон слуха: порог «удар или гул» по слышимости атаки над фоном (временная маскировка) не
 * считается — пока каналов хватает, удар остаётся ударом, даже самый тихий.
 * <p>
 * Часы {@code now} здесь — тики звукового движка ({@code SoundEngine.tickCount}): по ним голос стихает и гул растёт.
 */
final class BlastVoices {
    /** Источников OpenAL у OpenAL Soft по умолчанию (alsoft {@code sources}, {@code alcOpenDevice} в 1.23.1): 255 моно и 1 стерео. */
    static final int MONO = 255, STEREO = 1;
    /** Каналы ядерки: четыре петли гула пожаров, ветер в огонь, звон в ушах, две сирены. */
    static final int NUKE = 4 + 1 + 1 + 2;
    /**
     * Остальной мир, О: ваниль в бою — дождь (новый звук каждые несколько тиков, по 1–2 с), шаги и мобы; машины Create;
     * наши звуки мира и пожары воронок. Оценка, не замер: сверх неё держит проверка свободных каналов.
     */
    static final int WORLD = 64;
    /**
     * Каналы уступивших голосов, пока они стихают ({@link #FADE} и тик полной громкости) и пока движок их не вернул (тик):
     * три ближних взрыва по 4 голоса за эти ~5 тиков (бюджет).
     */
    static final int HEADROOM = 12;
    /** Сколько свободных каналов движка оставить миру (его звуки этого тика; бюджет). */
    static final int SPARE = 8;
    /** Голосов у взрыва больше всего: ракурсы вблизи, средний, вдали и удар в грудь. */
    static final int GROUP = 4;
    /** Тиков стихает уступивший голос (без щелчка), после тика полной громкости ({@link Rumble#voice}; подача). */
    static final int FADE = 3;
    /** Тиков у взрыва атака — окно мгновенной громкости (BS.1770, 400 мс). */
    static final int ATTACK = 8;
    /** Во столько раз (по мощности, +3 дБ) новый должен быть громче атаки, чтобы занять её место (подача). */
    static final double KEEP = 2;
    /** Каналов разовым звукам взрывов при OpenAL Soft по умолчанию. */
    static final int CAP = cap(MONO, STEREO);

    /** Разовый звук взрыва в канале движка. */
    interface Sound {
        /** Стихнуть за {@link #FADE} тиков (после тика полной громкости) и уйти. */
        void fade();

        /** Замолчать (сброс). */
        void cut();

        /** Отзвучал или движок его снял. */
        boolean over();

        /** Движок ещё держит его канал (остановленный звук держит его до возврата пулу). */
        default boolean held() {
            return !over();
        }
    }

    /** Взрыв с голосами: его звуки (i-й — i-й слой взрыва голосом движка) и что о нём знать гулу, если уступит. */
    static final class Voice {
        final Mix.Blast blast;
        final List<? extends Sound> sounds;
        final double born, azimuth;
        final float pitch, gain, highs;
        /** Какие звуки движок взял; до {@link #started} — все. */
        boolean[] took;
        /** С этого тика стихает (уступил); NaN — звучит. */
        double fadeFrom = Double.NaN;
        Rumble.Member member;

        Voice(Mix.Blast blast, List<? extends Sound> sounds, double born, double azimuth, float pitch, float gain, float highs) {
            this.blast = blast;
            this.sounds = sounds;
            this.born = born;
            this.azimuth = azimuth;
            this.pitch = pitch;
            this.gain = gain;
            this.highs = highs;
            this.took = new boolean[sounds.size()];
            java.util.Arrays.fill(took, true);
        }

        boolean fading() {
            return !Double.isNaN(fadeFrom);
        }

        /** Каналов держит у движка сейчас. */
        int held() {
            int n = 0;
            for (Sound s : sounds) if (s.held()) n++;
            return n;
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
        return Math.max(4, reach(mono, stereo) - VoiceBudget.CAP - Rumble.QUADRANTS - Quarters.N - NUKE - WORLD - HEADROOM);
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
     * или гул. Новый звучит в каналах, свободных у движка сейчас; места в своём потолке ({@link #cap}) и запаса миру
     * ({@link #SPARE}, когда уступившие вернут каналы) нет — уступают самые тихие (стихают, их мощность переходит в гул);
     * не уступает никто — новый сразу в гул. Новому, который тише кого-то из звучащих, {@link #GROUP} свободных не
     * достаются: это запас самому громкому.
     *
     * @param now     тик звукового движка
     * @param azimuth откуда взрыв от уха, atan2(dz, dx)
     * @param pitch   тон его записей
     * @param gain    доля мощности, что проходит преграду у уха (у голоса — фильтр EFX)
     * @param highs   доля верхов на пути
     * @param free    свободных статических каналов у движка сейчас; {@link Integer#MAX_VALUE} — не знаем
     * @return голос — играть его звуки; null — взрыв звучит в гуле
     */
    Voice admit(Mix.Blast blast, List<? extends Sound> sounds, double now, double azimuth, float pitch, float gain, float highs, int free) {
        prune(now);
        int need = sounds.size();
        long steady = 0, releasing = 0;
        double loudest = 0;
        for (Voice v : voices) {
            if (v.fading()) releasing += v.held();
            else {
                steady += v.held();
                loudest = Math.max(loudest, v.blast.heard());
            }
        }
        // GROUP свободных — запас самому громкому ({@link #reserve}): тише кого-то из звучащих его не берёт, иначе запас
        // тут же отнял бы у него голос (ревью R7)
        if (blast.heard() < loudest) free -= GROUP;
        // каналы нужны сейчас, а вытеснение вернёт их движку только через тики: сейчас их нет — гул
        if (need > free) return rumble(blast, now, azimuth, pitch, gain, highs);
        // уступившие держат каналы, пока стихают и пока движок их не вернул: запас на них кончился — гул
        if (steady + releasing + need > (long) cap + HEADROOM) return rumble(blast, now, azimuth, pitch, gain, highs);
        double fresh = blast.heard();
        List<Voice> quieter = new ArrayList<>();
        for (Voice v : voices) if (!v.fading() && priority(v, now) < fresh) quieter.add(v);
        quieter.sort(Comparator.comparingDouble(v -> priority(v, now)));
        // свой потолок и запас миру, когда уступившие вернут каналы: уступают самые тихие
        List<Voice> evict = new ArrayList<>();
        long freed = 0;
        for (Voice v : quieter) {
            if (steady - freed + need <= cap && (long) free - need + releasing + freed >= SPARE) break;
            evict.add(v);
            freed += v.held();
        }
        if (steady - freed + need > cap || (long) free - need + releasing + freed < SPARE) return rumble(blast, now, azimuth, pitch, gain, highs);
        for (Voice v : evict) give(v, now);
        Voice v = new Voice(blast, sounds, now, azimuth, pitch, gain, highs);
        voices.add(v);
        requested += need;
        return v;
    }

    /**
     * Громкость в смеси, с которой голос держит место; запись кончилась (звук держит канал ещё до 20 тиков запаса) — ноль:
     * смесь его больше не считает, и доля в ней — прежняя.
     */
    private static double priority(Voice v, double now) {
        return v.blast.ended(v.blast.now()) ? 0 : v.blast.heard() * (now - v.born < ATTACK ? KEEP : 1);
    }

    private Voice rumble(Mix.Blast blast, double now, double azimuth, float pitch, float gain, float highs) {
        rumble.add(blast, azimuth, pitch, gain, highs, now, 0);
        toRumble++;
        return null;
    }

    /** Движок взял первые {@code taken} звуков голоса {@code v} (проверки). */
    void started(Voice v, int taken, double now) {
        boolean[] took = new boolean[v.sounds.size()];
        for (int i = 0; i < took.length; i++) took[i] = i < taken;
        started(v, took, now);
    }

    /**
     * Движок взял звуки {@code took} голоса {@code v}; не все — голос уступает: взятые стихают, а мощность невзятых — в гуле
     * сразу (они не звучат вовсе).
     */
    void started(Voice v, boolean[] took, double now) {
        v.took = took.clone();
        int taken = 0;
        for (boolean t : took) if (t) taken++;
        started += taken;
        refused += took.length - taken;
        if (taken < took.length) give(v, now);
    }

    /** Голос уступает: его звучащие звуки стихают, а мощность взрыва переходит в гул за то же время (что не звучит — сразу). */
    void give(Voice v, double now) {
        if (v.fading()) return;
        v.fadeFrom = now;
        v.sounds.forEach(Sound::fade);
        v.member = rumble.add(v.blast, v.azimuth, v.pitch, v.gain, v.highs, v.took, now, FADE);
        yielded++;
    }

    /**
     * Запас на самый громкий, раз в тик: свободных у движка (вместе с теми, что уступившие вот-вот вернут) меньше
     * {@link #SPARE} + {@link #GROUP} — мир съел запас; самые тихие свои уступают заранее, пока он не вернётся: ближний,
     * пришедший потом, звучит в этих каналах, а не в гуле. Освободить каналы в тот же тик нельзя (движок возвращает их в
     * своём следующем тике), поэтому — заранее, а не при ударе. Сколько уступило.
     * <p>
     * Заранее уступает только тот, кого при приёме вытеснил бы взрыв громкости самого громкого голоса сейчас ({@link #admit}):
     * кто тише него (атака — в {@link #KEEP} раза) или чья запись кончилась. Сам самый громкий не уступает: запас — для
     * громкого, а он уже звучит; ближний, который потратил запас на свою атаку, им же и был бы вытеснен (ревью R7). Громче
     * него новый при приёме вытеснит его хвост сам, если у движка есть каналы сейчас; нет и их (мир съел всё, а свои голоса —
     * только самый громкий) — новый в гуле: отдать заранее слышимый голос ради взрыва, которого ещё нет, значит потерять
     * слышимое наверняка. Свои тихие запас не тратят ({@link #admit}), поэтому уступать заставляет только мир или самый
     * громкий, который его взял.
     */
    int reserve(double now, int free) {
        prune(now);
        long releasing = 0;
        double loudest = 0;
        for (Voice v : voices) {
            if (v.fading()) releasing += v.held();
            else loudest = Math.max(loudest, v.blast.heard());
        }
        if ((long) free + releasing >= SPARE + GROUP) return 0;
        List<Voice> quiet = new ArrayList<>();
        for (Voice v : voices) if (!v.fading() && (priority(v, now) < loudest || v.blast.ended(v.blast.now()))) quiet.add(v);
        quiet.sort(Comparator.comparingDouble(v -> priority(v, now)));
        int n = 0;
        long freed = 0;
        for (Voice v : quiet) {
            if ((long) free + releasing + freed >= SPARE + GROUP) break;
            freed += v.held();
            give(v, now);
            n++;
        }
        return n;
    }

    /** Голоса, чьи каналы движок уже вернул, — прочь. */
    void prune(double now) {
        voices.removeIf(v -> v.held() == 0);
    }

    /** Каналов держат голоса взрывов сейчас: звучащие, стихающие и остановленные, пока движок их не вернул. */
    int channels() {
        int n = 0;
        for (Voice v : voices) n += v.held();
        return n;
    }

    /** Каналов у звучащих (не уступивших). */
    int steady() {
        int n = 0;
        for (Voice v : voices) if (!v.fading()) n += v.held();
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
