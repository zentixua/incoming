package ua.zentix.incoming.util;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Heights above sea level start at the world's sea level ({@link Terrain#sea}), not a fixed 63: a flat world (−63) or a
 * pack with a raised sea keeps its wind profile, water plane and haze where its sea is.
 */
class SeaLevelTest {
    private static final Path MAIN = Path.of("src/main/java/ua/zentix/incoming");
    /** Level-free laws that may use the reference sea level {@code Air.SEA} (its javadoc names them), with their use count. */
    private static final Map<String, Integer> REFERENCE_USES = Map.of("util/Air.java", 4, "guidance/Ballistics.java", 1,
            "strike/WeaponSpec.java", 3);

    @Test
    void serverReadsItsGenerator() {
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        ChunkGenerator generator = mock(ChunkGenerator.class);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getGenerator()).thenReturn(generator);
        when(generator.getSeaLevel()).thenReturn(-63);
        assertEquals(-63, Terrain.sea(level));
    }

    @Test
    void clientUsesWhatTheServerSentForItsLevelOnly() {
        ClientLevel level = mock(ClientLevel.class), other = mock(ClientLevel.class);
        Terrain.seaFromServer(level, 140);
        assertEquals(140, Terrain.sea(level));
        // another level (a new dimension before its packet, a new world) falls back to vanilla, never a stale value
        assertEquals(Terrain.VANILLA_SEA, Terrain.sea(other));
        Terrain.seaFromServer(other, -63);
        assertEquals(-63, Terrain.sea(other));
        assertEquals(Terrain.VANILLA_SEA, Terrain.sea(level));
    }

    @Test
    void windProfileStartsAtTheWorldsSea() {
        Air vanilla = Air.of(5_000, 0.5, 0, 0), flat = Air.of(5_000, 0.5, 0, 0, -63);
        for (double h : new double[]{2, 10, 100, 1000})
            assertEquals(vanilla.speed(Terrain.VANILLA_SEA + h), flat.speed(-63 + h), 1e-12, "wind at " + h + " m above sea");
        // 10 m above a flat world's sea is not the profile's floor
        assertTrue(flat.speed(-53) > flat.speed(-62) * 1.2);
        // unknown ground (open site): height above the world's sea
        assertEquals(10, LocalWind.OPEN.above(-53, -63), 1e-12);
    }

    /** The reference sea level in the drag law: a flat world's offset (126 m) changes air density by 1.2 % ({@code Ballistics.Drag}). */
    @Test
    void referenceSeaShiftsDragDensityLittle() {
        double shift = 1 - StandardAtmosphere.density(126) / StandardAtmosphere.density(0);
        assertEquals(0.012, shift, 0.0005);
    }

    /** No world code reads a fixed sea level: {@code Air.SEA} only in the named level-free laws, no other 63 constant. */
    @Test
    void noFixedSeaLevelInWorldCode() throws IOException {
        Pattern reference = Pattern.compile("\\bAir\\.SEA\\b|(?<![\\w.])SEA\\b(?!_)");
        Pattern fixed = Pattern.compile("SEA_LEVEL\\s*=|getSeaLevel\\(");
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (fixed.matcher(src).find() && !Set.of("util/Terrain.java", "util/TerrainSurvey.java").contains(rel))
                    bad.add(rel + ": fixed sea level");
                if (rel.equals("util/Terrain.java")) continue;
                int uses = 0;
                Matcher m = Pattern.compile("\\bAir\\.SEA\\b").matcher(src);
                while (m.find()) uses++;
                if (rel.equals("util/Air.java")) {
                    Matcher own = Pattern.compile("(?<![\\w.])SEA\\b(?!_)").matcher(src);
                    while (own.find()) uses++;
                }
                if (uses != REFERENCE_USES.getOrDefault(rel, 0)) bad.add(rel + ": Air.SEA used " + uses + " times");
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad) + "\n(use util/Terrain.sea(level) or Air.sea())");
        assertTrue(reference.matcher("Air.SEA").find());
    }
}
