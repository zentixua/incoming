package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.Detonation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Wilson shell takes the sun at its crossing's altitude, as the mushroom and dust beside it. */
class WilsonShellSunTest {
    @Test
    void wilsonShellUsesTheBeamAtItsCrossingHeight() throws IOException {
        Detonation burst = new Detonation(1, new Vec3(0, 63, 0), 63, 1000, true, 0, 0, 0,
                20_000, 7, 1f, false, 1f);
        BlastWall wall = new BlastWall(burst, true);
        float[] slot = new float[BlastWall.SLOT];
        assertTrue(wall.slot(6, slot));
        assertTrue(slot[18] > 0, "A humid 1 Mt ground burst has a Wilson shell at six seconds");

        double height = 0.85 * slot[16];
        double sun = Math.sin(Math.toRadians(3));
        double[] ground = new double[3];
        double[] crossing = new double[3];
        Sight.sunThroughAir(sun, 0, ground);
        Sight.sunThroughAir(sun, height, crossing);
        String fixture = String.format(Locale.ROOT,
                "horizontal ray at %.0f m, sun 3 degrees: sea-level RGB %s; crossing RGB %s",
                height, Arrays.toString(ground), Arrays.toString(crossing));
        assertTrue(crossing[1] > 4 * ground[1], fixture);

        String source;
        try (InputStream in = getClass().getResourceAsStream("/assets/incoming/shaders/include/fx_pall.glsl")) {
            source = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher shell = Pattern.compile("vec4\\s+wilsonLight\\([^)]*\\)\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(source);
        assertTrue(shell.find(), "Wilson lighting entry point");
        assertFalse(shell.group(1).contains("PallSunLight"), fixture + "; wilsonLight currently applies the sea-level beam");
    }
}
