package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.PuffOptics;

import java.io.IOException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Клуб несёт гаусс модели ({@link PuffOptics}): нарисованный по пикселям кадра так, как его берёт шейдер ({@link ClubFrames}),
 * он несёт то же вещество (Σ τ по экрану = 2π·σ²·τ₀) и одиночным закрывает столько же (Σ (1 − e^(−τ)) = 2π·σ²·Ein(τ₀)), что
 * гаусс с толщей середины τ₀, — у каждого кадра и от едва заметного дыма до непрозрачного. До 3.6.1 квадрат клуба был
 * «видимым краем» гаусса с непрозрачностью середины по всей площади кадра: тонкий дым терял до 80 % вещества, густой — в
 * зависимости от кадра от −60 до +40 % (FZC: части кадра налёта светлее модели до 0,4).
 */
class PuffOpticsTest {
    /** Ein(x) = ∫₀^x (1 − e^(−t))/t dt — против прямого интеграла (Симпсон). */
    @Test
    void entireExponentialIntegral() {
        for (double x : new double[]{1e-4, 0.01, 0.5, 3.9, 4.1, 20, 300}) {
            int n = 200000;
            double h = x / n, sum = 0;
            for (int i = 0; i <= n; i++) {
                double t = i * h, f = t < 1e-12 ? 1 : -Math.expm1(-t) / t;
                sum += f * (i == 0 || i == n ? 1 : i % 2 == 1 ? 4 : 2);
            }
            assertEquals(sum * h / 3, PuffOptics.ein(x), 1e-9 * Math.max(1, x), "Ein(" + x + ")");
        }
    }

    /**
     * У каждого кадра дыма и брызг и смеси со следующим (и между узлами таблиц, по ⅛ кадра) клуб несёт вещество гаусса
     * точно и закрывает столько же, сколько он (1 %).
     */
    @Test
    void clubCarriesTheGaussiansMatterAndShadow() throws IOException {
        ClubFrames.install();
        double[] c = new double[2];
        int n = ClubFrames.N;
        for (boolean sprays : new boolean[]{false, true}) {
            PuffOptics optics = sprays ? PuffOptics.spray() : PuffOptics.puff();
            for (double frame = SmokePuffs.FIRST_FRAME; frame < FxAtlas.PUFF6_FRAMES - 1; frame += 0.375) {
                int f = (int) frame;
                double mix = frame - f;
                for (double tau0 : new double[]{1e-3, 0.01, 0.1, 0.3, 1, 3, 10, 30, 100, 1000}) {
                    optics.club(frame, tau0, c);
                    // σ = 1: квадрат полуразмером c, пиксель кадра — (2c/n)² площади
                    double dA = 4 * c[0] * c[0] / ((double) n * n), matter = 0, shadow = 0;
                    for (int y = 0; y < n; y++) {
                        for (int x = 0; x < n; x++) {
                            double t = ClubFrames.depth(sprays, f, mix, false, (x + 0.5) * 2.0 / n - 1, 1 - (y + 0.5) * 2.0 / n, c[1]);
                            matter += t * dA;
                            shadow += -Math.expm1(-t) * dA;
                        }
                    }
                    String at = String.format(Locale.ROOT, "%s, кадр %.3f, τ₀ %.3g: c %.2f, s %.3g", sprays ? "брызги" : "дым", frame, tau0, c[0], c[1]);
                    assertEquals(2 * Math.PI * tau0, matter, 0.01 * 2 * Math.PI * tau0, at + ": вещество");
                    double ein = 2 * Math.PI * PuffOptics.ein(tau0);
                    assertEquals(ein, shadow, 0.01 * ein, at + ": закрывает");
                    assertTrue(c[0] < 6, at + ": полуразмер");
                }
            }
        }
    }

    /** Множитель толщи клуба доходит до шейдера в {@code UV2.x} ({@code fx.vsh}: exp2(UV2.x / 1024)) с точностью 0,07 %. */
    @Test
    void tauSurvivesTheVertex() {
        for (double tau : new double[]{1e-6, 3.7e-3, 0.5, 1, 7.25, 4e3, 1e6}) {
            double back = Math.pow(2, ua.zentix.incoming.client.fx.layer.FxQuads.tauCode(tau) / (double) ua.zentix.incoming.client.fx.layer.FxQuads.TAU_STEPS);
            assertEquals(tau, back, 7e-4 * tau, "tau " + tau);
        }
    }
}
