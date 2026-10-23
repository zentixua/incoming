package ua.zentix.incoming.client.far;

import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.PuffOptics;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Кадры запечённого клуба в проверках без видеокарты: непрозрачность по пикселям кадра — так, как её берёт шейдер
 * ({@code fx.fsh}: смесь кадра со следующим, толща кадра × tau, 1 − (1 − α)^tau), и оптика листа ({@link PuffOptics}) по
 * тем же картинкам, что в игре.
 */
final class ClubFrames {
    static final int N = 128;
    private static int[][] puff, spray;

    private ClubFrames() {}

    /** Байты альфы кадров карты A (дым или брызги). */
    static synchronized int[][] alpha(boolean sprays) throws IOException {
        if (puff == null) {
            puff = read("puff6a");
            spray = read("spray6a");
        }
        return sprays ? spray : puff;
    }

    private static int[][] read(String name) throws IOException {
        int[][] out = new int[FxAtlas.PUFF6_FRAMES][N * N];
        for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
            String path = String.format(Locale.ROOT, "/assets/incoming/textures/fx/particle/%s_%02d.png", name, f);
            try (InputStream in = ClubFrames.class.getResourceAsStream(path)) {
                assertNotNull(in, path);
                BufferedImage img = ImageIO.read(in);
                if (img.getWidth() != N || img.getHeight() != N) throw new IllegalStateException(path + ": " + img.getWidth() + " px");
                for (int y = 0; y < N; y++) for (int x = 0; x < N; x++) out[f][y * N + x] = img.getRGB(x, y) >>> 24;
            }
        }
        return out;
    }

    /** Оптика листа — как у {@code FxAtlas} в игре. */
    static void install() throws IOException {
        PuffOptics.set(PuffOptics.of(alpha(false), N), PuffOptics.of(alpha(true), N));
    }

    /** Кадр (0…15) и отражён ли — по месту на листе. */
    static int frame(FxAtlas.Sprite sprite) {
        for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
            if (FxAtlas.puff6(f, false).equals(sprite) || FxAtlas.puff6(f, true).equals(sprite)) return f;
        }
        throw new IllegalArgumentException("не кадр клуба дыма: " + sprite);
    }

    static boolean mirrored(FxAtlas.Sprite sprite) {
        return FxAtlas.puff6(frame(sprite), true).equals(sprite);
    }

    /**
     * Непрозрачность клуба в точке (x, y) его квадрата (доли полуразмера, −1…1, y — вверх): кадр frame со смесью mix,
     * видимая доля a, множитель толщи tau.
     */
    static double at(int frame, double mix, boolean mirror, double x, double y, double a, double tau) throws IOException {
        return a * (1 - Math.exp(-depth(frame, mix, mirror, x, y, tau)));
    }

    /** Толща клуба в точке (x, y) его квадрата: −ln(1 − α) смеси кадров (байт 255 — {@link PuffOptics#TAU_MAX}) × tau. */
    static double depth(int frame, double mix, boolean mirror, double x, double y, double tau) throws IOException {
        return depth(false, frame, mix, mirror, x, y, tau);
    }

    /** То же у кадров дыма или брызг. */
    static double depth(boolean sprays, int frame, double mix, boolean mirror, double x, double y, double tau) throws IOException {
        if (Math.abs(x) >= 1 || Math.abs(y) >= 1) return 0;
        int[][] al = alpha(sprays);
        int ix = (int) ((mirror ? 1 - x : 1 + x) * 0.5 * N), iy = (int) ((1 - y) * 0.5 * N);
        ix = Math.min(ix, N - 1);
        iy = Math.min(iy, N - 1);
        int g = Math.min(frame + 1, FxAtlas.PUFF6_FRAMES - 1);
        double alpha = (al[frame][iy * N + ix] * (1 - mix) + al[g][iy * N + ix] * mix) / 255.0;
        return -Math.log(Math.max(1 - alpha, 1 / 255.0)) * tau;
    }
}
