package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.strike.WeaponType;

import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Оптический бюджет, включая хвост: регрессия света обычного удара на горе за километры (FLASH). */
class FireballRadiationTest {
    private static double integrate(double from, double to, DoubleUnaryOperator power) {
        int steps = 4000;
        double dt = (to - from) / steps, sum = 0;
        for (int i = 0; i < steps; i++) sum += power.applyAsDouble(from + (i + 0.5) * dt) * dt;
        return sum;
    }

    private static double area(double r) {
        return 4 * Math.PI * r * r;
    }

    /** Независимо от ряда модели: интеграл закона Планка по длине волны, Симпсон через 0,5 нм. */
    private static double visibleNumeric(double t) {
        double h = 6.62607015e-34, c = 299792458, k = 1.380649e-23, sum = 0;
        for (int i = 0; i <= 600; i++) {
            double wavelength = (400 + 0.5 * i) * 1e-9;
            double power = 2 * Math.PI * h * c * c / (Math.pow(wavelength, 5) * Math.expm1(h * c / (wavelength * k * t)));
            sum += power * (i == 0 || i == 600 ? 1 : i % 2 == 0 ? 2 : 4) * 0.5e-9 / 3;
        }
        return sum;
    }

    @Test
    void visibleBandIsNotBolometricOrPhotopicPower() {
        for (double t : new double[]{798, 1000, CloudModel.FUEL_BALL_K, 1500, 2000, 2500, 3000}) {
            double exact = visibleNumeric(t);
            assertEquals(exact, FireballRadiation.visibleExitance(t), exact * 1e-8, "Planck 400–700 nm, " + t + " K");
        }
        assertEquals(0.08091, FireballRadiation.visibleExitance(3000) / FireballRadiation.totalExitance(3000), 1e-5);
        assertEquals(0.000494, FireballRadiation.visibleExitance(CloudModel.FUEL_BALL_K)
                / FireballRadiation.totalExitance(CloudModel.FUEL_BALL_K), 1e-6);
    }

    @Test
    void emittedHighExplosiveLightFitsOneBudgetForEveryCharge() {
        assertTrue(FireballRadiation.VISIBLE_SHARE > 0 && FireballRadiation.VISIBLE_SHARE <= 0.01);
        double[] light = new double[4];
        for (double kg : new double[]{6.4, 10, 50, 150, 450, 500}) {
            CloudModel m = new CloudModel(BlastSource.air(kg), CloudModel.Air.STANDARD);
            double budget = FireballRadiation.VISIBLE_SHARE * kg * 4.184e6;
            DoubleUnaryOperator oldPower = t -> area(m.ballRadius(t)) * visibleNumeric(m.ballKelvin(t));
            DoubleUnaryOperator emitted = t -> {
                m.emittedLight(t, light);
                // Реальный выход CloudModel, не только вычисленный коэффициент: прежний ballLight без ε этот тест провалит.
                return oldPower.applyAsDouble(t) * light[3] / BlackBody.luminance(m.ballKelvin(t));
            };
            double energy = integrate(0, m.heBallSeconds, emitted) + integrate(m.heBallSeconds, m.fireSeconds, emitted);
            double old = integrate(0, m.heBallSeconds, oldPower) + integrate(m.heBallSeconds, m.fireSeconds, oldPower);
            assertTrue(old > 40 * budget, kg + " kg: прежнее чёрное тело действительно нарушало выбранный бюджет");
            assertEquals(budget, energy, budget * 5e-4, kg + " kg: бюджет видимой полосы со всем хвостом");
            assertTrue(energy < 0.01 * kg * 4.184e6, kg + " kg: не больше 1% энергии заряда");
            assertTrue(m.radiation.he() > 0 && m.radiation.he() < 1);
        }
    }

    @Test
    void fuelCoolingSharesTheFuelRadiationBudget() {
        for (WeaponType w : new WeaponType[]{WeaponType.DRONE, WeaponType.MISSILE}) {
            CloudModel m = new CloudModel(BlastSource.of(w, false), CloudModel.Air.STANDARD);
            double budget = CloudModel.RADIANT * FuelFireball.ballKg(m.source.fuelKg()) * m.source.fuel().heatJPerKg;
            double burning = integrate(0, m.fuelBallSeconds, t -> area(m.fuelBallRadius(t))
                    * FireballRadiation.SIGMA * Math.pow(CloudModel.FUEL_BALL_K, 4));
            double tail = integrate(m.burnSeconds, m.fireSeconds, t -> area(m.ballRadius(t))
                    * FireballRadiation.SIGMA * Math.pow(m.coolKelvin(t), 4));
            assertTrue(burning < budget, w + ": одно горение укладывалось");
            assertTrue(burning + tail > budget, w + ": прежний хвост излучал сверх бюджета");
            assertEquals(budget, burning * m.radiation.fuel() + tail * m.radiation.cooling(), budget * 5e-4);
            assertEquals(m.radiation.fuel(), m.radiation.cooling(), 0, "один коэффициент, нет скачка к чёрному телу");
            // После конца HE видимый выход реального источника использует тот же серый коэффициент.
            double t = (m.heBallSeconds + m.fuelBallSeconds) / 2;
            double[] out = new double[4];
            m.ballLight(t, out);
            assertEquals(BlackBody.luminance(CloudModel.FUEL_BALL_K) * m.radiation.fuel(), out[3], 1e-12);
            t = m.burnSeconds + 0.1;
            m.ballLight(t, out);
            assertEquals(BlackBody.luminance(m.coolKelvin(t)) * m.radiation.cooling(), out[3], 1e-12);
        }
    }

    @Test
    void noChargeOrFuelHasNoEmission() {
        CloudModel m = new CloudModel(BlastSource.air(0), CloudModel.Air.STANDARD);
        double[] out = new double[4];
        for (double t : new double[]{0, 0.1, 1, 10}) {
            m.emittedLight(t, out);
            for (double c : out) assertEquals(0, c, 0);
        }
    }
}
