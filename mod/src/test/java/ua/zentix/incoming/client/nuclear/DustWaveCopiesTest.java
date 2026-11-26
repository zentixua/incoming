package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java readers of the drawn dust wave (the stem foot, the eye's dust, breach dust: {@link PallField#drawnColumn}) hold the
 * shader's law: the same transfer, textured share, profile integral and column share as {@code dustwall.glsl}, and the same
 * column over hard ground (only its debris dust, the paved share) as {@code dwHaze}.
 */
class DustWaveCopiesTest {
    private static final Path GLSL = Path.of("src/main/resources/assets/incoming/shaders/include/dustwall.glsl");

    @Test
    void javaCopiesEqualTheShader() throws Exception {
        assertTrue(Files.readString(GLSL).contains("if (t >= tMarch || T < 0.01) break;") && Math.abs(PallField.HAZE_SEEN - Math.log(100)) < 1e-12,
                "the haze level's ceiling is the march's exit depth");
        // GlslFunction reads one constant per declaration
        String s = Files.readString(GLSL).replace("const float DW_G_SLOPE = 0.073, DW_G_KNEE = 0.5;",
                "const float DW_G_SLOPE = 0.073;\nconst float DW_G_KNEE = 0.5;")
                .replace("const float DW_HAZE_FROM = 24.0, DW_HAZE_TO = 60.0;", "const float DW_HAZE_FROM = 24.0;\nconst float DW_HAZE_TO = 60.0;");
        GlslFunction transfer = GlslFunction.parse(s, "dwTransfer"), textured = GlslFunction.parse(s, "dwTextured"),
                profile = GlslFunction.parse(s, "dwProfileInt"), above = GlslFunction.parse(s, "dwColumnAbove"),
                haze = GlslFunction.parse(s, "dwHazeDepth");
        for (double x = 0; x < 400; x += 0.37) {
            assertEquals(PallField.transfer(x), transfer.call(Map.of("tau", x)), 1e-9, "dwTransfer " + x);
            assertEquals(PallField.textured(x), textured.call(Map.of("a", x)), 1e-9, "dwTextured " + x);
            assertEquals(PallField.profileIntegral(x), profile.call(Map.of("h", x)), 1e-9, "dwProfileInt " + x);
            assertEquals(PallField.hazeDepth(x), haze.call(Map.of("tau", x)), 1e-6 * (1 + x), "dwHazeDepth " + x);
        }
        for (double h = 0; h <= 1; h += 0.01) {
            double java = 1.25 * (1 - h) - 0.25 * (1 - h * h); // DustSources.eyeDepth's `above`
            assertEquals(java, above.call(Map.of("x", h)), 1e-9, "dwColumnAbove " + h);
        }
    }

    /**
     * The haze level's top the camera's share reads ({@link DustNoise}: {@code dwEnvelope}, {@code dwPlume}, {@code dwRamp},
     * {@code dwSoft}) is the shader's; the swell field on the lattice is held by {@code DustNoiseTest} and
     * {@code tools/dust_wall_gl.py}.
     */
    @Test
    void hazeTopEqualsTheShader() throws Exception {
        String s = Files.readString(GLSL).replace("const float DW_RISE = 0.12, DW_HOLD = 0.25, DW_FALL = 0.3;",
                "const float DW_RISE = 0.12;\nconst float DW_HOLD = 0.25;\nconst float DW_FALL = 0.3;")
                .replace("const float DW_SHEAR_S = 300.0, DW_PLUME = 2.5;", "const float DW_SHEAR_S = 300.0;\nconst float DW_PLUME = 2.5;");
        GlslFunction envelope = GlslFunction.parse(s, "dwEnvelope"), plume = GlslFunction.parse(s, "dwPlume"),
                ramp = GlslFunction.parse(s, "dwRamp"), soft = GlslFunction.parse(s, "dwSoft");
        for (double a = 0; a < 4000; a = a * 1.17 + 0.05) {
            for (double h : new double[] {5, 40, 120})
                assertEquals(DustNoise.envelope(a, h, 1.65), envelope.call(Map.of("a", a, "h", h, "k", 1.65)), 1e-9, "dwEnvelope " + a);
            for (double l = 0; l <= 1; l += 0.05)
                assertEquals(DustNoise.plume(l, a, 0.8), plume.call(Map.of("L", l, "a", a, "hot", 0.8)), 1e-9, "dwPlume " + l + " " + a);
            assertEquals(DustNoise.ramp(a, 6.5, 1.2), ramp.call(Map.of("a", a, "c", 6.5, "w", 1.2)), 1e-12, "dwRamp " + a);
            assertEquals(DustNoise.soft(a - 3), soft.call(Map.of("x", a - 3)), 1e-12, "dwSoft " + a);
        }
        String text = Files.readString(GLSL);
        for (String c : new String[] {"const float DW_BULGE = " + DustNoise.BULGE + ",", "DW_SHEAR_S = " + DustNoise.SHEAR_S + ",",
                "DW_PLUME = " + DustNoise.PLUME + ";", "const float DW_EDDY_S = " + DustNoise.EDDY_S + ";",
                "const float DW_LATTICE = " + DustNoise.SIDE + ".0;"})
            assertTrue(text.contains(c), "shader constant " + c);
    }

    /** The camera's share reaches the march with the source: the frame inputs' sixth texel's w ({@code dwLoad}: DwSight). */
    @Test
    void theSightShareIsAFrameInput() throws Exception {
        String s = Files.readString(GLSL);
        assertTrue(s.contains("vec4 b = dwParam(4), f = dwParam(5), r = dwParam(6);") && s.contains("DwSight = f.a;"), "DwSight from texel 5's w");
        assertTrue(s.contains("seenD = seen > 0.0 ? dwTransfer(dwSight(r, a0)) : 0.0;")
                        && s.contains("float lawHaze = dwHazeDepth(law.y);") && s.contains("gDwTauHaze = lawHaze + seen * (seenD - lawHaze);") && s.contains("gDwTauTex = tex + seen * (seenD - tex);"),
                "the density moves each level's drawn depth to the inside sight's transfer by the share");
        // PallField.drawn: the haze level draws the law's depth, the textured level (the young head and its puffs) its transfer
        assertTrue(s.contains("float tc = gDwTauHaze * (1.0 + 0.5 * exp(-a / 1.1)) * paved") && s.contains("float tex = dwTransfer(law.y);")
                && s.contains("float taud = gDwTauTex * (1.0 + 0.5 * exp(-a / 1.1));")
                && s.contains("float taup = gDwTauTex * (1.0 + 0.5 * exp(-a / 1.1));"), "each level's drawn column");
        String sources = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/nuclear/DustSources.java"));
        assertTrue(sources.contains("p[4 * 5 + 3] = (float) sight;"), "DustSources writes the share to texel 5's w");
    }

    /** The source table's layout in the shader is the one the pass uploads (rows per source, the spray and frame-input rows, nodes). */
    @Test
    void tableLayoutIsTheShaders() throws Exception {
        String s = Files.readString(GLSL);
        int rows = ua.zentix.incoming.client.fx.layer.DustWallPass.ROWS;
        int sources = ua.zentix.incoming.client.fx.layer.DustWallPass.SOURCE_ROWS;
        assertTrue(s.contains("const int DW_SPRAY_ROW = " + rows + ", DW_SIGHT_ROW = " + PallField.SIGHT_ROW + ", DW_PARAMS_ROW = "
                + (sources - 1) + ", DW_ROWS = " + sources + ";"), "row layout");
        assertEquals(rows + 1, PallField.SIGHT_ROW, "the inside sight's rows follow the spray row");
        assertTrue(s.contains("const float DW_NODES = " + ua.zentix.incoming.client.fx.layer.DustWallPass.NODES + ".0;"), "nodes");
    }

    /** Over fully hard ground the Java readers take the paved share of the column, as dwHaze does (not the soil's 10–100×). */
    @Test
    void readersTakeHardGround() {
        for (double kt : new double[] {15, 1000}) {
            Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, kt, true, 0, 0, 5, 20_000, 7, 1, false, 0.35f);
            PallField f = new PallField(d);
            float[] table = f.waveTableNow();
            int n = ua.zentix.incoming.client.fx.layer.DustWallPass.NODES;
            double reach = f.reach(), end = f.lifetimeEnd();
            double[] soil = new double[2], hard = new double[2];
            int checked = 0;
            for (int j = 1; j < n - 2; j += 7) {
                double r = j / (double) (n - 1) * reach, t = table[4 * j] + 5;
                PallField.drawnColumn(table, n, end, reach, t, r, 0, 0, soil);
                PallField.drawnColumn(table, n, end, reach, t, r, 0, 1, hard);
                if (soil[0] <= 0) continue;
                assertEquals(table[4 * j + 1], hard[0] / soil[0], 1e-4, kt + " kt node " + j + ": paved share");
                checked++;
            }
            assertTrue(checked > 3, "nodes with dust");
            // the tiles drive the reader: all hard ground under the burst
            SourceMaterial all = new SourceMaterial(d, null, (x, z) -> ua.zentix.incoming.nuclear.material.DustMaterial.HARD);
            DustMaterialTiles tiles = new DustMaterialTiles(d, all);
            for (int i = 0; i < 300 && tiles.fill(); i++) { }
            assertEquals(1, tiles.hard(0.3 * reach, 0.1 * reach), 1e-9, "hard share from the tiles");
            int j = n / 3;
            double r = j / (double) (n - 1) * reach, ticks = (table[4 * j] + 5) * 20 * d.scale();
            double before = f.drawnTau(r, 0, ticks, PallField.Water.DRY);
            f.materials(tiles);
            double after = f.drawnTau(r, 0, ticks, PallField.Water.DRY);
            assertTrue(before > 0, kt + " kt: dust at the node");
            assertEquals(table[4 * j + 1], after / before, 0.02, kt + " kt: drawnTau over hard ground");
        }
    }
}
