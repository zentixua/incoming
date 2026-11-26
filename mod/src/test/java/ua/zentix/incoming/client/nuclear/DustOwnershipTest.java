package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.PallPass;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 (unified-wave acceptance): the dust wave is the only owner of nuclear ground dust, in every condition; the old wave's
 * drawings cannot come back unnoticed.
 */
class DustOwnershipTest {
    /** Under water, with one to three detonations, in a secondary view, at every preset, with programs ready or not. */
    @Test
    void theDustWaveIsTheOnlyOwnerInEveryCondition() {
        assertEquals(1, DustSources.Owner.values().length, "no other owner type exists to select");
        for (boolean water : new boolean[] {false, true})
            for (int n = 1; n <= 3; n++)
                for (boolean secondary : new boolean[] {false, true})
                    for (PallPass.Quality q : PallPass.Quality.values())
                        for (boolean ready : new boolean[] {false, true}) {
                            var c = new DustSources.Conditions(water, n, secondary, q, ready);
                            Optional<DustSources.Owner> owner = DustSources.owner(c);
                            if (q == PallPass.Quality.OFF || !ready) assertTrue(owner.isEmpty(), "nothing else draws the dust: " + c);
                            else assertEquals(Optional.of(DustSources.Owner.DUST_WAVE), owner, c.toString());
                        }
    }

    /** The pall's table has the haze row alone: no dust or spray row it could draw ground dust from. */
    @Test
    void thePallHasNoDustRow() {
        assertEquals(1, PallField.ROWS);
    }

    /**
     * The names of the old wave's drawings (the pall's head and its handoff, the young/pall crossfade, the pall's base surge, the
     * eye's lofted share, the fallback camera billows and the HUD veil): none may appear in the mod's code or shaders.
     */
    static final Pattern OLD = Pattern.compile("pallWall|PallWall|PallYoung|wall_ownership|wallShare|youngThrough|eyeLofted|addLoftedEye"
            + "|drawsHead|PallWater0|PallWater1|pallSurge|NukeDust\\.veil|veilWanted|dustColor\\(");

    static List<String> offenders(Path root) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".glsl") || f.toString().endsWith(".fsh")
                            || f.toString().endsWith(".vsh") || f.toString().endsWith(".json"))::iterator) {
                var m = OLD.matcher(Files.readString(p));
                if (m.find()) found.add(root.relativize(p) + ": " + m.group());
            }
        }
        return found;
    }

    @Test
    void noOldPathComesBack() throws IOException {
        List<String> found = new ArrayList<>(offenders(Path.of("src/main/java")));
        found.addAll(offenders(Path.of("src/main/resources/assets/incoming/shaders")));
        assertTrue(found.isEmpty(), "old ground-dust paths: " + found);
    }

    /** Mutation proof: the check finds each old name put back. */
    @Test
    void theCheckCatchesAnOldPathPutBack(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        for (String back : new String[] {"uniform mat4 PallWall0;", "float s = wallShare(PallYoung, r);", "f.eyeLofted.zero();",
                "if (!drawsHead(a, eye)) NukeDust.veil(a, eye, s, false);", "uniform vec4 PallWater0;"}) {
            Path f = dir.resolve("Back.java");
            Files.writeString(f, "class Back { void f() { " + back + " } }");
            assertFalse(offenders(dir).isEmpty(), back);
        }
    }
}
