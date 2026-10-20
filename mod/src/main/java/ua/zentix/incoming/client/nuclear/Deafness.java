package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.registry.ModSounds;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Слух игрока после ударной волны — ядерной и обычного взрыва (DESIGN-nuke §5, законы — {@link HearingModel}): сдвиг
 * порога по энергии слышимого у уха, восстановление по игровым часам (час — 1000 тиков), разрыв перепонок. Все звуки игры глуше — фильтр
 * нижних частот OpenAL EFX ({@link ua.zentix.incoming.client.sound.SoundFilters}, свои источники —
 * {@link ua.zentix.incoming.client.sound.OwnSources}): низы — на потерю громкости в улитке и кондуктивную, верха —
 * сверх того на разницу верхов и низов. Пока сдвиг есть, звенит в ушах. Громкость категорий игрока не трогается.
 * <p>
 * Часы слуха — тики клиента без паузы и прыжки времени суток вперёд (сон, {@code /time add}): за ночь сна слух
 * восстанавливается, как за ночь. Прыжок — только когда игровое время шло как обычно (сон и {@code /time} его не
 * двигают): новый мир клиента (портал) начинается с нулевых часов до пакета времени, и его первый пакет иначе
 * засчитывался бы сном. Слух — тела: смерть (возрождение) его возвращает, выход из мира — тоже (состояние только у
 * клиента).
 */
public final class Deafness {
    /** Тиков в часе игры. */
    private static final double HOUR_TICKS = 1000;
    /** Скачок времени суток больше этого — время прошло (сон), меньше — поправка часов сервером. */
    private static final long DAY_JUMP = 20;
    /** Громкость звона при сдвиге {@link #RINGING_SHIFT} дБ и больше (громкость звона — не из модели, приближение). */
    private static final float RINGING = 0.75f;
    private static final double RINGING_SHIFT = 50;
    /** Тише — звон не звучит. */
    private static final float RINGING_QUIET = 0.01f;

