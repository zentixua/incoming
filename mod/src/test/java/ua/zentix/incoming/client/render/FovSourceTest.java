package ua.zentix.incoming.client.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The size and place on the screen of what is attached to the world is only from the frame matrix ({@link ScreenProjection}): zoom modes
 *  (Ok Zoomer - at the output {@code GameRenderer.getFov}) change the field of view after the event {@code ComputeFov} and past
 * {@code options.fov}, and the size taken from there lies when zoomed in (complaint 23.10: marks on zoom). Field of view from settings
 * or events read only places from the list below - each has a reason why they don't need a zoom.
 */
class FovSourceTest {
    private static final Path CLIENT = Path.of("src/main/java/ua/zentix/incoming/client");
    /** Reading the field of view bypassing the frame matrix: setting, event {@code ComputeFov}, {@code getFov} itself. */
    private static final Pattern FOV = Pattern.compile("options\\.fov\\(\\)|\\.getFOV\\(\\)|gameRenderer\\.getFov|\\.fov\\(\\)\\.get\\(\\)");

    /**
     * Allowed places: file → how many times.
     * <ul>
     * <li>{@code nuclear/NukeFlash.java} - player's retina: afterimage, when the frame is not through his eyes (projectile camera, third-person view), and retinal burns that lie on itself - field seeing his eyes from the settings; from his eyes
     * the afterimage takes the frame matrix.</li>
     * <li>{@code cam/ProjectileCamera.java} - the projectile camera sets the field of view (in the event), and does not measure the screen with it.</li>
     * </ul>
     */
    private static final Map<String, Integer> ALLOWED = Map.of("nuclear/NukeFlash.java", 2, "cam/ProjectileCamera.java", 3);

    @Test
    void screenSizesTakeTheFieldOfViewOnlyFromTheFrameMatrix() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(CLIENT)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                Matcher m = FOV.matcher(Files.readString(p));
                int n = 0;
                while (m.find()) n++;
                if (n > 0) found.put(CLIENT.relativize(p).toString().replace('\\', '/'), n);
            }
        }
        assertEquals(new TreeMap<>(ALLOWED), found, "the field of view is not from the frame matrix: the size on the screen with zoom will be different from the world");
    }
}
