package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** The production shader's ordinary-front display bound, after projection and summing the two slots. */
class ChemicalFrontGlslTest {
    @Test
    void ordinaryFrontCannotPullDistantFlashPixelsIntoAnOpaqueDome() throws IOException {
        try (var in = getClass().getResourceAsStream("/assets/incoming/shaders/core/fx_heat.fsh")) {
            assertNotNull(in);
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
            GlslFunction scale = GlslFunction.parse(source, "chemicalFrontScale");
            assertEquals(24, scale.call(Map.of("pixels", 0.0)));
            double previous = 0;
            // Includes subpixel ripples, close strong shocks, zoom and high-resolution projections.
            for (double pixels = 1e-8; pixels < 1e6; pixels *= 1.05) {
                double factor = scale.call(Map.of("pixels", pixels)), shown = pixels * factor;
                assertTrue(shown <= 3.0 + 1e-12, "ordinary front shifted " + shown + " px");
                assertTrue(shown >= previous, "no reversal at the limiter");
                assertTrue(factor > 0, "direction is preserved");
                previous = shown;
            }
            // Two aligned overlapping fronts share one bound, rather than each spending three pixels.
            double twoFronts = 20 + 30;
            assertTrue(twoFronts * scale.call(Map.of("pixels", twoFronts)) <= 3);
            assertEquals(0.24, 0.01 * scale.call(Map.of("pixels", 0.01)), 0.001);
        }
    }
}
