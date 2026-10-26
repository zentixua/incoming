package ua.zentix.incoming.warhead;

import ua.zentix.incoming.strike.Article;

/**
 * Bomb-body penetration of soil, rock, concrete: Young equations (C. W. Young, “Penetration Equations”, Sandia
 * SAND97-2426, 1997, app. A; checked against primary source), without Minecraft. Meters, kilograms, seconds.
 * <p>
 * Homogeneous path: D = 0.000018·S·N·(m/A)^0.7·(V − 30.5) at V ≥ 61 m/s, otherwise D = 0.0008·S·N·(m/A)^0.7·ln(1
 * + 2.15·10⁻⁴·V²); S penetrability (larger softer), N nose, m/A mass per cross-section. No light-body
 * corrections: for 13.6 t bomb they are below thresholds (27 kg soil, 182 kg rock).
 * <p>
 * Layers (sec. 6): layer path length T gives exit V·√(1 − T/D), constant mean deceleration in layer; next layer
 * uses exit speed. Soil immediately behind concrete (under/between slabs) halves S for first {@value
 * #SOFT_UNDER_CONCRETE} calibers. Accuracy per Young: equation ~15%, with concrete S 15–20%, rock 20% to factor
 * two: rock depth is an estimate.
 * <p>
 * Water/other fluids do not use Young: ½·ρ·v²·CdA drag attenuates speed as e^(−ρ·CdA·x/(2m)).
 */
public final class Penetration {
    /** Equation branch boundary, m/s. */
    static final double V_SPLIT = 61;
    /** Soil behind concrete uses S/2 for this many path calibers (Young, sec. 6). */
    static final double SOFT_UNDER_CONCRETE = 6;
    /**
     * Below this fluid speed body counts as stopped, m/s (estimate): viscous drag/gravity absent from quadratic
     * law, otherwise bomb would slow forever through water.
     */
    static final double FLUID_REST = 1;

    private Penetration() {}

    /** Block medium encountered by body. */
    public enum Kind {
        /** Soil: earth, sand, gravel, clay, snow, loose/soft. */
        SOIL,
        /** Rock and stone. */
        ROCK,
        /** Concrete/masonry (following soil uses S/2). */
        CONCRETE,
        /** Fluid: density-based drag, not Young. */
        FLUID,
        /** Impenetrable (world bedrock, utility blocks): body stops. */
        STOP
    }

    /**
     * Body.
     *
     * @param mass mass, kg
     * @param diameter body diameter, m
     * @param length length, m
     * @param nose nose coefficient N ({@link #ogive})
     * @param cda air drag area C<sub>d</sub>·S, m² (same in fluid)
     */
    public record Body(double mass, double diameter, double length, double nose, double cda) {
        /** Cross-section, m². */
        public double area() {
            return Math.PI * diameter * diameter / 4;
        }

        /** m/A, kg/m². */
        public double massPerArea() {
            return mass / area();
        }
    }

    /** Spec article body: dry mass, diameter, length, nose, CdA; no penetrator means null. */
    public static Body body(Article a) {
        if (a.penetrator() == null) throw new IllegalArgumentException("not a penetrator");
        return new Body(a.dryMass(), a.diameter(), a.length(), ogive(a.penetrator().crh()), a.cda());
    }

    /** Ogive nose from arc radius in calibers (CRH): N = 0.18·√(CRH − 0.25) + 0.56. */
    public static double ogive(double crh) {
        return 0.18 * Math.sqrt(crh - 0.25) + 0.56;
    }

    /** Path of {@code body} at {@code v} m/s in homogeneous penetrability {@code s}, m. */
    public static double depth(Body body, double s, double v) {
        if (v <= 0) return 0;
        double k = s * body.nose() * Math.pow(body.massPerArea(), 0.7);
        return v >= V_SPLIT ? 0.000018 * k * (v - 30.5) : 0.0008 * k * Math.log(1 + 2.15e-4 * v * v);
    }

    /**
     * Body travel through medium in path pieces: adjacent same-medium pieces form one Young layer (any
     * subdivision gives homogeneous path D); medium change starts next layer at previous exit velocity.
     */
    public static final class Run {
        private final Body body;
        private double v;
        private Kind kind;
        private double s = Double.NaN;
        /** Layer entry speed, Young path, and distance traversed, m. */
        private double vIn, dIn, along;
        /** Soil path still at S/2 behind concrete, m. */
        private double soft;
        /** Last solid/fluid medium on path (voids ignored). */
        private Kind lastSolid;
        private boolean stopped;

        /** {@code body} enters medium at {@code v} m/s. */
        public Run(Body body, double v) {
            this.body = body;
            this.v = v;
        }

        /** Current speed, m/s. */
        public double speed() {
            return v;
        }

        /** Body stopped. */
        public boolean stopped() {
            return stopped;
        }

        /**
         * Traverse {@code len} m of {@code kind} with penetrability {@code s} (fluid {@code s} is density,
         * kg/m³). Returns distance traveled: less than {@code len} means stopped in this piece.
         */
        public double through(Kind kind, double s, double len) {
            if (stopped || len <= 0) return 0;
            if (kind == Kind.STOP) {
                stop();
                return 0;
            }
            // Soil directly behind concrete (and void between slabs) uses S/2 for first path calibers.
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

        /** Void (air, opening): no drag, layer ended; enter medium anew beyond it. */
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
            // v(x) = v₀·e^(−ρ·CdA·x/(2m)); stops at FLUID_REST.
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
