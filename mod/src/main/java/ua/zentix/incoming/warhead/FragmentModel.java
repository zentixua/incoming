package ua.zentix.incoming.warhead;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.SplittableRandom;

/**
 * Осколки корпуса боевой части ({@code formulas.md} §7). Без Minecraft, общий для сервера ({@link FragmentStrike}: урон,
 * стёкла, выбоины) и клиента (пыль и искры там, куда осколки попали): один закон и одно зерно у обоих.
 * <ul>
 *   <li><b>Корпус</b> — цилиндр ВВ в стальной оболочке наружного диаметра {@link Article.Charge#diameter}: из масс ВВ и
 *   корпуса и их плотностей выходят толщина стенки и длина (оценка формы: настоящие БЧ — с днищем, конусом и готовыми
 *   насечками).</li>
 *   <li><b>Скорость</b> — Гарни для цилиндра: V₀ = √(2E)·√(β/(1 + β/2)), β = C/M (Cooper, «Explosives Engineering»,
 *   1996, гл. 27). Постоянная Гарни тротила √(2E) = 2440 м/с, состава B — 2700 м/с (Cooper, табл. 27.1; в таблице
 *   Ahmed et al. — 2499 и 2774); у прочих ВВ — между ними по относительной силе ВВ (паспорт: ТНТ / масса ВВ; состав B —
 *   1,33): оценка, точнее — только по составу ВВ. При больших β (у шахеда 4) Гарни — верхняя оценка: тонкий корпус
 *   рвётся раньше, чем продукты разгонят его до конца.</li>
 *   <li><b>Массы</b> — Мотт: N(&gt;m) = N₀·exp(−√(m/μ)), N₀ = M/(2μ), средняя масса 2μ; √μ = B·t<sup>5/6</sup>·d<sup>1/3</sup>·(1
 *   + t/d), t и d — толщина стенки и внутренний диаметр, м; B — 3,31 кг<sup>½</sup>·м<sup>−7/6</sup> у тротила, 2,70 у
 *   состава B (Mott 1943; таблица — Ahmed et al., Eng. Math. Lett. 2021), между ними — так же по силе ВВ.</li>
 *   <li><b>Направления</b> — пояс поперёк оси: нормаль к стенке, наклонённая углом Тейлора θ = V₀/(2·D) (D — скорость
 *   детонации: тротил 6900 м/с, состав B 7980) в сторону бега детонации — от носа (взрыватель удара) к хвосту, разброс
 *   нормалью ±{@link #BELT_SIGMA} (оценка: торцы и неровный фронт). К скорости осколка прибавляется скорость самой БЧ
 *   у удара по паспорту ({@link Article.Impact#speed}, настоящая при любом масштабе): пояс уходит вперёд по курсу.</li>
 *   <li><b>Торможение</b> — v(x) = V₀·e<sup>−x/L</sup>, L = 2m/(ρ·C<sub>d</sub>·A), A = {@link #AREA_SHAPE}·(m/ρ<sub>ст</sub>)<sup>2/3</sup>
 *   — средняя площадь кувыркающегося тела, четверть его поверхности (теорема Коши; брусок 1:2:4 — 1,75; куб — 1,5);
 *   C<sub>d</sub> = 1,21 у неправильных осколков на сверхзвуке (Ahmed et al.); ниже звука настоящий C<sub>d</sub> меньше,
 *   и медленные осколки летят чуть дальше. Тяжесть не в счёт: опасный осколок долетает за доли секунды, опускаясь
 *   меньше чем на метр.</li>
 *   <li><b>Нагрев</b>: сталь корпуса греет ударная волна детонации (остаточный нагрев за 20–30 ГПа — 100–200 K) и
 *   пластическое растяжение до разрыва (0,5–1 ГПа × деформация ~0,6 — 80–170 K): осколок — ~500–700 K, ниже точки
 *   Дрейпера (798 K), и сам в полёте не светится (оценка; накалены лишь тонкие поверхности разрыва — микросекунды).
 *   Видно, куда осколки попали: искры о камень и металл, пыль из грунта.</li>
 * </ul>
 * Урон — по энергии удара: {@link #injury}, {@link #death}.
 *
 * @param count       осколков N₀
 * @param mu          масса Мотта μ, кг (средняя — 2μ)
 * @param speed       начальная скорость Гарни V₀ относительно БЧ, м/с
 * @param taylor      угол Тейлора, рад
 * @param impactSpeed скорость БЧ у удара, м/с
 * @param wall        толщина стенки, м
 * @param inner       внутренний диаметр, м
 * @param length      длина цилиндра ВВ, м
 */
