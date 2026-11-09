package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FlashLight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Production consumer contracts: adding a private exposure/visibility guess must fail this check. */
class NukeLightSourceTest {
    private static final Path ROOT = Path.of("src/main");
    private static final String CLIENT = "java/ua/zentix/incoming/client/";
    private static final String SHADER = "resources/assets/incoming/shaders/";
    private static final Map<String, String[]> READERS = Map.ofEntries(
            Map.entry("nuclear/NukeFlash.java", new String[]{"NukeLight.sample(", "NukeLight.camera(a)", "light.fluence()", "light.strength()", "light.peak(NukeLight.bodyExposure())", "lightUpWorld(mc.level, whiteness("}),
            Map.entry("nuclear/NukeSky.java", new String[]{"NukeLight.camera(a).light() * NukeLight.exposure()"}),
            Map.entry("nuclear/NukePall.java", new String[]{"into.exposure = (float) NukeLight.exposure()", "f.exposure = SKY.exposure", "CloudPuffs.ball(sky.exposure,"}),
            Map.entry("nuclear/MushroomField.java", new String[]{"FlashLight.source(s,", "FlashLight.coreTemperature(s,", "BALL[0] * exposure"}),
            Map.entry("nuclear/CloudPuffs.java", new String[]{"FlashLight.source(s,"}),
            Map.entry("nuclear/NukeRenderer.java", new String[]{"FlashLight.source(s,", "exposure = NukeLight.exposure()", "NukeLight.camera(a).seen()"}),
            Map.entry("fx/layer/PallPass.java", new String[]{"safeGetUniform(\"PallExposure\").set(f.exposure)", "safeGetUniform(\"PallBodyExposure\").set(f.bodyExposure)"}));

    private static Map<String, String> sources() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String file : READERS.keySet()) out.put(file, Files.readString(ROOT.resolve(CLIENT + file)).replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", ""));
        return out;
    }

    private static boolean wired(Map<String, String> sources) {
        for (var entry : READERS.entrySet()) {
            String source = sources.get(entry.getKey());
            for (String read : entry.getValue()) if (!source.contains(read)) return false;
            if (source.contains("Sight.adapted(")) return false;
        }
        return true;
    }

    @Test
    void everyNuclearLightConsumerReadsTheSharedInput() throws IOException {
        Map<String, String> original = sources();
        assertTrue(wired(original));
        for (var entry : READERS.entrySet()) for (String read : entry.getValue()) {
            Map<String, String> mutant = new LinkedHashMap<>(original);
            mutant.put(entry.getKey(), mutant.get(entry.getKey()).replace(read, "privateGuess("));
            assertFalse(wired(mutant), entry.getKey() + " bypassed " + read);
        }
        assertFalse(original.get("nuclear/MushroomField.java").contains("MushroomModel.coreTemperature("), "old emitting interior bypasses the pulse source");
        String flash = original.get("nuclear/NukeFlash.java");
        assertFalse(flash.contains("SCATTERED"));
        assertFalse(flash.contains("endTick"), "initial shelter cannot expire a later admitted flash");
        assertTrue(flash.contains("for (ClientNuclear.Active a : ClientNuclear.detonations())"));
        assertTrue(flash.contains("if (!a.live) continue;"), "sync/replay seek must not replay transient flash");
        assertTrue(flash.contains("if (afterLeft > 0) afterimage("), "acquired afterimage survives shelter");
        assertFalse(flash.contains("Math.cbrt"));
        assertFalse(flash.contains("sees ? 0.45 : 0.3"));
        String fx = Files.readString(ROOT.resolve(SHADER + "include/fx_pall.glsl"));
        assertTrue(fx.contains("PallBodyExposure / max(PallExposure, 1e-9)"), "film body must cancel frame exposure once");
        String composite = Files.readString(ROOT.resolve(SHADER + "core/fx_pall_composite.fsh"));
        assertTrue(composite.contains("flashOpening(open)"), "torch-lit walls must use independent skylight");
    }

    @Test
    void shaderOpeningMatchesTheEyeAtEveryLightLevel() throws IOException {
        String source = Files.readString(ROOT.resolve(SHADER + "include/pall.glsl"));
        GlslFunction f = GlslFunction.parse(source, "flashOpening");
        for (int sky = 0; sky <= 15; sky++) assertEquals(FlashLight.opening(sky), f.call(Map.of("sky", sky / 15.0)), 1e-12);
        // Even a white torch-lit wall receives exactly zero added nuclear light behind a sealed roof.
        double old = MushroomField.relit(0.8, 1, 0.3, 0);
        double sealed = MushroomField.relit(0.8, 1, 0.3, 1000 * f.call(Map.of("sky", 0.0)));
        assertEquals(old, sealed);
        assertTrue(MushroomField.relit(0.8, 1, 0.3, f.call(Map.of("sky", 1.0))) > sealed);
    }

    @Test
    void firstPersonHandAdmitsOnlySkylight() throws IOException {
        String composite = Files.readString(ROOT.resolve(SHADER + "core/fx_pall_composite.fsh"))
                .replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", "");
        int start = composite.indexOf("if (pallHand(depth))");
        String hand = composite.substring(start, composite.indexOf("return;", start));
        assertTrue(hand.contains("ballLight(p, n, pallOver(p)) * flashOpening(pallOpenHere(vec3(0.0)))"),
                "hand must gate the actual added light with raw skylight at the eye");
        assertTrue(hand.contains("fragColor = vec4(0.0, 0.0, 0.0, 1.0)"));
        assertTrue(hand.contains("if (dot(added, LUMA) > 1e-6)"), "zero admitted light must leave the hand pixel intact");
        assertFalse(hand.contains("pallSurfaceLevel(") || hand.contains("Sampler10"), "lamps cannot open the shelter");
        GlslFunction opening = GlslFunction.parse(Files.readString(ROOT.resolve(SHADER + "include/pall.glsl")), "flashOpening");
        double light = 1000;
        for (double pixel : new double[]{0.05, 0.8}) { // Dark sealed hand, then the same hand lit by a torch.
            double admitted = light * opening.call(Map.of("sky", 0.0));
            assertEquals(0, admitted);
            assertEquals(MushroomField.relit(pixel, 1, 0.3, 0), MushroomField.relit(pixel, 1, 0.3, admitted));
        }
        assertEquals(light, light * opening.call(Map.of("sky", 1.0)), "open sky preserves the existing hand light");
    }
}
