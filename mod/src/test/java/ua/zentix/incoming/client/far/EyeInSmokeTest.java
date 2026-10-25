package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.plume.TrailModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.io.IOException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Глаз в дыму (жалоба 24.10, финальный проход 3.7.0, сцена sam-burst: ветер несёт след ЗРК сквозь камеру). Кадр — квадраты
 * клубов и вуаль слоя ({@link SmokePuffs}, тот же отрезок трубки, что у следа, {@link FarFlightView#line}) — должен
 * повторять само вещество: пропускание по лучу из глаза через гауссову трубку. Прежде клубы трубки таяли, как только глаз
 * входил в их край (~3,7σ), а вещество шло в одну вуаль с толщей по середине экрана: весь кадр — ровная белая пелена, и над
 * трубкой, где луч её не встречает, тоже.
 */
class EyeInSmokeTest {
    /** Трубка следа ЗРК на ~9 с: σ 5 м, толща поперёк середины 2,7 (непрозрачность 0,93, {@code TrailModel.look}). */
    static final double SIGMA = 5, TAU = 2.7, E = TAU * Math.sqrt(2 * Math.PI) * SIGMA;
    /** Трубка вдоль z, от −LEN до LEN, кусками по STEP м. */
    static final double LEN = 150, STEP = 4;
    /** Частей кадра по горизонтали и вертикали. */
    static final int COLS = 6, ROWS = 3, SAMPLES = 8;

    /**
     * Трубку по ветру проносит сквозь глаз: ось — в d от глаза по горизонтали поперёк неё, от 4σ впереди до σ позади, шаг
     * 0,1σ; два взгляда — прямо на ось и на 40° выше (трубка уходит в низ кадра, верх смотрит мимо неё в небо). Кадр — 18
     * частей (6 × 3), пропускание части — среднее по 64 лучам.
     * <ul>
     * <li>Глаз в трубке (|d| ≤ σ: её клубы растаяли, вещество — вуаль) — каждая часть не дальше 0,15 от вещества (клубы
     * трубки гуляют на ~0,3σ — подробность, не ошибка).</li>
     * <li>Где вещество светлее вверху кадра, чем внизу (трубка в нижней части), чем на 0,1, кадр тоже светлее вверху — не
     * меньше половины этой разницы без 0,03: вуаль одним квадратом давала везде одно (прежде — ровная белая пелена и над
     * трубкой).</li>
     * <li>За шаг ни одна часть не меняется больше чем на 0,12: клубы сменяет вуаль без скачка.</li>
     * </ul>
     */
    @Test
    void tubeThroughTheEyeIsSeenRayByRay() throws IOException {
        double[][] prof = PlumeContinuityTest.profiles();
        for (double pitch : new double[]{0, 40}) {
            double inside = 0, jump = 0, flat = Double.POSITIVE_INFINITY;
            String insideAt = "", jumpAt = "", flatAt = "";
            double[] last = null;
            for (double d = 4 * SIGMA; d >= -SIGMA - 1e-9; d -= 0.1 * SIGMA) {
                Vector3f fwd = new Vector3f((float) Math.cos(Math.toRadians(pitch)), (float) Math.sin(Math.toRadians(pitch)), 0);
                FarView view = SmokeFillTest.view(new Vec3(0, 100, 0), fwd);
                SmokeFillTest.Screen screen = frame(view, d);
                double[] got = new double[COLS * ROWS], real = new double[COLS * ROWS];
                for (int i = 0; i < COLS; i++) {
                    for (int j = 0; j < ROWS; j++) {
                        for (int a = 0; a < SAMPLES; a++) {
                            for (int b = 0; b < SAMPLES; b++) {
                                double u = -SmokeFillTest.W + (i * SAMPLES + a + 0.5) * 2 * SmokeFillTest.W / (COLS * SAMPLES);
                                double v = -SmokeFillTest.H + (j * SAMPLES + b + 0.5) * 2 * SmokeFillTest.H / (ROWS * SAMPLES);
                                got[i * ROWS + j] += screen.transmittance(u, v, prof) / (SAMPLES * SAMPLES);
                                real[i * ROWS + j] += truth(view, d, u, v) / (SAMPLES * SAMPLES);
                            }
                        }
                    }
                }
                String at = String.format(Locale.ROOT, "ось в %.1f м: кадр %s, вещество %s", d, row(got), row(real));
                for (int k = 0; k < got.length; k++) {
                    if (Math.abs(d) <= SIGMA + 1e-9 && Math.abs(got[k] - real[k]) > inside) {
                        inside = Math.abs(got[k] - real[k]);
                        insideAt = at;
                    }
                    if (last != null && Math.abs(got[k] - last[k]) > jump) {
                        jump = Math.abs(got[k] - last[k]);
                        jumpAt = at;
                    }
                }
                for (int i = 0; i < COLS; i++) {
                    double want = real[i * ROWS + ROWS - 1] - real[i * ROWS], have = got[i * ROWS + ROWS - 1] - got[i * ROWS];
                    if (want > 0.1 && have - (0.5 * want - 0.03) < flat) {
                        flat = have - (0.5 * want - 0.03);
                        flatAt = at;
                    }
                }
                last = got;
            }
            String report = String.format(Locale.ROOT, "взгляд на %.0f° вверх: глаз в трубке — часть против вещества до %.3f (%s); верх против низа — запас %.3f (%s); шаг до %.3f (%s)",
                    pitch, inside, insideAt, flat, flatAt, jump, jumpAt);
            System.out.println("EYE " + report);
            assertTrue(inside <= 0.15, report);
            assertTrue(flat >= 0, report);
            assertTrue(jump <= 0.12, report);
        }
    }

    /** Части кадра строками сверху вниз. */
    private static String row(double[] parts) {
        StringBuilder b = new StringBuilder();
        for (int j = ROWS - 1; j >= 0; j--) {
            b.append(j == ROWS - 1 ? "[" : " / ");
            for (int i = 0; i < COLS; i++) b.append(String.format(Locale.ROOT, i == 0 ? "%.2f" : " %.2f", parts[i * ROWS + j]));
        }
        return b.append(']').toString();
    }

    /** Кадр: трубка с осью вдоль z в d м перед глазом по горизонтали поперёк неё, тем же отрезком трубки, что у следа. */
    private static SmokeFillTest.Screen frame(FarView view, double d) {
        SmokeFillTest.Screen screen = new SmokeFillTest.Screen(view);
        SmokePuffs.resetBudget();
        float[] joint = {Float.NaN, 0, 0};
        long seed = 91;
        for (double z = -LEN; z < LEN; z += STEP) {
            FarFlightView.Tube a = end(view, d, 0, z), b = end(view, d, 0, z + STEP);
            int drawn = FarFlightView.line(view, screen, a, b, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 1, 0.6, seed++, 1, joint);
            if (drawn != FarFlightView.RIBBON) joint[0] = Float.NaN;
        }
        SmokePuffs.flush();
        SmokePuffs.resetBudget();
        return screen;
    }

    /** Конец трубки в (dx, dy, dz) от глаза — как у следа ({@code FarFlightView.end}). */
    private static FarFlightView.Tube end(FarView view, double dx, double dy, double dz) {
        double tau = E / (Math.sqrt(2 * Math.PI) * SIGMA), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double width = TrailModel.width(tau, SIGMA);
        double[] rib = new double[2];
        FarFlightView.ribbon(width, d, view.pixel(), rib);
        return new FarFlightView.Tube().set(dx, dy, dz, SIGMA, E, width, rib[0], (float) ((1 - Math.exp(-tau)) * rib[1] * view.air(d, dy)), 0);
    }

    /**
     * Пропускание самого вещества по лучу из глаза в точку экрана (u, v): трубка — бесконечный гауссов цилиндр вдоль z с осью
     * в (d, 0) от глаза, ослабление на метр E: τ = E/(2πσ²)·e^(−r²/2σ²)·(σ/w)·√(π/2)·(1 + erf(w·t₀/(√2σ))), w — доля луча
     * поперёк оси, t₀ — ближайшая к оси точка луча, r — расстояние до оси там.
     */
    static double truth(FarView view, double d, double u, double v) {
        Vector3f f = view.forward(), l = view.left(), up = view.up();
        double rx = f.x + u * l.x + v * up.x, ry = f.y + u * l.y + v * up.y, rz = f.z + u * l.z + v * up.z, n = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double a = rx / n, b = ry / n, w2 = a * a + b * b;
        if (w2 < 1e-12) return Math.abs(d) < SIGMA ? 0 : 1;
        double w = Math.sqrt(w2), t0 = d * a / w2, r2 = d * d * b * b / w2;
        double tau = E / (2 * Math.PI * SIGMA * SIGMA) * Math.exp(-r2 / (2 * SIGMA * SIGMA)) * SIGMA / w * Math.sqrt(Math.PI / 2)
                * (1 + SmokeGrid.erf(w * t0 / (Math.sqrt(2) * SIGMA)));
        return Math.exp(-tau);
    }
}
