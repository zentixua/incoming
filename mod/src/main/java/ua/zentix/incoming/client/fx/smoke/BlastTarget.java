package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

/**
 * Во что попал заряд — семейство взрыва и вещество цели. Выбирается из того, что клиент знает о месте: материал под точкой
 * ({@code S2C.Blast.material}), опора у грани удара из пакета ({@code S2C.Blast.Support}: плиту над пустотой находит пробой
 * сервера, {@link BreachModel}: путь в плите / радиус пробоя её материала ≤ 1 и за ней пустота под перекрытием) и высота над
 * поверхностью ({@code FarBlasts.overSurface}), — а не по тому, крыша это, дорога или земля (решение Артёма 23.10). Без
 * Minecraft: опору из пакета берёт {@code FarBlasts.target}.
 * <ul>
 * <li>{@link Family#SOIL} — рыхлое на опоре (почва, песок, гравий, снег, глина): воронка выбрасывает грунт завесой
 * ({@link Ejecta}).</li>
 * <li>{@link Family#HARD} — связное на опоре (скала, бетон, кладка, доски на земле): конуса грунта нет, только сколы и
 * пыль дробления цвета материала.</li>
 * <li>{@link Family#SLAB} — плита над пустотой, которую заряд пробивает (крыша, настил, перекрытие): обломки плиты вверх
 * и вниз, пыль пролома; грунта нет.</li>
 * <li>{@link Family#WATER} — вода ({@link WaterBlast}).</li>
 * <li>{@link Family#AIR} — разрыв выше, чем заряд роет воронку ({@link CloudModel#digs}): шар продуктов без грунта.</li>
 * </ul>
 * Числа вещества ({@link Matter}) — оценки с причиной у каждого: опытов по всем целям для зарядов 6,4–150 кг нет
 * (physics.md фронта F, 25.10). Внутри помещения (выход продуктов через проёмы) закон не верен — его здесь нет.
 *
 * @param family   семейство
 * @param material материал под точкой (пакет взрыва)
 * @param slab     толщина пробитой плиты по нормали грани, м (0 — не плита)
 * @param hole     радиус пролома плиты, м: √(R² − t²) у радиуса пробоя R (BreachGameTests: дыра √(R² − t²)); 0 — не плита
 * @param face     где заряд лежит на грани плиты (null — не плита или место не известно)
 * @param normal   нормаль пробитой грани внутрь плиты — ось дыры (у не плиты — вниз)
 * @param atFace   заряд у грани, которую нашёл сервер (опора пакета): разрушенное — у точки взрыва, а не на высоте рельефа
 *                 под ней, которую клиент вдали знает грубо (ревью 3 фронта F)
 */
