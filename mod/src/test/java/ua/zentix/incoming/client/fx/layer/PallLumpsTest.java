package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The young dust's relief octaves ({@code pallReliefOctave}, {@code pallTopRelief}, constants {@code PALL_LUMP_*} in {@code pall.glsl}) on the production noise tile
 * ({@link VolumeNoise}, the seed {@link PallPass} uploads): the mean and σ of the R and B channels in equal parts are the shader's
 * constants, the octaves' mean is 0 and the top stays within the relief's bounds (the bounds take its rise): a density zero opened
 * holes lit by the mean column above, black voids in the 1 Mt wall (6.7 % of the dust near black at 4 s).
 */
class PallLumpsTest {
    private static double constant(String src, String name) {
        Matcher m = Pattern.compile("const float " + name + " = ([0-9.]+);").matcher(src);
        assertTrue(m.find(), name);
        return Double.parseDouble(m.group(1));
    }

    @Test
    void lumpsKeepTheMeanAndNeverOpenTheLayer() throws IOException {
        String src;
        try (InputStream in = PallLumpsTest.class.getResourceAsStream("/assets/incoming/shaders/include/pall.glsl")) {
            assertNotNull(in);
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        double mean = constant(src, "PALL_LUMP_MEAN"), sd = constant(src, "PALL_LUMP_SD");
        byte[] v = VolumeNoise.generate(VolumeNoise.SIZE, 0x9A11L);
        int n = v.length / VolumeNoise.CHANNELS;
        double s = 0, s2 = 0, octave = 0;
        double[] m = new double[n];
        for (int i = 0; i < n; i++) {
            double x = 0.5 * ((v[3 * i] & 255) / 255.0 + (v[3 * i + 2] & 255) / 255.0);
            m[i] = x;
            s += x;
            s2 += x * x;
        }
        double tileMean = s / n, tileSd = Math.sqrt(s2 / n - tileMean * tileMean);
        assertEquals(tileMean, mean, 0.002, "PALL_LUMP_MEAN is the tile's mean");
        assertEquals(tileSd, sd, 0.002, "PALL_LUMP_SD is the tile's σ");
        for (double x : m) octave += Math.max(-1, Math.min(1, (x - mean) / (2 * sd)));
        assertEquals(0, octave / n, 0.002, "an octave's mean is 0");
        double lo = constant(src, "PALL_RELIEF_LO"), hi = constant(src, "PALL_RELIEF_HI");
        assertEquals(hi - 1, constant(src, "PALL_RELIEF"), 1e-9, "PALL_RELIEF is the relief's rise");
        assertEquals(ua.zentix.incoming.client.nuclear.PallField.LUMP_AMP, hi - 1, 1e-9, "the bounds take the relief's rise");
        assertTrue(lo >= 0.5 && hi <= 1.15, "the top within " + lo + "…" + hi + " of the law's: at the 4.5 km ring of 1 Mt within ~85–90 m");
    }
}
