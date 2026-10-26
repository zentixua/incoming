package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.client.fx.layer.HeatPass;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CloudModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.ShockOptics;

/**
 * The nuke in the distortion pass ({@link HeatPass}): visible shock front and heat haze above the ball and firestorm.
 * All from the client detonation model, without packets.
 * <ul>
 * <li><b>Front</b> — a sphere of the game front radius ({@link Detonation#frontRadius}) at the detonation, once it separates from the ball
 * (until then the front is the glowing ball edge, and nothing behind it is visible); refractive-index jump ({@link ShockOptics#indexJump})
 * follows front pressure on this sphere at four heights from ground to top: for a surface burst the whole hemisphere is the ground-reflected
 * wave ({@link Detonation#overpressureKpa}); for an airburst it is the incident free-air wave
 * ({@link HobBlast#freeAirPsi}), with a Mach stem near the ground in the Mach region (ground pressure). The model has no triple-point
 * height; the jump interpolates linearly between the ground and the next height (⚠ stem — tens to hundreds of meters near the ground); the compressed
 * layer behind the front is {@link ShockOptics#DECAY_SHARE} of the game positive-phase length (from the front to
 * {@link Detonation#phaseEndRadius}). Beyond the dust-wall edge ({@link Detonation#HIT_KPA}), the model wave is sound only, and
 * the front is invisible: the jump fades from twice the edge pressure to the edge (<b>presentation:</b> the weak wave there is still a discontinuity, but
 * nothing renders it or the light behind it; the wave, its arrival and effects stay the same).</li>
 * <li><b>Ball and cap</b> — hot gas using the same law as a conventional explosion ball ({@code FarBlasts.heat}): a column from the cap bottom
 * to its top, with cap radius and visible temperature ({@link MushroomModel#edgeTemperature}); vortices rise at the
 * cloud rise speed ({@link CloudModel#riseSpeed}).</li>
 * <li><b>Firestorm</b> — a field of fire sources: each storm-map cell contains as many fire blocks as burn in it
 * (cell strength × flame — fraction of columns, {@link FireStorm#meanFlame}, averaged over the zone; × fire-model trust), using the same
 * law as Minecraft fires ({@link FirePlume}, Heskestad); the column spans the whole ignition zone, the vortices belong to each source.</li>
 * </ul>
 */
public final class NukeHeat {
    /** Front jump fades from this pressure, kPa, to the dust-wall edge (presentation, see above). */
    static final double FADE_FROM_KPA = 2 * Detonation.HIT_KPA;

    private static final double[] DN = new double[4];
    private static final double[] CENTRE = new double[3];

    private NukeHeat() {}

    /** Frame detonation fronts, balls and storms as seen from {@code eye}. */
    public static void heat(Vec3 eye, float partial, HeatPass.Frame f) {
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            if (a.ticks(partial) <= 0) continue;
            front(a, eye, partial, f);
            ball(a, eye, partial, f);
            storm(a, eye, f);
        }
    }

    /** Front-jump share at pressure {@code kpa}: fades from {@link #FADE_FROM_KPA} to zero at the dust-wall edge; beyond it — 0. */
    static double seen(double kpa) {
        double x = Math.max(0, Math.min(1, (kpa - Detonation.HIT_KPA) / (FADE_FROM_KPA - Detonation.HIT_KPA)));
        return x * x * (3 - 2 * x);
    }

    /**
     * Refractive-index jump on detonation {@code d}'s front sphere of radius {@code r} blocks, at four heights evenly from the ground
     * ({@code low} — sine of the angle above the detonation at the ground) to the top, into {@code out}; false — invisible everywhere.
     */
    static boolean jumps(Detonation d, double r, double low, double[] out) {
        boolean any = false;
        Vec3 b = d.burst();
        double hob = Math.max(0, d.hobMetres());
        boolean air = !d.surface() && hob > 0;
        for (int k = 0; k < 4; k++) {
            double sine = low + (1 - low) * k / 3.0, across = Math.sqrt(Math.max(0, 1 - sine * sine));
            Vec3 p = new Vec3(b.x + r * across, b.y + r * sine, b.z);
            // airburst: Mach stem at the ground in the Mach region (ground pressure), otherwise the incident wave
            boolean stem = k == 0 && d.metres(r * across) >= HobBlast.machStem(hob, d.yieldKt());
            double kpa = air && !stem ? BlastModel.kpa(HobBlast.freeAirPsi(d.metres(r), d.yieldKt())) : d.overpressureKpa(p);
            out[k] = ShockOptics.indexJump(kpa, HeatPass.N0) * seen(kpa);
            any |= out[k] > 0;
        }
        return any;
    }

    private static void front(ClientNuclear.Active a, Vec3 eye, float partial, HeatPass.Frame f) {
        Detonation d = a.d;
        double ticks = a.ticks(partial), r = d.frontRadius(ticks);
        if (r <= d.blocks(FireballModel.radius(a.seconds(partial), d.yieldKt(), d.surface()))) return;
        double low = Math.max(-1, Math.min(1, (d.groundY() - d.burst().y) / r));
        if (!jumps(d, r, low, DN)) return;
        double l = ShockOptics.DECAY_SHARE * (r - d.phaseEndRadius(ticks));
        Vec3 b = d.burst();
        f.front(b.x - eye.x, b.y - eye.y, b.z - eye.z, r, l, low, DN);
    }

    private static void ball(ClientNuclear.Active a, Vec3 eye, float partial, HeatPass.Frame f) {
        MushroomModel.Shape s = MushroomField.shape(a, partial);
        if (s == null) return;
        Detonation d = a.d;
        MushroomField.centre(a, s, CENTRE);
        double bottom = d.groundY() + d.blocks(s.capBottom()), top = d.groundY() + d.blocks(s.top());
        double kelvin = MushroomModel.edgeTemperature(s.t(), d.yieldKt(), d.surface());
        // model m/s are the same blocks per game second: both distance and time scale by the same factor
        double rise = CloudModel.riseSpeed(s.t(), Math.max(0, d.hobMetres()), d.yieldKt(), d.surface());
        f.hot(CENTRE[0] - eye.x, bottom - eye.y, CENTRE[2] - eye.z, top - bottom, d.blocks(s.capR()), kelvin, rise);
    }

    private static void storm(ClientNuclear.Active a, Vec3 eye, HeatPass.Frame f) {
        FireStorm s = a.storm;
        int cells = s.cells();
        if (!FireStorms.enabled || cells == 0) return;
        double burning = s.meanFlame() * s.gate();
        if (burning <= 0) return;
        double cell = s.cell(), blocks = burning * cell * cell;
        Detonation d = a.d;
        FirePlume p = new FirePlume(blocks, d.windSpeed(), ClientAir.now().stability());
        Vec3 b = d.burst();
        f.plumes(b.x - eye.x, d.groundY() - eye.y, b.z - eye.z, p, 1, cell * Math.sqrt(cells / Math.PI));
    }
}
