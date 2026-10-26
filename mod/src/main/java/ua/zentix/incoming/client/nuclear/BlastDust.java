package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Breach dust near the camera — a client cloud source (drawn by baked effects-layer billows, {@code far/SmokePuffs}): where
 * the wave demolished blocks ({@code S2C.NukeDebris}), wind behind the front carries the pulverized material ({@link BlastGust}).
 * Dust leaves the breach while the positive-phase wind blows, more with stronger wind (fraction removed by a time is
 * the fraction of air displacement, {@link BlastWall#carriedShare}), forming a downwind streak: earliest dust travels farthest, by the air displacement since
 * front arrival; latest dust stays at the breach ({@link #parcel}). Dust-layer eddies spread the streak; it merges into the layer
 * when the layer (wall head, {@link DustModel#waveTop}) grows above it — then the pall itself draws it ({@link #apart}).
 * <p>
 * Dust amount: in the pall model, city debris dust is {@code DustModel.Surface.REFERENCE.debris} = 0.1 kg/m² where
 * everything collapses (tuned to Hiroshima). Per kilogram of demolished mineral material this is {@link #FINES}: the fraction lofted as fine
 * dust. Block mass is {@code Shielding.density}. One breached stone block thus produces a meter-scale billow visible for a fraction of a second, while a collapsed
 * house produces a cloud. The pall distributes its debris dust uniformly by pressure without subtracting this dust (⚠ demolished houses have up to twice
 * the dust of the model over level ground; debris dust is the smaller part of the layer: wind lofts kilograms of soil).
 */
final class BlastDust implements SmokeGrid.Source {
    /**
     * Fraction of demolished masonry mass (brick, cobblestone, concrete, dressed stone) lofted as fine dust: the model's 0.1 kg/m² of debris
     * dust represents clay walls and plaster in a city where houses occupy half the land and contain ~400 kg of clay, plaster and tile
     * per square meter of built area (two-story house) — ⚠ estimate. Wood, foliage, glass and metal do not crumble into dust:
     * boards and trunks break into pieces.
     */
    static final double FINES = DustModel.Surface.REFERENCE.debris() / (0.5 * 400);
    /**
     * Dust streak rises and spreads with air distance traveled: upward — 0.02–0.08 of the distance (Kuhl et al. 1992, §3.4:
     * vertical layer speed is 0.02 of wind at the bottom and 0.04–0.12 at the top; same share as head dust, {@link BlastWall#origin}),
     * sideways — eddies at the same speed, streak middle 0.04–0.12 (⚠ midpoint estimate).
     */
    static final double RISE = 0.05, SPREAD = 0.07;
    /** Dust from a one-block breach forms a billow of this half-size, blocks. */
    static final double R0 = 0.5;
    /**
     * Maximum streaks per detonation (each contributes up to 12 layer billows per frame): if no slots remain, a new streak closer to the camera
     * replaces the farthest; otherwise it is not added — the wave approaches the camera, so the nearest breaches arrive last.
     */
    static final int MAX_STREAKS = 256;

    /** Streak from one breach or a cluster: position, local wind, dust mass in kg, initial σ, distance from ground zero in m. */
    private static final class Streak {
        final double x, y, z, mass, r0, groundM;
        final BlastGust gust;
        /**
         * Dust-layer top, blocks, and airborne extinction fraction for the full {@link #tick}: layer top requires a root search in the front-arrival
         * table; evaluating it each frame for hundreds of streaks is expensive, while it changes by fractions of a percent per tick.
         */
        double top, aloft;
        long tick = Long.MIN_VALUE;

        Streak(double x, double y, double z, BlastGust gust, double mass, double r0, double groundM) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.gust = gust;
            this.mass = mass;
            this.r0 = r0;
            this.groundM = groundM;
        }

        /** Layer top and airborne fraction at {@code t} (ticks since detonation), evaluated at the integer tick. */
        void layer(Detonation d, double t) {
            long k = (long) Math.floor(t);
            if (k == tick) return;
            tick = k;
            double age = k - gust.arrival;
            top = layerTop(d, groundM, age);
            // coarse material settles from a layer as high as the head top (at least a meter); extinction follows what remains airborne
            aloft = DustModel.extinctionAloft(Math.max(0, age) / (20.0 * d.scale()) / Math.max(1, d.metres(top)));
        }
    }

    private final ClientNuclear.Active a;
    private final List<Streak> streaks = new ArrayList<>();
    private final float dustR, dustG, dustB;
    private final double dustK;
    private final double[] parcel = new double[4];
    /** For the scenario line: streaks submitted last frame, maximum transverse optical depth, dust mass in them, kg. */
    private int drawn;
    private double tauMax, massDrawn;
    private boolean gone;

    BlastDust(ClientNuclear.Active a) {
        this.a = a;
        // same color as wall and pall dust ({@code NukePall.DUST}): model debris dust belongs to the same layer
        dustR = SmokeGrid.dustAlbedo((float) NukePall.DUST[0]);
        dustG = SmokeGrid.dustAlbedo((float) NukePall.DUST[1]);
        dustB = SmokeGrid.dustAlbedo((float) NukePall.DUST[2]);
        dustK = SmokeGrid.dustK(dustR, dustG, dustB);
    }

    /** {@code mass} kg of dust from a breach at {@code at} (cluster half-size {@code r0} blocks); {@code eye} is the camera. */
    void add(Vec3 at, double mass, double r0, Vec3 eye) {
        if (gone || mass <= 0) return;
        if (streaks.size() >= MAX_STREAKS) {
            int far = -1;
            double farD = at.distanceToSqr(eye);
            for (int i = 0; i < streaks.size(); i++) {
                Streak s = streaks.get(i);
                double dd = eye.distanceToSqr(s.x, s.y, s.z);
                if (dd > farD) {
                    farD = dd;
                    far = i;
                }
            }
            if (far < 0) return;
            streaks.remove(far);
        }
        Detonation d = a.d;
        streaks.add(new Streak(at.x, at.y, at.z, BlastGust.at(a, at), mass, Math.max(R0, r0),
                d.metres(Math.hypot(at.x - d.burst().x, at.z - d.burst().z))));
    }

    /** World change, seek: dust disappears immediately. */
    void end() {
        gone = true;
        streaks.clear();
    }

    /**
     * Air that left a location in wind {@code g} at {@code from} (ticks since detonation, no earlier than front arrival), at
     * {@code ticks}: displacement from the location (into[0..2], blocks), and σ gained by the streak through layer eddies (into[3], blocks).
     */
    static void parcel(BlastGust g, double from, double ticks, double[] into) {
        double s = Math.max(from, g.arrival), path = g.path(ticks) - g.path(Math.min(s, ticks)), travel = g.travel(ticks) - g.travel(Math.min(s, ticks));
        into[0] = g.ax * path;
        into[1] = RISE * travel;
        into[2] = g.az * path;
        into[3] = SPREAD * travel;
    }

    /**
     * Share of a streak with σ {@code sigma} not yet mixed into a dust layer with top {@code top} (blocks): a layer of height h is uniformly
     * mixed, with σ = h/√12; a streak outgrown by the layer is already its dust, drawn by the pall (like the wall head handing over to
     * the pall, {@link BlastWall#handoff}): share = σ²/(σ² + σ_layer²) (⚠ estimated transition shape). Do not expand the streak to the layer:
     * the frame would show a second dust over the pall — sky-lit, without the wall head's shadow above it ({@code film} scene:
     * a uniform beige veil over the whole frame).
     */
    static double apart(double sigma, double top) {
        double layer = top / Math.sqrt(12);
        return sigma * sigma / (sigma * sigma + layer * layer);
    }

    /**
     * Top of the dust layer into which the streak dissolves, blocks: Kuhl's dusty boundary layer at the local dust age
     * ({@link DustModel#waveTop}); a layer of height h is uniformly mixed, with σ = h/√12.
     */
    static double layerTop(Detonation d, double groundM, double ageTicks) {
        return d.blocks(DustModel.waveTop(groundM, d.hobMetres(), d.yieldKt(), Math.max(0, ageTicks) / (20.0 * d.scale())));
    }

    @Override
    public boolean emit(double seconds, SmokeGrid.Sink out) {
        if (gone) return false;
        Detonation d = a.d;
        double t = a.ticks(0) + (seconds - SmokeGrid.seconds(0)) * 20;
        drawn = 0;
        tauMax = massDrawn = 0;
        streaks.removeIf(s -> {
            BlastGust g = s.gust;
            double x = g.x(t);
            if (x <= 0) return false;
            // dust leaves while the positive-phase wind blows: fraction by a time equals the fraction of air displacement
            double released = BlastWall.carriedShare(Math.min(x, 1)), last = Math.min(t, g.arrival + g.phase);
            parcel(g, g.arrival, t, parcel);
            double hx = parcel[0], hy = parcel[1], hz = parcel[2], spreadHead = parcel[3];
            parcel(g, last, t, parcel);
            double tx = parcel[0], ty = parcel[1], tz = parcel[2], spreadTail = parcel[3];
            s.layer(d, t);
            double sigma = s.r0 + 0.5 * (spreadHead + spreadTail);
            double mass = s.mass * released * s.aloft * apart(sigma, s.top);
            double half = 0.5 * Math.sqrt((hx - tx) * (hx - tx) + (hz - tz) * (hz - tz) + (hy - ty) * (hy - ty));
            // transverse optical depth through the streak center: Gaussian spread over length 2·half
            double tau = DustModel.K_MIX * mass / (2 * Math.PI * sigma * (sigma + 2 * half / Math.sqrt(2 * Math.PI)));
            if (x >= 1 && tau < CloudModel.SEEN) return true;
            drawn++;
            tauMax = Math.max(tauMax, tau);
            massDrawn += mass;
            out.segment(s.x + 0.5 * (hx + tx), s.y + 0.5 * (hy + ty), s.z + 0.5 * (hz + tz), 0.5 * (hx - tx), 0.5 * (hy - ty), 0.5 * (hz - tz),
                    sigma, sigma, 0, mass * DustModel.K_MIX / dustK, 0, dustR, dustG, dustB, CloudModel.T_AIR_K, 0);
            return false;
        });
        return !streaks.isEmpty();
    }

    /** Scenario line: living and visible streaks, maximum transverse optical depth, dust mass in the frame. */
    String describe() {
        return String.format(java.util.Locale.ROOT, "[%d live, %d drawn, tau max %.2f, %.1f kg]", streaks.size(), drawn, tauMax, massDrawn);
    }

    @Override
    public boolean ended() {
        return gone || streaks.isEmpty();
    }

    @Override
    public long seed() {
        return a.d.seed() ^ 0xD057L;
    }

    /** Billow frame follows the youngest streak's growth (older streaks already have large billows). */
    @Override
    public double puffGrowth(double seconds) {
        if (streaks.isEmpty()) return 2;
        Streak s = streaks.getLast();
        double t = a.ticks(0) + (seconds - SmokeGrid.seconds(0)) * 20;
        parcel(s.gust, s.gust.arrival, t, parcel);
        return Math.max(1, (s.r0 + parcel[3]) / s.r0);
    }
}
