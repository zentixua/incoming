package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MushroomModelTest {
    /** Привыкание глаза ночью ({@code Sight.adapted} при свете неба 0,17): во столько раз ярче кажется свет. */
    private static final double NIGHT = 1 / Math.pow(0.17, 4);

    /** Шар сворачивается в кольцо за 2,5·√(R/g): у 15 кт — около 12 с; доля кольца растёт от 0 до 1. */
    @Test
    void ballRollsUpIntoARing() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertTrue(tr > 9 && tr < 14, "15 кт: " + tr + " с");
        assertTrue(MushroomModel.rollUpSeconds(1, false) < tr);
        assertEquals(0, MushroomModel.rollUp(0, 15, false), 0);
        assertEquals(1, MushroomModel.rollUp(tr, 15, false), 1e-12);
        double last = 0;
        for (double t = 0; t <= 2 * tr; t += 0.05) {
            double r = MushroomModel.rollUp(t, 15, false);
            assertTrue(r >= last, t + " с");
            last = r;
        }
    }

    /**
     * Снаружи шар — сам шар (вся доля перепада ядра у края, гладкая сфера), свернувшись, край смешан с воздухом
     * ({@link MushroomModel#EDGE_HEAT}, клубы в полный размах); видимая температура не выше ядра, без скачка, с конца
     * второго максимума только падает.
     */
    @Test
    void edgeCoolsAsTheBallRollsUp() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertEquals(1, MushroomModel.edgeShare(0.2 * tr, 15, false), 0);
        assertEquals(MushroomModel.EDGE_HEAT, MushroomModel.edgeShare(tr, 15, false), 1e-12);
        assertEquals(MushroomModel.BALL_BILLOWS, MushroomModel.billows(0.2 * tr, 15, false), 0);
        assertEquals(1, MushroomModel.billows(tr, 15, false), 1e-12);
        assertEquals(FireballModel.temperature(1, 15), MushroomModel.edgeTemperature(1, 15, false), 1e-9);
        double last = Double.MAX_VALUE, tm = FireballModel.secondMaximumSeconds(15);
        for (double t = tm; t < 600; t *= 1.01) {
            double edge = MushroomModel.edgeTemperature(t, 15, false);
            assertTrue(edge <= MushroomModel.coreTemperature(t, 15, false) + 1e-9, t + " с");
            assertTrue(edge <= last, t + " с: " + edge);
            assertTrue(last == Double.MAX_VALUE || last - edge < 0.02 * last, t + " с: скачок " + last + " → " + edge);
            last = edge;
        }
    }

    @Test
    void capCarriesSoilOnlyWhenTheBallTouchesTheGround() {
        double rf = FireballModel.maxRadius(2, false);
        assertEquals(1, MushroomModel.soil(2, 0, true), 0);
        assertEquals(1, MushroomModel.soil(2, 500, true), 0, "наземный — всегда с грунтом");
        assertEquals(1, MushroomModel.soil(2, rf, false), 1e-12, "шар касается земли");
        // 2 кт на 302 м (шар 92 м): в шапке только конденсат — остывшая она белая
        assertEquals(0, MushroomModel.soil(2, 302, false), 0);
        double last = 1;
        for (double h = 0; h <= 3 * rf; h += rf / 16) {
            double s = MushroomModel.soil(2, h, false);
            assertTrue(s <= last, "выше — грунта не больше: " + h);
            last = s;
        }
    }

    /**
     * Свет шара и шапки вокруг ({@link MushroomModel#light}): у поверхности — яркость чёрного тела её видимой температуры,
     * дальше — как (R/d)²; днём через секунду шар 15 кт в 5 км светит ярче солнца, а через 20 с свернувшаяся шапка — уже
     * нет и доли процента дня.
     */
    @Test
    void ballLightsItsSurroundings() {
        MushroomModel.Shape s = MushroomModel.shape(1, 15, 0, true, 300);
        double surface = BlackBody.luminance(MushroomModel.edgeTemperature(1, 15, true));
        assertEquals(surface, MushroomModel.light(s, 15, true, 0), 1e-9 * surface);
        assertEquals(surface / 4, MushroomModel.light(s, 15, true, 2 * s.capR()), 1e-9 * surface);
        assertTrue(MushroomModel.light(s, 15, true, 5_000) > 1, "1 с в 5 км: " + MushroomModel.light(s, 15, true, 5_000));
        MushroomModel.Shape late = MushroomModel.shape(20, 15, 0, true, 3_000);
        assertTrue(MushroomModel.light(late, 15, true, 2_000) < 1e-3, "20 с в 2 км: " + MushroomModel.light(late, 15, true, 2_000));
    }

    /** Ядро шапки — тот же шар до сворачивания и остывает смешением после: температура без скачка и только падает. */
    @Test
    void coreCoolsWithoutAJump() {
        double tr = MushroomModel.rollUpSeconds(15, false);
        assertEquals(MushroomModel.coreTemperature(tr - 1e-6, 15, false), MushroomModel.coreTemperature(tr + 1e-6, 15, false), 0.01);
        double last = Double.MAX_VALUE;
        for (double t = 0.2; t < 900; t += 0.2) {
            double k = MushroomModel.coreTemperature(t, 15, false);
            assertTrue(k <= last && k > MushroomModel.AMBIENT_K, t + " с: " + k);
            last = k;
        }
    }

    /**
     * Ночью ядро 15 кт светится минуты: через минуту — ярко (ярче белой стены в полдень с привыканием глаза), через две с
     * половиной — ещё заметно, через десять — нет. У 1 кт оно гаснет раньше.
     */
    @Test
    void coreGlowsForMinutesAtNight() {
        assertTrue(night(60, 15) > 1, "60 с: " + night(60, 15));
        assertTrue(night(150, 15) > 0.05, "150 с: " + night(150, 15));
        assertTrue(night(600, 15) < 0.01, "600 с: " + night(600, 15));
        assertTrue(night(60, 1) < 0.05 * night(60, 15), "1 кт через минуту: " + night(60, 1));
    }

    private static double night(double t, double yieldKt) {
        return BlackBody.luminance(MushroomModel.coreTemperature(t, yieldKt, false)) * NIGHT;
    }

    /**
     * Гриб: до подрыва и после рассеяния его нет; шапка — сначала сам огненный шар (сфера его радиуса на его высоте,
     * непрозрачная), кольцо растёт из него; ножка входит в выемку под куполом, но не выше середины кольца (у наземного — с
     * самого начала: она след шара, у воздушного столб пыли догоняет шапку позже); юбка не дальше фронта по земле.
     */
    @Test
    void shapeFollowsTheCloud() {
        assertNull(MushroomModel.shape(0, 15, 0, true, 0));
        assertNull(MushroomModel.shape(MushroomModel.lifeSeconds(15) + 1, 15, 0, true, 10_000));
        MushroomModel.Shape early = MushroomModel.shape(0.5, 15, 0, true, 300);
        assertNotNull(early);
        assertEquals(0, early.ringR(), 0);
        assertEquals(FireballModel.radius(0.5, 15, true), early.capR(), 1e-9);
        assertEquals(FireballModel.radius(0.5, 15, true), early.ringB(), 1e-9);
        assertEquals(FireballModel.centreHeight(0.5, 0, 15, true), early.capY(), 1e-9);
        // шар — горячий газ без капель, непрозрачный своим NO₂: поперёк в зелёном толща больше пяти
        assertEquals(0, early.capDrops(), 0);
        assertTrue(MushroomModel.capAbsorption(early, 1) * 2 * early.capR() > 5, "шар прозрачен");
        assertTrue(early.domeY() + early.domeH() < early.capY() + early.ringB(), "купол внутри шара");
        for (double t = 1; t < MushroomModel.lifeSeconds(15); t += 3) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 2_000);
            assertNotNull(s, t + " с");
            assertTrue(s.stemTop() <= s.capY() + 1e-9, t + " с: ножка выше середины кольца");
            assertTrue(s.stemTop() >= s.capBottom() - 1e-9, t + " с: у наземного ножка не дошла до шапки");
            assertTrue(s.skirtR() + s.skirtW() <= 2_000 + 1e-9, t + " с: юбка дальше фронта");
            assertTrue(s.ringR() + s.ringA() <= s.capR() + 1e-9, t + " с: кольцо шире шапки");
            assertTrue(s.top() >= s.capY(), t + " с");
        }
        // у воздушного столб пыли поднимается с земли и догоняет шапку позже
        double high = 4 * FireballModel.maxRadius(15, false);
        MushroomModel.Shape air = MushroomModel.shape(5, 15, high, false, 2_000);
        assertTrue(air.stemTop() < air.capBottom(), "воздушный: ножка у шапки уже через 5 с");
        MushroomModel.Shape late = MushroomModel.shape(60, 15, 0, true, 5_000);
        assertEquals(MushroomModel.RING * late.capR(), late.ringR(), 1e-9);
        double tau = late.capDrops() * 2 * Math.min(late.ringA(), late.ringB());
        assertTrue(tau > 30, "шапка через минуту густая: " + tau);
    }

    /**
     * Шар становится шапкой одной формой: после второго максимума (шар уже не растёт рывком) числа формы за сотую долю
     * секунды меняются на доли процента — без скачка на сворачивании.
     */
    @Test
    void ballTurnsIntoTheCapWithoutAJump() {
        for (boolean surface : new boolean[]{true, false}) {
            double hob = surface ? 0 : 2 * FireballModel.maxRadius(15, false), dt = 0.01, from = 2 * FireballModel.secondMaximumSeconds(15);
            MushroomModel.Shape last = MushroomModel.shape(from, 15, hob, surface, 300);
            for (double t = from + dt; t < 60; t += dt) {
                MushroomModel.Shape s = MushroomModel.shape(t, 15, hob, surface, 300);
                double[] a = {last.capY(), last.capR(), last.ringR(), last.ringB(), last.domeY(), last.domeR(), last.domeH(), last.top()};
                double[] b = {s.capY(), s.capR(), s.ringR(), s.ringB(), s.domeY(), s.domeR(), s.domeH(), s.top()};
                for (int i = 0; i < a.length; i++) {
                    assertTrue(Math.abs(b[i] - a[i]) < 0.02 * s.capR() + 2, (surface ? "наземный " : "воздушный ") + t + " с, число " + i + ": " + a[i] + " → " + b[i]);
                }
                last = s;
            }
        }
    }

    /** Ножка шире всего у земли (раструб пыли) или у шапки: рамка гриба берёт её края. */
    @Test
    void stemIsWidestAtItsEnds() {
        for (double flare : new double[]{0, 10, 40}) {
            double ends = Math.max(MushroomModel.stemRadius(0, 30, flare), MushroomModel.stemRadius(1, 30, flare));
            for (double w = 0; w <= 1; w += 0.01) assertTrue(MushroomModel.stemRadius(w, 30, flare) <= ends + 1e-9, "w = " + w);
        }
    }

    /**
     * Ножка по мощности (Glasstone и Dolan 2.17): до 20 кт её радиус — около половины радиуса шапки, у мегатонн ножка
     * в 5–10 раз уже облака; в среднем по высоте ножка шириной в {@code stemR}. Под шаром до сворачивания — его тонкий
     * след, свернувшись, — доля G&D.
     */
    @Test
    void stemFollowsGlasstone() {
        assertEquals(0.5, MushroomModel.stemShare(2), 1e-12);
        assertEquals(0.5, MushroomModel.stemShare(20), 1e-12);
        assertEquals(0.2, MushroomModel.stemShare(1000), 1e-12);
        assertEquals(0.1, MushroomModel.stemShare(30000), 1e-12);
        for (double y = 1; y < 1e5; y *= 1.3) assertTrue(MushroomModel.stemShare(y * 1.3) <= MushroomModel.stemShare(y) + 1e-12, y + " кт");
        double mean = 0;
        for (int i = 0; i < 1000; i++) mean += MushroomModel.stemRadius((i + 0.5) / 1000, 1, 0) / 1000;
        assertEquals(1, mean, 1e-3, "средний радиус ножки");
        for (double y : new double[]{1, 2, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 3 * FireballModel.maxRadius(y, false), tr = MushroomModel.rollUpSeconds(y, surface);
                MushroomModel.Shape ball = MushroomModel.shape(0.2 * tr, y, hob, surface, 1e9);
                assertEquals(MushroomModel.BALL_STEM * ball.capR(), ball.stemR(), 1e-9, y + " кт: ножка под шаром");
                MushroomModel.Shape s = MushroomModel.shape(CloudModel.stabilizationSeconds(y), y, hob, surface, 1e9);
                assertEquals(MushroomModel.stemShare(y) * s.capR(), s.stemR(), 1e-9, y + " кт: ножка свернувшейся шапки");
            }
        }
    }

    /** Юбка выходит за низ ножки с раструбом, пока пыль у земли не осела: широкая ножка килотонн её не накрывает. */
    @Test
    void skirtSticksOutOfTheStem() {
        for (double y : new double[]{1, 2, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 3 * FireballModel.maxRadius(y, false);
                for (double t : new double[]{10, 30, 60, CloudModel.stabilizationSeconds(y)}) {
                    MushroomModel.Shape s = MushroomModel.shape(t, y, hob, surface, 1e9);
                    double foot = MushroomModel.stemRadius(0, s.stemR(), MushroomModel.dustFlare(s));
                    double body = MushroomModel.stemRadius(0, s.stemR(), 0);
                    assertTrue(foot <= Math.max(MushroomModel.DUST_BASE * s.capR(), body) + 1e-9, y + " кт, " + t + " с: раструб шире круга пыли");
                    assertTrue(s.skirtR() + s.skirtW() > 1.2 * foot, y + " кт, " + t + " с: юбка под ножкой");
                }
            }
        }
    }

    /**
     * Часы кипения шапки — интеграл скорости обращения от подрыва: с нуля, без скачков, только вперёд, а к стабилизации
     * встают. Оборот шапки 15 кт на 20-й секунде — 15–35 с (вещество обходит сечение кольца со скоростью подъёма), потом
     * медленнее; за всю жизнь — несколько оборотов, у 1 и 15 кт — того же порядка.
     */
    @Test
    void capChurnsWithTheRise() {
        for (double kt : new double[]{1, 2, 15}) {
            MushroomModel.Churn c = MushroomModel.churn(kt, 0, true);
            assertEquals(0, c.at(0), 0);
            double last = 0, step = 0.05, fastest = 0;
            for (double t = 1e-3; t < c.end(); t += 0.01) fastest = Math.max(fastest, rate(kt, t));
            for (double t = step; t < c.end(); t += step) {
                double v = c.at(t);
                assertTrue(v >= last, kt + " кт, " + t + " с");
                // за шаг — не больше, чем при самом быстром обращении
                assertTrue(v - last <= 1.01 * fastest * step, kt + " кт, " + t + " с: " + (v - last));
                last = v;
            }
            assertEquals(c.at(c.end()), c.at(3 * c.end()), 0, kt + " кт: часы после конца таблицы стоят");
            assertTrue(c.at(c.end()) > 10 && c.at(c.end()) < 40, kt + " кт: " + c.at(c.end()) + " рад");
            // таблица — тот же интеграл, что прямое сложение мелкими шагами
            double sum = 0, rate0 = rate(kt, 1e-3);
            for (double t = 0; t < 120; t += 0.01) {
                double r = rate(kt, t + 0.005);
                sum += (t == 0 ? 0.5 * (rate0 + r) : r) * 0.01;
            }
            assertEquals(sum, c.at(120), 0.01 * sum, kt + " кт: часы через 2 минуты");
        }
        double turn20 = 2 * Math.PI / rate(15, 20), turn120 = 2 * Math.PI / rate(15, 120);
        assertTrue(turn20 > 15 && turn20 < 35, "оборот на 20-й секунде: " + turn20 + " с");
        assertTrue(turn120 > 3 * turn20, "оборот ко второй минуте: " + turn120 + " с");
    }

    private static double rate(double kt, double t) {
        MushroomModel.Shape s = MushroomModel.shape(t, kt, 0, true, 0);
        return s == null ? 0 : MushroomModel.churnRate(s, CloudModel.riseSpeed(t, 0, kt, true));
    }

    /**
     * Вещество шапки. Капли: σ = 3·LWC/(2·ρw·r_e) — 0,075 1/м при 0,5 г/м³ и 10 мкм, приходят с кольцом (у шара их нет),
     * и шапка 15 кт после подъёма — τ ≈ 130 поперёк сечения кольца, в пределах 30–450 для кучевого облака такой высоты.
     * NO₂: весь, что дал подрыв (доля NOx на мощность), — в объёме шапки; поглощает в синем в восемь раз сильнее, чем в
     * красном (Bogumil et al. 2003).
     */
    @Test
    void capHoldsDropsAndTheNo2OfTheBurst() {
        assertEquals(0.075, MushroomModel.DROP_SIGMA, 1e-12);
        for (double kt : new double[]{1, 15, 1000}) {
            double hob = 240 * Math.cbrt(kt), tr = MushroomModel.rollUpSeconds(kt, false);
            assertEquals(0, MushroomModel.shape(0.2 * tr, kt, hob, false, 1e9).capDrops(), 0, kt + " кт: капли в шаре");
            MushroomModel.Shape rolled = MushroomModel.shape(tr, kt, hob, false, 1e9);
            assertEquals(MushroomModel.DROP_SIGMA, rolled.capDrops(), 1e-12, kt + " кт: капли в кольце");
            for (double t = 0.5; t < MushroomModel.lifeSeconds(kt); t *= 1.7) {
                MushroomModel.Shape s = MushroomModel.shape(t, kt, hob, false, 1e9);
                double molecules = s.capNo2() * MushroomModel.capVolume(s);
                assertEquals(MushroomModel.NO2_SHARE * MushroomModel.NOX_PER_KT * kt * s.life(), molecules, 1e-9 * molecules, kt + " кт, " + t + " с");
                assertTrue(MushroomModel.capAbsorption(s, 0) < MushroomModel.capAbsorption(s, 1)
                        && MushroomModel.capAbsorption(s, 1) < MushroomModel.capAbsorption(s, 2), kt + " кт, " + t + " с: NO₂ бурый");
            }
        }
        assertEquals(8.1, MushroomModel.NO2_CROSS[2] / MushroomModel.NO2_CROSS[0], 0.1);
        double stab = CloudModel.stabilizationSeconds(15);
        MushroomModel.Shape s = MushroomModel.shape(stab, 15, 592, false, 1e9);
        double tau = s.capDrops() * 2 * Math.min(s.ringA(), s.ringB());
        assertTrue(tau > 100 && tau < 200, "15 кт после подъёма: τ " + tau);
        // объём шапки — сфероид: у шара — его объём
        MushroomModel.Shape ball = MushroomModel.shape(0.5, 15, 592, false, 1e9);
        assertEquals(4.0 / 3 * Math.PI * Math.pow(ball.capR(), 3), MushroomModel.capVolume(ball), 1e-6 * MushroomModel.capVolume(ball));
    }

    /** Скорость подъёма верха облака — производная его высоты; шар всплывает с той же скоростью. */
    @Test
    void riseSpeedIsTheSlopeOfTheTop() {
        for (boolean surface : new boolean[]{false, true}) {
            for (double t = 0.5; t < 600; t += 7.3) {
                double slope = (CloudModel.top(t + 1e-3, 0, 15, surface) - CloudModel.top(t - 1e-3, 0, 15, surface)) / 2e-3;
                assertEquals(slope, CloudModel.riseSpeed(t, 0, 15, surface), 1e-4 * Math.max(slope, 1), t + " с");
                double ball = (FireballModel.centreHeight(t + 1e-3, 0, 15, surface) - FireballModel.centreHeight(t - 1e-3, 0, 15, surface)) / 2e-3;
                assertEquals(slope, ball, 1e-4 * Math.max(slope, 1), t + " с: шар");
            }
        }
    }

    /**
     * Гриб виден, пока поднимается и пока турбулентность его рассеивает: пары частиц расходятся на радиус шапки,
     * ⟨r²⟩ = g·ε·t³ (Ричардсон — Обухов). Рассеяние растёт с размером облака, поэтому и жизнь растёт с мощностью — везде
     * от 0,1 кт до 1 Мт, хотя 1 кт стабилизируется вдвое дольше 15 кт (τ «Уоспа»): прежние 4 стабилизации давали 1 кт
     * 45 минут, а 15 кт — 24. 15 кт — 27 минут, 1 Мт — больше часа («около часа или больше», Glasstone и Dolan, §2.15).
     * Первую половину рассеяния гриб виден целиком, к концу — нет.
     */
    @Test
    void lifeGrowsWithYield() {
        double prev = 0;
        for (double kt = 0.1; kt <= 1000.001; kt *= 1.02) {
            double life = MushroomModel.lifeSeconds(kt), r = CloudModel.capRadius(kt), td = MushroomModel.dispersalSeconds(kt);
            assertTrue(life > prev, kt + " кт: " + life + " с после " + prev);
            assertEquals(CloudModel.stabilizationSeconds(kt) + td, life, 1e-9, kt + " кт");
            assertEquals(r * r, MushroomModel.RICHARDSON * MushroomModel.DISSIPATION * td * td * td, 1e-9 * r * r, kt + " кт");
            prev = life;
        }
        assertEquals(27 * 60, MushroomModel.lifeSeconds(15), 60, "15 кт");
        assertTrue(MushroomModel.lifeSeconds(1) < MushroomModel.lifeSeconds(15), "1 кт");
        assertTrue(MushroomModel.lifeSeconds(1000) > 3600, "1 Мт: " + MushroomModel.lifeSeconds(1000));
        for (double kt : new double[]{1, 15, 1000}) {
            double stab = CloudModel.stabilizationSeconds(kt), td = MushroomModel.dispersalSeconds(kt);
            assertEquals(1, MushroomModel.shape(stab + 0.5 * td - 1, kt, 0, true, 1e9).life(), 0, kt + " кт: середина рассеяния");
            assertEquals(0.5, MushroomModel.shape(stab + 0.75 * td, kt, 0, true, 1e9).life(), 1e-9, kt + " кт: тает");
            assertNull(MushroomModel.shape(MushroomModel.lifeSeconds(kt) + 1e-3, kt, 0, true, 1e9), kt + " кт: рассеялся");
        }
    }

    /**
     * Струи ножки поднимаются с воздухом, который тянет кольцо, — долей {@link MushroomModel#STEM_INFLOW} скорости подъёма
     * облака: в миг подрыва — эта доля её, к стабилизации встают (весь путь — та же доля пути верха), а не идут со
     * скоростью в миг подрыва всю жизнь гриба.
     */
    @Test
    void stemRisesWithTheCloud() {
        for (double kt : new double[]{1, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 240 * Math.cbrt(kt), life = MushroomModel.lifeSeconds(kt);
                String at = kt + " кт, " + (surface ? "наземный" : "воздушный");
                assertEquals(0, MushroomModel.stemRise(0, hob, kt, surface), 1e-9, at);
                double v0 = MushroomModel.stemRise(1e-3, hob, kt, surface) / 1e-3, rise = CloudModel.riseSpeed(0, hob, kt, surface);
                assertEquals(MushroomModel.STEM_INFLOW * rise, v0, 1e-3 * v0, at + ": в миг подрыва");
                double all = MushroomModel.STEM_INFLOW * (CloudModel.top(1e6, hob, kt, surface) - CloudModel.top(0, hob, kt, surface));
                assertEquals(all, MushroomModel.stemRise(life, hob, kt, surface), 0.01 * all, at + ": к концу жизни");
                double prev = 0;
                for (double t = 1; t < life; t += 10) {
                    double h = MushroomModel.stemRise(t, hob, kt, surface);
                    assertTrue(h >= prev && h <= all + 1e-9, at + ", " + t + " с: " + h);
                    prev = h;
                }
            }
        }
    }

    /**
     * Подъём — один закон у шара и облака: верх гриба и середина кольца шапки никогда не опускаются — ни пока шар
     * сворачивается в кольцо (форма идёт от шара к шапке), ни потом. Воздушный подрыв и наземный, 1, 15 и 1000 кт.
     */
    @Test
    void topAndRingNeverGoDown() {
        for (double kt : new double[]{1, 15, 1000}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 240 * Math.cbrt(kt), life = MushroomModel.lifeSeconds(kt);
                MushroomModel.Shape prev = null;
                for (double t = 0.005; t < life; t += t < 60 ? 0.005 : 0.5) {
                    MushroomModel.Shape s = MushroomModel.shape(t, kt, hob, surface, 1e9);
                    if (s == null) break;
                    if (prev != null) {
                        String at = kt + " кт, " + (surface ? "наземный" : "воздушный") + ", " + t + " с";
                        assertTrue(s.top() >= prev.top() - 1e-9, at + ": верх опустился " + prev.top() + " → " + s.top());
                        assertTrue(s.capY() >= prev.capY() - 1e-9, at + ": кольцо опустилось " + prev.capY() + " → " + s.capY());
                    }
                    prev = s;
                }
            }
        }
    }
}
