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
        // GlslFunction reads one constant per declaration
        String s = Files.readString(GLSL).replace("const float DW_G_SLOPE = 0.073, DW_G_KNEE = 0.5;",
                "const float DW_G_SLOPE = 0.073;\nconst float DW_G_KNEE = 0.5;")
                .replace("const float DW_HAZE_FROM = 24.0, DW_HAZE_TO = 60.0;", "const float DW_HAZE_FROM = 24.0;\nconst float DW_HAZE_TO = 60.0;");
        GlslFunction transfer = GlslFunction.parse(s, "dwTransfer"), textured = GlslFunction.parse(s, "dwTextured"),
                profile = GlslFunction.parse(s, "dwProfileInt"), above = GlslFunction.parse(s, "dwColumnAbove");
        for (double x = 0; x < 400; x += 0.37) {
            assertEquals(PallField.transfer(x), transfer.call(Map.of("tau", x)), 1e-9, "dwTransfer " + x);
            assertEquals(PallField.textured(x), textured.call(Map.of("a", x)), 1e-9, "dwTextured " + x);
            assertEquals(PallField.profileIntegral(x), profile.call(Map.of("h", x)), 1e-9, "dwProfileInt " + x);
        }
        for (double h = 0; h <= 1; h += 0.01) {
            double java = 1.25 * (1 - h) - 0.25 * (1 - h * h); // DustSources.eyeDepth's `above`
            assertEquals(java, above.call(Map.of("x", h)), 1e-9, "dwColumnAbove " + h);
        }
    }

    /** The source table's layout in the shader is the one the pass uploads (rows per source, the spray and frame-input rows, nodes). */
    @Test
    void tableLayoutIsTheShaders() throws Exception {
        String s = Files.readString(GLSL);
        int rows = ua.zentix.incoming.client.fx.layer.DustWallPass.ROWS;
        assertTrue(s.contains("const int DW_SPRAY_ROW = " + rows + ", DW_PARAMS_ROW = " + (rows + 1) + ", DW_ROWS = "
                + ua.zentix.incoming.client.fx.layer.DustWallPass.SOURCE_ROWS + ";"), "row layout");
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
