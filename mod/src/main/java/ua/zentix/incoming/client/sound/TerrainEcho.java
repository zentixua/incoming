package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.nuclear.model.FrontProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Distant blast terrain echoes (without Minecraft, covered by unit tests): which slopes return the impact to
 * ear, when, and how much quieter than direct sound. Front impact at listener is only the start: same front
 * reaches surrounding hills, forests and cities, and every slope facing both blast and ear reflects it —
 * rumble rolls for seconds, mountains return a distinct impact.
 * <p>
 * Rays from ear over height map ({@link Sightline.Heights}: client chunks, map terrain tiles beyond),
 * {@link #RAYS} around the circle; step grows with distance ({@link #step}). What ear sees reflects: a point
 * rising above the ray's horizon (elevation higher than all closer points) is an ear-visible facet; its solid
 * angle is elevation increment times ray width. Lambertian reflection (scattering, not mirror: height-map
 * slope is no flat wall): energy fraction relative to direct sound at ear
 * <pre>E = ρ · cos θ₁ · Ω / π · (p(r₁) / p(rₑ))²</pre>
 * where ρ is low-band ground reflectance ({@link #ALBEDO}), θ₁ is front incidence angle on slope (normal from
 * height-map differences), Ω is facet solid angle at ear, p is front pressure at slope distance r₁ and ear
 * distance rₑ from blast (near epicentre wave is nonlinear and weakens faster than spreading: no more returns
 * to ear than spherically spreading sound would give — ratio at most rₑ/r₁). Flat ground reflects lows nearly
 * specularly forward, not back to ear: fraction {@link #FLAT} scatters; slopes from {@link #STEEP} scatter fully
 * (forest crowns and house walls are also height-map slopes). A slope hidden from blast behind a ridge receives
 * the front around it — {@link #SHADOW} energy. Impact reaches slope with the game front
 * ({@code Detonation.arrivalTicks}: dust wall, sound speed beyond); slope to ear at sound speed; delay relative
 * to direct-impact arrival.
 * <p>
 * Diffuse model underestimates lows from large slopes (50–100 Hz wavelength 3–7 m, hundreds-of-metres slope
 * reflects nearly specularly) and ignores direct sound losing more near ground than reflection from above;
 * echoes are therefore boosted by {@link #LIFT_DB}, but no reflection exceeds {@link #CAP_DB} of direct sound.
 */
public final class TerrainEcho {
    /** Rays around circle (every 5°; budget). */
    public static final int RAYS = 72;
    /** Slope search distance from ear, blocks (budget; not audible range). */
    public static final double RANGE = 4096;
    /** Closer is not a reflector: near reflections already in direct-impact recording ({@link #MIN_DELAY}). */
    static final double NEAR = 24;
    /** Ray step: fraction of distance, with minimum and maximum, blocks (budget). */
    static final double STEP_SHARE = 0.03, STEP_MIN = 8, STEP_MAX = 96;
    /** Low-band ground reflectance (63–250 Hz): grass, forest, rocks — 0.7–0.9 (est.). */
    static final double ALBEDO = 0.8;
    /** Flat-ground scattering fraction; slopes steeper than {@link #STEEP} (sin² slope) scatter fully (est.). */
    static final double FLAT = 0.1, STEEP = 0.25;
    /** A slope behind a ridge receives this front-energy fraction around it (est.). */
    static final double SHADOW = 0.25;
    /** Echo boost over diffuse model and maximum reflection relative to direct sound, dB (presentation). */
    static final double LIFT_DB = 12, CAP_DB = -3;
    /** Below this (relative to direct, dB), omit reflection (budget). */
    static final double FLOOR_DB = -45;
    /**
     * Facet whose echo arrives earlier than this many ticks after direct impact is omitted (spread across an
     * accepted facet may begin earlier). presentation: direct impact is a recording, retaining its location's
     * early reflections (near perspective tail unattenuated for first 0.35 s; mid and far never attenuated,
     * {@code tools/build_sounds.py}); it supplies early coloration, convolution would add a second early set.
     * Match between recording and game site is unverified. Not “inaudible”: separate echoes are audible even
     * after 5–20 ms (clicks and noise bursts, Litovsky et al., JASA 106, 1999, table I).
     */
    static final double MIN_DELAY = 2;

    private TerrainEcho() {}

    /** Blast: when front reaches a distance from detonation and its pressure there. */
    public interface Blast {
        /** Ticks after detonation until front reaches {@code blocks}. */
        double arrivalTicks(double blocks);

        /** Front pressure at this distance (any units: ratio matters). */
        double pressure(double blocks);
    }

    /**
     * One reflection.
     *
     * @param x,y,z     slope position
     * @param azimuth   direction from ear, rad ({@code atan2(dz, dx)})
     * @param delay     arrival ticks after direct impact
     * @param energy    energy relative to direct impact (with {@link #LIFT_DB} boost, at most {@link #CAP_DB})
     * @param extraPath how many blocks longer path via slope is than direct — air absorbs highs
     * @param distance  ear-to-slope distance, blocks
     * @param early     earliest facet arrival, ticks after direct impact
     * @param spread    arrival spread across facet, ticks: whole slope area reflects, with different paths via
     *                  near/far and left/right edges — facet echo lasts this long from {@code early} ({@link EchoMix})
     */
    public record Reflection(double x, double y, double z, double azimuth, double delay, double energy, double extraPath, double distance,
                             double early, double spread) {
        /** Gain (amplitude) relative to direct impact. */
        public double gain() {
            return Math.sqrt(energy);
        }
    }

    /** Ray step at distance r: farther means larger (distant slope already subtends a small angle). */
    static double step(double r) {
        return Math.max(STEP_MIN, Math.min(STEP_MAX, r * STEP_SHARE));
    }

    /** All rays at once (checks; client computes in batches, {@link #ray}). */
    public static List<Reflection> trace(Sightline.Heights h, double ex, double ey, double ez, double bx, double by, double bz, Blast blast) {
        List<Reflection> out = new ArrayList<>();
        for (int i = 0; i < RAYS; i++) ray(h, i, RAYS, RANGE, ex, ey, ez, bx, by, bz, blast, out);
        return out;
    }

    /**
     * One ray from ear: ear-visible facets rising above ray horizon enter {@code out}.
     *
     * @param index ray number among {@code rays} around circle
     */
    public static void ray(Sightline.Heights h, int index, int rays, double range, double ex, double ey, double ez, double bx, double by, double bz,
                           Blast blast, List<Reflection> out) {
        double phi = 2 * Math.PI * index / rays, width = 2 * Math.PI / rays;
        double cx = Math.cos(phi), cz = Math.sin(phi);
        double re = Math.sqrt((ex - bx) * (ex - bx) + (ey - by) * (ey - by) + (ez - bz) * (ez - bz));
        double direct = blast.arrivalTicks(re), pe = blast.pressure(re);
        if (!(pe > 0)) return;
        double horizon = Double.NaN, lastTop = Double.NaN, lastR = 0;
        for (double r = NEAR; r <= range; r += step(r)) {
            double x = ex + cx * r, z = ez + cz * r;
            double top = h.at((int) Math.floor(x), (int) Math.floor(z));
            if (Double.isNaN(top)) continue;
            double t = (top - ey) / r;
            double prevTop = lastTop, prevR = lastR;
            lastTop = top;
            lastR = r;
            // first known point sets horizon: ear cannot see below it
            if (Double.isNaN(horizon)) {
                horizon = t;
                continue;
            }
            if (t <= horizon) continue;
            double rise = Math.atan(t) - Math.atan(horizon);
            horizon = t;
            // facet solid angle at ear: elevation increment × ray width × cos elevation
            double omega = rise * width * Math.cos(Math.atan(t));
            double[] n = normal(h, x, z, step(r), cx, cz, top, prevTop, r - prevR);
            double px = x - bx, py = top - by, pz = z - bz;
            double r1 = Math.sqrt(px * px + py * py + pz * pz);
            if (r1 < 1) continue;
            // front incident on slope: normal faces direction from blast
            double cos1 = -(px * n[0] + py * n[1] + pz * n[2]) / r1;
            if (cos1 <= 0) continue;
            double ux = ex - x, uy = ey - top, uz = ez - z;
            double r2 = Math.sqrt(ux * ux + uy * uy + uz * uz);
            // slope must face ear too (visible top, not reverse slope)
            if (ux * n[0] + uy * n[1] + uz * n[2] <= 0) continue;
            double ratio = Math.min(blast.pressure(r1) / pe, re / r1);
            double tilt = 1 - n[1] * n[1];
            double e = ALBEDO * (FLAT + (1 - FLAT) * Math.min(1, tilt / STEEP)) * cos1 * omega / Math.PI * ratio * ratio;
            if (!(e > 0)) continue;
            double delay = arrival(blast, x, top, z, ex, ey, ez, bx, by, bz) - direct;
            if (delay < MIN_DELAY) continue;
            if (Sightline.trace(h, bx, by, bz, x, top + 1, z).pathDifference() > 0) e *= SHADOW;
            double db = Math.min(CAP_DB, 10 * Math.log10(e) + LIFT_DB);
            if (db < FLOOR_DB) continue;
            // facet from previous ray point to this one, ray width across: arrival via its corners
            double lo = delay, hi = delay, half = width / 2;
            for (int c = 0; c < 4; c++) {
                double rc = c < 2 ? prevR : r, yc = c < 2 ? prevTop : top, ac = phi + (c % 2 == 0 ? -half : half);
                double d = arrival(blast, ex + Math.cos(ac) * rc, yc, ez + Math.sin(ac) * rc, ex, ey, ez, bx, by, bz) - direct;
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
            }
            out.add(new Reflection(x, top, z, phi, delay, Math.pow(10, db / 10), r1 + r2 - re, r2, lo, hi - lo));
        }
    }

    /** Ticks after detonation until impact reflected at slope point reaches ear: front to slope, sound from it. */
    static double arrival(Blast blast, double x, double y, double z, double ex, double ey, double ez, double bx, double by, double bz) {
        double r1 = Math.sqrt((x - bx) * (x - bx) + (y - by) * (y - by) + (z - bz) * (z - bz));
        double r2 = Math.sqrt((x - ex) * (x - ex) + (y - ey) * (y - ey) + (z - ez) * (z - ez));
        return blast.arrivalTicks(r1) + r2 / FrontProfile.SOUND_SPEED;
    }

    /**
     * Slope normal at point: height-map differences on four sides; where unavailable (known map edge), use rise
     * along ray from previous point.
     */
    static double[] normal(Sightline.Heights h, double x, double z, double step, double cx, double cz, double top, double prevTop, double along) {
        double g = Math.max(2, step / 2);
        double hx1 = h.at((int) Math.floor(x + g), (int) Math.floor(z)), hx0 = h.at((int) Math.floor(x - g), (int) Math.floor(z));
        double hz1 = h.at((int) Math.floor(x), (int) Math.floor(z + g)), hz0 = h.at((int) Math.floor(x), (int) Math.floor(z - g));
        double gx, gz;
        if (Double.isNaN(hx1) || Double.isNaN(hx0) || Double.isNaN(hz1) || Double.isNaN(hz0)) {
            double s = Double.isNaN(prevTop) || along <= 0 ? 0 : (top - prevTop) / along;
            gx = cx * s;
            gz = cz * s;
        } else {
            gx = (hx1 - hx0) / (2 * g);
            gz = (hz1 - hz0) / (2 * g);
        }
        double len = Math.sqrt(gx * gx + 1 + gz * gz);
        return new double[]{-gx / len, 1 / len, -gz / len};
    }
}
