package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MushroomFieldTest {
    /**
     * Рамка гриба (верх и радиус) — числа на всю жизнь гриба: у воздушного, пока фронт не дошёл до земли, юбки нет (её
     * поднимать нечему), и ноль её сечения не делит рамку (NaN выбрасывал гриб из кадра пирамидой видимости).
     */
    @Test
    void boundsAreFiniteBeforeTheFrontReachesTheGround() {
        double high = 4 * FireballModel.maxRadius(15, false);
        for (double t = 0.1; t < MushroomModel.lifeSeconds(15); t += 1.7) {
            for (double front : new double[] {0, 300, 3_000}) {
                MushroomModel.Shape s = MushroomModel.shape(t, 15, high, false, front);
                if (s == null) continue;
                double top = MushroomField.top(s), reach = MushroomField.reach(s);
                assertTrue(Double.isFinite(top) && top >= 1, t + " с, фронт " + front + ": верх " + top);
                assertTrue(Double.isFinite(reach) && reach >= 1, t + " с, фронт " + front + ": радиус " + reach);
                if (front == 0) assertEquals(0, s.skirtTau(), 0, t + " с: юбка до прихода фронта");
            }
        }
    }

    /**
     * Рамка луча — наклонный цилиндр (mushSpan): каждое место тела (ось сдвинута сносом на долю высоты, mushShare, вокруг
     * неё — до reach) лежит в ней на любой высоте от 0,05 верха под землёй до верха, и ниже и выше конца подъёма.
     */
    @Test
    void boundHoldsTheDriftedBody() {
        double reach = 900, drift = 1700;
        for (double[] te : new double[][] {{4000, 3000}, {2500, 3000}, {500, 3000}}) {
            double top = te[0], end = te[1], k = drift / end;
            double r = MushroomField.boundRadius(reach, drift, top, end);
            for (double h = -0.05 * top; h <= top; h += top / 200) {
                double axis = drift * Math.clamp(h / end, 0, 1), tilted = k * h;
                // дальше всего от наклонной оси — место тела на краю reach в сторону расхождения осей
                double worst = Math.abs(axis - tilted) + reach;
                assertTrue(worst <= r + 1e-9, "верх " + top + ", конец " + end + ", высота " + h + ": " + worst + " > " + r);
            }
        }
    }

    /**
     * Рамка со столбом воды держит его стенку и корону с клубами (до {@link MushroomField#REACH_COLUMN} толщины стенки
     * за их край) всю жизнь столба, пока гриб у подрыва 15 кт на воде ещё молод и уже его, и не раздувается: и у низкого
     * столба (первые доли секунды, конец падения) — не шире трёх его радиусов (луч гриба шёл бы по пустому, а толща к
     * свету — мимо шапки).
     */
    @Test
    void boundHoldsTheWaterColumn() {
        for (double t = 0.001; t < 40; t += t < 1 ? 0.01 : 0.37) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 3_000);
            WaterModel.Column c = WaterModel.column(t, 15);
            if (c == null) continue;
            double r = MushroomField.REACH_COLUMN * MushroomField.columnUnit(c);
            double reach = MushroomField.reach(s, c), top = MushroomField.top(s, c);
            assertTrue(reach >= c.mid() + c.half() + r - 1e-9 && reach >= c.crownR() + r - 1e-9, t + " с: радиус " + reach);
            assertTrue(top >= c.top() + r - 1e-9, t + " с: верх " + top);
            assertTrue(reach >= MushroomField.reach(s) && top >= MushroomField.top(s), t + " с: рамка гриба");
            // и не шире нужного: рамка со столбом — не шире трёх его радиусов и не выше его радиуса над ним
            assertTrue(reach <= Math.max(MushroomField.reach(s), 3 * c.outer()), t + " с: радиус " + reach + " у столба " + c.outer());
            assertTrue(top <= Math.max(MushroomField.top(s), c.top() + c.outer()), t + " с: верх " + top + " у столба " + c.top());
        }
        MushroomModel.Shape s = MushroomModel.shape(5, 15, 0, true, 3_000);
        assertEquals(MushroomField.reach(s), MushroomField.reach(s, null), 0, "без столба — рамка гриба");
    }

    /** Направление тени — единичное, не ниже {@link MushroomField#SHADOW_LOW}; оси плоскости поперёк него — ортонормированы. */
    @Test
    void shadowAxesAreOrthonormal() {
        double[] s = new double[3], a = new double[3], b = new double[3];
        for (double angle = 0; angle < 2 * Math.PI; angle += 0.01) {
            for (double z : new double[] {0, 0.3, -0.95}) {
                MushroomField.shadowSun(-Math.sin(angle), Math.cos(angle), z, s);
                MushroomField.shadowAxes(s, a, b);
                String at = "угол " + angle + ", z " + z;
                assertEquals(1, dot(s, s), 1e-9, at);
                assertTrue(s[1] >= MushroomField.SHADOW_LOW - 1e-12, at);
                assertEquals(1, dot(a, a), 1e-9, at);
                assertEquals(1, dot(b, b), 1e-9, at);
                assertEquals(0, dot(a, s), 1e-9, at);
                assertEquals(0, dot(b, s), 1e-9, at);
                assertEquals(0, dot(a, b), 1e-9, at);
            }
        }
    }

    /**
     * Плитка карты тени держит проекцию рамки гриба поперёк солнца (наклонный цилиндр mushLine: круги радиуса boundR вокруг
     * оси, сдвинутой сносом на долю высоты, от −0,05 верха до верха) с запасом {@link MushroomField#SHADOW_PAD} текселей
     * с каждой стороны: прямые у края плитки проходят мимо гриба, и тень не обрезана краем.
     */
    @Test
    void shadowTileHoldsTheBound() {
        double[] s = new double[3], a = new double[3], b = new double[3], rect = new double[4];
        for (double angle = -1.55; angle <= 1.55; angle += 0.05) {
            MushroomField.shadowSun(-Math.sin(angle), Math.cos(angle), 0, s);
            MushroomField.shadowAxes(s, a, b);
            for (double[] g : new double[][] {{1200, 2500, 3000, 1700, -400}, {600, 400, 3000, 0, 0}, {80, 300, 1, 40, 25}}) {
                double r = g[0], top = g[1], end = g[2], dx = g[3], dz = g[4];
                MushroomField.shadowRect(a, b, r, dx, dz, top, end, rect);
                double kx = dx / Math.max(end, 1), kz = dz / Math.max(end, 1);
                for (int i = 0; i < 2; i++) {
                    double[] e = i == 0 ? a : b;
                    double pad = MushroomField.SHADOW_PAD * rect[2 + i] / MushroomField.SHADOW, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (double h = -0.05 * top; h <= top + 1e-9; h += top / 100) {
                        for (double phi = 0; phi < 2 * Math.PI; phi += 0.05) {
                            double u = e[0] * (kx * h + r * Math.cos(phi)) + e[1] * h + e[2] * (kz * h + r * Math.sin(phi));
                            min = Math.min(min, u);
                            max = Math.max(max, u);
                        }
                    }
                    String at = "угол " + angle + ", рамка " + r + "/" + top + ", ось " + i;
                    assertTrue(min >= rect[i] + pad - 1e-6, at + ": " + min + " < " + (rect[i] + pad));
                    assertTrue(max <= rect[i] + rect[2 + i] - pad + 1e-6, at + ": " + max + " > " + (rect[i] + rect[2 + i] - pad));
                }
            }
        }
    }

    /**
     * Цвет шапки — из капель и NO₂, без часов. Наблюдения: в отражённом свете облако белое, в проходящем — красно-бурое, и
     * внутри него бурый свет (Taylor и Minge 1963); сперва облако «красное или красно-бурое» (G&D §2.08). Что даёт физика
     * этого вещества: шапка 15 кт после подъёма отражает почти белым (каналы не ниже 0,6, синий не меньше 0,75 красного), а
     * пучок сверху выходит под ней и стоит в её середине бурым (синий — меньше трети красного). Только что свернувшееся
     * кольцо в отражённом свете — охра (оттенок на экране 28–45°; у красно-бурого — меньше 25°), и красно-бурым оно не
     * становится ни при какой доле NO₂: у сильного поглощения R∞ ∝ ω' ∝ 1/σ по каналу — оттенок 29°. Красно-бурый — свет,
     * прошедший кольцо насквозь (оттенок меньше 25°). Белеет шапка сама, пока NO₂ редеет в растущем объёме.
     */
    @Test
    void capIsWhiteOutsideAndBrownInside() {
        double hob = 592, stab = CloudModel.stabilizationSeconds(15);
        double[] w = new double[4];
        MushroomModel.Shape s = MushroomModel.shape(stab, 15, hob, false, 1e9);
        double tau = MushroomField.capTau(s, MushroomField.capMatter(s, 0, 0, w));
        double[] r = reflect(w), down = new double[3], mid = new double[3];
        for (int i = 0; i < 3; i++) {
            down[i] = PallLight.diffuse(w[i], w[3], tau, tau, 0, 1, 1, 1, 0, 0, 0, -2.0 / 3) + Math.exp(-tau);
            mid[i] = PallLight.diffuse(w[i], w[3], tau / 2, tau, 0, 1, 1, 1, 0, 0, 0, 0);
        }
        String at = String.format(Locale.ROOT, "отражение %.2f %.2f %.2f, под шапкой %.3f %.3f %.3f", r[0], r[1], r[2],
                down[0], down[1], down[2]);
        assertTrue(r[2] >= 0.6 && r[2] >= 0.75 * r[0], at);
        assertTrue(down[2] < down[0] / 3 && mid[2] < mid[0] / 3, at);
        assertTrue(tau > 20, "непрозрачна: " + tau);
        // только что свернувшееся кольцо: в отражённом свете — охра, насквозь — красно-бурое
        double tr = MushroomModel.rollUpSeconds(15, false);
        MushroomModel.Shape rolled = MushroomModel.shape(tr, 15, hob, false, 1e9);
        double ringTau = MushroomField.capTau(rolled, MushroomField.capMatter(rolled, 0, 0, w));
        double[] young = reflect(w), through = new double[3];
        for (int i = 0; i < 3; i++) {
            through[i] = PallLight.diffuse(w[i], w[3], ringTau, ringTau, 0, 1, 1, 1, 0, 0, 0, -2.0 / 3) + Math.exp(-ringTau);
        }
        String ring = String.format(Locale.ROOT, "кольцо: %.3f %.3f %.3f, оттенок %.0f°, насквозь %.0f°", young[0], young[1], young[2],
                hue(young), hue(through));
        assertTrue(young[0] > young[1] && young[1] > young[2] && young[2] > 0, ring);
        assertTrue(hue(young) >= 28 && hue(young) <= 45, ring);
        assertTrue(hue(through) < 25, ring);
        // больше NO₂ — темнее, но не краснее охры: поглощение по каналам × k
        double[] dark = new double[3];
        for (double k = 1; k <= 1e4; k *= 3) {
            for (int i = 0; i < 3; i++) dark[i] = MushroomField.wallReflectance(1 / (1 + k * (1 / w[i] - 1)), w[3]);
            assertTrue(hue(dark) >= 28, String.format(Locale.ROOT, "NO₂ × %.0f: оттенок %.1f°", k, hue(dark)));
        }
        // белеет само, без скачков: синий к красному растёт
        double last = 0;
        for (double t = tr; t <= stab; t += 5) {
            MushroomField.capMatter(MushroomModel.shape(t, 15, hob, false, 1e9), 0, 0, w);
            double[] c = reflect(w);
            double ratio = c[2] / c[0];
            assertTrue(ratio >= last, t + " с: " + ratio);
            last = ratio;
        }
    }

    /**
     * Отражение толстого слоя ({@link MushroomField#wallReflectance}, полусферическое среднее) — от 0 у одного поглощения
     * до 1 у одного рассеяния и не меньше нуля при любом ω'; у Эддингтона ({@link PallLight#reflectance}) при сильном
     * поглощении — меньше нуля.
     */
    @Test
    void wallReflectanceIsNeverNegative() {
        for (double g : new double[]{0, 0.375, 0.459, 0.6}) {
            assertEquals(0, MushroomField.wallReflectance(0, g), 1e-12);
            assertEquals(1, MushroomField.wallReflectance(1, g), 1e-6);
            double last = -1;
            for (double w = 0; w <= 1; w += 0.001) {
                double r = MushroomField.wallReflectance(w, g);
                assertTrue(r >= 0 && r <= 1 && r >= last, "ω' " + w + ", g' " + g + ": " + r);
                last = r;
            }
        }
        assertTrue(PallLight.reflectance(0, 0.459) < -0.05, "Эддингтон у одного поглощения");
    }

    /**
     * Шапка с грунтом (наземный подрыв), когда NO₂ в ней почти не осталось, — серая цвета {@link MushroomField#CAP_DUSTY}:
     * поглощение грунта — сверх NO₂. Шар до капель только поглощает (ω' = 0): его видно своим светом.
     */
    @Test
    void dustyCapAndBall() {
        double[] w = new double[4];
        MushroomModel.Shape late = MushroomModel.shape(3 * CloudModel.stabilizationSeconds(15), 15, 0, true, 1e9);
        MushroomField.capMatter(late, 1, 0, w);
        double g = PallLight.truncated(MushroomField.G);
        assertEquals(g, w[3], 1e-12);
        for (int i = 0; i < 3; i++) {
            double dusty = PallLight.albedo(PallLight.linear(((MushroomField.CAP_DUSTY >> (16 - 8 * i)) & 0xFF) / 255.0), g);
            assertTrue(w[i] < dusty && w[i] > dusty - 0.02, i + ": " + w[i] + " против " + dusty);
        }
        MushroomModel.Shape ball = MushroomModel.shape(0.5, 15, 592, false, 1e9);
        double sigma = MushroomField.capMatter(ball, 0, 0, w);
        assertEquals(0, w[0] + w[1] + w[2], 0);
        assertTrue(MushroomField.capTau(ball, sigma) > 5, "шар непрозрачен");
    }

    /**
     * Пар над водой — смесь веществ по ослаблению: ω' смеси — среднее ω' по долям ослабления. Пока пар только появился
     * (свежее кольцо 1 и 15 кт, где ω' каналов ниже 1/(4 − 3g') ≈ 0,38), ω' не прыгает: прежнее смешение в цвете слоя
     * обращало отражение Эддингтона, зажатое в нуль, и в первый же кадр пара поднимало такие каналы до 0,38. Где пара
     * всё ослабление — ω' пара, между — по прямой от шапки к пару; ослабление и асимметрия — шапки.
     */
    @Test
    void steamMixesWithoutAJump() {
        double[] dry = new double[4], wet = new double[4];
        boolean dark = false;
        for (double kt : new double[]{1, 15}) {
            double tr = MushroomModel.rollUpSeconds(kt, false), hob = 240 * Math.cbrt(kt);
            for (double t = 0.25 * tr; t <= tr; t += 0.05 * tr) {
                MushroomModel.Shape s = MushroomModel.shape(t, kt, hob, false, 1e9);
                double sigma = MushroomField.capMatter(s, 0, 0, dry);
                assertEquals(sigma, MushroomField.capMatter(s, 0, 1e-6, wet), 0, kt + " кт, " + t + " с: ослабление");
                for (int i = 0; i < 4; i++) {
                    assertEquals(dry[i], wet[i], 1e-6, kt + " кт, " + t + " с, канал " + i);
                    if (i < 3 && dry[i] < 1 / (4 - 3 * dry[3])) dark = true;
                }
                double steam = MushroomModel.rollUp(t, kt, false), share = MushroomField.STEAM_SHARE * steam;
                MushroomField.capMatter(s, 0, steam, wet);
                for (int i = 0; i < 3; i++) {
                    double mixed = dry[i] + (MushroomField.steamAlbedo(i, dry[3]) - dry[i]) * share;
                    assertEquals(mixed, wet[i], 1e-12, kt + " кт, " + t + " с, канал " + i + ": смесь");
                }
            }
        }
        assertTrue(dark, "у свежего кольца есть каналы с ω' ниже 0,38");
        MushroomModel.Shape s = MushroomModel.shape(MushroomModel.rollUpSeconds(15, false), 15, 592, false, 1e9);
        MushroomField.capMatter(s, 0, 1 / MushroomField.STEAM_SHARE, wet);
        for (int i = 0; i < 3; i++) assertEquals(MushroomField.steamAlbedo(i, wet[3]), wet[i], 1e-12, "один пар, канал " + i);
        assertTrue(MushroomField.steamAlbedo(1, wet[3]) > 0.98, "пар почти не поглощает");
    }

    /** Отражение толстого слоя по каналам при альбедо и асимметрии {@code w} (ω' r, g, b, g'). */
    private static double[] reflect(double[] w) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) r[i] = MushroomField.wallReflectance(w[i], w[3]);
        return r;
    }

    /** Оттенок цвета на экране, градусы (как в HSV): линейные r, g, b — в значения экрана, γ 2,2. */
    private static double hue(double[] linear) {
        double r = PallLight.display(linear[0]), g = PallLight.display(linear[1]), b = PallLight.display(linear[2]);
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), c = max - min;
        if (c <= 0) return 0;
        double h;
        if (max == r) h = (g - b) / c;
        else if (max == g) h = 2 + (b - r) / c;
        else h = 4 + (r - g) / c;
        return (60 * h + 360) % 360;
    }

    private static double dot(double[] x, double[] y) {
        return x[0] * y[0] + x[1] * y[1] + x[2] * y[2];
    }

    /**
     * Узор поверхности шара: до минимума яркости — его нет (место −1), к отрыву — первый кадр, потом кадры идут вперёд с
     * всплытием; доля узора — пока шар шар (1 − доля кольца), без прочитанного файла — 0 (шар как прежде).
     */
    @Test
    void ballSurfaceFollowsTheBreakawayAndTheRollUp() {
        double[] out = new double[2];
        double y = 15;
        MushroomField.ballSurface(0.5 * FireballModel.firstMinimumSeconds(y), 600, y, false, true, out);
        assertEquals(-1, out[0], 0);
        assertEquals(1, out[1], 1e-12);
        MushroomField.ballSurface(FireballModel.breakawaySeconds(y), 600, y, false, true, out);
        assertEquals(0, out[0], 1e-9);
        double last = -1;
        for (double t = 0.001; t < 40; t *= 1.1) {
            MushroomField.ballSurface(t, 600, y, false, true, out);
            assertTrue(out[0] >= last, "кадр не назад: " + t);
            last = out[0];
            assertEquals(1 - MushroomModel.rollUp(t, y, false), out[1], 1e-12);
            MushroomField.ballSurface(t, 600, y, false, false, out);
            assertEquals(0, out[1], 0);
        }
    }
}
