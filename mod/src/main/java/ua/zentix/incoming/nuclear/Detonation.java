package ua.zentix.incoming.nuclear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.model.ArrivalTable;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BlackRainModel;
import ua.zentix.incoming.nuclear.model.FalloutModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.FrontProfile;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.util.StreamCodecs;
import ua.zentix.incoming.util.Terrain;

/**
 * Один ядерный подрыв — всё, из чего сервер и каждый клиент по одной и той же модели выводят волну, свет,
 * шар, гриб и осадки. Хранится в мире ({@link NuclearEvents}) и уходит клиентам одним пакетом.
 * <p>
 * Масштаб {@code scale}: 1 блок = 1/scale метра (1.0 — как в жизни). Расстояния модели делятся на него,
 * время фронта умножается. Фронт в игре вдали идёт медленнее модели — видимой стеной со скоростью {@code wave}, за ней
 * звук со скоростью звука ({@link FrontProfile}): {@link #arrivalTicks}, {@link #frontRadius}.
 *
 * @param id        номер подрыва в мире (растёт)
 * @param burst     точка подрыва
 * @param groundY   высота земли под эпицентром
 * @param yieldKt   мощность, кт
 * @param surface   наземный подрыв (шар касается земли): воронка и осадки
 * @param gameTime  игровое время подрыва, тики
 * @param windDir   направление ветра, рад (дует в сторону (cos, sin) в плоскости x–z)
 * @param windSpeed скорость ветра, м/с
 * @param visibility видимость, м (ясно 20 км, дождь 5, гроза 1) — ослабление света
 * @param seed      сид случайных деталей (одинаковый у всех клиентов)
 * @param fallout   осадки включены (настройка {@code nuclear.fallout} в миг подрыва): след наземного подрыва и
 *                  радиоактивность чёрного дождя; у подрыва из сохранения прежней сборки — только у наземного
 * @param wave      скорость стены пыли вдали от шара, доля скорости звука (настройка {@code nuclear.wave_speed} в момент подрыва)
 * @param dayTime   время суток мира в миг подрыва (тики, как {@code Level.getDayTime}): у него мгла над пожарами ждёт
 *                  утра; {@link #NO_DAY_TIME} — неизвестно (подрыв из сохранения или повтора прежней сборки)
 * @param fires     сервер поджигает пожары от этого подрыва (настройки {@code nuclear.fires} и {@code fires_per_detonation}
 *                  в момент подрыва): без них огненного шторма и гула пожаров у клиента нет; у подрыва из сохранения или
 *                  повтора прежней сборки — да, как было
 */