    /** Волны, что дошли до уха. */
    private static final HearingModel.Ear EAR = new HearingModel.Ear();
    /** Когда порвались перепонки (часы слуха); −1 — целы. */
    private static long rupturedAt = -1;
    private static long clock;
    /** Время суток и игровое время прошлого тика и чей это мир клиента. */
    private static long lastDay, lastGame;
    private static WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);
    /** Фильтр сейчас: громкость и верха (его читает и поток звука). */
    private static volatile float gain = 1, highs = 1;
    /** Сдвиг верхов сейчас, дБ (для звона и строки сценария). */
    private static double shiftNow;
    @Nullable
    private static Ringing ringing;
    /**
     * Звуки самой волны (удар, хлопки, стёкла): их слышит ухо, каким оно было до неё. Иначе решал бы случай: запись без
     * preload в первый раз за игру стартует в потоке звука позже, чем волна бьёт по ушам, и удар глох бы от себя самого,
     * а со второго раза — нет. Ключи слабые; читает и поток звука.
     */
    private static final Map<SoundInstance, float[]> BEFORE = Collections.synchronizedMap(new WeakHashMap<>());

    private Deafness() {}

    /**
     * Волна дошла до уха: {@code exposure} — энергия её слышимого, дБ·с ({@link HearingModel#exposure(double, double)}, за
     * вычетом того, что съел путь), {@code kpa} — давление у уха, {@code seed} — зерно взрыва (перепонки рвутся одинаково
     * при повторе того же взрыва).
     */
    public static void blast(double exposure, double kpa, long seed) {
        EAR.blast(exposure, clock / HOUR_TICKS);
        // порванные и ещё не зажившие рвутся снова — заживать с начала
        Minecraft mc = Minecraft.getInstance();
        long who = mc.player == null ? 0 : mc.player.getUUID().getLeastSignificantBits();
        if (RandomSource.create(seed ^ who ^ 0x7E3D_7E3DL).nextDouble() < HearingModel.ruptureProbability(kpa)) rupturedAt = clock;
        update();
    }

    /** Звук волны, которая сейчас дойдёт до уха: его слышно, как до неё ({@link #BEFORE}). Звать до {@link #blast}. */
    public static void before(SoundInstance sound) {
        BEFORE.put(sound, new float[]{gain, highs});
    }

    /** Тик клиента: часы слуха, фильтр, звон. */
    static void tick(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        long day = level.getDayTime(), game = level.getGameTime(), jump = 0;
        // время суток ушло вперёд сверх игрового, а игровое шло как обычно, — прошло время (сон, /time add)
        if (lastLevel.get() == level && Math.abs(game - lastGame - 1) <= DAY_JUMP) jump = day - lastDay - (game - lastGame);
        if (lastLevel.get() != level) lastLevel = new WeakReference<>(level);
        lastDay = day;
        lastGame = game;
        if (!mc.isPaused()) clock += 1 + (jump > DAY_JUMP ? jump : 0);
        if (!EAR.any() && rupturedAt < 0) return;
        // повреждение слуха выключили в настройках — слух сразу как был
        if (!IncomingConfig.CLIENT.nukeTinnitus.get()) {
            reset();
            return;
        }
        update();
        ring(mc);
    }

    private static void update() {
        double high = EAR.shift(clock / HOUR_TICKS);
        shiftNow = high;
        double days = rupturedAt < 0 ? 0 : (clock - rupturedAt) / (24 * HOUR_TICKS);
        double torn = rupturedAt < 0 ? 0 : 1;
        double lossHigh = HearingModel.loudnessLoss(high) + torn * HearingModel.rupturedLoss(true, days);
        double lossLow = HearingModel.loudnessLoss(HearingModel.low(high)) + torn * HearingModel.rupturedLoss(false, days);
        if (rupturedAt >= 0 && HearingModel.rupturedLoss(false, days) <= 0) rupturedAt = -1;
        gain = (float) Math.pow(10, -lossLow / 20);
        highs = (float) Math.pow(10, -Math.max(0, lossHigh - lossLow) / 20);
    }

    /** Звон в ушах: петля в голове, громкость — по сдвигу порога. */
    private static void ring(Minecraft mc) {
        float v = (float) Math.min(RINGING, RINGING * shiftNow / RINGING_SHIFT);
        if (ringing != null && !ringing.playing()) ringing = null;
        if (v < RINGING_QUIET) {
            if (ringing != null) ringing.end();
            ringing = null;
            return;
        }
        if (ringing == null) {
            if (mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0) return;
            ringing = new Ringing();
            ringing.level(v);
            mc.getSoundManager().play(ringing);
            return;
        }
        ringing.level(v);
    }

    /** Слух — снова как был: выход из мира, перемотка повтора, возрождение. */
    static void reset() {
        if (gain < 1 || highs < 1) SoundFilters.restoreLoops();
        EAR.clear();
        shiftNow = 0;
        rupturedAt = -1;
        gain = highs = 1;
        if (ringing != null) ringing.end();
        ringing = null;
    }

    private static boolean deaf(SoundInstance sound) {
        // музыку, пластинки и сам звон в ушах не трогаем
        return sound.getSource() != SoundSource.MUSIC && sound.getSource() != SoundSource.RECORDS && !(sound instanceof Ringing);
    }

    /** Громкость этого звука сейчас (низы), 1 — слышно как обычно. Зовут из звукового потока. */
    public static float gain(SoundInstance sound) {
        if (!deaf(sound)) return 1;
        float[] b = BEFORE.get(sound);
        return b == null ? gain : b[0];
    }

    /** Доля верхов против низов этого звука сейчас. Зовут из звукового потока. */
    public static float highs(SoundInstance sound) {
        if (!deaf(sound)) return 1;
        float[] b = BEFORE.get(sound);
        return b == null ? highs : b[1];
    }

    /** Громкость звука мира сейчас — и своих источников OpenAL ({@link ua.zentix.incoming.client.sound.OwnSources}). */
    public static float gain() {
        return gain;
    }

    /** Доля верхов звука мира сейчас. */
    public static float highs() {
        return highs;
    }

    /** Строка {@code SCENARIO}: сдвиг порога, перепонки, фильтр, звон. */
    static String describe() {
        return String.format(Locale.ROOT, "hearing shift %.1fdB torn %s gain %.2f highs %.2f ringing %s", shiftNow,
                rupturedAt >= 0, gain, highs, ringing != null);
    }

    /**
     * Звон — в голове: петля без места (у слушателя, где бы он ни был и как бы быстро ни летел), мимо фильтров пути,
     * пелены и Sound Physics. Место у уха в мире отставало бы от слушателя на тик: тон гулял бы по сторонам при ходьбе.
     */
    private static final class Ringing extends AbstractTickableSoundInstance {
        /** На столько громкость подходит к цели за тик. */
        private static final float SLEW = 0.05f;
        /** Хозяин молчит столько тиков — звон уходит. */
        private static final int ORPHAN = 10;
        private float target;
        private int quiet;
        private boolean ending;

        Ringing() {
            super(ModSounds.NUKE_TINNITUS.get(), SoundSource.MASTER, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0.001f;
            relative = true;
            attenuation = Attenuation.NONE;
            x = y = z = 0;
        }

        void level(float v) {
            target = v;
            quiet = 0;
        }

        void end() {
            ending = true;
            target = 0;
        }

        /** Звучит ли (как {@link ua.zentix.incoming.client.sound.AmbientLoop#playing}). */
        boolean playing() {
            return !isStopped() && Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
        }

        @Override
        public void tick() {
            if (++quiet > ORPHAN) end();
            volume += Math.max(-SLEW, Math.min(SLEW, target - volume));
            if (ending && volume <= 0.002f) stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
