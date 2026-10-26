package ua.zentix.incoming.client.nuclear;

import net.minecraft.util.Mth;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.ThermalModel;

/**
 * Detonation light on dust (DESIGN-nuke §6): ball above and fires below illuminate the dust wall, Wilson cloud and the pall
 * behind them ({@code fx_pall}, slot parameters — {@link #pallLight}), and dust at the camera ({@code NukePall.dustColor}). The ball is the same
 * source that lights the stem and particles ({@link MushroomModel#light}: Lambertian sphere of cap radius, black body at its visible
 * temperature, L·R²/d²); fires use {@link #glow}, {@link #burn}. This no longer has its own billows (the name remains): mushroom
 * and water column are volumes ({@link MushroomField}); dust wall, Wilson cloud and base surge are volumes in the pall
 * ({@link BlastWall}, {@link PallField}).
 */
public final class CloudPuffs {
    /** Cap black-body light: r, g, b, brightness (client thread). */
    private static final double[] BALL = new double[4];
    /** Over water: spray and mist near the ground (its color continues into the pall over water — {@code MIST} in {@code fx_pall}). */
    static final int SPRAY = 0xE6EAED;
    /** Incandescent light within the smoke, and billow glow strength over the smoke. */
    private static final double GLOW_IN_SMOKE = 0.85, EMISSIVE = 0.3;

    private final Detonation d;
    /** Ground distance to which the light pulse ignites (≥ {@link ThermalModel#IGNITE}), m: fires burn beneath the dust there. */
    private final double fireGround;
    /** {@link #pallLight} at {@link #nowT} without partial tick ({@link #pallLightNow}), and whether a mushroom existed. */
    private final float[] now = new float[8];
    private double nowT = Double.NaN;
    private boolean nowLit;

    public CloudPuffs(Detonation d) {
        this.d = d;
        double hob = Math.max(0, d.hobMetres());
        double slant = ThermalModel.rangeForFluence(ThermalModel.IGNITE, d.yieldKt(), d.surface(), d.visibility());
        fireGround = Math.sqrt(Math.max(0, slant * slant - hob * hob));
    }

    /** How many model seconds the mushroom is visible: rise and dispersion ({@link MushroomModel#lifeSeconds}). */
    public static double lifeSeconds(Detonation d) {
        return MushroomModel.lifeSeconds(d.yieldKt());
    }

    /**
     * Glow time stretch with yield: 1 at 15 kt, ×0.51 at 1 kt (ball and mushroom are half as high, cool faster:
     * full incandescence for 20 s, fades by 75 s).
     */
    static double glowScale(double yieldKt) {
        return Math.pow(Math.max(yieldKt, 0.1) / 15, 0.25);
    }

    /**
     * Fire glow beneath wall dust over time, 0..1 ({@link #burn}): at 15 kt full for 40 s, 0.57 at 90 s, fades by 150 s;
     * at 1 kt half as long ({@link #glowScale}).
     */
    static double glow(double t, double yieldKt) {
        double k = glowScale(yieldKt);
        return 1 - smooth(40 * k, 150 * k, t);
    }

    /**
     * Ball light on a white wall facing it, in fractions of a white wall at noon (without eye adaptation), from slot parameters
     * ({@link #pallLight}: black-body radius and brightness) at distance {@code d} from its center — {@link MushroomModel#light}
     * (same in {@code fx_pall}, {@code pallBallShare}, × eye adaptation); d and radius use the same units.
     */
    static double ball(double luminance, double radius, double d) {
        return luminance * radius * radius / Math.max(Math.max(d * d, radius * radius), 1e-6);
    }

    /**
     * Fire glow beneath dust 0..1 (same as {@code pallBurn} in {@code pall.glsl}): in the ignition zone out to {@code fireR}
     * ({@link #pallLight}), stronger from below, with incandescence {@code glow}.
     */
    static double burn(double r, double up, double glow, double fireR) {
        double e = Math.max(fireR, 1e-3);
        return (1 - smooth(0.92 * e, 1.04 * e, r)) * (0.95 - 0.45 * up) * glow;
    }

    /**
     * Glow strength on smoke at sky light {@code ambient}: incandescent light within smoke and glow over it. By day the eye
     * adapts to light, making the glow less visible.
     */
    public static double glowOnSmoke(float ambient) {
        return GLOW_IN_SMOKE * (1 - 0.5 * ambient) + EMISSIVE;
    }

    /**
     * Ball and fire light on the dust wall, Wilson cloud and pall behind them for mushroom {@code s} ({@code MushroomField.shape})
     * — used by {@code fx_pall} to light dust (ball — {@link #ball} beam from the cap center; fires — {@link #burn} from below), and
     * by {@code NukePall.dustColor} for camera dust. Into {@code into}: cap radius (ball radius for a ball), fire incandescence, downwind
     * drift of the cap center, fire-zone edge (blocks), black-body light at the visible cap temperature
     * ({@link MushroomModel#edgeTemperature}: linear r, g, b, fractions of a white wall at noon), cap-center height above
     * ground zero (blocks). Same center as the mushroom body and particle light ({@code MushroomField.centre}). false —
     * the mushroom no longer (or not yet) exists ({@code into} zeroed): no glow either.
     */
    public boolean pallLight(MushroomModel.Shape s, float[] into) {
        java.util.Arrays.fill(into, 0, 8, 0f);
        if (s == null) return false;
        double t = s.t(), y = d.yieldKt();
        BlackBody.light(MushroomModel.edgeTemperature(t, y, d.surface()), BALL);
        into[0] = (float) d.blocks(s.capR());
        into[1] = (float) glow(t, y);
        into[2] = (float) MushroomField.driftAlong(d, s);
        into[3] = (float) d.blocks(fireGround);
        for (int i = 0; i < 3; i++) into[4 + i] = (float) BALL[i];
        into[7] = (float) d.blocks(s.capY());
        return true;
    }

    /**
     * {@link #pallLight} of detonation {@code a}'s mushroom at its time without partial tick — one calculation per time: mushroom shape and black-body
     * spectrum cost tens of microseconds, while camera dust ({@code NukePall.dustColor}) reads light for every billow.
     */
    boolean pallLightNow(ClientNuclear.Active a, float[] into) {
        double t = a.seconds(0);
        if (t != nowT) {
            nowLit = pallLight(MushroomField.shape(a, 0), now);
            nowT = t;
        }
        System.arraycopy(now, 0, into, 0, now.length);
        return nowLit;
    }

    static double smooth(double e0, double e1, double x) {
        double f = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return f * f * (3 - 2 * f);
    }
}
