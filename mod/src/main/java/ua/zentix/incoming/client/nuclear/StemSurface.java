package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.StemDust;

/**
 * Surface at the dust stem's fixed source footprint. Eight equally weighted angular samples per model annulus
 * estimate terrestrial area; this is quadrature, not proof that the complete area between samples is water.
 * Only raw known columns count as water. Unknown samples keep the reference-land estimate and retry later;
 * neither the epicenter's sea-level guess nor PallGround's nearest-filled map can suppress unseen land.
 * First known samples are retained with this detonation, like its source clock, including after replay seek.
 */
final class StemSurface {
    static final int ANGLES = 8, PER_TICK = 8;
    private final Detonation d;
    private final boolean[] known;
    private final double[] water, land;
    private StemDust.Source source;
    private int next, remaining;
    private boolean dirty;

    StemSurface(Detonation d, StemDust.Source source) {
        this.d = d;
        this.source = source;
        known = new boolean[source.rings() * ANGLES];
        water = new double[known.length];
        land = new double[source.rings()];
        remaining = known.length;
    }

    /** At most eight non-loading column queries per tick; publish only after a sweep, and only when amounts changed. */
    StemDust.Source survey(@Nullable FarTerrain.Water columns) {
        if (columns == null || remaining == 0) return source;
        for (int count = 0; count < PER_TICK; count++) {
            int i = next++, ring = i / ANGLES;
            if (!known[i]) {
                double angle = 2 * Math.PI * ((i % ANGLES) + 0.5) / ANGLES;
                double radius = d.blocks(source.radius(ring));
                double wet = columns.at(Mth.floor(d.burst().x + radius * Math.cos(angle)),
                        Mth.floor(d.burst().z + radius * Math.sin(angle)));
                if (Double.isFinite(wet)) {
                    known[i] = true;
                    remaining--;
                    water[i] = Math.max(0, Math.min(1, wet));
                    dirty |= water[i] > 0;
                }
            }
            if (next == known.length || remaining == 0) {
                if (next == known.length) next = 0;
                publish();
                break;
            }
        }
        return source;
    }

    private void publish() {
        if (!dirty) return;
        for (int r = 0; r < land.length; r++) {
            double wet = 0;
            for (int a = 0; a < ANGLES; a++) wet += water[r * ANGLES + a];
            land[r] = 1 - wet / ANGLES;
        }
        source = source.withLand(land);
        dirty = false;
    }
}
