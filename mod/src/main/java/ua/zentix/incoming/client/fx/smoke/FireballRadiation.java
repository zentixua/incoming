package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.FuelFireball;

import java.util.function.DoubleUnaryOperator;

/**
 * Ordinary fireball radiation, separate from geometry and gas temperature. A greybody with constant effective
 * emissivity ε = budget / ∫4πR²M(T)dt, calculated once when the cloud is created, not per frame.
 * HE has a visible-band budget (400–700 nm); fuel has a whole-spectrum budget. These are different quantities.
 */
record FireballRadiation(double he, double fuel, double cooling) {
    /**
     * Visible energy / HE energy: Khristoforov, FGV 50(1), 2014, pp.109–110, fig.2 (visible curve ×10),
     * DOI 10.1134/S0010508214010122: TG 50/50, 0.95 kg, 400–700 nm, 0.24% by t/m⅓ = 0.2 s/kg⅓.
     * https://www.sibran.ru/upload/iblock/f6e/f6e9410409c31429c6382e40eeb701df.pdf
     * ⚠ Estimate: extrapolation of one small test to other compositions and 10–500 kg charges, not measured yields.
     * R(t), T(t) remain approximate profiles; this law bounds their integral, not the distance light travels.
     */
    static final double VISIBLE_SHARE = 0.0024;
    /** TNT equivalent, J/kg (NIST SP 811, ton of TNT = 4.184·10⁹ J). */
    static final double TNT_J_PER_KG = 4.184e6;
    /** Stefan–Boltzmann constant and hc/k in SI (from defining h, c, k, BIPM SI Brochure). */
    static final double SIGMA = 5.670374419e-8, C2 = 1.4387768775039338e-2;
    /** Quadrature of the existing geometry; checked by a finer independent integral in FireballRadiationTest. */
    private static final int STEPS = 128;

    static FireballRadiation of(CloudModel m) {
        double heEnergy = integrate(0, m.heBallSeconds, t -> area(m.heBallRadius(t)) * visibleExitance(m.heBallKelvin(t)));
        double fuelEnergy = integrate(0, m.fuelBallSeconds, t -> area(m.fuelBallRadius(t)) * totalExitance(CloudModel.FUEL_BALL_K));
        boolean fuelLast = m.source.fuelKg() > 0 && m.fuelBallSeconds >= m.heBallSeconds;
        // The cooling gas is no longer split into two balls: ⚠ assign its tail to the last burning ball, like its
        // radius and initial T in CloudModel. It shares that budget instead of reverting to full blackbody emission.
        double tail = integrate(m.burnSeconds, m.fireSeconds, t -> area(m.ballRadius(t))
                * (fuelLast ? totalExitance(m.coolKelvin(t)) : visibleExitance(m.coolKelvin(t))));
        if (fuelLast) fuelEnergy += tail;
        else heEnergy += tail;
        double he = emissivity(VISIBLE_SHARE * TNT_J_PER_KG * m.source.chargeKg(), heEnergy);
        // χr = 0.3, the same estimate used for thermal buoyancy. TNO Yellow Book (2005), 6.91–6.93, 6.119:
        // Fs = 0.00325 Psv^0.32, about 0.314 at 1.6 MPa. ⚠ A crashed tank is not a measured BLEVE at that pressure.
        double fuel = emissivity(CloudModel.RADIANT * FuelFireball.ballKg(m.source.fuelKg()) * m.source.fuel().heatJPerKg, fuelEnergy);
        return new FireballRadiation(he, fuel, fuelLast ? fuel : he);
    }

    private static double emissivity(double budget, double energy) {
        return energy > 0 ? Math.clamp(budget / energy, 0, 1) : 0;
    }

    private static double area(double r) {
        return 4 * Math.PI * r * r;
    }

    private static double integrate(double from, double to, DoubleUnaryOperator power) {
        double dt = (to - from) / STEPS, energy = 0;
        if (dt <= 0) return 0;
        for (int i = 0; i < STEPS; i++) energy += power.applyAsDouble(from + (i + 0.5) * dt) * dt;
        return energy;
    }

    static double totalExitance(double kelvin) {
        return SIGMA * Math.pow(Math.max(0, kelvin), 4);
    }

    /**
     * Planck exitance, W/m² in 400–700 nm, without CIE photopic weighting. Substituting x = hc/(λkT):
     * σT⁴·15/π⁴·∫x³/(exp(x)−1)dx. Expand 1/(exp(x)−1) = Σexp(−nx) and integrate each term exactly;
     * six terms suffice at T ≤ 3000 K (x ≥ 6.85), avoiding a spectral loop at each time node.
     */
    static double visibleExitance(double kelvin) {
        if (kelvin <= 0) return 0;
        double a = C2 / (700e-9 * kelvin), b = C2 / (400e-9 * kelvin), sum = 0;
        for (int n = 1; n <= 6; n++) sum += tail(a, n) - tail(b, n);
        return totalExitance(kelvin) * (15 / Math.pow(Math.PI, 4)) * sum;
    }

    private static double tail(double x, int n) {
        double inv = 1.0 / n;
        return Math.exp(-n * x) * inv * (x * x * x + inv * (3 * x * x + inv * (6 * x + 6 * inv)));
    }
}
