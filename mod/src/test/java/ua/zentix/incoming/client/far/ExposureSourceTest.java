package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One eye adjustment per frame ({@link Sight#exposure}, {@link FarView#exposure}): everything that the effects layer paints with the light of
 *  of a conventional weapon - the body of fire, flare and veil, light on smoke, particles and the world, the jet from the entity - takes the addiction of the frame,
 *  and not just one night in the light of the sky ({@link Sight#adapted}). Otherwise, there are two eyes in one frame: the light on the world got used to the fire, but the glare of the torch in 8 m did not, and the night start filled the frame with orange (22.10). {@link Sight#adapted} - only for the nuclear
 *  picture (her eye is her own, the nuke does not change this), for {@link Sight} itself and for the flash on the screen ({@code FlashFalloff}:
 *  at the moment of the flash the eye is not yet used to it - it gets used to it).
 */
class ExposureSourceTest {
    private static final Path CLIENT = Path.of("src/main/java/ua/zentix/incoming/client");
    /** Call adapted(…) without a destination (static import): not after a letter, number or period. */
    private static final java.util.regex.Pattern ADAPTED = java.util.regex.Pattern.compile("(?<![\\w.])adapted\\(");
    private static final Set<String> ALLOWED = Set.of("far/Sight.java", "fx/FlashFalloff.java", "fx/layer/PallPass.java");

    @Test
    void conventionalLightUsesTheFrameExposure() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CLIENT)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String rel = CLIENT.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("nuclear/") || ALLOWED.contains(rel)) continue;
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("*") || line.startsWith("//")) continue;
                    if (line.contains("Sight.adapted") || ADAPTED.matcher(line).find() && !line.contains("double adapted(")) bad.add(rel + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), bad, "light of conventional weapons with night addictive, past addictive frame");
    }

    /**
     * billows of the smoke layer shines the same sky light as the blocks and particles in the open air ({@link FarView#sky}, light map),
     *  and not {@link FarView#ambient}: with it, white smoke at night came out darker than the moonlit stone nearby (night trail of ZRK
     *  clubs, 22.10).
     */
    @Test
    void puffsTakeTheWorldsSkyLight() throws IOException {
        String text = Files.readString(CLIENT.resolve("far/SmokePuffs.java"));
        assertEquals(false, text.contains("view.ambient()"), "SmokePuffs: sky light billows - FarView.sky, not ambient");
        assertEquals(true, text.contains("view.skyLight()"), "SmokePuffs: sky light billows – FarView.sky");
    }
}
