package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.sound.Air;

/**
 * Что звучит от обычного взрыва на расстоянии d и как громко — по заряду и давлению у уха (без Minecraft, проверяется
 * юнит-тестом).
 * <ul>
 * <li>Тембр и длина — закон подобия Сакса: волна заряда W на расстоянии R — та же, что у заряда W₀ на R·∛(W₀/W), во
 * времени сжатая в ∛(W₀/W) раз. Записи ракурсов ({@code tools/build_sounds.py}, раздел blasts) звучат как есть у
 * заряда {@link #REFERENCE_KG}; у другого — быстрее или медленнее в ∛(W₀/W) раз: тон выше и короче у «Ланцета», ниже и
 * дольше у ракеты ({@link #pitch}). Minecraft играет тон только от 0,5 до 2 ({@code SoundEngine.calculatePitch}):
 * записи не сжать сильнее, чем у заряда 6,25 кг, и не растянуть сильнее, чем у 400 кг, — «Ланцет» (3 кг) звучит записью
 * заряда 6,25 кг (отступление: предел движка). Удар у уха из модели ({@link BlastPulse}) этого предела не знает.</li>
 * <li>Громкость — по пиковому давлению у уха (Кинни–Грэхем, {@link BlastModel}): полная с {@link #FULL_PA}, тише —
 * на половину децибел давления, как у ядерного удара ({@link Outdoor#pressureVolume}) и у дальней модели
 * {@link Outdoor}: вдали давление падает как 1/R — −3 дБ на удвоение, вблизи быстрее.</li>
 * <li>Ракурсы одной записи (вблизи — удар и тело взрыва, средний — запись с эхом склонов и домов, вдали — она же
 * через километр воздуха) складываются по амплитуде, в сумме 1. Вдали — сколько воздух съел верхов: доля дальнего
 * ракурса — потеря 4 кГц на пути (ISO 9613-1, 22,9 дБ/км, та же таблица, что у сборки дальнего ракурса) против потери
 * на краю ближней модели ({@link Outdoor#NEAR}): там дальний ракурс звучит один, и дальняя модель продолжает его без
 * ступеньки. Вблизи или средний — какой фронт у уха: у ближнего ракурса скачок с мгновенным фронтом (в него вшит
 * импульс Фридлендера), а настоящий скачок нарастает тем дольше, чем он слабее ({@link BlastPulse#riseSeconds}); доля
 * ближнего — доля верхов скачка на 2 кГц ({@link #crack}). Где ближний уходит, удар у уха играет модель
 * ({@link Layers#pulse}): без двух ударов.</li>
 * <li>«Удар в грудь» снизу — низ ближнего ракурса (сборка вынесла его в свою запись), с той же долей.</li>
 * </ul>
 */
public final class BlastMix {
    /** Громкость файлов ракурсов, LUFS (наибольшая мгновенная) — те же числа, что в {@code tools/build_sounds.py}. */
    static final double NEAR_LUFS = -13.5, MID_LUFS = -13.5, FAR_LUFS = -15;
    /**
     * Заряд, у которого записи ракурсов звучат как есть, кг ТНТ. Оценка: ракурсы собраны и сведены как взрыв шахеда
     * (у ближнего — фаза 8 мс, «заряд ~50 кг в двух десятках метров», {@code build_sounds.py}); сами записи — подрывы от
     * 4 кг до карьерных.
     */
    public static final double REFERENCE_KG = 50;
    /** С этого пикового давления громкость полная, Па (1 psi — как у ядерного удара, {@code NukeSounds}). */
    public static final double FULL_PA = 1000 * BlastModel.kpa(1);
    /** Тон, который играет Minecraft ({@code SoundEngine.calculatePitch}). */
    static final double PITCH_MIN = 0.5, PITCH_MAX = 2;
    /**
     * Поглощение воздухом на 4 кГц в условиях записей, дБ/м ({@link Air#alpha}: ISO 9613-1, 20 °C, 70 %): по нему дальний
     * ракурс сменяет ближние — калибровка записей (в дальней уже есть свой воздух), а не воздух сейчас.
     */
    static final double AIR_4K = Air.alpha(4000);
    /** Полоса, по верхам которой ближний ракурс уступает среднему, Гц (верха {@link Outdoor}). */
    static final double CRACK_HZ = BlastPulse.HIGHS_HZ;

