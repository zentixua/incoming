package ua.zentix.incoming.nuclear.model;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Locale;

/** Reproducible model sensitivity CSV; no game/server/GPU and no renderer integration. */
public final class CapWakeStudy {
    private CapWakeStudy() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Expected output CSV path");
        try (var out = new PrintWriter(Path.of(args[0]).toFile())) {
            out.println("yield_kt,hob_m,beta,rise_fraction,time_s,release_start_s,cap_inventory,wake_inventory,wake_bottom_m,wake_top_m,mean_height_m,peak_depth_relative_to_cap,droplet_wake_share,peak_droplet_tau_est,slabs");
            for (double y : new double[]{5, 15}) {
                for (double beta : new double[]{0, 0.03, 0.1, 0.2}) {
                    for (double rise : new double[]{0, 0.25, 0.5, 0.75}) {
                        double hob = 240 * Math.cbrt(y);
                        var wake = new CapWakePrototype(y, hob, false, new CapWakePrototype.Closure(beta, rise), 128);
                        for (double t : new double[]{5, 15, 30, 60, 120, 300}) {
                            var s = wake.at(t, 0, 0);
                            out.printf(Locale.ROOT, "%.0f,%.6f,%.3f,%.2f,%.0f,%.6f,%.9f,%.9f,%.6f,%.6f,%.6f,%.9f,%.9f,%.6f,%d%n",
                                    y, hob, beta, rise, t, wake.startsAt(), s.capInventory(), s.wakeInventory(),
                                    s.bottom(), s.top(), s.meanHeight(), s.peakDepthRelativeToCap(),
                                    s.dropletBudget() == 0 ? 0 : s.wakeDroplets() / s.dropletBudget(), s.peakDropletDepth(), s.slabs().size());
                        }
                    }
                }
            }
        }
    }
}
