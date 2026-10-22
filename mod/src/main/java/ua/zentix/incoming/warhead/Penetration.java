package ua.zentix.incoming.warhead;

import ua.zentix.incoming.strike.Article;

/**
 * Пробитие грунта, скалы и бетона телом бомбы — уравнения Янга (C. W. Young, «Penetration Equations», Sandia
 * SAND97-2426, 1997, прил. A; сверено по первоисточнику), без Minecraft. Метры, килограммы, секунды.
 * <p>
 * Путь в однородной среде: D = 0,000018·S·N·(m/A)^0,7·(V − 30,5) при V ≥ 61 м/с, иначе
 * D = 0,0008·S·N·(m/A)^0,7·ln(1 + 2,15·10⁻⁴·V²); S — проницаемость среды (чем больше, тем мягче), N — нос, m/A — масса
 * на площадь сечения. Поправок лёгкого тела нет: у бомбы в 13,6 т они ниже порогов (27 кг в грунте, 182 кг в скале).
 * <p>
 * Слои (разд. 6): в слое по пути длиной T скорость на выходе V·√(1 − T/D) — средняя перегрузка в слое постоянна; следующий
 * слой — по этой скорости. Грунт сразу за бетоном (под плитой и между плитами) — с S вдвое меньше на первые
 * {@value #SOFT_UNDER_CONCRETE} калибров пути. Точность (по Янгу): уравнение ~15 %, с S бетона 15–20 %, у скалы — от 20 %
 * до двух раз: глубина в камне — оценка.
 * <p>
 * Вода и другие жидкости — не по Янгу: сопротивление жидкости ½·ρ·v²·CdA гасит скорость как e^(−ρ·CdA·x/(2m)).
 */
public final class Penetration {
    /** Граница двух ветвей уравнения, м/с. */
    static final double V_SPLIT = 61;
    /** Грунт за бетоном — с S/2 на столько калибров пути (Янг, разд. 6). */
    static final double SOFT_UNDER_CONCRETE = 6;
    /**
     * Медленнее этого тело в жидкости считается вставшим, м/с (оценка): вязкое сопротивление и тяжесть в законе
     * квадратичного сопротивления не учтены, иначе бомба шла бы в воде бесконечно, замедляясь.
     */
    static final double FLUID_REST = 1;

    private Penetration() {}

    /** Чем блок встречает тело. */
    public enum Kind {
        /** Грунт: земля, песок, гравий, глина, снег, рыхлое и мягкое. */
        SOIL,
        /** Скала и камень. */
        ROCK,
        /** Бетон и кладка (после него грунт — с S/2). */
        CONCRETE,
        /** Жидкость: сопротивление по плотности, не по Янгу. */
        FLUID,
        /** Непробиваемое (коренная порода мира, служебные блоки): тело встаёт. */
        STOP
    }

    /**
     * Тело.
     *
     * @param mass     масса, кг
     * @param diameter диаметр корпуса, м
     * @param length   длина, м
     * @param nose     коэффициент носа N ({@link #ogive})
     * @param cda      площадь сопротивления в воздухе C<sub>d</sub>·S, м² (её же — в жидкости)
     */
    public record Body(double mass, double diameter, double length, double nose, double cda) {
        /** Площадь сечения, м². */
        public double area() {
            return Math.PI * diameter * diameter / 4;
        }

        /** m/A, кг/м². */
        public double massPerArea() {
            return mass / area();
        }
    }

    /** Тело изделия по паспорту: масса без топлива, диаметр, длина, нос, CdA; изделие без пробивателя — null. */
    public static Body body(Article a) {
        if (a.penetrator() == null) throw new IllegalArgumentException("не пробиватель");
        return new Body(a.dryMass(), a.diameter(), a.length(), ogive(a.penetrator().crh()), a.cda());
    }

