package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.util.Visibility;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Подача огненного шара ядерки и кадра во вспышку ({@link NukeExposure}): шар не превращается в ровный диск, оболочка
 * Вильсона и блик в его свете не ярче его на экране, кадр догоняет вспышку за доли секунды, а не за весь импульс и ещё
 * 2–4 с. Законы шейдера
 * ({@code mushroom.glsl}: {@code mushBallGain}, {@code mushFilm}, {@code mushFilmWhite}) — те же, что здесь.
 */
class NukeExposureTest {
    /** Привыкание глаза к небу днём и ночью ({@code Sight.adapted}: ambient^−4; ночью ambient 0,17). */
    private static final double DAY = 1, NIGHT = Math.pow(0.17, -4);

    private static NukeBallSurface.Data pattern() {
        try (InputStream in = NukeExposureTest.class.getResourceAsStream("/assets/incoming/textures/fx/nukeball.bin")) {
            assertNotNull(in, "nukeball.bin");
            return NukeBallSurface.parse(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Доля p узора кадра {@code frame} (с долей между кадрами — ближний) по всей сфере, по телесному углу. */
    private static double percentile(NukeBallSurface.Data d, double frame, double q) {
        int k = (int) Math.round(Math.max(frame, 0)), n = d.side();
        List<double[]> all = new ArrayList<>();
        double total = 0;
        for (int v = 0; v < n; v++) {
            for (int u = 0; u < n; u++) {
                double px = 2 * (u + 0.5) / n - 1, pz = 2 * (v + 0.5) / n - 1, y = 1 - Math.abs(px) - Math.abs(pz), x = px, z = pz;
                if (y < 0) {
                    x = (1 - Math.abs(pz)) * Math.signum(px);
                    z = (1 - Math.abs(px)) * Math.signum(pz);
                }
                double l1 = Math.abs(x) + Math.abs(y) + Math.abs(z), l2 = Math.sqrt(x * x + y * y + z * z);
                double w = Math.pow(l1 / l2, 3);
                all.add(new double[]{d.at(k, u, v), w});
                total += w;
            }
        }
        all.sort((a, b) -> Double.compare(a[0], b[0]));
        double acc = 0;
        for (double[] e : all) {
            acc += e[1];
            if (acc >= q * total) return e[0];
        }
        return all.getLast()[0];
    }

    /** Яркость на экране (мера экрана, степень 1/2,2) поверхности шара температуры {@code kelvin} при экспозиции {@code gain}. */
    private static double shown(double kelvin, double adapt, double gain) {
        double[] l = new double[4], c = new double[3];
        BlackBody.light(kelvin, l);
        NukeExposure.screen(l[0] * adapt, l[1] * adapt, l[2] * adapt, gain, c);
        return Math.pow(0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2], 1 / 2.2);
    }

    /** То же в мере белой стены без экспозиции шара — как было: ярче белого срезано. */
    private static double clipped(double kelvin, double adapt) {
        return Math.pow(Math.min(1, BlackBody.luminance(kelvin) * adapt), 1 / 2.2);
    }

    /**
     * Шар не превращается в ровный диск (жалоба FX 23.10: 15 кт на 3–5 с — плоский солнечный диск): на 1–5 с перепад
     * температуры по поверхности — узор шара (10-я и 90-я доли по сфере), как его рисует {@code mushAt}: T = T_окр +
     * ΔT_ядра·доля края·p, — на экране не меньше 10 % яркости светлого, днём и ночью (камера выставлена по огню). В мере
     * белой стены (как было) перепад — ноль.
     */
    @Test
    void fireballKeepsItsPatternFromOneToFiveSeconds() {
        NukeBallSurface.Data d = pattern();
        StringBuilder log = new StringBuilder();
        for (double y : new double[]{1, 15}) {
            for (boolean surface : new boolean[]{false, true}) {
                double hob = surface ? 0 : 240 * Math.cbrt(y);
                for (double t = 1; t <= 5 + 1e-9; t += 0.5) {
                    double[] ball = new double[2];
                    MushroomField.ballSurface(t, hob, y, surface, d.rises(), ball);
                    double contrast = MushroomFieldContrast.of(ball[0]);
                    double lo = 1 + (percentile(d, ball[0], 0.1) - 1) * contrast * ball[1];
                    double hi = 1 + (percentile(d, ball[0], 0.9) - 1) * contrast * ball[1];
                    double core = MushroomModel.coreTemperature(t, y, surface), share = MushroomModel.edgeShare(t, y, surface);
                    double amb = FireballModel.AMBIENT_K, dT = core - amb;
                    for (double adapt : new double[]{DAY, NIGHT}) {
                        double gain = NukeExposure.gain(BlackBody.luminance(amb + dT * share) * adapt, adapt);
                        double bright = shown(amb + dT * share * hi, adapt, gain), dark = shown(amb + dT * share * lo, adapt, gain);
                        double spread = (bright - dark) / bright;
                        log.append(String.format(Locale.ROOT, "%n%2.0f кт %s %.1f с %s: экран %.3f…%.3f, перепад %.3f (было %.3f)", y,
                                surface ? "земля" : "воздух", t, adapt == DAY ? "день" : "ночь", dark, bright, spread,
                                (clipped(amb + dT * share * hi, adapt) - clipped(amb + dT * share * lo, adapt)) / clipped(amb + dT * share * hi, adapt)));
                        assertTrue(spread >= 0.10, "ровный диск:" + log);
                    }
                }
            }
        }
        // как было: в мере белой стены у 15 кт на 3 с днём перепада нет вовсе
        double core = MushroomModel.coreTemperature(3, 15, false), dT = core - FireballModel.AMBIENT_K;
        assertEquals(0, clipped(FireballModel.AMBIENT_K + 0.9 * dT, DAY) - clipped(FireballModel.AMBIENT_K + 1.1 * dT, DAY), 1e-9);
    }

    /** Помощник: видимость узора по месту кадра ({@code mushBallContrast}): до отрыва фронта он проступает. */
    private static final class MushroomFieldContrast {
        static double of(double frame) {
            return Math.max(0, Math.min(1, frame + 1));
        }
    }

    /**
     * Остывая, шар на экране темнеет и уходит в оранжевое и красное, а не стоит одной яркостью: поверхность у 15 кт на
     * экране с 0,5 по 10 с только темнеет, днём и ночью, на 0,5 с — почти белая (≥ 0,9 экрана), на 7 с — не ярче 0,6.
     * А горячее нутро в просветах свернувшейся шапки (ядро при экспозиции по поверхности) на 7–10 с — снова пересвечено,
     * как было: кадр вернулся к экспозиции сцены.
     */
    @Test
    void coolingBallDarkensOnScreen() {
        for (double adapt : new double[]{DAY, NIGHT}) {
            double last = 2;
            for (double t : new double[]{0.5, 1, 2, 3, 5, 7, 10}) {
                double edge = MushroomModel.edgeTemperature(t, 15, false), core = MushroomModel.coreTemperature(t, 15, false);
                double gain = NukeExposure.gain(BlackBody.luminance(edge) * adapt, adapt);
                double s = shown(edge, adapt, gain);
                assertTrue(s < last, t + " с: " + s + " после " + last);
                if (t == 0.5) assertTrue(s >= 0.9, "0,5 с: " + s);
                if (t == 7) assertTrue(s <= 0.6, "7 с: " + s);
                if (t >= 7) assertTrue(shown(core, adapt, gain) > 0.95, t + " с: нутро шапки " + shown(core, adapt, gain));
                last = s;
            }
        }
    }

    /**
     * Тусклое свечение — как было: экспозиция почти не меняется (на экране тусклее 0,05 белого днём и 0,01 ночью — в
     * пределах 10 %), и плёночная кривая не ярче белого и монотонна.
     */
    @Test
    void dimGlowKeepsItsExposureAndTheCurveIsMonotonic() {
        assertTrue(NukeExposure.gain(0.05, DAY) > 0.9 && NukeExposure.gain(0.01, NIGHT) > 0.9, "у тусклого — почти без поправки");
        double last = -1;
        for (double x = 0; x < 40; x += 0.01) {
            double f = NukeExposure.film(x);
            assertTrue(f >= last && f <= 1, "x " + x);
            last = f;
        }
        // экспозиция шара по ядру — только темнее и тем темнее, чем ярче ядро; вход кривой растёт с яркостью
        double lastKey = 0;
        for (double key = 0.01; key < 1e6; key *= 1.1) {
            double x = key * NukeExposure.gain(key, DAY);
            assertTrue(NukeExposure.gain(key, DAY) <= 1 && x > lastKey, "ключ " + key);
            lastKey = x;
        }
    }

    /**
     * Предел света шара на оболочке Вильсона и у блика ({@link NukeExposure#cap}) — сам шар на экране: его поверхность, как
     * её рисует {@code mushAt} (экспозиция по поверхности), и не ярче горячего нутра; во втором максимуме — почти белый.
     */
    @Test
    void capIsTheBallOnScreen() {
        double[] l = new double[4], cap = new double[3], edge = new double[3], core = new double[3];
        for (double t : new double[]{0.12, 0.5, 1, 3, 5, 7}) {
            double te = MushroomModel.edgeTemperature(t, 15, false), tc = MushroomModel.coreTemperature(t, 15, false);
            double gain = NukeExposure.gain(BlackBody.luminance(te), DAY);
            BlackBody.light(te, l);
            NukeExposure.cap(l[0], l[1], l[2], DAY, cap);
            NukeExposure.screen(l[0], l[1], l[2], gain, edge);
            BlackBody.light(tc, l);
            NukeExposure.screen(l[0], l[1], l[2], gain, core);
            for (int c = 0; c < 3; c++) {
                assertEquals(edge[c], cap[c], 2e-3, t + " с, канал " + c);
                assertTrue(cap[c] <= core[c] + 1e-4, t + " с, канал " + c + ": " + cap[c] + " ярче нутра " + core[c]);
            }
        }
        BlackBody.light(FireballModel.PEAK_K, l);
        NukeExposure.cap(l[0], l[1], l[2], DAY, cap);
        for (double c : cap) assertTrue(c > 0.85, "во втором максимуме — почти белый: " + c);
    }

    /**
     * Кадр во вспышку: у 15 кт в 1,4 км днём — белый до второго максимума, через ~0,25 с после него тёмное уже видно
     * (пересвет меньше 100 — рассеянный в объективе свет не белит кадр), через 0,5 с пересвет не больше 4, через секунду —
     * не больше 1,15, а через 1,5 с кадр догнал вспышку (прежде белый экран стоял 1,24 с импульса и гас ещё 2–4 с). Свет
     * у камеры — физика ({@code NukeFlash.flashOnCamera}): ~2 000 полуденных белых стен в 1,4 км и ~200 в 5 км.
     */
    @Test
    void frameCatchesUpWithTheFlashInASecond() {
        double near = flash(1400), far = flash(5000);
        assertTrue(near > 1000 && near < 4000, "1,4 км: " + near);
        assertTrue(far > 100 && far < 400, "5 км: " + far);
        double tm = FireballModel.secondMaximumSeconds(15);
        assertEquals(near, NukeExposure.overexposure(near, -0.05), 1e-9, "до максимума — весь свет вспышки");
        assertTrue(NukeExposure.overexposure(near, 0.25) < 100, "0,25 с");
        assertTrue(NukeExposure.overexposure(near, 0.5 - tm) < 4, "0,5 с");
        assertTrue(NukeExposure.overexposure(near, 1 - tm) < 1.15, "1 с");
        assertTrue(NukeExposure.overexposure(near, 1.5 - tm) < 1.05, "1,5 с");
        assertTrue(NukeExposure.settled(near, 1.004) < 2, "кадр догнал вспышку");
        // вдали — раньше, ночью — позже
        assertTrue(NukeExposure.overexposure(far, 0.5) < NukeExposure.overexposure(near, 0.5));
        assertTrue(NukeExposure.overexposure(near * NIGHT, 0.5) > NukeExposure.overexposure(near, 0.5));
        double last = Double.MAX_VALUE;
        for (double u = 0; u < 3; u += 0.01) {
            double x = NukeExposure.overexposure(near, u);
            assertTrue(x <= last && x >= 1, "без скачков вверх: " + u);
            last = x;
        }
    }

    /**
     * Оболочка облака Вильсона видит шар диском в десятки градусов ({@code hgDisc} в {@code fx_pall}): к оси — средняя по
     * конусу диска фаза Хеньи–Гринстейна (численный интеграл фазы по конусу — тот же), а не пик точечного источника (у
     * капель g 0,85 — 82 против ~11 у диска в 30°); у далёкого шара — сама фаза; дальше от оси — фаза.
     */
    @Test
    void wilsonShellSeesTheBallAsADisc() throws IOException {
        String fx;
        try (InputStream in = NukeExposureTest.class.getResourceAsStream("/assets/incoming/shaders/core/fx_pall.fsh")) {
            assertNotNull(in, "fx_pall.fsh");
            fx = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        }
        GlslFunction disc = GlslFunction.parse(fx, "hgDisc"), hg = GlslFunction.parse(fx, "hg");
        double g = 0.85;
        for (double alpha : new double[]{5, 15, 30, 60}) {
            double mu0 = Math.cos(Math.toRadians(alpha)), sum = 0;
            int n = 20000;
            for (int i = 0; i < n; i++) {
                double mu = mu0 + (1 - mu0) * (i + 0.5) / n;
                sum += hg.call(Map.of("g", g, "c", mu));
            }
            assertEquals(sum / n, disc.call(Map.of("g", g, "c", 1.0, "mu0", mu0)), 1e-3 * sum / n, "диск " + alpha + "°");
            assertEquals(hg.call(Map.of("g", g, "c", -0.5)), disc.call(Map.of("g", g, "c", -0.5, "mu0", mu0)), 1e-9, "назад — фаза");
        }
        assertEquals(hg.call(Map.of("g", g, "c", 1.0)), disc.call(Map.of("g", g, "c", 1.0, "mu0", 1 - 1e-5)), 1e-6, "точка — пик фазы");
        assertTrue(disc.call(Map.of("g", g, "c", 1.0, "mu0", Math.cos(Math.toRadians(30)))) < 0.2 * hg.call(Map.of("g", g, "c", 1.0)));
    }

    /** Свет шара у камеры во втором максимуме днём, в ясную погоду (как {@code NukeFlash.flashOnCamera}). */
    private static double flash(double distM) {
        double r = FireballModel.maxRadius(15, false);
        return BlackBody.luminance(FireballModel.PEAK_K) * r * r / Math.max(distM * distM, r * r) * ThermalModel.transmittance(distM, Visibility.CLEAR);
    }
}