public record BlastTarget(Family family, GroundMaterial material, double slab, double hole, @Nullable Vec3 face, Vec3 normal,
                          boolean atFace) {
    /** Вниз мира: ось у цели без плиты. */
    static final Vec3 DOWN = new Vec3(0, -1, 0);

    /** Семейство взрыва по цели. */
    public enum Family { SOIL, HARD, SLAB, WATER, AIR }

    /**
     * Вещество цели для картинки: насыпная плотность (масса разрушенного объёма), крупность выброса, которую видит свет, и
     * цвет сухой мелкой пыли. ⚠ Все — оценки по составу материала, а не замеры выброса этих зарядов:
     * <ul>
     * <li>плотность — справочная насыпная/кажущаяся: почва и песок 1600 (как {@link CloudModel#SOIL_DENSITY}), гравий
     * 1700, снег 400 (уплотнённый 300–500), бетон и камень 2500 (бетон 2400, гранит 2650 — одно число на класс),
     * глубинный сланец 2800, кладка 1900 (кирпич 1800–2000), древесина 500, обожжённая глина 1900;</li>
     * <li>крупность — диаметр, по площади которого свет видит баллистический выброс (ослабление 3Q/(2ρd), Q = 2, как
     * {@code DustModel}): песок — средний 0,25–0,5 мм (USDA, physics.md [S21]) → 0,35 мм; почва — 1 мм (песок и мелкие
     * агрегаты; комья 2–30 см свет почти не задерживают, у них своя доля {@code clods}); снег — зерно ~1 мм; глина —
     * комочки ~5 мм; гравий — 10 мм (гравий по USDA 2–75 мм);</li>
     * <li>доля комьев ({@code clods}) — что летит кусками и видно частицами вблизи, а не завесой: почва и глина ½
     * (связность держит комья — physics.md), гравий ⅘ (сам — куски), песок и снег 0 (сыпучее, «нет основания делать весь
     * выброс кубиками»);</li>
     * <li>цвет пыли — сухая мелкая фракция (на экране, толстым слоем): почва — как стена пыли ядерки, песок и снег — свои,
     * бетон и камень светло-серые (цемент и известняк светлые), сланец тёмно-серый, кладка — смесь раствора и кирпича,
     * древесина — охристые опилки, обожжённая глина — светлее блока (мелкая пыль светлее массива: зёрна мельче — больше
     * рассеяния).</li>
     * </ul>
     */
    public enum Matter {
        DIRT(1600, 1e-3, 2650, 0.5, FINES_LOOSE, true, 0.60f, 0.53f, 0.45f),
        SAND(1600, 3.5e-4, 2650, 0, FINES_LOOSE, true, 0.85f, 0.78f, 0.58f),
        SNOW(400, 1e-3, 917, 0, FINES_LOOSE, true, 0.95f, 0.95f, 0.97f),
        GRAVEL(1700, 1e-2, 2650, 0.8, FINES_LOOSE, true, 0.58f, 0.55f, 0.50f),
        CLAY(1900, 5e-3, 2600, 0.5, FINES_LOOSE, true, 0.70f, 0.52f, 0.42f),
        STONE(2500, 0, 2650, 1, FINES_CRUSHED, false, 0.72f, 0.71f, 0.68f),
        DEEPSLATE(2800, 0, 2800, 1, FINES_CRUSHED, false, 0.42f, 0.42f, 0.44f),
        BRICK(1900, 0, 2600, 1, FINES_CRUSHED, false, 0.66f, 0.60f, 0.55f),
        WOOD(500, 0, 500, 1, FINES_LOOSE, false, 0.70f, 0.58f, 0.40f),
        WATER(1000, 0, 1000, 0, 0, false, 0.80f, 0.85f, 0.90f);

        /** Насыпная плотность, кг/м³. */
        public final double density;
        /** Диаметр выброса, по площади которого его видит свет, м (0 — завесы нет: связное). */
        public final double grain;
        /** Плотность зёрна, кг/м³. */
        public final double grainDensity;
        /** Доля баллистического выброса, что летит комьями (частицы вблизи), а не зёрнами завесы. */
        public final double clods;
        /** Доля разрушенного, что уходит в воздух пылью мельче ~50 мкм ({@link #FINES_LOOSE}, {@link #FINES_CRUSHED}). */
        public final double fines;
        /** Рыхлое: воронка выбрасывает его завесой. */
        public final boolean loose;
        /** Цвет сухой мелкой пыли на экране. */
        public final float r, g, b;

        Matter(double density, double grain, double grainDensity, double clods, double fines, boolean loose, float r, float g, float b) {
            this.fines = fines;
            this.density = density;
            this.grain = grain;
            this.grainDensity = grainDensity;
            this.clods = clods;
            this.loose = loose;
            this.r = r;
            this.g = g;
            this.b = b;
        }

        /** Ослабление баллистического выброса на кг, м²/кг: 3Q/(2ρd), Q = 2 (геометрическая оптика, как {@code DustModel}). */
        public double extinction() {
            return grain > 0 ? 3 * 2 / (2 * grainDensity * grain) : 0;
        }

        /** Вещество материала пакета. */
        public static Matter of(GroundMaterial m) {
            return switch (m) {
                case DIRT -> DIRT;
                case SAND -> SAND;
                case SNOW -> SNOW;
                case GRAVEL -> GRAVEL;
                case TERRACOTTA -> CLAY;
                case STONE -> STONE;
                case DEEPSLATE -> DEEPSLATE;
                case BRICK -> BRICK;
                case WOOD -> WOOD;
                case WATER -> WATER;
            };
        }
    }

    /**
     * Доля разрушенного рыхлого (и досок), что уходит в воздух пылью мельче ~50 мкм — прежняя оценка
     * {@link CloudModel#DUST_LOFTED}: у грунта мелочь большей частью остаётся в комьях и падает с ними.
     */
    static final double FINES_LOOSE = CloudModel.DUST_LOFTED;
    /**
     * То же у связного (скала, бетон, кладка): чаша контактного заряда там — зона дробления и откола (переднее дробление
     * плиты, physics.md [S13]; зона дробления у заряда в породе — 2–4 его радиуса, Esen, Onederra, Bilgin, «Modelling the
     * size of the crushed zone around a blasthole», Int. J. Rock Mech. Min. Sci. 40, 2003: у 50 кг ТНТ — 0,4–0,8 м, порядка
     * всей чаши бетона), и раздробленное там размолото, а не выброшено зёрнами. ⚠ Доля — оценка: замеров мелочи контактного
     * взрыва по бетону в источниках нет; впятеро больше грунта, а не «как грунт» и не «никакой пыли» (physics.md).
     */
    static final double FINES_CRUSHED = 0.1;

    /** Удар в голый рыхлый грунт — как у взрыва без сведений о цели (проверки, прежние вызовы). */
    public static final BlastTarget SOIL = new BlastTarget(Family.SOIL, GroundMaterial.DIRT, 0, 0, null, DOWN, false);

    /** Вещество цели. */
    public Matter matter() {
        return Matter.of(material);
    }

    /**
     * Семейство: вода — вода; выше, чем заряд роет воронку, — разрыв в воздухе; плита над пустотой, которую заряд
     * пробивает ({@code slab} > 0), — плита; иначе — по связности того, во что попал: рыхлое — грунт, связное — твёрдое.
     *
     * @param loose рыхлое ли то, во что попал (группа пробоя «земля», FM 5-250 табл. B-3)
     * @param slab  толщина пробитой плиты над пустотой по нормали грани, м (0 — опора сплошная)
     * @param digs  заряд роет воронку на своей высоте ({@link CloudModel#digs})
     */
    public static Family family(GroundMaterial material, boolean loose, double slab, boolean digs) {
        if (material.isWater()) return Family.WATER;
        if (!digs) return Family.AIR;
        if (slab > 0) return Family.SLAB;
        return loose ? Family.SOIL : Family.HARD;
    }

    /** Цель по материалу, без мира (чанка у клиента нет): опора — сплошная, связность — по веществу материала. */
    public static BlastTarget of(GroundMaterial material, double tntKg, double hobM) {
        return new BlastTarget(family(material, Matter.of(material).loose, 0, CloudModel.digs(tntKg, hobM)), material, 0, 0, null, DOWN, false);
    }

    /**
     * Цель с опорой из мира: {@code loose} — связность того, во что попал; {@code slab} — толщина плиты до пустоты по оси
     * (0 — не дошли до пустоты в пределах пробоя), {@code breach} — радиус пробоя её материала у этого заряда, м.
     */
    public static BlastTarget of(GroundMaterial material, boolean loose, double slab, double breach) {
        return of(material, loose, slab, breach, null, DOWN);
    }

    /**
     * То же с местом заряда на грани {@code face} и нормалью грани внутрь плиты {@code normal} (опора пакета). Опора — это
     * контакт, который нашёл сервер (заряд у грани, {@code SlabBreach.locate}): высота над рельефом вокруг здесь не решает
     * — вдали клиент знает её грубо (плитка {@code FarTerrain} без дома даёт крышу в 5 м над землёй, и пролом стал бы
     * разрывом в воздухе без массы и пыли; ревью 2 фронта F). Разрыв в воздухе — только без опоры.
     */
    public static BlastTarget of(GroundMaterial material, boolean loose, double slab, double breach, @Nullable Vec3 face, Vec3 normal) {
        Family f = family(material, loose, slab, true);
        boolean plate = f == Family.SLAB;
        double hole = plate ? Math.sqrt(Math.max(0, breach * breach - slab * slab)) : 0;
        return new BlastTarget(f, material, plate ? slab : 0, hole, plate ? face : null, plate ? normal.normalize() : DOWN, true);
    }

    /** Середина дыры на тыльной грани плиты (null — не плита или место заряда не известно). */
    @Nullable
    public Vec3 exit() {
        return face == null ? null : face.add(normal.scale(slab));
    }

    /** Разлёт тыльного откола около оси дыры, градусы (⚠ оценка). */
    public static final double SPALL_CONE_DEG = 30;

    /**
     * {@code n} кусков тыльного откола: из дыры на тыльной грани ({@link #exit}: круг радиуса {@link #hole} поперёк оси
     * дыры, кусок — в 0,3 м за гранью) по нормали пробитой грани {@link #normal}, а не вниз мира и не курсом боевой части:
     * плиту толкает давление продуктов, а оно по нормали (сэндвич Гёрни одномерен; импульс самой боевой части — у шахеда
     * ~200 кг × 50 м/с против пробки ~3,5 т × 29 м/с в метре досок — на порядок меньше, ⚠ им пренебрегаем), и дыра
     * сервера — около нормали. Скорость — пробки по Гёрни ({@link #plugSpeed}) ±30 % (⚠ разброс — подача), направление —
     * в конусе ±{@value #SPALL_CONE_DEG}°, наружу от оси с той же стороны, где кусок в дыре. Тяжесть — у частицы. Не плита —
     * ничего.
     */
    public void spall(double tntKg, int n, java.util.function.DoubleSupplier random, Ejecta.Pieces out) {
        Vec3 exit = exit();
        if (exit == null || n <= 0) return;
        double v = plugSpeed(tntKg), cone = Math.toRadians(SPALL_CONE_DEG);
        Vec3[] across = Ejecta.across(normal);
        for (int i = 0; i < n; i++) {
            double a = random.getAsDouble() * Math.PI * 2, tilt = cone * Math.sqrt(random.getAsDouble()), r = hole * Math.sqrt(random.getAsDouble());
            Vec3 side = across[0].scale(Math.cos(a)).add(across[1].scale(Math.sin(a)));
            double speed = v * (0.7 + 0.6 * random.getAsDouble());
            out.piece(exit.add(side.scale(r)).add(normal.scale(0.3)), normal.scale(Math.cos(tilt) * speed).add(side.scale(Math.sin(tilt) * speed)));
        }
    }

    /**
     * Разрушенный объём цели, м³: у плиты — пролом (круг радиуса {@link #hole} толщины {@link #slab}); иначе — чаша закона
     * воронки по грунту материала ({@link CraterModel#chargeVolume}: тот же закон, что роет сервер; ⚠ у связного сервер
     * снимает метры и хранит остаток глубины, {@code SurfaceDamage}, — объём тот же); у воды и в воздухе — 0.
     */
    public double volume(double tntKg) {
        return switch (family) {
            case SLAB -> Math.PI * hole * hole * slab;
            case SOIL, HARD -> CraterModel.chargeVolume(tntKg, material.soil());
            case WATER, AIR -> 0;
        };
    }

    /**
     * Постоянная Гёрни ТНТ √(2E), м/с: 2,44 км/с (Cooper P. W., «Explosives Engineering», Wiley-VCH 1996, табл. 27.1;
     * Kennedy J. E., «Gurney energy of explosives», Sandia SC-RR-70-790, 1970).
     */
    static final double GURNEY_TNT = 2440;

    /**
     * Скорость тыльного откола пробитой плиты, м/с: пробка пролома массы M ({@link #mass}) под зарядом C — открытый
     * «сэндвич» Гёрни (Kennedy 1970): v = √(2E)·[((1 + 2M/C)³ + 1)/(6(1 + M/C)) + M/C]^(−½). ⚠ Формула — для слоя ВВ,
     * лежащего на всей плите; у заряда, что меньше пролома, вниз толкает его часть, и настоящий откол медленнее (насколько —
     * в источниках нет). У шахеда (50 кг) в метре досок — ~29 м/с, в метре камня — ~6 м/с. Не плита — 0.
     */
    public double plugSpeed(double tntKg) {
        double m = mass(tntKg);
        if (family != Family.SLAB || m <= 0 || tntKg <= 0) return 0;
        double k = m / tntKg, a = 1 + 2 * k;
        return GURNEY_TNT / Math.sqrt((a * a * a + 1) / (6 * (1 + k)) + k);
    }

    /** Пыль разрушенного, что уходит в воздух, кг: {@link #mass} × доля мелочи вещества ({@link Matter#fines}). */
    public double dust(double tntKg) {
        return mass(tntKg) * matter().fines;
    }

    /** Баллистический выброс разрушенного, кг: {@link #mass} без пыли, что ушла в воздух. */
    public double ballistic(double tntKg) {
        return mass(tntKg) * (1 - matter().fines);
    }

    /**
     * Баллистический выброс кусками — частицами вблизи, кг: у грунта — доля комьев вещества ({@link Matter#clods}), остальное —
     * зёрна завесы ({@link Ejecta#grains}); у твёрдого и плиты завесы нет — весь. Куски + зёрна завесы + пыль = {@link #mass}.
     */
    public double clods(double tntKg) {
        return family == Family.SOIL ? ballistic(tntKg) * matter().clods : ballistic(tntKg);
    }

    /** То же объёмом на месте (насыпной плотностью), м³: столько чаши или пролома летит кусками. */
    public double clodVolume(double tntKg) {
        return clods(tntKg) / matter().density;
    }

    /** Масса разрушенного вещества цели, кг: {@link #volume} × насыпная плотность. */
    public double mass(double tntKg) {
        return volume(tntKg) * matter().density;
    }
}