    /**
     * Громкость слоёв (0..1, как у звука Minecraft) и удара у уха своим источником ({@link BlastPulse}: громкость
     * источника, дорожка — с пиком 0,98).
     */
    public record Layers(float near, float mid, float far, float sub, float pulse) {}

    private BlastMix() {}

    /** Пиковое давление взрыва у уха на расстоянии {@code d} блоков, Па. */
    public static double pressure(BlastSource s, double d) {
        return 1000 * BlastModel.overpressureKpa(d, s.chargeKg(), s.surface());
    }

    /** Положительная фаза у уха, с. */
    public static double phase(BlastSource s, double d) {
        return BlastModel.chemicalPhaseSeconds(d, s.chargeKg(), s.surface());
    }

    /** Тон и скорость записи заряда {@code chargeKg}: ∛(W₀/W) в пределах движка. */
    public static float pitch(double chargeKg) {
        if (!(chargeKg > 0)) return 1;
        return (float) Math.max(PITCH_MIN, Math.min(PITCH_MAX, Math.cbrt(REFERENCE_KG / chargeKg)));
    }

    /** Громкость прямого звука у уха, LUFS: ближний ракурс с {@link #FULL_PA}, тише — половина децибел давления. */
    public static double loudness(double pa) {
        return NEAR_LUFS + 10 * Math.log10(Math.max(1e-12, Math.min(1, pa / FULL_PA)));
    }

    /**
     * Доля верхов скачка у уха на {@link #CRACK_HZ}: фронт Тейлора ½(1 + th(t/θ)) — спектр (πωθ/2)/sh(πωθ/2) против
     * скачка, θ = нарастание 10–90 % / (2·arth 0,8). У 50 кг до ~200 м — почти весь (скачок), к ~900 м — десятая.
     */
    public static double crack(double pa) {
        double theta = BlastPulse.riseSeconds(pa) / (2 * 1.0986), x = Math.PI * 2 * Math.PI * CRACK_HZ * theta / 2;
        return x < 1e-6 ? 1 : x / Math.sinh(Math.min(x, 700));
    }

    /** Доля дальнего ракурса на расстоянии {@code d}: потеря 4 кГц в воздухе против неё на {@link Outdoor#NEAR}. */
    public static double far(double d) {
        double lost = 1 - Math.pow(10, -AIR_4K * Math.max(0, d) / 20), edge = 1 - Math.pow(10, -AIR_4K * Outdoor.NEAR / 20);
        return Math.min(1, lost / edge);
    }

    /** Громкость дальнего ракурса на краю ближней модели ({@link Outdoor#NEAR}): с неё продолжается {@link Outdoor}. */
    public static float v640(BlastSource s) {
        return gain(loudness(pressure(s, Outdoor.NEAR)) - FAR_LUFS);
    }

    /** Слои взрыва {@code s} на расстоянии {@code d} блоков. */
    public static Layers at(BlastSource s, double d) {
        double pa = pressure(s, d), l = loudness(pa), far = far(d), crack = crack(pa);
        double near = (1 - far) * crack, mid = (1 - far) * (1 - crack);
        float nearV = (float) near * gain(l - NEAR_LUFS);
        return new Layers(nearV, (float) mid * gain(l - MID_LUFS), (float) far * gain(l - FAR_LUFS), nearV,
                (float) ((1 - near) * Math.sqrt(Math.min(1, pa / FULL_PA))));
    }

    /** Множитель громкости для разницы db, не больше 1 (громче файла Minecraft не играет). */
    static float gain(double db) {
        return (float) Math.min(1, Math.pow(10, db / 20));
    }

    /** Множитель громкости для разницы db без предела (своим источникам OpenAL разрешено громче единицы). */
    static float uncapped(double db) {
        return (float) Math.pow(10, db / 20);
    }
}
