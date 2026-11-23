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

/** Probe (unified-wave step 1, not merged): the dust law's numbers the whole-lifetime dust wave reads, as JSON for the harness. */
class DustLawDumpProbe {
    @Test
    void dump() throws IOException {
        Path out = Path.of("build/uw-probe");
        Files.createDirectories(out);
        for (double kt : new double[] {1000, 15}) {
            Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, kt, true, 0, 0, 5, 20_000, 7, 1, false, 0.35f);
            PallField f = new PallField(d);
            StringBuilder s = new StringBuilder();
            s.append(String.format(Locale.ROOT, "{\"kt\": %.0f, \"reach\": %.3f, \"ageEnd\": %.3f, \"ages\": %d, \"nodes\": %d,%n", kt, f.reach(),
                    f.lifetimeEnd(), PallField.AGES, DustWallPass.NODES));
            float[] table = new float[DustWallPass.NODES * (1 + PallField.AGES) * 4];
            f.lifetime(DustWallPass.NODES, NukePall.YOUNG_PRESENT_HEIGHT, table);
            s.append("\"table\": [");
            for (int i = 0; i < table.length; i++) s.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "%.7g", table[i]));
            s.append("],\n");
            // take 33's young table at 30 s (arrival, presentation layer at 1 s, presentation sigma, paved)
            float[] young = new float[DustWallPass.NODES * 4];
            f.young(30, 1, 24, DustWallPass.NODES, young);
            s.append("\"young\": [");
            for (int j = 0; j < DustWallPass.NODES; j++) {
                young[4 * j + 1] *= (float) NukePall.YOUNG_PRESENT_HEIGHT;
                young[4 * j + 2] = NukePall.youngSigma(young[4 * j + 2]);
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
        }
    }
}
