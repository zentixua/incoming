package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.nuclear.model.ShockOptics;

import java.util.Arrays;

/**
 * Conventional-weapon flash/fire light on world and air shimmer above fire/ball: full-frame pass after pall and
 * before layer quads ({@code fx_heat}: laws there), without a new mod: frame copy, world distance ({@link SceneDepth}).
 * Nuclear fireball world light is separate in the pall pass ({@code fx_pall_composite}); this pass handles conventional balls/fire.
 * Block fire belongs to the game: it emits block light; this mod draws no light/shimmer over it (only over own sources).
 * <p>
 * <b>Shimmer.</b> Air refractive index n − 1 = {@value #N0} at 288 K (Edlén 1966, 550 nm), proportional to density,
 * i.e. 1/T; plume temperature fluctuations are {@value #TURBULENCE} of axial excess (George, Alpert, Tamanini, Int. J.
 * Heat Mass Transfer 20, 1977: 0.38–0.40); nozzle jet uses {@value #JET_TURBULENCE} (⚠ heated-jet (est.)),
 * δn = (n₀ − 1)·I·(T₀/T)·(1 − T₀/T); eddies are {@value #EDDY} of column radius (outer scale, ⚠ (est.)), carried by gas.
 * Ray angle of arrival (one axis) after path L in Kolmogorov turbulence: σ² = 2.91·C_n²·L·D^(−1/3) (Tatarskii 1961;
 * Andrews, Phillips, “Laser Beam Propagation through Random Media”, 2005), D is what eye resolves at gas (pupil or
 * visual-acuity spot there), C_n² ≈ 2δn²·ℓ^(−2/3) (structure function reaches 2δn² at outer scale ℓ, ⚠
 * order estimate): σ = δn·√(5.82·L/ℓ)·(ℓ/D)^(1/6); spots larger than ℓ average eddies, ×ℓ/D ({@link #hazeAngle}). Eddies
 * passing faster than eye integration ({@value #EYE_S} s) average out: σ·√(τ/{@value #EYE_S})
 * ({@link #hazeSeen}). Old δn·√(L/ℓ) included only ℓ-sized eddies, 2–5 times smaller.
 * <p>
 * <b>presentation:</b> at normal field of view a screen pixel spans 3–6 arcminutes; eye resolves one ({@value #ACUITY} rad):
 * shimmer visible to eye at fire would occupy fractions of a pixel on screen. Frame shift uses fractions of what eye
 * resolves rather than a pixel ({@link #resolve}): presentation, gain pixel/acuity (3–6 at 70° FOV); in binoculars,
 * where a pixel is finer than acuity, unchanged. Haze size, position and strength follow physics.
 * <p>
 * <b>Engine plume light</b> is a jet, not a ball, from nozzle exit to tail-light end ({@link Frame#jet}, {@link Flame#tailEnd}): each
 * {@link Flame} part (core, afterburning flame, cooling tail) is a uniform emissive segment of its brightness/color; face
 * light is the exact segment integral ({@link #segment}), only over the part above the face. Far away it converges to the old
 * ball of equal side-view area.
 * <p>
 * <b>Shadows.</b> Blocks occlude direct world light: from world point to source through eye-local occluder-window cells
 * ({@link Solids}, {@link #blocked}): world consists of blocks; path occluders are known exactly, in frame or not. Frame depth
 * cannot decide this (behind visible face may be house thickness or emptiness behind a post). No shadows beyond the window
 * ({@value Solids#SIZE_XZ} blocks across; ⚠ window limit).
 * <p>
 * <b>Nuclear shock wave</b> ({@code client/nuclear/NukeHeat}) uses the same image shift behind compressed front air:
 * front sphere, refractive-index jump from model pressure, deviation from {@link ShockOptics} (shader copy). Visible
 * as a thin ring at front edge against sky/ground while contrast remains noticeable.
 * <p>
 * At most four sources of each kind (two fronts), most noticeable at eye. No pass without light,
 * hot gas or front; front shifts below {@link #SHIFT_MIN} are skipped, unchanged pixels written as before.
 */
public final class HeatPass {
    /**
     * Direct light of a conventional Lambertian sphere: area / distance², air, face cosine. Copy: heatBallGain in
     * include/heat.glsl. Diffuse/reflected light is not modeled here: nuclear thermal approximation does not define
     * conventional-flash visible angular transfer. Nuclear pass retains its own law.
     */
    public static double ballGain(double d2, double r2, double sd, double nv) {
        return r2 / Math.max(d2, r2) * Math.exp(-sd) * Math.max(nv, 0);
    }

    /** Air n − 1 at ground (288 K, 1 atm, 550 nm), Edlén. */
    public static final double N0 = 2.77e-4;
    /** Plume temperature fluctuations as a fraction of axial excess. */
    public static final double TURBULENCE = 0.4;
    /**
     * Heated-jet axial temperature fluctuations as a fraction of excess there (⚠ (est.): round heated jets give 0.15–0.25 on axis
     * — Chevray, Tutu, J. Fluid Mech. 88, 1978).
     */
    public static final double JET_TURBULENCE = 0.2;
    /** Eddies as this fraction of column radius (⚠ (est.)). */
    public static final double EDDY = 0.2;
    /** Visual acuity, one arcminute in rad (Snellen 1.0). */
    public static final double ACUITY = 2.9e-4;
    /** Pupil, m (day 2–4 mm, night up to 7: ⚠ average; enters formula at power 1/6). */
    public static final double PUPIL = 4e-3;
    /** Eye light-integration time, s (Bloch: 0.05–0.1; ⚠ lower bound). */
    public static final double EYE_S = 0.05;
    /** Kolmogorov angle-of-arrival sum: 2·2.91 (C_n² = 2δn²ℓ^(−2/3), σ² = 2.91·C_n²·L·D^(−1/3)). */
    public static final double KOLMOGOROV = 5.82;
    /** Plume/jet velocity fluctuations as a fraction of mean speed (eddy lifetime ℓ/u'; ⚠ (est.)). */
    public static final double SWIRL = 0.3;
    /** Eddy-pattern advection cycle, s: two layers offset by half-cycle, switch at zero weight (flow map). */
    public static final double HAZE_CYCLE = 2;
    /**
     * Gas cooler than air plus this, K, has no shimmer (budget). At +20 K, δn is still about a quarter of peak
     * (T₀/T·(1 − T₀/T)), not 1 %: ⚠ visible shimmer there has not been checked in a frame.
     */
    public static final double COOL = 20;
    /** Shimmer below this pixel fraction is not drawn. */
    public static final double SHIFT_MIN = 0.05;
    /** No world light if ball light at ground beneath it is below this share of open-sky light (like the nuke). */
    static final double SEEN = 0.005;
    /** Maximum shadow-ray cells (beyond is clear): diagonal through whole window can take 480. */
    static final int SHADOW_CELLS = 160;
    /**
     * Skip shadow search for sources whose direct light is below this share of total face illumination (sky/fire): eye contrast
     * threshold ~1–2 % (Blackwell, “Contrast thresholds of the human eye”, JOSA 36, 1946; higher at night, so conservative).
     * Copy: {@code WEBER} in {@code include/heat.glsl} (evaluated there: visibility pass {@code fx_heat_shadow} searches shadows).
     */
    static final double WEBER = 0.02;