    /** Нос-оживало по радиусу дуги в калибрах (CRH): N = 0,18·√(CRH − 0,25) + 0,56. */
    public static double ogive(double crh) {
        return 0.18 * Math.sqrt(crh - 0.25) + 0.56;
    }

    /** Путь тела {@code body} со скоростью {@code v} м/с в однородной среде проницаемости {@code s}, м. */
    public static double depth(Body body, double s, double v) {
        if (v <= 0) return 0;
        double k = s * body.nose() * Math.pow(body.massPerArea(), 0.7);
        return v >= V_SPLIT ? 0.000018 * k * (v - 30.5) : 0.0008 * k * Math.log(1 + 2.15e-4 * v * v);
    }

    /**
     * Ход тела сквозь среду, по кускам пути: соседние куски одной среды — один слой Янга (как бы его ни резали, путь в
     * однородной среде — D), смена среды — новый слой со скоростью на выходе из прежнего.
     */
    public static final class Run {
        private final Body body;
        private double v;
        private Kind kind;
        private double s = Double.NaN;
        /** Скорость на входе в слой, его путь по Янгу и сколько пройдено в нём, м. */
        private double vIn, dIn, along;
        /** Сколько пути грунта ещё с S/2 за бетоном, м. */
        private double soft;
        /** Последняя твёрдая среда или жидкость на пути (пустоты не в счёт). */
        private Kind lastSolid;
        private boolean stopped;

        /** Тело {@code body} входит в среду со скоростью {@code v} м/с. */
        public Run(Body body, double v) {
            this.body = body;
            this.v = v;
        }

        /** Скорость сейчас, м/с. */
        public double speed() {
            return v;
        }

        /** Тело встало. */
        public boolean stopped() {
            return stopped;
        }

        /**
         * Пройти {@code len} м средой {@code kind} проницаемости {@code s} ({@code s} у жидкости — её плотность, кг/м³).
         * Возвращает, сколько пройдено: меньше {@code len} — тело встало в этом куске.
         */
        public double through(Kind kind, double s, double len) {
            if (stopped || len <= 0) return 0;
            if (kind == Kind.STOP) {
                stop();
                return 0;
            }
            // грунт сразу за бетоном (и за пустотой между плитами) — с S/2 на первые калибры пути
            if (kind == Kind.SOIL && lastSolid == Kind.CONCRETE) soft = SOFT_UNDER_CONCRETE * body.diameter();
            else if (kind != Kind.SOIL) soft = 0;
            lastSolid = kind;
            if (kind == Kind.FLUID) return fluid(s, len);
            if (kind == Kind.SOIL && soft > 0) {
                double first = Math.min(len, soft);
                double went = layer(kind, s / 2, first);
                soft -= went;
                if (went < first || first >= len) return went;
                return went + layer(kind, s, len - first);
            }
            return layer(kind, s, len);
        }

        /** Пустота (воздух, проём): сопротивления нет, слой кончился — за ней тело входит в среду заново. */
        public void gap() {
            kind = null;
            s = Double.NaN;
        }

        private double layer(Kind kind, double s, double len) {
            if (kind != this.kind || s != this.s) {
                this.kind = kind;
                this.s = s;
                vIn = v;
                dIn = depth(body, s, v);
                along = 0;
            }
            double left = dIn - along;
            if (len >= left) {
                stop();
                return Math.max(0, left);
            }
            along += len;
            v = vIn * Math.sqrt(1 - along / dIn);
            return len;
        }

        private double fluid(double density, double len) {
            kind = Kind.FLUID;
            s = density;
            // v(x) = v₀·e^(−ρ·CdA·x/(2m)); тело встаёт на FLUID_REST
            double k = density * body.cda() / (2 * body.mass());
            double rest = Math.log(v / FLUID_REST) / k;
            if (len >= rest) {
                stop();
                return Math.max(0, rest);
            }
            v *= Math.exp(-k * len);
            return len;
        }

        private void stop() {
            stopped = true;
            v = 0;
        }
    }
}
