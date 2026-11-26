package ua.zentix.incoming.client.nuclear;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.DustWallPass;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;

/**
 * The dust law's numbers the dust wave reads, as JSON for the shader harness ({@code tools/uw_probe/probe_gl.py}), with the
 * reference take's young-wall inputs it compares against. Runs only with {@code -Duw.probe=true} (it writes files).
 */
class DustLawDumpProbe {
    @Test
    void dump() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("uw.probe"), "harness dump: -Duw.probe=true");
        Path out = Path.of("build/uw-probe");
        Files.createDirectories(out);
        for (double kt : new double[] {1000, 15}) {
            Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, kt, true, 0, 0, 5, 20_000, 7, 1, false, 0.35f);
            PallField f = new PallField(d);
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.ROOT, "{\"kt\": %.0f, \"reach\": %.3f, \"ageEnd\": %.3f, \"ages\": %d, \"nodes\": %d,%n", kt, f.reach(),
                    f.lifetimeEnd(), PallField.AGES, DustWallPass.NODES));
            float[] table = new float[DustWallPass.NODES * (1 + PallField.AGES) * 4];
            f.lifetime(DustWallPass.NODES, DustSources.PRESENT_HEIGHT, table);
            s.append("\"table\": [");
            for (int i = 0; i < table.length; i++) s.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "%.7g", table[i]));
            s.append("],\n");
            // take 33's young table at 30 s (arrival, presentation layer at 1 s, presentation sigma, paved)
            float[] young = young(f, d, 30, 1, 24, DustWallPass.NODES);
            s.append("\"young\": [");
            for (int j = 0; j < DustWallPass.NODES; j++) {
                young[4 * j + 1] *= (float) DustSources.PRESENT_HEIGHT;
                // take 33's presentation extinction: 0.11/block where the lofted depth is 20, the square root of its share below
                double depth = young[4 * j + 2];
                young[4 * j + 2] = depth <= 0 ? 0f : (float) (0.11 * Math.min(1.6, Math.sqrt(depth / 20)));
            }
            for (int i = 0; i < young.length; i++) s.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "%.7g", young[i]));
            s.append("],\n\"raw\": [");
            // raw law at a few radii: r, arrival s, positive s, then (age, height m, depth) at ages
            double[] ages = {0.05, 0.1, 0.25, 0.5, 1, 2, 4, 8, 12, 16, 24, 30, 60, 120, 300, 600, 1200, 1800, 3600, 7200, 10800};
            boolean first = true;
            for (double rb : new double[] {500, 1000, 2000, 3000, 4500, 6000, 9000, 12000}) {
                if (rb > f.reach()) continue;
                DustModel.Column c = DustModel.column(d.metres(rb), 0, kt, true, 20_000, DustModel.Surface.REFERENCE);
                s.append(first ? "" : ",").append(String.format(Locale.ROOT, "{\"r\": %.0f, \"arrival\": %.4f, \"positive\": %.4f, \"ceiling\": %.3f, \"pts\": [",
                        rb, d.arrivalTicks(rb) / 20.0, c.positive(), c.ceiling()));
                first = false;
                for (int k = 0; k < ages.length; k++)
                    s.append(k == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%.3f, %.4f, %.6g]", ages[k], c.height(ages[k]), c.depth(ages[k])));
                s.append("]}");
            }
            s.append("]}\n");
            Files.writeString(out.resolve(String.format(Locale.ROOT, "law-%.0fkt.json", kt)), s);
            StringBuilder fb = new StringBuilder();
            for (double t = 0; t <= 240; t += 1)
                fb.append(String.format(Locale.ROOT, "%.0f %.1f%n", t, ua.zentix.incoming.nuclear.model.FireballModel.groundRadius(t, 0, kt, true)));
            Files.writeString(out.resolve(String.format(Locale.ROOT, "footprint-%.0fkt.txt", kt)), fb);
        }
    }

    /**
     * The reference take's young-wall inputs at model second t (the harness's take-33 model): per node, the front's arrival,
     * the layer top at settleS, the lofted depth at handoffS (none under the fireball's footprint) and the paved share.
     */
    private static float[] young(PallField f, Detonation d, double t, double settleS, double handoffS, int n) {
        float[] out = new float[n * 4];
        double hob = Math.max(0, d.hobMetres()), reach = f.reach();
        double ball = ua.zentix.incoming.nuclear.model.FireballModel.groundRadius(t, hob, d.yieldKt(), d.surface());
        for (int j = 0; j < n; j++) {
            double rb = j / (n - 1.0) * reach, slant = Math.hypot(d.metres(rb), hob);
            int i = Math.min(PallField.BINS - 1, Math.max(0, (int) (rb / reach * PallField.BINS)));
            DustModel.Column c = f.layer(i);
            out[4 * j] = (float) (d.arrivalTicks(d.blocks(slant)) / (20.0 * d.scale()));
            out[4 * j + 1] = (float) Math.max(d.blocks(c.height(settleS)), PallField.MIN_TOP_BLOCKS);
            out[4 * j + 2] = d.metres(rb) < ball ? 0f : (float) c.depth(handoffS);
            out[4 * j + 3] = (float) f.paved(i);
        }
        return out;
    }
}