    @Nullable
    static ShaderInstance shader;

    /** Frame sources. */
    public static final class Frame {
        final Matrix4f lights = new Matrix4f().zero(), lightColor = new Matrix4f().zero(), hot = new Matrix4f().zero(),
                shape = new Matrix4f().zero(), front = new Matrix4f().zero();
        /**
         * Hot gas, beyond {@link #hot} (eye-relative base/radius) and {@link #shape} (axial length, base eddy size, base gas speed,
         * (n₀ − 1)·fluctuation share): axis from base (xyz), radius growth per axial meter (w); base temperature excess,
         * K, axial position beyond which it falls as 1/x, position beyond which speed does too (nowhere for a column).
         */
        final Matrix4f axis = new Matrix4f().zero(), gas = new Matrix4f().zero();
        /** Shell e-folding thickness and sine of angle above ground-level center: front 0 (x, y), 1 (z, w). */
        final Vector4f frontShape = new Vector4f();
        /**
         * Plumes: column i is eye-relative nozzle exit (xyz), core end (w; 0 absent); jet axis from nozzle (xyz), flame end;
         * jet end, exit half-width, growth per meter, air extinction; core/flame/tail light per jet meter
         * (rgb, linear, noon white wall = 1 at one meter sideways from a meter of jet; no adaptation, frame applies it).
         */
        final Matrix4f jets = new Matrix4f().zero(), jetAxis = new Matrix4f().zero(), jetSize = new Matrix4f().zero(),
                jetCore = new Matrix4f().zero(), jetFlame = new Matrix4f().zero(), jetTail = new Matrix4f().zero();
        private final double[] lightWeight = new double[4], hotWeight = new double[4], frontWeight = new double[2], jetWeight = new double[4];
        /** Frame hot-gas column shift, pixels (most noticeable position, as in selection). */
        final double[] hotShift = new double[4];
        /** Slot front is nuclear (conventional explosions cannot evict it: their presence leaves nuclear visuals unchanged). */
        private final boolean[] frontNuke = new boolean[2];
        private final Vector4f column = new Vector4f();
        /**
         * Frame eye adaptation ({@code Sight.exposure}: skylight and fire light at eye, one for whole frame), open-sky
         * light (linear, for light threshold); pixel angle, rad.
         */
        public double exposure = 1, open = 1, pixel = 1e-3;
        /**
         * Frame fire light at eye (white-wall illuminance at eye, linear, noon white wall = 1, without
         * adaptation): drives next frame's eye adaptation ({@link #atEye()}).
         */
        double atEye;

        /**
         * Eye adaptation including fire: fire illuminates eye position, eye/exposure adapts to surrounding skylight
         * (day 1, night 1/1200, {@code Sight.adapted}) and fire light at eye together ({@code Sight.exposure}).
         * Otherwise fire light would enter frame with night ×1200 and white out everything within hundreds of blocks (night Grad salvo:
         * seconds of white frame), while booster plume 8 m from camera fills frame with glare/veil. Balls, pools, fires, debris use this
         * frame (collected before quads), projectile plumes previous frame; immediate adaptation, while real eye takes 0.1–1 s
         * (pupil, cones; ⚠ order estimate).
         */
        double adapted() {
            return exposure;
        }

        void begin() {
            atEye = 0;
            lights.zero();
            lightColor.zero();
            hot.zero();
            shape.zero();
            axis.zero();
            gas.zero();
            front.zero();
            frontShape.zero();
            jets.zero();
            jetAxis.zero();
            jetSize.zero();
            jetCore.zero();
            jetFlame.zero();
            jetTail.zero();
            Arrays.fill(lightWeight, 0);
            Arrays.fill(hotWeight, 0);
            Arrays.fill(hotShift, 0);
            Arrays.fill(frontWeight, 0);
            Arrays.fill(frontNuke, false);
            Arrays.fill(jetWeight, 0);
        }

        /** Frame fire collected: eye light drives adaptation for what draws before layer next frame. */
        public void done() {
            lastAtEye = atEye;
        }

        /** Additional eye light (linear, noon white wall = 1) from sources absent from this frame's {@link #light}. */
        public void eye(double light) {
            atEye += Math.max(0, light);
        }

        /** This frame's fire light at eye (linear, noon white wall = 1). */
        public double atEye() {
            return atEye;
        }

        boolean lit() {
            return lights.m03() > 0 || lights.m13() > 0 || lights.m23() > 0 || lights.m33() > 0 || jetWeight[0] > 0 || jetWeight[1] > 0
                    || jetWeight[2] > 0 || jetWeight[3] > 0;
        }

        boolean hotAny() {
            return hot.m03() > 0 || hot.m13() > 0 || hot.m23() > 0 || hot.m33() > 0;
        }

        boolean frontAny() {
            return front.m03() > 0 || front.m13() > 0;
        }

        /** Front slot i holds the nuclear front. */
        boolean nuke(int i) {
            return frontNuke[i];
        }

        /**
         * Light sphere: eye-relative center (x, y, z), radius r; surface light linear, noon white
         * wall = 1 (rgb, no adaptation, frame applies it); air extinction air, 1/block; h is center height above
         * ground (threshold: ground light below ball versus sky).
         */
        public void light(double x, double y, double z, double r, double cr, double cg, double cb, double air, double h) {
            light(x, y, z, r, cr, cg, cb, air, h, true);
        }

