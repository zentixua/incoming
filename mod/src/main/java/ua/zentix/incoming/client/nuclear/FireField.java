package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Where and how much burns after detonation — client model for burning-city sound and secondary explosions (the server places
 * the fire itself; the client sees only fire blocks in local chunks). Rings of width {@link #RING} around ground zero: in each ring
 * light ignites a fraction of columns with the same probability as the server ({@code RuinPlanner.fire}: min(1, q/20), q is light on the
 * ground, fading at the ruin edge); {@link #SHARE} of columns contain fuel. Fire starts with the flash
 * (server flash layer, {@code FlashScorch}: each location when light reaches the ignition threshold there, {@link Detonation#flashTicks});
 * on arrival, the wave blows out half at 2 psi and above ({@link #keep}). The server fire limit per detonation ({@link #MAX_FIRES})
 * counts what burns after the wave ({@code RuinPlan.write}): only fire the wave will not blow out counts; the flash
 * layer keeps igniting while this count is below the limit, and ignites removable fire without counting it. The model cuts rings from ground zero with the same count
 * as the layer: the same amount ignited out to the same ring (before the wave, including what it will blow out: at 2 psi and above near ground zero, twice
 * the limit); the wave then leaves what the layer would have left without ruins. The model has no knowledge of ruins — its known
 * limitation: counted layer fire extinguished by ruins (rubble, collapse) leaves the server count, while ruins beyond the ring where
 * the layer hit its limit and ignited nothing start their own fires. After the wave in a city, the server also has fires beyond the model's last
 * ring (beyond ~146 blocks for a 15 kt airburst), while the model is empty there; near the player this is corrected by
 * {@link ua.zentix.incoming.client.fx.FireWatch} (actual local-chunk fire) and {@code NukeAfter.trust}; not at distance. Later burning follows
 * {@link #envelope}: it grows for {@link #RISE} ticks from
 * ignition, then decays with time constant {@link #TAU}. The client checks actual nearby fire against local chunks
 * ({@link ua.zentix.incoming.client.fx.FireWatch}).
 */
final class FireField {
    /** Ring width, blocks. */
    static final double RING = 32;
    /** Fraction of columns with somewhere for fire to stand (grass, dirt, wood — not stone or water). */
    static final double SHARE = 0.6;
    /** Default server post-wave fires per detonation ({@code fires_per_detonation}); the client does not know the server's custom value. */
    static final int MAX_FIRES = 20_000;
    /** Fire growth time, ticks. */
    static final double RISE = 20 * 20;
    /** And decay time, ticks: grass and dirt burn out in seconds, houses and forest in minutes. */
    static final double TAU = 5 * 60 * 20;
    /** Light ignition probability threshold ({@code RuinPlanner.fire}). */
    private static final double FULL_Q = 20;
    /** Light marks fade at the ruin edge over this fraction of radius ({@code RuinPlanner.edge}). */
    private static final double EDGE_FADE = 0.2;

    final Detonation d;
    /** Flash-ignited fires in a ring, including those blown out by the wave ({@link #MAX_FIRES} caps those that remain). */
    final double[] lit;
    /** Share not blown out by the wave (1 or {@code RuinPlanner.BLOWN_KEEP} at 2 psi and above). */
    final double[] keep;
    /** Post-wave fires in a ring ({@link #lit} × {@link #keep}): what burns later; total capped at {@link #MAX_FIRES}. */
    final double[] fires;
    /**
     * Flash ignition of a ring and front arrival at ground in its middle (slant range from the detonation point: an airburst
     * wave reaches the ground below it after a second, while light arrives immediately), ticks since detonation.
     */
    final double[] ignite, arrival;
    /** Total post-wave fires. */
    final double total;

    FireField(Detonation d) {
        this.d = d;
        int rings = (int) Math.ceil(d.ruinRadius() / RING);
        double[] l = new double[rings], k = new double[rings], f = new double[rings], ig = new double[rings], at = new double[rings];
        double after = 0;
        for (int i = 0; i < rings; i++) {
            double r = radius(i);
            at[i] = d.arrivalTicks(Math.hypot(r, d.burst().y - d.groundY()));
            k[i] = 1;
            double places = after < MAX_FIRES ? candidates(d, i) : 0;
            if (places <= 0) continue;
            k[i] = keepAt(d, i);
            // server counts only fires not blown out: the ring filling it receives the remaining count and removable fires between them
            f[i] = Math.min(MAX_FIRES - after, places * k[i]);
            l[i] = f[i] / k[i];
            ig[i] = d.flashTicks(d.fluence(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)), ThermalModel.IGNITE);
            after += f[i];
        }
        this.lit = l;
        this.keep = k;
        this.fires = f;
        this.ignite = ig;
        this.arrival = at;
        this.total = after;
    }

    /** Share of fires not blown out by the wave at 2 psi and above ({@code RuinPlanner.BLOWN_KEEP}). */
    static final double BLOWN_KEEP = 0.5;

    /**
     * How many locations in {@code ring} the flash would ignite without a fire limit: fraction of columns with a place for fire,
     * using light probability at the ring middle ({@code RuinPlanner.fire}); inside the fireball at ground level or below the ignition
     * threshold — 0.
     */
    static double candidates(Detonation d, int ring) {
        double r = radius(ring), ruin = d.ruinRadius();
        if (d.surface() && r < d.fireballRadius() * 0.8) return 0;
        double q = d.fluence(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)) * edge(r, ruin);
        if (q < ThermalModel.IGNITE) return 0;
        double area = Math.PI * (Math.pow((ring + 1) * RING, 2) - Math.pow(ring * RING, 2));
        return SHARE * Math.min(1, q / FULL_Q) * area;
    }

    /** Share of {@code ring} fire the wave will not blow out: {@link #BLOWN_KEEP} at 2 psi and above at ring middle, otherwise 1. */
    static double keepAt(Detonation d, int ring) {
        return d.psi(new Vec3(d.burst().x + radius(ring), d.groundY() + 1, d.burst().z)) >= 2 ? BLOWN_KEEP : 1;
    }

    private static double edge(double r, double ruin) {
        double f = Math.max(0, Math.min(1, (1 - r / ruin) / EDGE_FADE));
        return f * f * (3 - 2 * f);
    }

    static double radius(int ring) {
        return (ring + 0.5) * RING;
    }

    /** Fraction of fire burning {@code ticks} after flash ignition. */
    static double envelope(double ticks) {
        if (ticks <= 0) return 0;
        return ticks < RISE ? ticks / RISE : Math.exp(-(ticks - RISE) / TAU);
    }

    /** Amount burning in the ring {@code ticks} after detonation: flash-ignited fires, minus those blown out when the wave arrives. */
    double burning(int ring, double ticks) {
        return lit[ring] * envelope(ticks - ignite[ring]) * (ticks < arrival[ring] ? 1 : keep[ring]);
    }

    /** Total burning amount. */
    double burning(double ticks) {
        double s = 0;
        for (int i = 0; i < fires.length; i++) s += burning(i, ticks);
        return s;
    }

    /** Fire ended: ticks since detonation when less than 2 % remains. */
    double endTicks() {
        return lastIgnition() + RISE + TAU * Math.log(50);
    }

    /** Ignition of the last burning ring (flash), ticks since detonation. */
    double lastIgnition() {
        double last = 0;
        for (int i = 0; i < lit.length; i++) if (lit[i] > 0) last = Math.max(last, ignite[i]);
        return last;
    }

    /** Secondary explosion in the burning city: gas, fuel, warehouses. */
    record Secondary(double ticks, double x, double z, boolean big, long seed) {}

    /**
     * Detonation secondary explosions (all from the detonation seed: identical on all clients and in replay): more burning means
     * more explosions; locations where fire burns; timing from {@link #SECONDARY_AFTER} after wave arrival, becoming less frequent.
     */
    List<Secondary> secondaries() {
        List<Secondary> out = new ArrayList<>();
        if (total < 1) return out;
        RandomSource random = RandomSource.create(d.seed() ^ 0x5EC0DA2L);
        int n = (int) Math.round(Math.max(4, Math.min(24, total / 800)));
        for (int k = 0; k < n; k++) {
            double u = random.nextDouble() * total;
            int ring = 0;
            while (ring < fires.length - 1 && u > fires[ring]) u -= fires[ring++];
            double r = (ring + random.nextDouble()) * RING;
            double angle = random.nextDouble() * 2 * Math.PI;
            // timing: after fire develops, becoming less frequent (mean — SECONDARY_TAU), no later than SECONDARY_END
            double t = arrival[ring] + SECONDARY_AFTER - SECONDARY_TAU * Math.log(1 - random.nextDouble() * (1 - Math.exp(-SECONDARY_END / SECONDARY_TAU)));
            out.add(new Secondary(t, d.burst().x + Math.cos(angle) * r, d.burst().z + Math.sin(angle) * r, random.nextFloat() < 0.2f, random.nextLong()));
        }
        out.sort((a, b) -> Double.compare(a.ticks, b.ticks));
        return out;
    }

    /** Secondary explosions no earlier than this many ticks after wave arrival, this mean delay, and no later than this. */
    static final double SECONDARY_AFTER = 30 * 20, SECONDARY_TAU = 3 * 60 * 20, SECONDARY_END = 10 * 60 * 20;
}
