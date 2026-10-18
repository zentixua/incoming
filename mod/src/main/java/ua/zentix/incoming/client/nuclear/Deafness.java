package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.sound.AmbientLoop;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.registry.ModSounds;

import java.util.Locale;

/**
 * Слух игрока после ударной волны (DESIGN-nuke §5, законы — {@link HearingModel}): сдвиг порога по энергии слышимого
 * фронта у уха, восстановление по игровым часам (час — 1000 тиков), разрыв перепонок. Все звуки игры глуше — фильтр
 * нижних частот OpenAL EFX ({@link ua.zentix.incoming.client.sound.SoundFilters}, свои источники —
 * {@link ua.zentix.incoming.client.sound.OwnSources}): низы — на потерю громкости в улитке и кондуктивную, верха —
 * сверх того на разницу верхов и низов. Пока сдвиг есть, звенит в ушах. Громкость категорий игрока не трогается.
 * <p>
 * Часы слуха — тики клиента без паузы и прыжки времени суток вперёд (сон, {@code /time add}): за ночь сна слух
 * восстанавливается, как за ночь. Слух — тела: смерть (возрождение) его возвращает, выход из мира — тоже (состояние
 * только у клиента).
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

    /** Сдвиг верхов через 2 минуты после последней волны, дБ, и часы слуха в её миг. */
    private static double first;
    private static long exposedAt;
    /** Когда порвались перепонки (часы слуха); −1 — целы. */
    private static long rupturedAt = -1;
    private static long clock;
    private static long lastDay = Long.MIN_VALUE;
    /** Фильтр сейчас: громкость и верха (его читает и поток звука). */
    private static volatile float gain = 1, highs = 1;
    /** Сдвиг верхов сейчас, дБ (для звона и строки сценария). */
    private static double shiftNow;
    @Nullable
    private static AmbientLoop ringing;

    private Deafness() {}

    /**
     * Волна дошла до уха: {@code exposure} — энергия её слышимого фронта, дБ·с ({@link HearingModel#exposure}, за вычетом
     * того, что съел путь), {@code kpa} — давление у уха, {@code seed} — зерно подрыва (перепонки рвутся одинаково при
     * повторе того же подрыва).
     */
    static void blast(double exposure, double kpa, long seed) {
        double s = HearingModel.shift(exposure);
        // новая волна поверх прежней: что сильнее сейчас
        if (s > shiftNow) {
            first = s;
            exposedAt = clock;
        }
        if (rupturedAt < 0) {
            Minecraft mc = Minecraft.getInstance();
            long who = mc.player == null ? 0 : mc.player.getUUID().getLeastSignificantBits();
            if (RandomSource.create(seed ^ who ^ 0x7E3D_7E3DL).nextDouble() < HearingModel.ruptureProbability(kpa)) rupturedAt = clock;
        }
        update();
    }

    /** Тик клиента: часы слуха, фильтр, звон. */
    static void tick(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        long day = level.getDayTime(), jump = lastDay == Long.MIN_VALUE ? 0 : day - lastDay - 1;
        lastDay = day;
        if (!mc.isPaused()) clock += 1 + (jump > DAY_JUMP ? jump : 0);
        if (first <= 0 && rupturedAt < 0) return;
        // повреждение слуха выключили в настройках — слух сразу как был
        if (!IncomingConfig.CLIENT.nukeTinnitus.get()) {
            reset();
            return;
        }
        update();
        ring(mc);
    }

    private static void update() {
        double hours = (clock - exposedAt) / HOUR_TICKS;
        double high = HearingModel.shiftAfter(first, hours);
        if (high <= 0) first = 0;
        shiftNow = high;
        double days = rupturedAt < 0 ? 0 : (clock - rupturedAt) / (24 * HOUR_TICKS);
        double torn = rupturedAt < 0 ? 0 : 1;
        double lossHigh = HearingModel.loudnessLoss(high) + torn * HearingModel.rupturedLoss(true, days);
        double lossLow = HearingModel.loudnessLoss(HearingModel.low(high)) + torn * HearingModel.rupturedLoss(false, days);
        if (rupturedAt >= 0 && HearingModel.rupturedLoss(false, days) <= 0) rupturedAt = -1;
        gain = (float) Math.pow(10, -lossLow / 20);
        highs = (float) Math.pow(10, -Math.max(0, lossHigh - lossLow) / 20);
    }

    /** Звон в ушах: петля у самого уха, громкость — по сдвигу порога. */
    private static void ring(Minecraft mc) {
        float v = (float) Math.min(RINGING, RINGING * shiftNow / RINGING_SHIFT);
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        if (ringing != null && !ringing.playing()) ringing = null;
        if (v < RINGING_QUIET) {
            if (ringing != null) ringing.end();
            ringing = null;
            return;
        }
        if (ringing == null) {
            if (mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0) return;
            ringing = new AmbientLoop(ModSounds.NUKE_TINNITUS.get(), SoundSource.MASTER, 1, 0);
            ringing.set(ear, ear, v, 0);
            mc.getSoundManager().play(ringing);
            return;
        }
        ringing.set(ear, ear, v, 0);
    }

    /** Слух — снова как был: выход из мира, перемотка повтора, возрождение. */
    static void reset() {
        first = 0;
        shiftNow = 0;
        rupturedAt = -1;
        gain = highs = 1;
        if (ringing != null) ringing.end();
        ringing = null;
    }

    private static boolean deaf(SoundInstance sound) {
        // музыку, пластинки и сам звон в ушах не трогаем
        return sound.getSource() != SoundSource.MUSIC && sound.getSource() != SoundSource.RECORDS
                && !sound.getLocation().equals(ModSounds.NUKE_TINNITUS.get().getLocation());
    }

    /** Громкость этого звука сейчас (низы), 1 — слышно как обычно. Зовут из звукового потока. */
    public static float gain(SoundInstance sound) {
        return deaf(sound) ? gain : 1;
    }

    /** Доля верхов против низов этого звука сейчас. Зовут из звукового потока. */
    public static float highs(SoundInstance sound) {
        return deaf(sound) ? highs : 1;
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
        return String.format(Locale.ROOT, "hearing shift %.1fdB (first %.1f) torn %s gain %.2f highs %.2f ringing %s", shiftNow, first,
                rupturedAt >= 0, gain, highs, ringing != null);
    }
}