        /** Same; eye means light also reaches eye ({@link #atEye}; false if already counted there). */
        public void light(double x, double y, double z, double r, double cr, double cg, double cb, double air, double h, boolean eye) {
            double lum = 0.2126 * cr + 0.7152 * cg + 0.0722 * cb;
            if (r <= 0 || lum <= 0) return;
            double w = lum * r * r / Math.max(x * x + y * y + z * z, r * r);
            if (eye) atEye += w;
            if (lum * exposure * r * r / Math.max(h * h, r * r) < SEEN * Math.max(open, 1e-4)) return;
            int slot = 0;
            for (int i = 1; i < 4; i++) if (lightWeight[i] < lightWeight[slot]) slot = i;
            if (w <= lightWeight[slot]) return;
            lightWeight[slot] = w;
            lights.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r));
            lightColor.setColumn(slot, column.set((float) cr, (float) cg, (float) cb, (float) air));
        }

        /**
         * Hot gas: column from base (x, y, z relative to eye), height h, radius r, temperature kelvin, rise speed rise
         * (m/s = blocks/s).
         */
        public void hot(double x, double y, double z, double h, double r, double kelvin, double rise) {
            hot(x, y, z, h, r, kelvin, rise, EDDY * r);
        }

        /** Same with eddy size {@code eddy}, blocks: column is a field of separate plumes, eddies are theirs ({@link #plumes}). */
        public void hot(double x, double y, double z, double h, double r, double kelvin, double rise, double eddy) {
            if (r <= 0 || h <= 0 || kelvin < CloudModel.T_AIR_K + COOL) return;
            tube(x, y, z, 0, 1, 0, h, r, 0, Math.max(0.05, eddy), rise, kelvin - CloudModel.T_AIR_K, NOWHERE, NOWHERE, TURBULENCE);
        }

        /**
         * Hot gas of nozzle jet {@code flame}: eye-relative jet-light center (x, y, z), unit axis from nozzle
         * backward (jx, jy, jz), cut is axial path from exit to ground (as in {@link #jet}). Jet law matches light
         * ({@code FlameModel}): flame temperature to flame end, excess beyond ∝ 1/x; exit gas speed to core
         * end, beyond ∝ 1/x (Pope, “Turbulent Flows”, 2000, ch. 5: U₀/Uⱼ = B·d/x); half-width {@link Flame#widthAt}.
         * Jet extends until excess falls to {@link #COOL}, no farther than ground or gas travel since ignition: burn
         * seconds of burning ({@link Flame#gasReach}) plus straight projectile travel flown from ignition (gas left there is
         * behind nozzle). Eddy pattern moves with nozzle, while a fast projectile leaves hot gas almost
         * stationary behind; for a turning projectile gas follows path but column follows current axis (⚠ simplifications).
         */
        public void exhaust(double x, double y, double z, double jx, double jy, double jz, Flame flame, double cut, double burn, double flown) {
            double core = flame.core(), fl = Math.max(flame.flame(), core), excess = flame.flameTemperature() - CloudModel.T_AIR_K;
            if (core <= 0 || flame.width() <= 0 || flame.speed() <= 0 || excess < COOL) return;
            double len = Math.min(Math.min(cut, fl * excess / COOL), flown + flame.gasReach(burn)), mid = flame.middle();
            if (!(len > 0)) return;
            tube(x - jx * mid, y - jy * mid, z - jz * mid, jx, jy, jz, len, flame.width(), flame.width() / core, EDDY * flame.width(),
                    flame.speed(), excess, fl, core, JET_TURBULENCE);
        }

        /**
         * Hot-gas column along axis (ax, ay, az) from eye-relative base (x, y, z), length len: base radius r0, growth grow per
         * axial meter, base eddy size eddy (grows with radius), gas speed speed, base temperature excess excess; excess
         * beyond axial position hotTo and speed beyond fastTo fall as 1/x; fluctuations are turbulence of excess.
         * Columns giving no pixel a {@link #SHIFT_MIN} shift are unnecessary.
         */
        private void tube(double x, double y, double z, double ax, double ay, double az, double len, double r0, double grow, double eddy,
                          double speed, double excess, double hotTo, double fastTo, double turbulence) {
            // most noticeable column position: δn peaks at T = 2T₀ (if gas is that hot), otherwise at base
            double at = excess > CloudModel.T_AIR_K && hotTo < len ? Math.min(len, hotTo * excess / CloudModel.T_AIR_K) : 0;
            double hotAt = excess * Math.min(1, hotTo / Math.max(at, 1e-3)), r = r0 + grow * at;
            double dn = dn(CloudModel.T_AIR_K + hotAt, turbulence), e = eddy * r / r0, u = speed * Math.min(1, fastTo / Math.max(at, 1e-3));
            double cx = x + ax * 0.5 * len, cy = y + ay * 0.5 * len, cz = z + az * 0.5 * len, d = Math.sqrt(cx * cx + cy * cy + cz * cz);
            double chord = Math.max(2 * r, Math.min(len, 4 * r)), near = Math.max(1, d - 0.5 * len - r);
            double px = hazeShift(dn, chord, e, near, pixel, u);
            if (px < SHIFT_MIN) return;
            double w = px * r * Math.max(r, len) / Math.max(d * d, r * r);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (hotWeight[i] < hotWeight[slot]) slot = i;
            if (w <= hotWeight[slot]) return;
            hotWeight[slot] = w;
            hotShift[slot] = px;
            hot.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r0));
            axis.setColumn(slot, column.set((float) ax, (float) ay, (float) az, (float) grow));
            shape.setColumn(slot, column.set((float) len, (float) eddy, (float) Math.max(0.1, speed), (float) (N0 * turbulence)));
            gas.setColumn(slot, column.set((float) excess, (float) hotTo, (float) fastTo, 0));
        }

        /**
         * Hot gas above plume {@code p}'s flame source (eye-relative base (x, y, z)): column up to height where Heskestad axial excess
         * ({@link FirePlume#excess}) falls to {@link #COOL}, radius is source or 0.12 of height (plume half-width,
         * Heskestad), temperature is midpoint excess, speed axial there ({@link FirePlume#axisSpeed}); share share.
         */
        public void plume(double x, double y, double z, FirePlume p, double share) {
            plumes(x, y, z, p, share, 0);
        }

        /**
         * Hot gas above a field of sources like {@code p}, in a circle of radius {@code across} blocks, eye-relative ground center
         * (x, y, z) (nuclear firestorm): each source column as {@link #plume}; whole field column has its height/temperature,
         * field width, each source's eddies (field is many adjacent plumes, not one).
         */
        public void plumes(double x, double y, double z, FirePlume p, double share, double across) {
            if (share <= 0) return;
            double lo = p.flameHeight(), hi = 400;
            for (int i = 0; i < 30; i++) {
                double mid = 0.5 * (lo + hi);
                if (p.excess(mid) > COOL) lo = mid;
                else hi = mid;
            }
            double h = lo, r = Math.max(2 * p.size, 0.12 * h);
            hot(x, y, z, h, Math.max(r, across), CloudModel.T_AIR_K + p.excess(0.5 * h) * share, p.axisSpeed(0.5 * h), EDDY * r);
        }

        /**
         * Shock front: sphere centered at eye-relative (x, y, z), radius r, post-front compression e-folding thickness l (all
         * blocks), {@code low} sine of angle above ground center (no front below); dn refractive-index jump at four
         * heights evenly from low to top. Fronts giving no pixel a {@link #SHIFT_MIN} shift are unnecessary.
         * Nuclear front ({@code NukeHeat}) always precedes conventional fronts ({@link #chemicalFront}).
         */
        public void front(double x, double y, double z, double r, double l, double low, double[] dn) {
            front(x, y, z, r, l, low, dn, true);
        }

        /** Conventional explosion front ({@code FarBlasts.front}): like {@link #front}, without occupying a nuclear slot. */
        public void chemicalFront(double x, double y, double z, double r, double l, double low, double[] dn) {
            // Jump remains physical. presentation: applied to pixel shift in fx_heat, capped at 3 px;
            // boosting dn before optics created a lens pulling flash overexposure into the entire dome.
            front(x, y, z, r, l, low, dn, false);
        }

        private double rank(int slot) {
            return frontWeight[slot] + (frontNuke[slot] ? 1e9 : 0);
        }

        private void front(double x, double y, double z, double r, double l, double low, double[] dn, boolean nuke) {
            double most = 0;
            for (double v : dn) most = Math.max(most, v);
            if (r <= 0 || l <= 0 || most <= 0) return;
            double near = Math.abs(Math.sqrt(x * x + y * y + z * z) - r);
            double w = ShockOptics.most(r, most, near, pixel) / pixel;
            if (!nuke) w *= 24; // Upper display estimate; shader limits total conventional shift.
            if (w < SHIFT_MIN) return;
            int slot = rank(1) < rank(0) ? 1 : 0;
            if (w + (nuke ? 1e9 : 0) <= rank(slot)) return;
            frontWeight[slot] = w;
            frontNuke[slot] = nuke;
            front.setColumn(slot, column.set((float) x, (float) y, (float) z, (float) r));
            front.setColumn(2 + slot, column.set((float) dn[0], (float) dn[1], (float) dn[2], (float) dn[3]));
            if (slot == 0) frontShape.set((float) l, (float) low, frontShape.z, frontShape.w);
            else frontShape.set(frontShape.x, frontShape.y, (float) l, (float) low);
        }

        /**
         * Engine plume: eye-relative jet center (x, y, z), unit jet axis backward from nozzle (jx, jy, jz), plume flame;
         * air extinction air, 1/block; h is center height above ground (threshold like sphere). Each jet part is a uniform
         * segment: core [0, core] with core cone side area; flame [0, flame] with flame area excluding core (surrounds
         * core); tail [flame, {@link Flame#tailEnd}] (same tail flux center); part light per meter is brightness × side area / length / π (a body of brightness
         * L, side area S, gives facing white wall at d blocks L·S/(π·d²): sphere S = πr², hence L·r²/d²).
         * Eye light already belongs to frame ({@link #eye}); four-source selection/threshold as equal-area sphere ({@link Flame#lightRadius}).
         * cut is axial path from exit to ground: no jet beyond it (spreads over ground; ⚠ no wall-jet glow),
         * no light through ground; part light per meter unchanged, only endpoints shorten.
         */
        public void jet(double x, double y, double z, double jx, double jy, double jz, Flame flame, double air, double h, double cut) {
            double lr = flame.lightRadius(), lum = flame.brightness();
            double mid = flame.middle(), core = flame.core(), fl = Math.max(flame.flame(), core), end = flame.tailEnd();
            double c = Math.min(cut, end);
            if (lr <= 0 || lum <= 0 || !(c > 0)) return;
            if (lum * exposure * lr * lr / Math.max(h * h, lr * lr) < SEEN * Math.max(open, 1e-4)) return;
            double w = lum * lr * lr / Math.max(x * x + y * y + z * z, lr * lr);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (jetWeight[i] < jetWeight[slot]) slot = i;
            if (w <= jetWeight[slot]) return;
            jetWeight[slot] = w;
            double[] parts = flame.partAreas();
            jets.setColumn(slot, column.set((float) (x - jx * mid), (float) (y - jy * mid), (float) (z - jz * mid), (float) Math.max(1e-3, Math.min(core, c))));
            jetAxis.setColumn(slot, column.set((float) jx, (float) jy, (float) jz, (float) Math.min(fl, c)));
            jetSize.setColumn(slot, column.set((float) c, (float) flame.width(), (float) (flame.width() / Math.max(core, 1e-6)), (float) air));
            part(jetCore, slot, flame.coreColor(), flame.coreLuminance(), parts[0], core);
            part(jetFlame, slot, flame.flameColor(), flame.flameLuminance(), parts[1], fl);
            part(jetTail, slot, flame.tailColor(), flame.tailLuminance(), parts[2], end - fl);
        }

        private void part(Matrix4f m, int slot, int color, double luminance, double area, double length) {
            if (length <= 1e-6 || area <= 0 || luminance <= 0) {
                m.setColumn(slot, column.set(0, 0, 0, 0));
                return;
            }
            double[] rgb = rgb(color, luminance * area / (length * Math.PI));
            m.setColumn(slot, column.set((float) rgb[0], (float) rgb[1], (float) rgb[2], 0));
        }
    }

    /** Axial position until which column excess/speed stays constant: everywhere for column (float in shader). */
    static final double NOWHERE = 1e9;

    /** Gas refractive-index fluctuation at temperature kelvin (SD); temperature fluctuations are turbulence of excess. */
    public static double dn(double kelvin, double turbulence) {
        double t0 = CloudModel.T_AIR_K / kelvin;
        return N0 * turbulence * t0 * (1 - t0);
    }

    /**
     * What eye resolves on screen, rad (presentation: {@link HeatPass}): visual acuity, or pixel where finer (binoculars).
     * Shift in these units is frame shift in pixels.
     */
    public static double resolve(double pixel) {
        return Math.min(ACUITY, pixel);
    }

    /** Eye-resolved spot at gas distance blocks away, m: pupil or {@code resolve} angle × distance. */
    public static double aperture(double distance, double resolve) {
        return Math.max(PUPIL, resolve * distance);
    }

    /**
     * Ray angle of arrival after path len in gas with index fluctuation dn and eddies eddy (outer scale), one-axis SD,
     * rad, for spot aperture ({@link #aperture}); {@code hazeAngle} in {@code fx_heat.fsh}.
     */
    public static double hazeAngle(double dn, double len, double eddy, double aperture) {
        double k = aperture < eddy ? Math.pow(eddy / aperture, 1 / 6.0) : eddy / aperture;
        return dn * Math.sqrt(KOLMOGOROV * len / eddy) * k;
    }

    /**
     * Eye-visible shimmer share for eddies eddy in gas at speed speed: gas carries eddy past ray in ℓ/u (faster than
     * its own change, ℓ/(0.3u)); eye integrates light for {@value #EYE_S} s, averaging faster eddies (SD of mean of
     * τ_eye/τ independent samples); {@code hazeSeen}.
     */
    public static double hazeSeen(double eddy, double speed) {
        double tau = eddy / Math.max(speed, 1e-6);
        return tau < EYE_S ? Math.sqrt(tau / EYE_S) : 1;
    }

    /**
     * Frame shift behind hot gas, pixels (one-axis SD): arrival angle at eye-resolved spot distance blocks away, in units of
     * what eye resolves on screen ({@link #resolve}; presentation), × eye-visible share ({@link #hazeSeen}).
     */
    public static double hazeShift(double dn, double len, double eddy, double distance, double pixel, double speed) {
        double res = resolve(pixel);
        return hazeAngle(dn, len, eddy, aperture(distance, res)) * hazeSeen(eddy, speed) / res;
    }

    /**
     * Ray inside column: f(t) = a·t² − 2b·t + c < 0 on [t0, t1]; {@code hazeIn}/{@code hazeOut} in {@code fx_heat.fsh}.
     * Cone |t·d⊥ − b⊥|² < (R₀ + G·t)²: a = d⊥² − G², b = d⊥·b⊥ + R₀G, c = b⊥² − R₀². Truncated cone is convex; on
     * [t0, t1] (between base/end planes with positive radius), one interval; empty if end no farther than start.
     */
    public static double[] coneSpan(double a, double b, double c, double t0, double t1) {
        double disc = b * b - a * c;
        if (a > CONE_FLAT) {
            if (disc <= 0) return new double[]{1, 0};
            double q = Math.sqrt(disc);
            return new double[]{Math.max(t0, (b - q) / a), Math.min(t1, (b + q) / a)};
        }
        if (a < -CONE_FLAT) {
            if (disc <= 0) return new double[]{t0, t1};
            double q = Math.sqrt(disc), r1 = (b + q) / a, r2 = (b - q) / a;
            return t0 < r1 ? new double[]{t0, Math.min(t1, r1)} : new double[]{Math.max(t0, r2), t1};
        }
        if (b > 1e-12) return new double[]{Math.max(t0, c / (2 * b)), t1};
        if (b < -1e-12) return new double[]{t0, Math.min(t1, c / (2 * b))};
        return c < 0 ? new double[]{t0, t1} : new double[]{1, 0};
    }

    /** Smaller |a| gives a linear cone equation (ray nearly along generator). */
    public static final double CONE_FLAT = 1e-6;

    /** Linear rgb of color (sRGB, largest channel 255) at brightness (luma) luminance. */
    static double[] rgb(int color, double luminance) {
        double r = FlameModel.linear(color >> 16 & 255), g = FlameModel.linear(color >> 8 & 255), b = FlameModel.linear(color & 255);
        double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b, k = luma > 0 ? luminance / luma : 0;
        return new double[]{r * k, g * k, b * k};
    }

    /**
     * Direct uniform emissive-segment light on face: ∫ cos θ / d² along segment, 1/m (light per meter × this = light on
     * white wall); segment from point a (relative to face point) along unit u over [t0, t1], face normal n; only part above
     * face (face cannot see source below it). w2 is squared jet half-width: a face inside jet does not see a singularly
     * bright source. Primitive of (c₀ + c₁τ)/(τ² + h²)^{3/2} for τ = t + a·u: c₀τ/(h²√(τ² + h²)) − c₁/√(τ² + h²), c₀ = n·a − (a·u)(n·u),
     * c₁ = n·u, h distance to line; differences avoid subtracting near-equal numbers (shader float). Same calculation:
     * {@code heatSegment} in {@code include/heat.glsl}.
     */
    public static double segment(double ax, double ay, double az, double ux, double uy, double uz, double t0, double t1, double nx, double ny,
                                 double nz, double w2) {
        double[] t = above(ax, ay, az, ux, uy, uz, t0, t1, nx, ny, nz);
        if (t[1] <= t[0]) return 0;
        double na = nx * ax + ny * ay + nz * az, nu = nx * ux + ny * uy + nz * uz;
        double b = ax * ux + ay * uy + az * uz, h2 = Math.max(ax * ax + ay * ay + az * az - b * b, 0) + w2;
        double c0 = na - b * nu, s0 = t[0] + b, s1 = t[1] + b;
        double q0 = 1 / (s0 * s0 + h2), q1 = 1 / (s1 * s1 + h2), r0 = Math.sqrt(q0), r1 = Math.sqrt(q1);
        // (s1·r1 − s0·r0)/h² and r1 − r0 without near-equal subtraction: s²r² = 1 − h²r²
        double k = s0 * s1 > 0 ? (s1 - s0) * (s1 + s0) * q0 * q1 / (s1 * r1 + s0 * r0) : (s1 * r1 - s0 * r0) / h2;
        double dr = (s0 - s1) * (s0 + s1) * q0 * q1 / (r0 + r1);
        return Math.max(c0 * k - nu * dr, 0);
    }

    /** Part [t0, t1] of segment from a along unit u above face with normal n ({@code heatAbove}); empty if t1 ≤ t0. */
    public static double[] above(double ax, double ay, double az, double ux, double uy, double uz, double t0, double t1, double nx, double ny,
                                 double nz) {
        double na = nx * ax + ny * ay + nz * az, nu = nx * ux + ny * uy + nz * uz;
        if (Math.abs(nu) > 1e-6) {
            double ts = -na / nu;
            if (nu > 0) t0 = Math.max(t0, ts);
            else t1 = Math.min(t1, ts);
        } else if (na <= 0) {
            t1 = t0;
        }
        return new double[]{t0, t1};
    }

    /** World occluders for {@link #blocked}: is window cell (x, y, z) an occluder, or outside window (clear then). */
    public interface Cells {
        boolean solid(int x, int y, int z);
    }

    /**
     * Is path a to b occluded (occluder-window coordinates, blocks): mirrors {@code heatBlocked} in {@code include/heat.glsl}
     * (opposite result there: 1 means clear path); visit cells
     * along segment in order (Amanatides, Woo, “A fast voxel traversal algorithm”, Eurographics 1987); part outside window
     * size is clear; cell containing point a is its face, not an occluder; at most {@link #SHADOW_CELLS} cells.
     */
    public static boolean blocked(double ax, double ay, double az, double bx, double by, double bz, int[] size, Cells cells) {
        double[] a = {ax, ay, az}, d = {bx - ax, by - ay, bz - az};
        double t0 = 0, t1 = 1;
        for (int k = 0; k < 3; k++) {
            if (Math.abs(d[k]) < 1e-6) {
                if (a[k] < 0 || a[k] >= size[k]) return false;
            } else {
                double ta = -a[k] / d[k], tb = (size[k] - a[k]) / d[k];
                t0 = Math.max(t0, Math.min(ta, tb));
                t1 = Math.min(t1, Math.max(ta, tb));
            }
        }
        if (t1 <= t0) return false;
        int[] cell = new int[3], dir = new int[3];
        double[] inv = new double[3], next = {1e9, 1e9, 1e9};
        for (int k = 0; k < 3; k++) {
            cell[k] = Math.max(0, Math.min(size[k] - 1, (int) Math.floor(a[k] + d[k] * t0)));
            dir[k] = (int) Math.signum(d[k]);
            inv[k] = 1 / Math.max(Math.abs(d[k]), 1e-9);
            if (d[k] > 0) next[k] = (cell[k] + 1 - a[k]) * inv[k];
            else if (d[k] < 0) next[k] = (a[k] - cell[k]) * inv[k];
        }
        boolean own = t0 <= 0;
        for (int i = 0; i < SHADOW_CELLS; i++) {
            if (!own && cells.solid(cell[0], cell[1], cell[2])) return true;
            own = false;
            double t = Math.min(next[0], Math.min(next[1], next[2]));
            if (t >= t1) break;
            // step on every axis whose face is nearest (shader uses step: both on a tie)
            boolean out = false;
            for (int k = 0; k < 3; k++) {
                if (next[k] > t) continue;
                cell[k] += dir[k];
                next[k] += inv[k];
                out |= cell[k] < 0 || cell[k] >= size[k];
            }
            if (out) break;
        }
        return false;
    }

    /**
     * Visible share of ball disk centered at (bx, by, bz), radius r, from occluder-window point (ax, ay, az); mirrors
     * {@code heatBallSeen} ({@code include/heat.glsl}): meter-scale ball near ground is extended; one ray to center at hill edge
     * would remove all light despite visible top. Ground occluder edge is nearly horizontal; disk share above edge
     * at height y is ∫ 2√(1 − y²): second-kind Gauss–Chebyshev quadrature along vertical diameter, nodes 0 and
     * ±r·cos(π/4), weights ½ and ¼. Edge from below: center visible implies upper node visible, test lower; hidden implies lower
     * hidden, test upper (two rays: shares 0, ¼, ¾, 1). Rays end at ball edge facing point.
     */
    public static double ballSeen(double ax, double ay, double az, double bx, double by, double bz, double r, int[] size, Cells cells) {
        double vx = bx - ax, vy = by - ay, vz = bz - az, d = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double end = 1 - r / Math.max(d, r);
        boolean mid = !blocked(ax, ay, az, ax + vx * end, ay + vy * end, az + vz * end, size, cells);
        vx /= Math.max(d, 1e-3);
        vy /= Math.max(d, 1e-3);
        vz /= Math.max(d, 1e-3);
        double ux = -vx * vy, uy = 1 - vy * vy, uz = -vz * vy;
        if (ux * ux + uy * uy + uz * uz < 1e-4) {
            ux = 1 - vx * vx;
            uy = -vy * vx;
            uz = -vz * vx;
        }
        double k = Math.sqrt(0.5) * r / Math.sqrt(ux * ux + uy * uy + uz * uz), s = mid ? -1 : 1;
        double tx = bx + s * ux * k - ax, ty = by + s * uy * k - ay, tz = bz + s * uz * k - az;
        boolean node = !blocked(ax, ay, az, ax + tx * end, ay + ty * end, az + tz * end, size, cells);
        return mid ? 0.75 + (node ? 0.25 : 0) : node ? 0.25 : 0;
    }

    private static final Frame FRAME = new Frame();
    /** Fire light at eye in last collected frame ({@link Frame#atEye}). */
    private static double lastAtEye;

    /**
     * Fire light at eye in last collected frame (linear, noon white wall = 1): frame eye adapts to it
     * ({@code Sight.exposure}, {@code FarView.exposure}).
     */
    public static double atEye() {
        return lastAtEye;
    }

    /** World exit/dimension change: no fire at eye. */
    public static void forget() {
        lastAtEye = 0;
        FRAME.atEye = 0;
    }
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f();
    /**
     * Advection cycle index modulo this (each cycle has its own pattern offset; repeats every 97 cycles); second
     * layer shifted by half an index: both layers share an index for half-cycle; same offset would align their patterns.
     */
    static final double CYCLE_SEEDS = 97;
    private static final Vector3f FORWARD = new Vector3f();
    private static final Vector3f OPEN_SKY = new Vector3f();
    private static final Vector4f FACE_SHADE = new Vector4f();
    private static final int[] VIEWPORT = new int[4];
    private static int copy = -1, copyW, copyH;
    private static final long BORN = System.nanoTime();

    private HeatPass() {}

    /** Source frame: skylight/eye adaptation before collection (define light threshold). */
    public static Frame begin(ClientLevel level, double exposure, double pixel) {
        Frame f = FRAME;
        f.begin();
        f.exposure = exposure;
        f.pixel = pixel;
        int c = Minecraft.getInstance().gameRenderer.lightTexture().lightPixels.getPixelRGBA(0, 15);
        OPEN_SKY.set((c & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c >> 16 & 0xFF) / 255f);
        FACE_SHADE.set(level.getShade(Direction.UP, true), level.getShade(Direction.DOWN, true), level.getShade(Direction.NORTH, true),
                level.getShade(Direction.EAST, true));
        f.open = 0.2126 * linear(OPEN_SKY.x) + 0.7152 * linear(OPEN_SKY.y) + 0.0722 * linear(OPEN_SKY.z);
        return f;
    }

    private static double linear(double v) {
        return Math.pow(v, 2.2);
    }

    /**
     * Occluder window ({@link Solids}) for this frame around eye (source positions relative to it); reread sections near
     * light sources now. Layer calls before pass if frame has light.
     */
    static void solids(Frame f, ClientLevel level, net.minecraft.world.phys.Vec3 eye) {
        if (!f.lit()) return;
        Vector4f c = new Vector4f();
        for (int i = 0; i < 4; i++) {
            if (f.lights.getColumn(i, c).w > 0) Solids.near(eye.x + c.x, eye.y + c.y, eye.z + c.z, c.w);
            // jet: from nozzle exit along its length (to ground), no farther than 8 blocks, where it hits ground
            if (f.jets.getColumn(i, c).w > 0) {
                double reach = Math.min(8, f.jetSize.getColumn(i, new Vector4f()).x);
                f.jets.getColumn(i, c);
                Solids.near(eye.x + c.x, eye.y + c.y, eye.z + c.z, reach);
            }
        }
        Solids.update(level, eye);
    }

    /** Pixels this frame's pass can change ({@link #needed}). */
    private static final HeatArea AREA = new HeatArea();

    /**
     * Whether this frame needs the pass: a source whose light or shift reaches the screen ({@link HeatArea}); view and
     * projection map eye-relative positions to clip space. Sources only off screen or too faint to move a pixel — no pass.
     */
    static boolean needed(Frame f, Matrix4fc view, Matrix4fc projection) {
        if (shader == null || !(f.lit() || f.hotAny() || f.frontAny())) return false;
        float shade = Math.min(Math.min(FACE_SHADE.x, FACE_SHADE.y), Math.min(FACE_SHADE.z, FACE_SHADE.w));
        double visible = HeatArea.visible(OPEN_SKY.x, OPEN_SKY.y, OPEN_SKY.z, Math.max(shade, 0.05f));
        return AREA.of(f, view, projection, f.adapted(), visible).count > 0;
    }

    /** This frame's area (after {@link #needed}; checks). */
    static HeatArea area() {
        return AREA;
    }

    /**
     * Pass: frame copy, full-frame output. {@code scene} is world distance, {@code view} view from eye,
     * {@code pixel} pixel angle, rad; {@code haze} frame haze/fog.
     */
    static boolean draw(Frame f, int scene, Matrix4f view, Matrix4f projection, Vector4f haze, double pixel) {
        ShaderInstance s = shader;
        HeatArea area = AREA;
        // shifted pixels read the copy up to SHIFT_REACH away; light alone reads its own pixel
        int margin = f.hotAny() || f.frontAny() ? HeatArea.SHIFT_REACH : 0;
        if (s == null || scene < 0 || area.count == 0 || !copyFrame(area, margin)) return false;
        VIEW_PROJECTION.set(projection).mul(view);
        VIEW_PROJECTION.invert(INVERSE);
        view.positiveZ(FORWARD).negate();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        // source direct-light visibility in own half-resolution pass (shadow rays dominate cost)
        boolean shadows = f.lit() && seen(f, scene, pixel);
        RenderSystem.setShaderTexture(0, copy);
        RenderSystem.setShaderTexture(1, scene);
        RenderSystem.setShaderTexture(2, shadows ? shadowTex : copy);
        common(s, f, pixel);
        s.safeGetUniform("HeatShadow").set(shadows ? 1f : 0f, 0f, 0f, 0f);
        s.safeGetUniform("Haze").set(haze.x, haze.y, haze.z, haze.w);
        s.safeGetUniform("HeatHot").set(f.hot);
        s.safeGetUniform("HeatShape").set(f.shape);
        s.safeGetUniform("HeatAxis").set(f.axis);
        s.safeGetUniform("HeatGas").set(f.gas);
        // gas advects eddy pattern: two layers half-cycle apart; cycle fraction/index in double, not float time
        double cycles = (System.nanoTime() - BORN) / 1e9 / HAZE_CYCLE, half = cycles + 0.5;
        s.safeGetUniform("HeatClock").set((float) (cycles - Math.floor(cycles)), (float) (Math.floor(cycles) % CYCLE_SEEDS),
                (float) (half - Math.floor(half)), (float) (Math.floor(half) % CYCLE_SEEDS + 0.5));
        s.safeGetUniform("HeatFront").set(f.front);
        s.safeGetUniform("HeatFrontShape").set(f.frontShape.x, f.frontShape.y, f.frontShape.z, f.frontShape.w);
        s.safeGetUniform("HeatFrontChemical").set(f.frontNuke[0] ? 0f : 1f, f.frontNuke[1] ? 0f : 1f);
        s.safeGetUniform("HeatFrame").set(f.frontAny() ? 1f : 0f, (float) pixel, f.lit() ? 1f : 0f, f.hotAny() ? 1f : 0f);
        s.safeGetUniform("HeatReach").set(area.whole ? 1e9f : HeatArea.SHIFT_REACH);
        boolean drawn = quads(s, area, 0, area.count, copyW, copyH, 0);
        RenderSystem.enableDepthTest();
        return drawn;
    }

    /** Shared by visibility/light passes: frame world, sources, occluder window. */
    private static void common(ShaderInstance s, Frame f, double pixel) {
        s.safeGetUniform("InvViewProj").set(INVERSE);
        s.safeGetUniform("PallForward").set(FORWARD.x, FORWARD.y, FORWARD.z);
        s.safeGetUniform("PallOpenSky").set(OPEN_SKY.x, OPEN_SKY.y, OPEN_SKY.z);
        s.safeGetUniform("PallFaceShade").set(FACE_SHADE.x, FACE_SHADE.y, FACE_SHADE.z, FACE_SHADE.w);
        s.safeGetUniform("HeatLights").set(f.lights);
        double k = f.adapted();
        s.safeGetUniform("HeatLightColor").set(exposed(f.lightColor, k));
        Solids.bind(s);
        s.safeGetUniform("HeatJet").set(f.jets);
        s.safeGetUniform("HeatJetAxis").set(f.jetAxis);
        s.safeGetUniform("HeatJetSize").set(f.jetSize);
        s.safeGetUniform("HeatJetCore").set(exposed(f.jetCore, k));
        s.safeGetUniform("HeatJetFlame").set(exposed(f.jetFlame, k));
        s.safeGetUniform("HeatJetTail").set(exposed(f.jetTail, k));
        s.safeGetUniform("HeatFrame").set(0f, (float) pixel, f.lit() ? 1f : 0f, 0f);
    }

    /**
     * Rectangles from..to of area a with shader s, in pixels of the bound w×h target widened by margin (the whole target
     * for a whole-screen area); false if not drawn.
     */
    private static boolean quads(ShaderInstance s, HeatArea a, int from, int to, int w, int h, int margin) {
        if (to <= from) {
            Solids.unbind();
            return false;
        }
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int i = from; i < to; i++) {
            a.pixels(i, w, h, margin, RECT);
            float x0 = 2f * RECT[0] / w - 1, y0 = 2f * RECT[1] / h - 1, x1 = 2f * RECT[2] / w - 1, y1 = 2f * RECT[3] / h - 1;
            b.addVertex(x0, y0, 0);
            b.addVertex(x1, y0, 0);
            b.addVertex(x1, y1, 0);
            b.addVertex(x0, y1, 0);
        }
        MeshData mesh = b.build();
        if (mesh == null) {
            Solids.unbind();
            return false;
        }
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        s.apply();
        com.mojang.blaze3d.vertex.BufferUploader.draw(mesh);
        s.clear();
        Solids.unbind();
        return true;
    }

    /** Pixel rectangle scratch: x0, y0, x1, y1. */
    private static final int[] RECT = new int[4];

    @Nullable
    static ShaderInstance shadowShader;
    private static int shadowTex = -1, shadowFbo = -1, shadowW, shadowH;
    private static boolean shadowFailed;

    /**
     * Visibility pass ({@code fx_heat_shadow}): ball/plume visibility codes and sample world distance into own texture
     * (RGBA16F, half frame on each axis); restores frame/viewport afterward. false means no pass
     * (shader failed to build, texture creation failed): unshadowed light.
     */
    private static boolean seen(Frame f, int scene, double pixel) {
        ShaderInstance s = shadowShader;
        if (s == null || shadowFailed) return false;
        int w = Math.max(1, (copyW + 1) / 2), h = Math.max(1, (copyH + 1) / 2);
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (w != shadowW || h != shadowH || shadowTex < 0) {
            if (shadowTex < 0) shadowTex = GlStateManager._genTexture();
            if (shadowFbo < 0) shadowFbo = GlStateManager.glGenFramebuffers();
            GlStateManager._bindTexture(shadowTex);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, shadowFbo);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, shadowTex, 0);
            boolean ok = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            if (!ok) {
                Incoming.LOG.error("Heat pass: shadow target {}×{} not created, fire light without shadows", w, h);
                shadowFailed = true;
                return false;
            }
            shadowW = w;
            shadowH = h;
        }
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, shadowFbo);
        GlStateManager._viewport(0, 0, w, h);
        RenderSystem.setShaderTexture(1, scene);
        common(s, f, pixel);
        // only where balls and jets light the screen; the light pass reads up to a sample beyond its pixel
        boolean drawn = quads(s, AREA, 0, AREA.lit, w, h, 2);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        return drawn;
    }

    private static final Matrix4f EXPOSED = new Matrix4f();

    /** Light m (rgb columns) with eye adaptation k in shared matrix for one call (uniform copies immediately). */
    private static final Vector4f EXPOSED_COLUMN = new Vector4f();

    private static Matrix4f exposed(Matrix4f m, double k) {
        EXPOSED.set(m);
        Vector4f c = EXPOSED_COLUMN;
        for (int i = 0; i < 4; i++) {
            EXPOSED.getColumn(i, c);
            EXPOSED.setColumn(i, c.set((float) (c.x * k), (float) (c.y * k), (float) (c.z * k), c.w));
        }
        return EXPOSED;
    }

    /**
     * Copy of the frame targeted by the pass (currently bound) into its own texture with linear sampling: only the area's
     * rectangles, widened by margin (pixels the pass may read).
     */
    private static boolean copyFrame(HeatArea area, int margin) {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        if (w <= 0 || h <= 0) return false;
        if (copy < 0) {
            copy = GlStateManager._genTexture();
            GlStateManager._bindTexture(copy);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(copy);
        if (w != copyW || h != copyH) {
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null);
            copyW = w;
            copyH = h;
        }
        for (int i = 0; i < area.count; i++) {
            area.pixels(i, w, h, margin, RECT);
            int cw = RECT[2] - RECT[0], ch = RECT[3] - RECT[1];
            if (cw > 0 && ch > 0) GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, RECT[0], RECT[1], RECT[0], RECT[1], cw, ch);
        }
        return true;
    }

    /** For scenario line: frame sources. */
    public static String describe() {
        Frame f = FRAME;
        int l = 0, h = 0, n = 0, j = 0;
        Vector4f c = new Vector4f();
        for (int i = 0; i < 4; i++) {
            if (f.lights.getColumn(i, c).w > 0) l++;
            if (f.hot.getColumn(i, c).w > 0) h++;
            if (i < 2 && f.front.getColumn(i, c).w > 0) n++;
            if (f.jetWeight[i] > 0) j++;
        }
        StringBuilder sb = new StringBuilder(String.format(java.util.Locale.ROOT, "heat lights %d hot %d front %d jets %d, area %s", l, h, n, j,
                AREA.describe()));
        for (int i = 0; i < 2; i++) {
            f.front.getColumn(i, c);
            if (c.w <= 0) continue;
            Vector4f dn = f.front.getColumn(2 + i, new Vector4f());
            sb.append(String.format(java.util.Locale.ROOT, " {R %.0f L %.1f dn %.2e..%.2e most %.1f px}", c.w,
                    i == 0 ? f.frontShape.x : f.frontShape.z, dn.x, dn.w, f.frontWeight[i]));
        }
        for (int i = 0; i < 4; i++) {
            f.hot.getColumn(i, c);
            if (c.w <= 0) continue;
            Vector4f s = f.shape.getColumn(i, new Vector4f()), g = f.gas.getColumn(i, new Vector4f());
            sb.append(String.format(java.util.Locale.ROOT, " [r %.1f h %.0f dT %.0f px %.2f]", c.w, s.x, g.x, f.hotShift[i]));
        }
        return sb.toString();
    }
}
