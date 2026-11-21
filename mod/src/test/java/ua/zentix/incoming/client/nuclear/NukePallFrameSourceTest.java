package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A view frame takes its eye medium first ({@code NukePall.eyeMedium}: the floors' gate {@code f.inside}, the ray cutoff, the drawn
 * dust shares) and nothing later in {@code frame} overwrites the gate: a later {@code f.inside = 0} turned the inside light floor off
 * on every camera (review round 2: diffuse light 0.10 → 0.00002 at depth 20).
 */
class NukePallFrameSourceTest {
    @Test
    void frameKeepsTheEyeGate() throws IOException {
        String src = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/nuclear/NukePall.java"));
        int start = src.indexOf("public static PallPass.Frame frame("), end = src.indexOf("\n    }\n", start);
        assertTrue(start >= 0 && end > start);
        String body = src.substring(start, end);
        int medium = body.indexOf("else eyeMedium(level, eye, partial, f);");
        assertTrue(medium > 0, "frame takes its eye medium");
        String after = body.substring(medium);
        assertFalse(after.contains("f.inside ="), "nothing after eyeMedium overwrites the gate");
        assertFalse(after.contains("f.eyeDust.set(") || after.contains("f.eyeDust.zero()"), "nothing after eyeMedium overwrites the eye medium");
        assertTrue(medium < body.indexOf("MushroomField.frame("), "the eye medium comes before the mushroom tables (stem foot share)");
    }
}
