package ua.zentix.incoming.client.fx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.ConcussionDose;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Concussion on the screen ({@link Concussion}): activated by dose, shocks add up, type and strength - from the settings. */
class ConcussionTest {
    @AfterEach
    void clear() {
        Concussion.reset();
    }

    /** Below the threshold (dose 0) the screen does not touch: no impact, no level. */
    @Test
    void noDoseNoEffect() {
        assertEquals(0, Concussion.hit(0, 0));
        assertEquals(0, Concussion.hit(-0.3, 0));
        for (int t = 0; t < 80; t++) assertEquals(0, Concussion.level(t), "tick " + t);
    }

    /**
     * The volley blows are added up in fractions: together stronger than any one and no more than the full one; ran out (response length
     *  model) - no level.
     */
    @Test
    void salvoAddsUpToOne() {
        Concussion.hit(0.6, 0);
        double one = Concussion.level(2);
        Concussion.hit(0.6, 0);
        Concussion.hit(0.9, 0);
        double three = Concussion.level(2);
        assertTrue(one > 0 && three > one && three <= 1, one + " → " + three);
        int end = (int) Math.ceil(20 * ConcussionDose.durationSeconds(0.9)) + 1;
        for (int t = 0; t < end; t++) Concussion.tick();
        assertEquals(0, Concussion.level(end), 1e-12);
    }

    /** The front passed the body half a tick ago - the response began half a tick ago, and not at the beginning of the tick, where it was noticed. */
    @Test
    void startsAtTheFrontNotAtTheTick() {
        Concussion.hit(1, 0.5);
        double early = Concussion.level(0);
        Concussion.reset();
        Concussion.hit(1, 0);
        assertTrue(early > Concussion.level(0), "half a century ago - already stronger: " + early);
    }

    /** Picture: at zero - nothing; blur along the level cube goes away before grayness and edges; increases with frame height. */
    @Test
    void lookFollowsTheLevel() {
        double[] zero = Concussion.look(0, 1080), half = Concussion.look(0.5, 1080), full = Concussion.look(1, 1080);
        for (double v : zero) assertEquals(0, v);
        assertEquals(Concussion.BLUR_PX, full[0], 1e-9);
        assertEquals(0.125 * full[0], half[0], 1e-9, "blur - cube: disappears before grayness");
        assertEquals(0.5 * full[1], half[1], 1e-9);
        assertEquals(0.5 * full[2], half[2], 1e-9);
        assertEquals(2 * full[0], Concussion.look(1, 2160)[0], 1e-9, "in the frame twice as high - twice as many pixels");
    }

    /** Strength - settings × vanilla “Screen distortion”; any of them in 0 does not touch the screen. */
    @Test
    void settingsScaleIt() {
        assertEquals(1, Concussion.strength(1, 1));
        assertEquals(0, Concussion.strength(0, 1));
        assertEquals(0, Concussion.strength(1, 0));
        assertEquals(0.25, Concussion.strength(0.5, 0.5), 1e-12);
    }

    /**
     * The pass program declares each of its {@code uniform} (it does not specify undeclared vanilla - the shader would read zero), there are no extra ones in
     * JSON; no "++;" and nested “?:” - they are broken by parsing GLSL Veil (client-fx.md).
     */
    @Test
    void programDeclaresItsUniforms() throws Exception {
        Path dir = Path.of(ConcussionTest.class.getResource("/assets/incoming/shaders/program").toURI());
        JsonObject program = JsonParser.parseString(Files.readString(dir.resolve("concussion.json"))).getAsJsonObject();
        Set<String> declared = new HashSet<>();
        program.getAsJsonArray("uniforms").forEach(u -> declared.add(u.getAsJsonObject().get("name").getAsString()));
        program.getAsJsonArray("samplers").forEach(u -> declared.add(u.getAsJsonObject().get("name").getAsString()));
        String fsh = Files.readString(dir.resolve("concussion.fsh"));
        Set<String> used = new HashSet<>();
        Matcher m = Pattern.compile("uniform\\s+\\w+\\s+(\\w+)\\s*;").matcher(fsh);
        while (m.find()) used.add(m.group(1));
        // vertex program - vanilla “blur”: ProjMat, InSize, OutSize, BlurDir
        used.addAll(Set.of("ProjMat", "InSize", "OutSize", "BlurDir"));
        assertEquals(used, declared);
        assertFalse(fsh.matches("(?s).*\\+\\+\\s*;.*"), "«++;»");
        // pass writes the entire frame: no blending
        JsonObject blend = program.getAsJsonObject("blend");
        assertEquals("one", blend.get("srcrgb").getAsString());
        assertEquals("zero", blend.get("dstrgb").getAsString());
    }
}