public record FragmentModel(double count, double mu, double speed, double taylor, double impactSpeed, double wall, double inner,
                            double length) {
    /** Плотность стали корпуса, кг/м³. */
    public static final double STEEL = 7850;
    /** Средняя площадь кувыркающегося осколка — столько (m/ρ)<sup>2/3</sup>: брусок 1:2:4 (оценка формы). */
    public static final double AREA_SHAPE = 1.75;
    /** Сопротивление неправильного осколка на сверхзвуке (Ahmed et al. 2021). */
    public static final double CD = 1.21;
    /** Плотность воздуха у земли, кг/м³ (МСА). */
    public static final double AIR = 1.225;
    /** Разброс направлений поперёк пояса (σ), рад: оценка, у цилиндрических БЧ пояс — 20–30° в ширину. */
    public static final double BELT_SIGMA = Math.toRadians(10);

    /** Постоянные Гарни √(2E), м/с: тротил и состав B (Cooper 1996, табл. 27.1). */
    static final double GURNEY_TNT = 2440, GURNEY_COMP_B = 2700;
    /** Постоянные Мотта B, кг<sup>½</sup>·м<sup>−7/6</sup> (Mott 1943; Ahmed et al. 2021). */
    static final double MOTT_TNT = 3.31, MOTT_COMP_B = 2.70;
    /** Скорость детонации, м/с: литой тротил и состав B. */
    static final double DETONATION_TNT = 6900, DETONATION_COMP_B = 7980;
    /** Плотность ВВ, кг/м³: литой тротил 1,65, состав B 1,72 г/см³. */
    static final double DENSITY_TNT = 1650, DENSITY_COMP_B = 1720;
    /** Относительная сила состава B (RDX/TNT 60/40) по тротилу. */
    static final double RE_COMP_B = 1.33;

    /** Осколок слабее этого никого не ранит и стекла не бьёт — дальше его не ведут, Дж. */
    public static final double STOP_J = 3;
    /** Стекло окна: бьёт осколок от столька, Дж (оценка: стальной шарик в несколько джоулей бьёт отожжённое стекло 4 мм). */
    public static final double GLASS_J = 3;
    /**
     * Опасный осколок — от 79 Дж (58 фут·фунт, DoD 6055.09-M, DDESB): медиана тяжёлого ранения при попадании (оценка:
     * у DDESB — порог, разброс β 0,5 — оценка).
     */
    public static final double HAZARD_J = 79, HAZARD_BETA = 0.5;
    /**
     * Гибель от одного попадания: доля жизненно важного в силуэте (голова, грудь, живот, ~45 %, оценка) × вероятность,
     * что осколок проходит вглубь (медиана 150 Дж, β 0,5 — оценка).
     */
    public static final double VITAL_SHARE = 0.45, DEEP_J = 150, DEEP_BETA = 0.5;
    /**
     * Выбоина в кладке: осколок выбивает ямку глубиной 1 см и больше, когда его энергия на площадь больше прочности
     * кладки на сжатие × 1 см (50 МПа: кирпич 10–50, камень 100–200 — оценка), Дж/м².
     */
    public static final double CHIP_J_PER_M2 = 50e6 * 0.01;
    /** Больше осколков на взрыв не ведут лучами: остальные — вес ({@link #weight}). */
    public static final int MAX_TRACED = 8192;
    /** Дальше осколок не ведут, м. */
    public static final double MAX_RANGE = 1000;

    /** Осколки боевой части оружия, которое бьёт; null — нет наземного взрыва или корпуса (бомба под землёй, ядерка). */
    public static @Nullable FragmentModel of(WeaponSpec spec) {
        if (spec.charge() <= 0) return null;
        Article a = spec.striker().article();
        Article.Charge w = a.warhead();
        if (w == null || w.caseMass() <= 0 || w.explosive() <= 0) return null;
        return of(w, a.impact() == null ? 0 : a.impact().speed());
    }

    /** Осколки заряда {@code w} при скорости удара {@code impactSpeed}, м/с. */
    public static FragmentModel of(Article.Charge w, double impactSpeed) {
        // доля пути от тротила к составу B по относительной силе ВВ
        double k = Math.clamp((w.tnt() / w.explosive() - 1) / (RE_COMP_B - 1), 0, 1);
        double gurney = lerp(k, GURNEY_TNT, GURNEY_COMP_B), mott = lerp(k, MOTT_TNT, MOTT_COMP_B);
        double detonation = lerp(k, DETONATION_TNT, DETONATION_COMP_B), rhoHe = lerp(k, DENSITY_TNT, DENSITY_COMP_B);
        // цилиндр: объём корпуса к объёму ВВ = (R/r)² − 1
        double vHe = w.explosive() / rhoHe, vCase = w.caseMass() / STEEL;
        double outer = w.diameter() / 2, inner = outer / Math.sqrt(1 + vCase / vHe);
        double t = outer - inner, d = 2 * inner;
        double length = vHe / (Math.PI * inner * inner);
        double beta = w.explosive() / w.caseMass();
        double v0 = gurney * Math.sqrt(beta / (1 + beta / 2));
        double sqrtMu = mott * Math.pow(t, 5.0 / 6) * Math.cbrt(d) * (1 + t / d);
        double mu = sqrtMu * sqrtMu;
        return new FragmentModel(w.caseMass() / (2 * mu), mu, v0, v0 / (2 * detonation), impactSpeed, t, d, length);
    }

    private static double lerp(double k, double a, double b) {
        return a + (b - a) * k;
    }

    /** Средняя масса осколка, кг. */
    public double meanMass() {
        return 2 * mu;
    }

    /** Доля осколков тяжелее {@code m} кг. */
    public double shareAbove(double m) {
        return Math.exp(-Math.sqrt(Math.max(0, m) / mu));
    }

    /** Средняя площадь осколка массой {@code m}, м². */
    public static double area(double m) {
        return AREA_SHAPE * Math.pow(m / STEEL, 2.0 / 3);
    }

    /** Путь, на котором скорость осколка массой {@code m} падает в e раз, м. */
    public static double dragLength(double m) {
        return 2 * m / (AIR * CD * area(m));
    }

    /** Скорость осколка массой {@code m}, начавшего с {@code v}, через {@code s} м, м/с. */
    public static double speedAt(double v, double m, double s) {
        return v * Math.exp(-s / dragLength(m));
    }

    /** Время, за которое осколок пролетает {@code s} м, с: v = V·e^(−x/L) ⇒ t = L/V·(e^(s/L) − 1). */
    public static double timeTo(double v, double m, double s) {
        double l = dragLength(m);
        return l / v * Math.expm1(s / l);
    }

    /** Путь, на котором энергия осколка падает до {@code joules}, м (0 — уже ниже). */
    public static double rangeTo(double v, double m, double joules) {
        double ve = Math.sqrt(2 * joules / m);
        return v <= ve ? 0 : dragLength(m) * Math.log(v / ve);
    }

    /** Энергия осколка, Дж. */
    public static double energy(double m, double v) {
        return 0.5 * m * v * v;
    }

    /** Выбьет ли осколок ямку в кладке ({@link #CHIP_J_PER_M2}). */
    public static boolean chips(double m, double v) {
        return energy(m, v) / area(m) >= CHIP_J_PER_M2;
    }

    /** Вероятность тяжёлого ранения (или гибели) от попадания с энергией {@code joules}. */
    public static double injury(double joules) {
        return Fragility.probability(joules, HAZARD_J, HAZARD_BETA);
    }

    /** Вероятность гибели от попадания с энергией {@code joules}. */
    public static double death(double joules) {
        return VITAL_SHARE * Fragility.probability(joules, DEEP_J, DEEP_BETA);
    }

    /** Сколько осколков ведут лучами. */
    public int traced() {
        return (int) Math.min(MAX_TRACED, Math.ceil(count));
    }

    /** Скольких настоящих осколков стоит один луч (≥ 1). */
    public double weight() {
        return count / traced();
    }

    /**
     * Докуда долетает самый тяжёлый осколок (в среднем один на взрыв: доля 1/N₀ по Мотту) с энергией {@link #STOP_J}, м;
     * дальше осколков нет.
     */
    public double reach() {
        double lnN = Math.log(Math.max(2, count));
        double m = mu * lnN * lnN;
        return Math.min(MAX_RANGE, rangeTo(speed + impactSpeed, m, STOP_J));
    }

    /**
     * Опасная плотность DDESB: один осколок от {@link #HAZARD_J} на 600 кв. футов (55,7 м²) — по ней считается
     * опасное расстояние осколков (DoD 6055.09-M).
     */
    public static final double HAZARD_DENSITY = 1 / 55.7;

    /**
     * Осколков с энергией от {@code joules} на м² поперёк луча посреди пояса в {@code r} м (без скорости самой БЧ):
     * N·P(m ≥ m*)·g(0)/(2π·r²), g(0) — плотность пояса по углу в его середине.
     */
    public double peakDensity(double r, double joules) {
        double peak = 1 / (BELT_SIGMA * Math.sqrt(2 * Math.PI)) / (2 * Math.PI);
        return count * shareAbove(lightest(r, joules)) * peak / (r * r);
    }

    /** Самый лёгкий осколок, у которого на {@code r} м энергия не меньше {@code joules} (энергия растёт с массой), кг. */
    double lightest(double r, double joules) {
        double lo = 1e-9, hi = 1e4 * mu;
        if (energy(hi, speedAt(speed, hi, r)) < joules) return hi;
        for (int i = 0; i < 80; i++) {
            double m = Math.sqrt(lo * hi);
            if (energy(m, speedAt(speed, m, r)) >= joules) hi = m;
            else lo = m;
        }
        return hi;
    }

    /** Опасное расстояние осколков по DDESB (плотность {@link #HAZARD_DENSITY} посреди пояса), м. */
    public double hazardDistance() {
        double lo = 1, hi = MAX_RANGE;
        if (peakDensity(hi, HAZARD_J) >= HAZARD_DENSITY) return hi;
        for (int i = 0; i < 60; i++) {
            double r = (lo + hi) / 2;
            if (peakDensity(r, HAZARD_J) >= HAZARD_DENSITY) lo = r;
            else hi = r;
        }
        return lo;
    }

    /**
     * Осколок {@code i} взрыва с зерном {@code seed} и осью БЧ {@code axis} (единичный вектор, нос по курсу): направление
     * (единичное), масса, скорость в мире. Один и тот же у сервера и клиента.
     */
    public Fragment fragment(long seed, int i, Vec3 axis) {
        SplittableRandom r = new SplittableRandom(seed ^ (0x9E3779B97F4A7C15L * (i + 1)));
        double u = 1 - r.nextDouble();
        double ln = -Math.log(u);
        double m = mu * ln * ln;
        // нормаль к стенке, наклонённая к хвосту углом Тейлора, с разбросом пояса
        double polar = Math.PI / 2 + taylor + BELT_SIGMA * gaussian(r);
        double az = r.nextDouble() * 2 * Math.PI;
        Vec3 a = axis.lengthSqr() < 1e-12 ? new Vec3(0, -1, 0) : axis.normalize();
        Vec3 p = Math.abs(a.y) < 0.9 ? a.cross(new Vec3(0, 1, 0)).normalize() : a.cross(new Vec3(1, 0, 0)).normalize();
        Vec3 q = a.cross(p);
        Vec3 side = p.scale(Math.cos(az)).add(q.scale(Math.sin(az)));
        Vec3 v = a.scale(speed * Math.cos(polar) + impactSpeed).add(side.scale(speed * Math.sin(polar)));
        double s = v.length();
        return new Fragment(v.scale(1 / s), m, s);
    }

    private static double gaussian(SplittableRandom r) {
        double u1 = 1 - r.nextDouble(), u2 = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /**
     * Осколок.
     *
     * @param dir   направление полёта (единичное)
     * @param mass  масса, кг
     * @param speed скорость в мире, м/с
     */
    public record Fragment(Vec3 dir, double mass, double speed) {
        /** Путь до {@link #STOP_J}, м (не дальше {@link #MAX_RANGE}). */
        public double range() {
            return Math.min(MAX_RANGE, rangeTo(speed, mass, STOP_J));
        }

        public double speedAt(double s) {
            return FragmentModel.speedAt(speed, mass, s);
        }

        public double energyAt(double s) {
            return energy(mass, speedAt(s));
        }
    }
}