public record Detonation(int id, Vec3 burst, double groundY, double yieldKt, boolean surface, long gameTime,
                         float windDir, float windSpeed, float visibility, long seed, float scale, boolean fallout, float wave,
                         long dayTime, boolean fires) {

    /** Скорость стены вдали у подрывов, сохранённых до настройки {@code wave_speed}: 3 блока за тик. */
    public static final float OLD_WAVE = (float) (3 / FrontProfile.SOUND_SPEED);
    /** Время суток подрыва неизвестно: сохранён или записан в повтор до этого поля. */
    public static final long NO_DAY_TIME = Long.MIN_VALUE;

    /** Подрыв без времени суток: место подрыва заранее ({@code NuclearWarhead.geometry}) и проверки. */
    public Detonation(int id, Vec3 burst, double groundY, double yieldKt, boolean surface, long gameTime,
                      float windDir, float windSpeed, float visibility, long seed, float scale, boolean fallout, float wave) {
        this(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, NO_DAY_TIME);
    }

    /** Подрыв с пожарами. */
    public Detonation(int id, Vec3 burst, double groundY, double yieldKt, boolean surface, long gameTime,
                      float windDir, float windSpeed, float visibility, long seed, float scale, boolean fallout, float wave,
                      long dayTime) {
        this(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, dayTime, true);
    }

    public static final Codec<Detonation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Detonation::id),
            Vec3.CODEC.fieldOf("burst").forGetter(Detonation::burst),
            Codec.DOUBLE.fieldOf("ground_y").forGetter(Detonation::groundY),
            Codec.DOUBLE.fieldOf("yield").forGetter(Detonation::yieldKt),
            Codec.BOOL.fieldOf("surface").forGetter(Detonation::surface),
            Codec.LONG.fieldOf("game_time").forGetter(Detonation::gameTime),
            Codec.FLOAT.fieldOf("wind_dir").forGetter(Detonation::windDir),
            Codec.FLOAT.fieldOf("wind_speed").forGetter(Detonation::windSpeed),
            Codec.FLOAT.fieldOf("visibility").forGetter(Detonation::visibility),
            Codec.LONG.fieldOf("seed").forGetter(Detonation::seed),
            Codec.FLOAT.fieldOf("scale").forGetter(Detonation::scale),
            Codec.BOOL.fieldOf("fallout").forGetter(Detonation::fallout),
            Codec.FLOAT.optionalFieldOf("wave", OLD_WAVE).forGetter(Detonation::wave),
            Codec.LONG.optionalFieldOf("day_time", NO_DAY_TIME).forGetter(Detonation::dayTime),
            Codec.BOOL.optionalFieldOf("fires", true).forGetter(Detonation::fires)
    ).apply(i, Detonation::new));

    /**
     * Подрыв в пакете — поля выпуска 3.1.0, без {@code wave} ({@link #OLD_WAVE}) и времени суток. Формат заморожен:
     * повторы Flashback хранят пакеты байтами (replay.md), поэтому новое поле подрыва пакет дописывает в свой конец
     * ({@code S2C.NukeDetonation}, {@code S2C.NukeSync}), а не сюда — в {@code NukeSync} за подрывом идут другие.
     */
    public static final StreamCodec<ByteBuf, Detonation> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public Detonation decode(ByteBuf b) {
            return new Detonation(ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), b.readDouble(), b.readDouble(), b.readBoolean(),
                    b.readLong(), b.readFloat(), b.readFloat(), b.readFloat(), b.readLong(), b.readFloat(), b.readBoolean(), OLD_WAVE);
        }

        @Override
        public void encode(ByteBuf b, Detonation d) {
            ByteBufCodecs.VAR_INT.encode(b, d.id);
            StreamCodecs.VEC3.encode(b, d.burst);
            b.writeDouble(d.groundY);
            b.writeDouble(d.yieldKt);
            b.writeBoolean(d.surface);
            b.writeLong(d.gameTime);
            b.writeFloat(d.windDir);
            b.writeFloat(d.windSpeed);
            b.writeFloat(d.visibility);
            b.writeLong(d.seed);
            b.writeFloat(d.scale);
            b.writeBoolean(d.fallout);
        }
    };

    public Detonation withWave(float wave) {
        return new Detonation(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, dayTime, fires);
    }

    public Detonation withFires(boolean fires) {
        return new Detonation(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, dayTime, fires);
    }

    public Detonation withDayTime(long dayTime) {
        return new Detonation(id, burst, groundY, yieldKt, surface, gameTime, windDir, windSpeed, visibility, seed, scale, fallout, wave, dayTime, fires);
    }

    /** Время суток подрыва известно ({@link #dayTime}). */
    public boolean hasDayTime() {
        return dayTime != NO_DAY_TIME;
    }

    /** Порог дальней зоны: 0.5 psi (стёкла). */
    public static final double FAR_KPA = BlastModel.kpa(0.5);
    /**
     * Край руин: стекло (порог 0.8 psi, разброс от 0.85) лопается от отражённого давления на грани к взрыву — от 0.68 psi
     * отражённого, то есть от ~0.34 psi падающего (у слабой волны отражённое — вдвое больше).
     */
    public static final double RUIN_EDGE_KPA = BlastModel.kpa(0.34);
    /** Слабее этого волна не бьёт и не пугает ({@code BlastFront}) и не поднимает пыль (край стены пыли): 0.3 psi. */
    public static final double HIT_KPA = BlastModel.kpa(0.3);

    // ---------------------------------------------------------------- масштаб

    /** Метры модели по блокам мира. */
    public double metres(double blocks) {
        return blocks / scale;
    }

    public double blocks(double metres) {
        return metres * scale;
    }

    /** Высота подрыва над землёй, м. */
    public double hobMetres() {
        return metres(burst.y - groundY);
    }

    // ---------------------------------------------------------------- волна

    /**
     * Давление волны в точке, кПа — по высоте подрыва над ней ({@link HobBlast}): дальность по земле — от оси подрыва,
     * высота — подрыв над точкой. Точка выше подрыва — как у земли на наклонной дальности (полусфера наземного).
     */
    public double overpressureKpa(Vec3 p) {
        double h = heightAbove(p), s = metres(p.distanceTo(burst));
        return BlastModel.kpa(HobBlast.psi(Math.sqrt(Math.max(0, s * s - h * h)), h, yieldKt));
    }

    public double psi(Vec3 p) {
        return BlastModel.psi(overpressureKpa(p));
    }

    /** Давление на земле эпицентра в {@code groundBlocks} блоках по земле от него, кПа. */
    public double groundOverpressureKpa(double groundBlocks) {
        return BlastModel.kpa(HobBlast.psi(metres(groundBlocks), groundHob(), yieldKt));
    }

    /** Дальность по земле эпицентра, где давление падает до {@code kpa}, блоки (0 — такого нет и под подрывом). */
    public double groundRange(double kpa) {
        return blocks(HobBlast.rangeFor(BlastModel.psi(kpa), groundHob(), yieldKt));
    }

    /** Наклонная дальность до точки земли эпицентра в {@code groundBlocks} блоках по земле от него, блоки. */
    public double slantOf(double groundBlocks) {
        return Math.hypot(groundBlocks, blocks(groundHob()));
    }

    /** Дальность по земле эпицентра до точки на наклонной дальности {@code slantBlocks}, блоки (ближе высоты подрыва — 0). */
    public double groundOf(double slantBlocks) {
        double h = blocks(groundHob());
        return Math.sqrt(Math.max(0, slantBlocks * slantBlocks - h * h));
    }

    /**
     * Скоростной напор за фронтом вдоль земли в точке, кПа: по давлению (Ренкин–Гюгонио), у правильного отражения под
     * подрывом — только его доля вдоль земли ({@link HobBlast#alongGround}).
     */
    public double dynamicPressureKpa(Vec3 p) {
        return BlastModel.dynamicPressureKpa(overpressureKpa(p)) * along(p);
    }

    /** Ветер сразу за фронтом вдоль земли в точке, м/с. */
    public double blastWind(Vec3 p) {
        return BlastModel.windSpeed(overpressureKpa(p)) * Math.sqrt(along(p));
    }

    /** Ветер сразу за фронтом вдоль земли эпицентра в {@code groundBlocks} блоках по земле от него, м/с. */
    public double groundWind(double groundBlocks) {
        double g = metres(groundBlocks);
        return BlastModel.windSpeed(groundOverpressureKpa(groundBlocks)) * Math.sqrt(HobBlast.alongGround(g, groundHob(), yieldKt));
    }

    /** Положительная фаза в точке, с модели: от 2 psi — по Броуду, ниже 1 psi — Кинни–Грэхем у слушателя, между — смесь. */
    public double positivePhaseSeconds(Vec3 p) {
        double h = heightAbove(p), s = metres(p.distanceTo(burst));
        return HobBlast.positivePhaseSeconds(Math.sqrt(Math.max(0, s * s - h * h)), h, yieldKt);
    }

    /** Положительная фаза на земле эпицентра в {@code groundBlocks} блоках по земле от него, с модели. */
    public double groundPositivePhaseSeconds(double groundBlocks) {
        return HobBlast.positivePhaseSeconds(metres(groundBlocks), groundHob(), yieldKt);
    }

    /** Положительная фаза на земле на наклонной дальности {@code slantBlocks} от подрыва, тики игры. */
    public double positivePhaseTicks(double slantBlocks) {
        return groundPositivePhaseSeconds(groundOf(slantBlocks)) * 20 * scale;
    }

    /**
     * Докуда (наклонная дальность, блоки) через {@code ticks} после подрыва уже прошла положительная фаза: где приход
     * фронта и фаза за ним вместе — не позже {@code ticks}.
     */
    public double phaseEndRadius(double ticks) {
        double lo = 0, hi = frontRadius(ticks);
        if (hi <= 0 || arrivalTicks(lo) + positivePhaseTicks(lo) > ticks) return 0;
        for (int i = 0; i < 40; i++) {
            double m = 0.5 * (lo + hi);
            if (arrivalTicks(m) + positivePhaseTicks(m) <= ticks) lo = m;
            else hi = m;
        }
        return lo;
    }

    private double along(Vec3 p) {
        double h = heightAbove(p), s = metres(p.distanceTo(burst));
        return HobBlast.alongGround(Math.sqrt(Math.max(0, s * s - h * h)), h, yieldKt);
    }

    /** Высота подрыва над точкой, м (не меньше 0). */
    private double heightAbove(Vec3 p) {
        return Math.max(0, metres(burst.y - p.y));
    }

    /** Высота подрыва над землёй эпицентра, м (не меньше 0). */
    private double groundHob() {
        return Math.max(0, hobMetres());
    }

    /**
     * С какой скоростью волна скоростным напором {@code qKpa} бросает сорванный обломок (куски снесённого у клиента),
     * блоков за тик, не больше 6. Тела бросает {@code BlastBody} по сопротивлению тела.
     */
    public static double throwSpeed(double qKpa) {
        return Math.min(6.0, 0.8 * Math.sqrt(qKpa));
    }

    /** Таблица прихода фронта (кэш на подрыв). */
    public ArrivalTable arrival() {
        return Caches.arrival(this);
    }

    /** Приход фронта в игре (кэш на подрыв): у эпицентра как у модели, дальше — со скоростью {@code wave} ({@link FrontProfile}). */
    public FrontProfile front() {
        return Caches.front(this);
    }

    /** Через сколько тиков после подрыва фронт дойдёт до точки на расстоянии {@code blocks}. */
    public double arrivalTicks(double blocks) {
        return front().arrivalTicks(blocks);
    }

    /** Световой импульс в игре, тиков после подрыва ({@link FireballModel#pulseSeconds} в масштабе удара, как у вспышки клиента). */
    public double pulseTicks() {
        return FireballModel.pulseSeconds(yieldKt) * 20 * scale;
    }

    /**
     * Когда место, куда за весь импульс приходит {@code q} кал/см², набирает {@code need}, тиков после подрыва: по
     * излученной доле импульса ({@link FireballModel#thermalFraction}). Не набирает за весь импульс — к его концу.
     * По нему встаёт след света на земле и пожары у сервера ({@code FlashScorch}) и их начало у клиента.
     */
    public double flashTicks(double q, double need) {
        return FireballModel.thermalTau(q <= need ? 1 : need / q) / 10 * pulseTicks();
    }

    /** Радиус фронта (блоки) через {@code ticks} после подрыва. */
    public double frontRadius(double ticks) {
        return front().radiusAt(ticks);
    }

    /** Докуда по земле что-то вообще меняется (блоки): стёкла или ожоги 1-й степени — что дальше. */
    public double radiusMax() {
        return Caches.radiusMax(this);
    }

    /**
     * Докуда волна ещё бьёт ({@link #HIT_KPA}) — наклонная дальность, блоки: у земли и над ней. Точка над землёй — точка
     * земли с подрывом ниже ({@link #overpressureKpa}), поэтому наибольшая дальность — по высотам от земли до подрыва:
     * у высокого подрыва давление над землёй доходит дальше, чем на ней, а под ним на земле его может не быть вовсе.
     */
    public double blastReach() {
        return Caches.blastReach(this);
    }

    /**
     * Докуда по земле идут руины (блоки): до {@link #RUIN_EDGE_KPA}, где отражённое давление ещё бьёт стекло, и не ближе
     * {@link #radiusMax}. Не дальше 1.5 {@link #radiusMax} (0.34 psi против 0.5) — докуда посчитан приход фронта.
     */
    public double ruinRadius() {
        return Caches.ruinRadius(this);
    }

    // ---------------------------------------------------------------- свет и шар

    /** Световой импульс в точке, кал/см² (без учёта тени — её проверяет вызывающий). */
    public double fluence(Vec3 p) {
        return ThermalModel.fluenceCalPerCm2(Math.max(1, metres(p.distanceTo(burst))), yieldKt, surface, visibility);
    }

    /** Наибольший радиус огненного шара, блоки. */
    public double fireballRadius() {
        return blocks(FireballModel.maxRadius(yieldKt, surface));
    }

    // ---------------------------------------------------------------- осадки

    public FalloutModel falloutModel() {
        return new FalloutModel(yieldKt, hobMetres(), windDir, windSpeed);
    }

    /** Чёрный дождь от пожаров этого подрыва (без пожаров — без дождя). */
    public BlackRainModel blackRainModel() {
        return Caches.blackRain(this);
    }

    /**
     * Есть ли у подрыва радиоактивные осадки (включены в миг подрыва): след наземного или то, что вымыл на землю
     * чёрный дождь.
     */
    public boolean hasFallout() {
        return fallout && (falloutModel().surfaceFraction() > 0 || blackRainModel().fireRadiusM() > 0);
    }

    /**
     * Мощность дозы осадков в точке, Р/ч: след наземного подрыва и вымытое чёрным дождём; всё по игровым часам.
     *
     * @param ticksSince игровые тики после подрыва (1000 тиков = 1 час)
     */
    public double falloutRate(double x, double z, long ticksSince) {
        if (!hasFallout()) return 0;
        double hours = ticksSince / 1000.0, mx = metres(x - burst.x), mz = metres(z - burst.z);
        double rain = blackRainModel().depositH1(mx, mz, ticksSince / 20.0);
        double decay = Math.pow(Math.max(hours, FalloutModel.MIN_DECAY_HOURS), -FalloutModel.DECAY_EXP);
        return falloutModel().doseRate(mx, mz, hours) + rain * decay;
    }

    /** Через сколько игровых тиков после подрыва след наземного подрыва ложится в точку. */
    public double falloutArrivalTicks(double x, double z) {
        return falloutModel().arrivalHours(metres(x - burst.x), metres(z - burst.z)) * 1000;
    }

    /** Идёт ли в точке чёрный дождь (одинаково на сервере и клиенте): часы — огненного шторма, секунды тиков. */
    public boolean blackRain(double x, double z, long ticksSince) {
        return blackRainModel().raining(metres(x - burst.x), metres(z - burst.z), ticksSince / 20.0);
    }

    /** Через сколько игровых тиков после подрыва чёрный дождь кончается везде. */
    public double blackRainEndTicks() {
        return blackRainModel().endSeconds() * 20;
    }

    /**
     * Под открытым небом ли точка (на неё падает дождь): над ней нет ничего, что держит движение, — ни крыши,
     * ни листвы, ни воды. Карта высот есть и на сервере, и на клиенте, поэтому ответ у них одинаковый.
     */
    public static boolean underOpenSky(net.minecraft.world.level.Level level, Vec3 pos) {
        return Terrain.height(level, net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.util.Mth.floor(pos.x), net.minecraft.util.Mth.floor(pos.z)) <= pos.y;
    }

    /** Кэши, которые дорого считать на каждый вызов (таблица прихода, наибольший радиус). */
    private static final class Caches {
        private static final java.util.Map<Detonation, ArrivalTable> ARRIVAL = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, FrontProfile> FRONT = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, Double> RADIUS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, Double> RUINS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, Double> REACH = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        /** Высот между землёй и подрывом в {@link #blastReach}. */
        private static final int REACH_HEIGHTS = 32;
        private static final java.util.Map<Detonation, BlackRainModel> RAIN = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        static double ruinRadius(Detonation d) {
            return RUINS.computeIfAbsent(d, x -> Math.min(x.radiusMax() * 1.5, Math.max(x.radiusMax(), x.groundRange(RUIN_EDGE_KPA))));
        }

        static ArrivalTable arrival(Detonation d) {
            return ARRIVAL.computeIfAbsent(d, x -> ArrivalTable.of(x.yieldKt, x.groundHob(), Math.max(1000, x.metres(tableRange(x)))));
        }

        static FrontProfile front(Detonation d) {
            // у эпицентра — как у модели: вспышка и шар не меняются; за стеной пыли (0.3 psi) — только звук
            return FRONT.computeIfAbsent(d, x -> FrontProfile.of(x.arrival(), x.scale, FrontProfile.slowFrom(x.fireballRadius()),
                    x.slantOf(x.groundRange(HIT_KPA)), tableRange(x), x.wave * FrontProfile.SOUND_SPEED));
        }

        /** Докуда таблицы прихода фронта, блоки: руины (до 1.5 {@link #radiusMax}) и всё, что волна ещё бьёт. */
        private static double tableRange(Detonation d) {
            return Math.max(d.radiusMax() * 1.5, d.blastReach());
        }

        static double blastReach(Detonation d) {
            return REACH.computeIfAbsent(d, x -> {
                double hob = x.groundHob(), psi = BlastModel.psi(HIT_KPA), m = 0;
                for (int i = 0; i <= REACH_HEIGHTS; i++) {
                    double h = hob * i / REACH_HEIGHTS, g = HobBlast.rangeFor(psi, h, x.yieldKt);
                    if (g > 0) m = Math.max(m, Math.hypot(g, h));
                }
                return x.blocks(m);
            });
        }

        static BlackRainModel blackRain(Detonation d) {
            return RAIN.computeIfAbsent(d, x -> {
                // зона пожаров — где свет поджигает (как у руин и огненного шторма клиента); сервер не поджигает — её нет
                double hob = Math.max(0, x.hobMetres());
                double slant = ThermalModel.rangeForFluence(ThermalModel.IGNITE, x.yieldKt, x.surface, x.visibility);
                double fire = x.fires ? Math.sqrt(Math.max(0, slant * slant - hob * hob)) : 0;
                return new BlackRainModel(x.yieldKt, fire, x.windDir, x.windSpeed, 1 - x.falloutModel().surfaceFraction());
            });
        }

        static double radiusMax(Detonation d) {
            return RADIUS.computeIfAbsent(d, x -> {
                double light = ThermalModel.rangeForFluence(ThermalModel.BURN_1, x.yieldKt, x.surface, x.visibility);
                return Math.max(x.groundRange(FAR_KPA), x.groundOf(x.blocks(light)));
            });
        }
    }
}
