package ua.zentix.incoming.client.far;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.BlastTime;
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.Ejecta;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.fx.smoke.WaterBlast;
import ua.zentix.incoming.client.sound.Acoustics;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.Outdoor;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.nuclear.model.ShockOptics;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/**
 * Explosions at a distance: a flash, a fireball, a cloud of smoke and dust, and a rumble delayed by the speed of sound.
 * <p>
 * An explosion packet is one event: the cloud is the explosion model ({@link CloudModel}: charge, fuel, ground and air), the same
 * up close and far away. The event is the client's cloud source ({@link SmokeGrid.Source}): the model's puffs and the condensation cloud
 * (fuel in the crater burns as fire on the server's blocks, its smoke is one fire among the client's fires, {@code FireSmoke.pool});
 * the effects layer draws them as baked puffs ({@link SmokePuffs}), the grid's volume does not (a cell
 * of 2–32 m cannot resolve a cloud of metres to tens of metres). The fireball — frames of {@code fx/particle/fireball} (a combustion
 * simulation in Blender, colour — black body of the temperature field), sized and timed by the model's ball (the fuel ball by CCPS,
 * or the ball of detonation products, then cooling as air is drawn in), brightness — black body at its temperature ({@link BlackBody}): by day
 * the fuel ball (~1500 K) is on the order of a white wall, the detonation-product flash (~3000 K) is hundreds of times that.
 * <p>
 * A bomb underground ({@code kind} BUNKER) — the same model clouds from the share of the charge that reached the air
 * ({@code warhead.BuriedBlast}): a breakthrough to the surface — a cloud over the crater; deeper — gas and dust from the shaft,
 * a sinkhole, dust at the entrance
 * ({@link #cloud}).
 * <p>
 * Terrain between the blast and the eye — one {@link Sightline} ray on arrival, then no more than once a second: behind a hill only
 * the part of the cloud that rises above it is visible, and the flash as a faint glow above the crest. Light arrives at once: the glare and veil
 * around the ball — here at any distance, and nearby (they are in the eye, not in the air; at night the glow on the clouds above the place
 * {@link #cloudGlow}); the sound — when the front reaches the ear; farther out {@link #NEAR} it is here ({@link BlastSounds#far}), closer —
 * the angles of the near model ({@code BlastMix}).
 */
public final class FarBlasts {
    /** Edge of the near picture and the near sound, in blocks. */
    public static final double NEAR = Outdoor.NEAR;
    /** No more events than this: old ones leave first. */
    static final int CAP = 256;
    /** A terrain ray — at most once every this many ticks per event; rays per tick — no more than this (except new rays). */
    static final int RETRACE = 20, TRACES_PER_TICK = 16;
    /**
     * Balls in the drawing that get block rays each tick ({@link #clear}), beyond the terrain rays, no more than: while
     * a ball glows — each tick in turn, then with a terrain ray.
     */
    static final int CLEARS_PER_TICK = 8;
    /** Rays to the ball around its middle ({@link #clear}) and the ring's radius, in ball radii. */
    static final int RING_RAYS = 6;
    static final double RING = 0.7;
    private static final double[] DISC = new double[3];
    /** Source for the ray and sound — above the impact point, in blocks (as the near picture's flash). */
    static final double LIFT = 1.5;
    /**
     * Share of the ball's light that glows as a haze when the ball is covered:
     * air scatters the light of a ball of radius R at distance r — brightness σ·ℓ·R²/(4r²) of the ball's brightness (σ of clear air
     * ~2·10⁻⁴ per metre, path ℓ ~100 m, r — 2–3 R), a thousandth (⚠ estimate of a single case). By day this is barely visible;
     * at night the glow runs up to the veil's brightness
     * ({@link #halo}).
     */
    static final double BEHIND = 0.001;
    /** Radius of the glowing air around a covered ball, in ball radii: farther out the scattered light weakens as 1/r. */
    static final double HALO_SIZE = 3;
    /** Centre of the fireball above the explosion point (in radii): a ground ball is a hemisphere, its bottom in the ground. */
    public static final double BALL_LIFT = 0.45;
    /**
     * Glow on the clouds: the underside of the clouds over the place is lit by the ball (illuminance E = I/h², brightness ≈ albedo·E/π) — a spot
     * of radius {@link #SKY_SPREAD} cloud heights above the place (at one height — a third of the peak, at two — a tenth); the peak against
     * white — no more than SKY_PEAK (in rain the clouds are solid), on a clear night — {@link #SKY_CLEAR} of it. Clouds lower than
     * {@link #SKY_LOW} blocks over the place — counted at that height. Albedo — ⚠ estimate; the spot, the peak and the clear sky — presentation.
     */
    static final double CLOUD_ALBEDO = 0.7, SKY_SPREAD = 1, SKY_PEAK = 0.45, SKY_CLEAR = 0.45, SKY_LOW = 40;

    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    /**
     * Gone from view beyond {@link #CAP}, whose sound is still travelling to the ear: the rumble lives by the travel time of sound
     * ({@link Event#soundWait}), not by its place in the picture list — in a raid of more than 256 hits an early distant one does not go silent.
     */
    private static final ArrayDeque<Event> SOUNDS = new ArrayDeque<>();
    /** Numbers per frame without allocation: the point {@link Sight#point}, the light {@link Sight#light} and the ball's colour. */
    private static final double[] POINT = new double[2], LIGHT = new double[5], BB = new double[4];
    /** Brightest body of the last frame (for the log): core, glare and veil in pixels, overexposure, share above the terrain. */
    private static final double[] BRIGHTEST = new double[5];
    private static final float[] TINT = new float[3];
    /** Last client pre-tick: the age of an ordinary effect does not depend on the order of post-tick listeners. */
    private static long clock;
    /** Last distant sound — for the scenario log. */
    private static Outdoor.Heard lastHeard;
    private static double lastDistance;
    private static long lastDelay;

    private FarBlasts() {}

    /** One explosion: where, what kind, its cloud (the model or the bomb's former picture), its random seed and the last terrain ray. */
    private static final class Event implements SmokeGrid.Source {
        final ClientLevel level;
        /** What exploded: the sound by charge. */
        final BlastSource source;
        /** Cloud by model. */
        final CloudModel model;
        /** Base: the impact point; for an underground bomb — where the cloud came out (surface, shaft mouth, sinkhole). */
        final double x, y, z;
        final GroundMaterial ground;
        /** Explosion on water: a spray column, crown and water dust ({@link WaterBlast}); null — on land. */
        @Nullable
        final WaterBlast water;
        /** Seed of the explosion: the puffs and the variant of the recorded sound. */
        final long seed;
        final long born;
        /** The packet came closer than {@link #NEAR}: the near picture and sound are present. */
        final boolean near;
        /**
         * Whose rumble is heard at a distance: usually the explosion itself ({@link #source}); for a cloud without its own charge (a bomb's entry into the ground,
         * a collapse of the vault) — the share of the impact energy that went into the air, as a dull thud from underground.
         */
        final BlastSource voice;
        /**
         * Albedo ω' of the dust by channel: surfaces around (wave dust at ground level, the stem), the target's material (crush dust, dust of the
         * cloud — mixed with the first share of {@link CloudModel#capMatterShare}) and the ejecta clods (the curtain — the colour of the material itself:
         * wet clods with humus are darker than dry dust, physics.md front F).
         */
        final float dustR, dustG, dustB, matterR, matterG, matterB, clodR, clodG, clodB;
        /** Curtain of ejecta from loose ground ({@link Ejecta}); null — the target has none. */
        @Nullable
        final Ejecta curtain;
        /** Attenuation of the curtain's grains, in kg of mixture dust ({@link SmokeGrid#dustK} by the clods' colour) per kg of grains. */
        final double curtainK;
        boolean soundPending;
        /**
         * No longer waiting for a sound whose front never reached the ear (the listener moves away faster than sound), in ticks: the sound's path
         * over its whole audible range ({@link BlastSounds#range}).
         */
        final long soundWait;
        /** The ball glows (in the last frame): the block rays to the ball ({@link #clear}) must be updated. */
        boolean glows = true;
        long traced;
        /** From the last ray: by how many blocks it is visible above the source; the path difference through the edge. */
        double hidden, path;
        /** The ball in the drawing is not covered by blocks (share of rays, {@link #clear}): the glare and veil at the eye are not cut by depth. */
        float open = 1;
        /** The cloud's source is still needed (the event is alive). */
        boolean alive = true;
        /** Shock-wave front over time ({@link #front}); null — not computed yet. */
        @Nullable
        ChemicalFront front;

        Event(ClientLevel level, BlastSource source, CloudModel model, double x, double y, double z, GroundMaterial ground, long seed,
              boolean near, BlastSource voice) {
            this.level = level;
            this.source = source;
            this.voice = voice;
            this.soundWait = (long) Math.ceil(BlastSounds.range(voice) / Acoustics.SPEED);
            this.model = model;
            this.x = x;
            this.y = y;
            this.z = z;
            this.ground = ground;
            this.water = ground.isWater() && source.chargeKg() > 0 ? water(level, new Vec3(x, y, z), source.chargeKg(), model.air) : null;
            this.seed = seed;
            this.born = BlastTime.ticks();
            this.near = near;
            this.soundPending = !near;
            // Fuel in the crater burns as fire on the server's blocks: its smoke and sound come with it in a packet (BlastEffects.fuelFire)
            float[] dust = dustColour(ground);
            dustR = SmokeGrid.dustAlbedo(dust[0]);
            dustG = SmokeGrid.dustAlbedo(dust[1]);
            dustB = SmokeGrid.dustAlbedo(dust[2]);
            BlastTarget.Matter m = model.target.matter();
            matterR = SmokeGrid.dustAlbedo(m.r);
            matterG = SmokeGrid.dustAlbedo(m.g);
            matterB = SmokeGrid.dustAlbedo(m.b);
            GroundMaterial gm = model.target.material();
            clodR = SmokeGrid.dustAlbedo(gm.r);
            clodG = SmokeGrid.dustAlbedo(gm.g);
            clodB = SmokeGrid.dustAlbedo(gm.b);
            curtain = curtain(model, source);
            curtainK = curtain == null ? 0 : m.extinction() / SmokeGrid.dustK(clodR, clodG, clodB);
        }

        double distanceTo(Vec3 p) {
            double dx = x - p.x, dy = y + LIFT - p.y, dz = z - p.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** To the explosion point, in blocks: the front goes out from it, as for the near sound ({@code BlastEffects.Timeline}). */
        double blastDistanceTo(Vec3 p) {
            double dx = x - p.x, dy = y - p.y, dz = z - p.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /** Front of this explosion ({@link #arrivalTicks(ChemicalFront, double)}); for an underground bomb or one without a charge — null. */
        @Nullable
        ChemicalFront front() {
            if (front == null && !source.underground() && source.chargeKg() > 0) front = ChemicalFront.cached(source.chargeKg(), source.surface());
            return front;
        }

        /** Front of the rumble ({@link #voice}): for the explosion itself its front; for a share from underground — at the speed of sound. */
        @Nullable
        ChemicalFront soundFront() {
            return voice == source ? front() : null;
        }

        /** How many ticks the event lives (the cloud is visible). */
        double life() {
            // Water dust, foam and the blast wave on water live longer than the cloud without crater dust
            return Math.max(model.life, water == null ? 0 : Math.max(water.mistLife(), water.waterLife())) * 20;
        }

        /** How many ticks the fireball glows (0 — no ball). */
        double ballTicks() {
            return model.fireSeconds * 20;
        }

        /** Largest radius of the fireball, in blocks (half-size of its picture). */
        double ballRadius() {
            return Math.max(0.5 * model.fuelBall, model.heBall);
        }

        /** Height of the ball's centre above the base on tick age, in blocks. */
        double ballHeight(double age) {
            return model.ballHeight(age / 20);
        }

        /**
         * Brightness of the ball on tick age against a white screen by day (without the eye's adaptation): the spectrum of the gas temperature,
         * emissivity — from the model's energy budget.
         */
        double brightness(double age) {
            model.ballLight(age / 20, BB);
            return BB[3];
        }

        /**
         * Light of the source on tick age ({@link CloudModel#emittedLight}), without exposure and the day/night background.
         */
        double emitted(double age) {
            model.emittedLight(age / 20, BB);
            return BB[3];
        }

        /** Colour of the ball's light on tick age (largest channel — 1). */
        float[] colour(double age) {
            model.ballLight(age / 20, BB);
            double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
            for (int i = 0; i < 3; i++) TINT[i] = (float) (BB[i] / Math.max(m, 1e-12));
            return TINT;
        }

        @Override
        public boolean ended() {
            return !alive || level != Minecraft.getInstance().level;
        }

        @Override
        public long seed() {
            return seed;
        }

        /** Fireball — the fire's body ({@link #light}): the model's hot puffs are its, not smoke. */
        @Override
        public boolean drawsGlow() {
            return true;
        }

        /** How many times the cloud has grown since the start (σ horizontally) — the frame of a baked puff. */
        @Override
        public double puffGrowth(double seconds) {
            double t = sourceAge(seconds);
            return model.sigmaH(t) / Math.max(model.sigmaH(0), 1e-6);
        }

        /** Spray — of an explosion on water. */
        @Override
        public boolean sprayClubs() {
            return water != null;
        }

        @Override
        public boolean sprayClubs(double seconds) {
            // After the last jet returns — a suspension of small droplets, not falling spray streaks.
            return water != null && water.aloft(sourceAge(seconds)) > 0;
        }

        /** Above the terrain that blocks the explosion point at a distance (the last ray, {@link #trace}). */
        @Override
        public double visible(double wy, double r) {
            return FarBlasts.visible(wy - y, r, hidden > 0 ? LIFT + hidden : 0);
        }

        @Override
        public void visibility(double[] out, int at) {
            out[at] = hidden;
        }

        @Override
        public double visible(double wy, double r, double[] saved, int at) {
            double h = saved[at];
            return FarBlasts.visible(wy - y, r, h > 0 ? LIFT + h : 0);
        }

        private double sourceAge(double seconds) {
            // Only the fractional tick comes from SmokeGrid; its post-tick counter may already have advanced at birth.
            return BlastTime.seconds(BlastTime.ticks(), born, 20 * (seconds - SmokeGrid.seconds(0)));
        }

        @Override
        public double age(double seconds) { return sourceAge(seconds); }

        @Override
        public void maintain(double seconds) { model.maintain(sourceAge(seconds)); }

        @Override
        public boolean bounds(double seconds, ua.zentix.incoming.client.fx.smoke.SmokeBounds out) {
            double t = sourceAge(seconds);
            model.bounds(t, level.getMinBuildHeight() - y, level.getMaxBuildHeight() - y, out);
            model.condensationBounds(t, out);
            if (curtain != null) curtain.bounds(t, out);
            if (water != null) water.bounds(t, out, 0, water.depth, 0);
            out.move(x, y, z);
            return true;
        }

        @Override
        public boolean budgeted() { return true; }

        /** Cloud source: the model's puffs in the world, the condensation cloud and the explosion's spray on water. */
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            if (!alive || level != Minecraft.getInstance().level) return false;
            double t = sourceAge(seconds);
            if (t >= Math.max(model.life, water == null ? 0 : water.mistLife())) return false;
            if (t < model.life) {
                double growth = puffGrowth(seconds);
                // the airburst cloud — its own frames (by the cloud's growth, like a smoke cloud); the dust of a crushed target — frames
                // of its own matter: loose — ejecta, cohesive — crush (by the share of its life)
                boolean air = model.target.family() == BlastTarget.Family.AIR, loose = model.target.matter().loose;
                SmokeGrid.Look cap = air ? SmokeGrid.Look.AIRBURST : SmokeGrid.Look.SMOKE, crush = loose ? SmokeGrid.Look.EJECTA : SmokeGrid.Look.CRUSH;
                double capFrame = air ? FxAtlas.growthFrame(cap, growth) : 0;
                double crushFrame = FxAtlas.lifeFrame(crush, model.crushed.life > 0 ? t / model.crushed.life : 0);
                double share = model.capMatterShare(t);
                float cr = mix(matterR, dustR, share), cg = mix(matterG, dustG, share), cb = mix(matterB, dustB, share);
                model.blobs(t, new CloudModel.Blobs() {
                    @Override
                    public void blob(double bx, double by, double bz, double sh, double sv, double soot, double dustKg, double water, double kelvin,
                                     double rise) {
                        out.look(cap, capFrame);
                        out.blob(x + bx, y + by, z + bz, sh, sv, soot, dustKg, water, cr, cg, cb, kelvin, rise);
                    }

                    @Override
                    public void ground(double bx, double by, double bz, double sh, double sv, double dustKg, double kelvin) {
                        out.look(SmokeGrid.Look.SMOKE, 0);
                        out.blob(x + bx, y + by, z + bz, sh, sv, 0, dustKg, 0, dustR, dustG, dustB, kelvin, 0);
                    }

                    @Override
                    public void crushed(double bx, double by, double bz, double sh, double sv, double dustKg, double kelvin) {
                        out.look(crush, crushFrame);
                        out.blob(x + bx, y + by, z + bz, sh, sv, 0, dustKg, 0, matterR, matterG, matterB, kelvin, 0);
                    }

                    // the stem — as a tube, like a fire's trail (near and far without beads); its puffs' frame — by the cloud's growth, as for the cap
                    @Override
                    public void tube(double ax, double ay, double az, double sa, double massA, double bx, double by, double bz, double sb,
                                     double massB, double kelvin, double rise) {
                        out.tube(x + ax, y + ay, z + az, sa, sa, 0, massA, x + bx, y + by, z + bz, sb, sb, 0, massB, dustR, dustG, dustB, kelvin, rise,
                                sa / Math.max(growth, 1));
                    }
                });
            }
            out.look(SmokeGrid.Look.SMOKE, 0);
            if (curtain != null && t < curtain.life) curtainClubs(curtain, t, seed, x, y, z, curtainK, clodR, clodG, clodB, out);
            if (t < model.condensationSeconds()) condensation(t, out);
            // Jets come out of the surface, even when the charge itself is under it.
            if (water != null) water.blobs(t, out, x, y + water.depth, z);
            return true;
        }

        /** Water follows the pressure history, rather than appearing at the final radius in the first frame. */
        private void condensation(double t, SmokeGrid.Sink out) {
            model.condensation(t, (bx, by, bz, sh, sv, soot, dust, water, kelvin, rise) ->
                    out.blob(x + bx, y + by, z + bz, sh, sv, 0, 0, water, 0.95f, 0.95f, 0.95f, kelvin, rise));
        }

    }

    /**
     * Colour of the thick explosion dust on screen: ground dust is dry fine grains (finer than ~50 µm), and a thick layer of them is lighter
     * than the ground itself: the block's colour is damp soil with humus and coarse grains, which absorb more (fine
     * mineral dust has a single-scattering albedo at 550 nm of 0.9–0.97, Dubovik et al., J. Atmos. Sci. 59, 2002).
     * The ground under the impact is taken as the same dry loamy sand as for the nuke ({@code DustModel.Surface.REFERENCE}) —
     * and dust of the same colour as the nuke's dust wall ({@code NukePall.DUST}); sand and snow — their own (lighter). In 3.5.0 dust
     * was coloured with the ground block's colour — a dark, almost black disc at the ground (F3).
     */
    public static float[] dustColour(GroundMaterial ground) {
        if (ground == GroundMaterial.SAND || ground == GroundMaterial.SNOW) return new float[]{ground.r, ground.g, ground.b};
        double[] d = ua.zentix.incoming.client.nuclear.NukePall.DUST;
        return new float[]{(float) d[0], (float) d[1], (float) d[2]};
    }

    /**
     * Curtain of ejecta from the crater at the target, from loose ground ({@link BlastTarget.Family#SOIL}): the crater by the law for the
     * material's soil, ejecta —
     * its mass without the dust that went into the air ({@link CloudModel#DUST_LOFTED}), as clods — the share of the matter ({@link Ejecta}). No
     * curtain — null.
     */
    @Nullable
    static Ejecta curtain(CloudModel model, BlastSource source) {
        BlastTarget t = model.target;
        if (t.family() != BlastTarget.Family.SOIL || source.underground() || source.chargeKg() <= 0) return null;
        double r = ua.zentix.incoming.nuclear.model.CraterModel.chargeRadius(source.chargeKg(), t.material().soil());
        Ejecta e = new Ejecta(r, t.ballistic(source.chargeKg()), t.matter().clods);
        return e.grains > 0 ? e : null;
    }

    /**
     * Puffs of the ejecta curtain {@code c} at age t with base (x, y, z): jets as segments, grains as dust with the same
     * attenuation ({@code k} — kg of mixture dust per kg of grains, albedo ω' of the clods), impact frames into dust by the share of the
     * curtain's life (up to
     * nine tenths on the ground); then, at the receiver, a smoke cloud again.
     */
    static void curtainClubs(Ejecta c, double t, long seed, double x, double y, double z, double k, float r, float g, float b, SmokeGrid.Sink out) {
        out.look(SmokeGrid.Look.EJECTA, FxAtlas.lifeFrame(SmokeGrid.Look.EJECTA, t / Math.max(c.settled, 1e-3)));
        c.jets(t, seed, (jx, jy, jz, ax, ay, az, sigma, kg) -> out.segment(x + jx, y + jy, z + jz, ax, ay, az, sigma, sigma, 0, kg * k, 0, r, g, b,
                CloudModel.T_AIR_K, 0));
        out.look(SmokeGrid.Look.SMOKE, 0);
    }

    /** Albedo of a mix of two dusts: share {@code share} of the first (by attenuation). */
    static float mix(float a, float b, double share) {
        return (float) (b + (a - b) * Math.clamp(share, 0, 1));
    }

    /**
     * What the charge hit ({@link BlastTarget}): the packet's material and the support at the impact face from the packet
     * ({@link S2C.Blast.Support}) —
     * the server's breach computes it by the same rule that removes a slab, with a ray along the face's normal
     * ({@code warhead.SlabBreach#support}):
     * a slab over a void is one, and only one, that the server breaches, at any distance (the client has no blocks far away, the server has
     * them); the charge's place on the face and the normal — the axis of the hole and the spall. There is no support in
     * the packet (a replay from before this field, an explosion without a server, a charge not applied to a barrier) — by the material's
     * matter, the support
     * is solid.
     */
    static BlastTarget target(S2C.Blast p, GroundMaterial material, double tnt, double hob) {
        // the charge lies on the face: the explosion point is behind it along the normal by lift (the aim point — half a block into the block)
        return p.support().map(s -> BlastTarget.of(material, s.loose(), s.slab(), s.breach(), p.pos().subtract(s.normal().scale(s.lift())),
                        s.normal()))
                .orElseGet(() -> BlastTarget.of(material, tnt, hob));
    }

    /** An explosion packet closer than {@link #NEAR} to the camera: particles draw it and the near model voices it. */
    public static boolean near(Vec3 pos) {
        return camera().distanceTo(pos) <= NEAR;
    }

    /**
     * An explosion packet arrived (light at once, sound when it arrives): the cloud by the model ({@link BlastSource}, air — by the light of
     * day, weather and biome at the explosion's place) — the smoke grid's source near the camera and the picture here. An underground bomb —
     * the former picture.
     * The model's cloud — for whoever draws the explosion up close (null — the bomb, or no world).
     */
    @Nullable
    public static CloudModel add(S2C.Blast p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        Vec3 pos = p.pos();
        BlastSource source = BlastSource.of(p);
        GroundMaterial ground = GroundMaterial.byId(p.material());
        if (p.kind() == S2C.Blast.BUNKER) {
            // underground bomb: broke through to the surface — the cloud of the share of the charge that went into the air, over the crater (dust — the
            // crater's ejected ground); deeper — the cloud from the shaft comes in its own packet (S2C.Vent)
            ua.zentix.incoming.warhead.BuriedBlast law = BlastSource.law(pos.y, p.surfaceY());
            if (!law.breaches()) return null;
            Vec3 top = new Vec3(pos.x, p.surfaceY(), pos.z);
            CloudModel model = CloudModel.buried(source, air(level, pos), law, ground.isWater(), ground(level, top));
            return cloud(level, source, model, top, ground, p.seed());
        }
        // The impact is on whatever is under the point (ground, roof, wall — it does not matter; above a craft in the air — the terrain far below): what
        // is around, and at what height, is seen from the top blocks (ground); what it hit and whether there is a void behind it — the server's
        // breach along the axis of impact (the support in the packet, target)
        double hob = overSurface(level, pos);
        BlastTarget target = target(p, ground, source.chargeKg(), hob);
        return cloud(level, source, new CloudModel(source, air(level, pos), target, hob, ground(level, pos)), pos, ground, p.seed());
    }

    /**
     * The cloud of model {@code model} of explosion {@code source} with its base at {@code at}: an event — the client's cloud source (its
     * baked puffs of the layer draw it, {@link SmokePuffs}), its ball and light, the rumble far away. Returns the model.
     */
    public static CloudModel cloud(ClientLevel level, BlastSource source, CloudModel model, Vec3 at, GroundMaterial ground, long seed) {
        return cloud(level, source, model, at, ground, seed, source);
    }

    /**
     * As {@link #cloud(ClientLevel, BlastSource, CloudModel, Vec3, GroundMaterial, long)}, but the rumble at a distance — from
     * {@code voice} ({@link Event#voice}): for a cloud without a charge of its own, the event's sound is the voice's own.
     */
    public static CloudModel cloud(ClientLevel level, BlastSource source, CloudModel model, Vec3 at, GroundMaterial ground, long seed,
                                   BlastSource voice) {
        Event e = new Event(level, source, model, at.x, at.y, at.z, ground, seed, near(at), voice);
        trace(e, FarTerrain.of(level), camera());
        clear(e, camera(), 0);
        admit(EVENTS, e, CAP, old -> {
            old.alive = false;
            if (old.soundPending) SOUNDS.addLast(old);
        });
        SmokeGrid.add(e);
        return model;
    }

    /** {@code e} goes to the end of {@code events}; beyond {@code cap} the first one goes to {@code gone} (picture — yes, sound — on its own schedule). */
    static <E> void admit(ArrayDeque<E> events, E e, int cap, java.util.function.Consumer<E> gone) {
        if (events.size() >= cap) gone.accept(events.pollFirst());
        events.addLast(e);
    }

    /**
     * Ticks from the explosion until the front arrives {@code d} blocks from the explosion point — one law for the near sound, the far
     * and the visible ring: {@link ChemicalFront} (Kinney–Graham and Rankine–Hugoniot, faster than sound near the blast); without its own wave
     * ({@code front} null) — at the speed of sound.
     */
    public static double arrivalTicks(@Nullable ChemicalFront front, double d) {
        return front == null ? d / Acoustics.SPEED : 20 * front.arrivalSeconds(d);
    }

    /**
     * An airburst ({@code S2C.Intercept}: an air-defence missile's warhead, charge {@code source}) — the same model cloud
     * as for a ground explosion, at its own height above the terrain ({@link #heightAbove}): no crater, the wave's dust — by the pressure at
     * the ground (at flight height there is not enough of it to tear up ground), no fuel; flash, product ball, a small soot cloud, rumble
     * at a distance. The model's cloud — for whoever draws the burst up close (null — no world).
     */
    @Nullable
    public static CloudModel airburst(Vec3 pos, BlastSource source, long seed) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        double hob = heightAbove(level, pos);
        return cloud(level, source, new CloudModel(source, air(level, pos), BlastTarget.of(GroundMaterial.STONE, source.chargeKg(), hob), hob,
                ground(level, pos)), pos, GroundMaterial.STONE, seed);
    }

    /**
     * What lies around the place {@code pos} by sectors ({@code GroundDust.SECTORS}) and radii ({@code GroundDust.BANDS}, up to
     * 48 m): the top block of the {@code MOTION_BLOCKING} heightmap (grass without collision does not hold the map; the top is sod) in
     * the middle of the sector at this radius — its material and the height of its top above {@code pos}: a roof, a street or a slope.
     * Loose: ground without sod, sand, gravel, snow, farmland; sod and foliage hold the ground under them; the rest (stone,
     * brick, planks, metal) — hard: on it only settled dust; water — no dust. The terrain the layer runs down to is the same
     * top anywhere ({@link FarTerrain}: the client's chunks, farther out the map tiles). Where the client has no chunk — as for
     * the nuke: bare ground ({@code Surface.REFERENCE}) at the map tile's height, and if there is none, the height is unknown (a flat
     * surface at the blast's height above what is under the point, lower; ⚠ a hit on a roof beyond the drawing range without a terrain map —
     * dust at the roof's height: the client does not see it). Permeable foliage does not stand in for the forest floor: in a finished chunk under the canopy
     * the first solid surface is sought ({@code FarTerrain.dustY}); it gives dust by its own material. Logs, roofs,
     * rock and water stop the flow alike; unknown terrain of the map remains an approximation.
     */
    static ua.zentix.incoming.client.fx.smoke.GroundDust.Ground ground(ClientLevel level, Vec3 pos) {
        int n = ua.zentix.incoming.client.fx.smoke.GroundDust.SECTORS;
        double[] bands = ua.zentix.incoming.client.fx.smoke.GroundDust.BANDS;
        double[][] loose = new double[n][bands.length], covered = new double[n][bands.length], hard = new double[n][bands.length],
                height = new double[n][bands.length];
        Sightline.Heights relief = FarTerrain.dust(level);
        for (int k = 0; k < n; k++) {
            double a = 2 * Math.PI * (k + 0.5) / n;
            for (int b = 0; b < bands.length; b++) {
                int x = (int) Math.floor(pos.x + bands[b] * Math.cos(a)), z = (int) Math.floor(pos.z + bands[b] * Math.sin(a));
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
                if (chunk == null) {
                    loose[k][b] = 1;
                    height[k][b] = relief.at(x, z) - pos.y;
                    continue;
                }
                int y = FarTerrain.dustY(chunk, x, z);
                height[k][b] = y + 1 - pos.y;
                net.minecraft.world.level.block.state.BlockState top = chunk.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
                if (!top.getFluidState().isEmpty()) continue;
                if (loose(top)) loose[k][b] = 1;
                else if (sod(top)) covered[k][b] = 1;
                else hard[k][b] = 1;
            }
        }
        return new ua.zentix.incoming.client.fx.smoke.GroundDust.Ground(loose, covered, hard, height,
                (dx, dz) -> relief.at((int) Math.floor(pos.x + dx), (int) Math.floor(pos.z + dz)) - pos.y);
    }

    /** Ground under sod on top: dust is there below, but the sod holds it (tear-off from 3 m/s). */
    public static boolean sod(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)
                || s.is(net.minecraft.world.level.block.Blocks.MUD) || s.is(net.minecraft.world.level.block.Blocks.CLAY);
    }

    /** Whether the ground on top is loose: a weak wind tears dust off it. */
    public static boolean loose(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(net.minecraft.world.level.block.Blocks.DIRT) || s.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                || s.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT) || s.is(net.minecraft.world.level.block.Blocks.FARMLAND)
                || s.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) || s.is(net.minecraft.tags.BlockTags.SAND)
                || s.is(net.minecraft.world.level.block.Blocks.GRAVEL) || s.is(net.minecraft.world.level.block.Blocks.SNOW)
                || s.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK) || s.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                || s.is(net.minecraft.world.level.block.Blocks.SUSPICIOUS_SAND) || s.is(net.minecraft.world.level.block.Blocks.SUSPICIOUS_GRAVEL);
    }

    /**
     * Height of a ground explosion above what is directly under the point, m: the top of the column ({@link FarTerrain}: the client's chunk —
     * with crowns
     * and buildings, farther out the map tiles); a hit on a roof, wall or slope — 0 (contact: the charge crushes what it hit), above
     * a craft in the sky — the height above the terrain. A wall impact point lies on its face — columns on both sides of the face (in
     * a half-block), the highest is taken. If we know nothing — 0: the packet names the explosion as ground.
     */
    static double overSurface(ClientLevel level, Vec3 pos) {
        return overSurface(FarTerrain.dust(level), pos);
    }

    /** Same, by heights {@code heights} (the first air above the top of the column). */
    static double overSurface(Sightline.Heights heights, Vec3 pos) {
        double ground = Double.NaN;
        for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            double h = heights.at((int) Math.floor(pos.x + 0.5 * d[0]), (int) Math.floor(pos.z + 0.5 * d[1]));
            if (!Double.isNaN(h) && !(h <= ground)) ground = h;
        }
        return Double.isNaN(ground) ? 0 : Math.max(0, pos.y - ground);
    }

    /**
     * Height of a point above the terrain under it, m: the client's chunk (heightmap {@code MOTION_BLOCKING} — with crowns and buildings),
     * farther out — the map's terrain tiles ({@link FarTerrain}); if we know nothing — above sea level.
     */
    static double heightAbove(ClientLevel level, Vec3 pos) {
        double ground = FarTerrain.dust(level).at((int) Math.floor(pos.x), (int) Math.floor(pos.z));
        if (Double.isNaN(ground)) ground = ua.zentix.incoming.util.Air.SEA;
        return Math.max(0, pos.y - ground);
    }

    /**
     * The explosion cloud at {@code pos} ({@link CloudModel}) — for the picture up close (flash, particles of solids); the same as
     * the event's.
     */
    public static CloudModel model(S2C.Blast p) {
        ClientLevel level = Minecraft.getInstance().level;
        BlastSource source = BlastSource.of(p);
        GroundMaterial mat = GroundMaterial.byId(p.material());
        if (level == null) return new CloudModel(source, CloudModel.Air.STANDARD, BlastTarget.of(mat, source.chargeKg(), 0), 0,
                ua.zentix.incoming.client.fx.smoke.GroundDust.Ground.BARE);
        double hob = overSurface(level, p.pos());
        return new CloudModel(source, air(level, p.pos()), target(p, mat, source.chargeKg(), hob), hob, ground(level, p.pos()));
    }

    /**
     * Air at the explosion place: the world's air ({@link ClientAir}) with the wind at the place's ground ({@link ClientAir#site}: a street, crowns,
     * a hill); humidity — the air's own; 1 at the water (the condensation cloud), over the water — the field.
     */
    public static CloudModel.Air air(ClientLevel level, Vec3 pos) {
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(pos);
        boolean water = !level.getFluidState(at).isEmpty() || !level.getFluidState(at.below()).isEmpty();
        ua.zentix.incoming.util.Air air = ClientAir.now();
        // the place's correction is measured from the surface under the explosion (under a crown — from the ground beneath it): the cloud's heights are measured from the same surface too
        return CloudModel.Air.of(air, water ? 1 : air.humidity(),
                water ? ua.zentix.incoming.util.LocalWind.OPEN : ClientAir.site(pos.x, pos.y - overSurface(level, pos), pos.z));
    }

    /**
     * An explosion on water of a charge of {@code kg} kg of TNT at the place {@code pos} ({@link WaterBlast}): the depth of the charge under the surface and the water under
     * it — by the client's chunk (up from the point while there is water — the surface; down from the surface while there is water — the bottom; no chunk — an impact on the surface
     * of deep water). One for the event's cloud and for the jets as particles up close.
     */
    public static WaterBlast water(ClientLevel level, Vec3 pos, double kg, CloudModel.Air air) {
        net.minecraft.core.BlockPos.MutableBlockPos m = net.minecraft.core.BlockPos.containing(pos).mutable();
        if (level.getChunkSource().getChunk(m.getX() >> 4, m.getZ() >> 4, false) == null) return new WaterBlast(kg, 0, Double.POSITIVE_INFINITY, air);
        // the point — in the water or just above the surface (the packet places the explosion on the surface): the top of the water is the first air above it
        if (level.getFluidState(m).isEmpty() && level.getFluidState(m.move(0, -1, 0)).isEmpty()) return new WaterBlast(kg, 0, Double.POSITIVE_INFINITY, air);
        while (m.getY() < level.getMaxBuildHeight() && !level.getFluidState(m).isEmpty()) m.move(0, 1, 0);
        double surface = m.getY() - 1 + ua.zentix.incoming.client.fx.layer.WaterDecal.SURFACE;
        int bottom = m.getY() - 1, deepest = Math.max(level.getMinBuildHeight(), bottom - 64);
        m.setY(bottom);
        while (m.getY() > deepest && !level.getFluidState(m).isEmpty()) m.move(0, -1, 0);
        double floor = level.getFluidState(m).isEmpty() ? m.getY() + 1 : Double.NEGATIVE_INFINITY;
        return new WaterBlast(kg, surface - pos.y, surface - floor, air);
    }

    /** Profiles of the foam and the wave of the explosion ring on water, into the frame (each frame without new objects). */
    private static final float[] DARK = new float[ua.zentix.incoming.client.fx.layer.WaterDecal.SAMPLES],
            FOAM = new float[ua.zentix.incoming.client.fx.layer.WaterDecal.SAMPLES];

    /**
     * Decal of an explosion on water ({@link ua.zentix.incoming.client.fx.layer.WaterDecal}): the front — the wave's crest from the cavity
     * ({@link WaterBlast#waveRadius}), behind it — crests every wavelength (ripple darkens the water; ⚠ presentation: the darkness — by the
     * wave height, without its own optics) and foam where the column's water fell ({@link WaterBlast#foam}).
     */
    private static void decal(Event e, double t) {
        WaterBlast w = e.water;
        if (!ua.zentix.incoming.client.fx.layer.WaterDecal.wanted() || t >= w.waterLife()) return;
        double front = waterProfile(w, t, DARK, FOAM);
        float most = 0;
        for (int i = 0; i < DARK.length; i++) most = Math.max(most, Math.max(DARK[i], FOAM[i]));
        if (most > 0.004f) ua.zentix.incoming.client.fx.layer.WaterDecal.ring(e.x, e.z, front, front, DARK, FOAM, true);
    }

    /** Wave presentation: the front exactly from the law; the crest darkens smoothly behind it, without a hard rim. Foam — the model's share. */
    static double waterProfile(WaterBlast w, double t, float[] dark, float[] foam) {
        double front = w.waveRadius(t), lambda = w.waveLength();
        for (int i = 0; i < dark.length; i++) {
            double behind = ua.zentix.incoming.client.fx.layer.WaterDecal.sampleAt(i, front), r = Math.max(0, front - behind);
            // crests: the leading one at the front, the next ones one wavelength inward, lower by their own share (a wave packet, ⚠ shape)
            double phase = behind / lambda, crest = 0.5 + 0.5 * Math.cos(2 * Math.PI * phase);
            double edge = Math.clamp(behind / (lambda / 16), 0, 1);
            edge = edge * edge * (3 - 2 * edge);
            double wave = w.waveAt(r, t) * crest * Math.exp(-phase / 1.5) * edge;
            // Presentation, not a change in the wave height: the water keeps the reflections of the pack ice under the soft crest.
            dark[i] = (float) (0.35 * wave);
            foam[i] = (float) w.foam(r, t);
        }
        return front;
    }

    /** Once per tick: forget the past and a foreign dimension; the sound when the front has arrived; terrain rays in turn. */
    static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            reset();
            return;
        }
        clock = BlastTime.ticks();
        if (!SOUNDS.isEmpty()) {
            Vec3 ear = camera();
            Sightline.Heights heights = FarTerrain.of(level);
            SOUNDS.removeIf(e -> {
                if (e.level != level) return true;
                listen(e, heights, ear, clock - e.born);
                return !e.soundPending;
            });
        }
        if (EVENTS.isEmpty()) return;
        Vec3 ear = camera();
        Sightline.Heights heights = FarTerrain.of(level);
        int traces = 0, clears = 0;
        Iterator<Event> it = EVENTS.iterator();
        while (it.hasNext()) {
            Event e = it.next();
            long age = clock - e.born;
            if (e.level != level || age > e.life() && (!e.soundPending || age > e.soundWait)) {
                e.alive = false;
                it.remove();
                continue;
            }
            if (e.soundPending) listen(e, heights, ear, age);
            if (clock - e.traced >= RETRACE && traces < TRACES_PER_TICK) {
                trace(e, heights, ear);
                if (e.glows) clear(e, ear, age);
                traces++;
            } else if (age < e.ballTicks() && clears < CLEARS_PER_TICK) {
                clear(e, ear, age);
                clears++;
            }
        }
    }

    /** The front reached the ear — the rumble ({@link #hear}); if it did not arrive within the whole sound path to the audible range — it will not arrive now. */
    private static void listen(Event e, Sightline.Heights heights, Vec3 ear, long age) {
        double d = e.blastDistanceTo(ear);
        if (arrivalTicks(e.soundFront(), d) <= age) {
            e.soundPending = false;
            trace(e, heights, ear);
            hear(e, d, age);
        } else if (age > e.soundWait) {
            e.soundPending = false;
        }
    }

    private static void hear(Event e, double d, long age) {
        Outdoor.Heard heard = BlastSounds.far(e.voice, new Vec3(e.x, e.y + LIFT, e.z), Math.max(1, 0.4 * e.ballRadius()),
                e.ground, d, e.path, e.seed);
        if (heard == null) return;
        lastHeard = heard;
        lastDistance = d;
        lastDelay = age;
    }

    /**
     * The ball in the drawing is covered by blocks (a house, a wall, a cave vault), while the glare and veil at the eye are not cut by depth: rays along
     * blocks to the ball's middle and to the ring around it ({@link #disc}); the open share is the share of the glare and glow of a covered ball.
     * Two rays to the ball's axis at a corner of a high-rise would both hit the corner, and the visible half of the ball would glow without glare. Part
     * of the disc in the ground is covered by everything and does not count. Beyond the drawing range the terrain covers the ball ({@link #trace}). The ball in the frame
     * its glare is also taken from what is on screen ({@code SourceProbes}: including Distant Horizons LODs, which the rays do not see).
     * The ball is where it is on the {@code age}-th tick, as are its puffs, sprite and light: the place at mid-life of the ball under wind
     * had moved from the current one by metres (a rocket, 5 m/s, first ticks — ~10 m), and a ball at a wall's edge counted as covered.
     */
    private static void clear(Event e, Vec3 eye, double age) {
        double r = e.ballRadius();
        if (r <= 0) return;
        if (e.distanceTo(eye) > Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0) {
            e.open = 1;
            return;
        }
        double t = Math.min(age, e.ballTicks()) / 20, mid = e.y + e.model.ballHeight(t);
        double mx = e.x + e.model.ballX(t), mz = e.z + e.model.ballZ(t);
        int seen = 0, counted = 0;
        for (int i = 0; i <= RING_RAYS; i++) {
            disc(eye, mx, mid, mz, RING * r, i, DISC);
            if (i > 0 && DISC[1] < e.y + 0.5) continue;
            counted++;
            if (!FarFlightView.blocked(eye, DISC[0], DISC[1], DISC[2])) seen++;
        }
        e.open = seen / (float) counted;
    }

    /**
     * Point i of the ball's disc centred at (cx, cy, cz), as the eye sees it: 0 — the centre, 1…{@link #RING_RAYS} — around the circle
     * of radius ring in the plane across the line of sight, the first — to the side, then around the circle upwards.
     */
    static void disc(Vec3 eye, double cx, double cy, double cz, double ring, int i, double[] out) {
        out[0] = cx;
        out[1] = cy;
        out[2] = cz;
        if (i == 0) return;
        double dx = cx - eye.x, dy = cy - eye.y, dz = cz - eye.z, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return;
        dx /= len;
        dy /= len;
        dz /= len;
        // sideways — across the ray horizontally (a vertical ray — along x), up the disc — side × ray
        double sx = -dz, sz = dx, s = Math.sqrt(sx * sx + sz * sz);
        if (s < 1e-9) {
            sx = 1;
            sz = 0;
        } else {
            sx /= s;
            sz /= s;
        }
        double ux = -sz * dy, uy = sz * dx - sx * dz, uz = sx * dy;
        double a = 2 * Math.PI * (i - 1) / RING_RAYS, c = Math.cos(a) * ring, n = Math.sin(a) * ring;
        out[0] += sx * c + ux * n;
        out[1] += uy * n;
        out[2] += sz * c + uz * n;
    }

    private static void trace(Event e, Sightline.Heights heights, Vec3 eye) {
        Sightline.Result r = Sightline.trace(heights, e.x, e.y + LIFT, e.z, eye.x, eye.y, eye.z);
        e.hidden = r.hidden();
        e.path = r.pathDifference();
        e.traced = clock;
    }

    // ---------------------------------------------------------------- frame

    static void collect(FarView view, FarSprites out, FxLights lights) {
        ClientLevel level = Minecraft.getInstance().level;
        Vec3 cam = view.camera();
        double now = BlastTime.ticks() + view.partial();
        Arrays.fill(BRIGHTEST, 0);
        for (Event e : EVENTS) {
            if (e.level != level) continue;
            double age = Math.max(0, now - e.born);
            if (e.water != null) decal(e, age / 20);
            // base relative to the camera
            double bx = e.x - cam.x, by = e.y - cam.y, bz = e.z - cam.z;
            double d = e.distanceTo(cam);
            e.glows = age < e.ballTicks();
            // the line below which the explosion point is covered by the terrain — above the base (depth also cuts bodies in the drawing)
            double line = e.hidden > 0 ? LIFT + e.hidden : 0;
            sky(e, view, out, age, bx, by, bz, d, line);
            light(e, view, out, lights, age, bx, by, bz, d, line);
        }
    }

    /** Weights and the column of balls for {@link #fireballs} (each frame without new objects). */
    private static final double[] WEIGHT = new double[4];
    private static final org.joml.Vector4f COLUMN = new org.joml.Vector4f();

    /**
     * Fireballs that light the grid's smoke and the world ({@code fx_pall}: {@code SmokeBalls}, output per frame:
     * {@code PallBalls}): up to four of the most visible balls of the model's clouds (light at the eye — brightness × R²/d²) into the free
     * columns {@code centres} (occupied ones — the nuke's balls — are left alone) — centre from the eye and radius; in {@code light} — the light
     * of the fire at the surface ({@link CloudModel#emittedLight}: the source in its own energy budget)
     * (linear, a white wall at noon — 1) × {@code exposure}, in w — {@code air} (attenuation
     * of the air, 1/block). true — at least one ball was added.
     */
    public static boolean fireballs(Vec3 eye, float partial, double exposure, double air, Matrix4f centres, Matrix4f light) {
        double now = BlastTime.ticks() + partial;
        double[] weight = WEIGHT;
        boolean any = false;
        org.joml.Vector4f column = COLUMN;
        for (int i = 0; i < 4; i++) {
            centres.getColumn(i, column);
            weight[i] = column.w > 0 ? Double.MAX_VALUE : 0;
        }
        for (Event e : EVENTS) {
            double age = Math.max(0, now - e.born), t = age / 20;
            double r = e.model.ballRadius(t);
            if (r <= 0) continue;
            double dx = e.x + e.model.ballX(t) - eye.x, dy = e.y + e.model.ballHeight(t) - eye.y, dz = e.z + e.model.ballZ(t) - eye.z;
            e.model.emittedLight(t, BB);
            double w = BB[3] * r * r / Math.max(dx * dx + dy * dy + dz * dz, r * r);
            int slot = 0;
            for (int i = 1; i < 4; i++) if (weight[i] < weight[slot]) slot = i;
            if (w <= weight[slot]) continue;
            weight[slot] = w;
            centres.setColumn(slot, column.set((float) dx, (float) dy, (float) dz, (float) r));
            light.setColumn(slot, column.set((float) (BB[0] * exposure), (float) (BB[1] * exposure), (float) (BB[2] * exposure), (float) air));
            any = true;
        }
        return any;
    }

    /**
     * Light and hot gas of the model's clouds on the frame's world ({@code HeatPass}): the fireball — a Lambertian grey body in the budget of the
     * model ({@link CloudModel#emittedLight}, as for {@link #fireballs} and the light on puffs); the gas — the cloud's thermal
     * (centre, radius, temperature and the model's rise); the front
     * of the shock wave ({@link #front(ChemicalFront, double, double, double, double[], double[])}). Flame and
     * the column above the fuel in the crater — belong to fires ({@code FireSmoke.heat}).
     */
    public static void heat(Vec3 eye, float partial, ua.zentix.incoming.client.fx.layer.HeatPass.Frame f, double air) {
        ClientLevel level = Minecraft.getInstance().level;
        for (Event e : EVENTS) {
            if (e.level != level || !e.alive) continue;
            double t = BlastTime.seconds(BlastTime.ticks(), e.born, partial);
            double bx = e.x - eye.x, by = e.y - eye.y, bz = e.z - eye.z;
            double r = e.model.ballRadius(t);
            if (r > 0) {
                e.model.emittedLight(t, BB);
                double h = e.model.ballHeight(t);
                if (BB[3] > 0) f.light(bx + e.model.ballX(t), by + h, bz + e.model.ballZ(t), r, BB[0], BB[1], BB[2], air, h);
            }
            // the thermal's hot gas — where its smoke is (carried by the wind)
            double cr = e.model.radius(t), ch = e.model.height(t);
            if (t < e.model.life) {
                f.hot(bx + e.model.driftX(t), by + Math.max(0, ch - cr), bz + e.model.driftZ(t), 2 * cr, cr, e.model.kelvin(t), e.model.rise(t));
            }
            if (e.source.underground() || e.source.chargeKg() <= 0 || t > FRONT_SECONDS) continue;
            if (front(e.front(), t, r, e.model.hob, FRONT)) f.chemicalFront(bx, by, bz, FRONT[0], FRONT[1], FRONT[2], FRONT_DN);
        }
    }

    /** The front is not sought beyond this age, s: at 2 km it is already sound (the jump at 450 kg there is a hundredth of a percent of the density). */
    static final double FRONT_SECONDS = 6;
    private static final double[] FRONT = new double[3], FRONT_DN = new double[4];

    /**
     * The visible shock-wave front of an ordinary explosion — the same law and the same pass as for the nuke ({@code NukeHeat},
     * {@code HeatPass.Frame#front}, {@link ShockOptics}): a sphere of the front of radius {@link ChemicalFront#radiusAt} at the place
     * of the explosion (for an airburst — in the air; there is none below ground), the jump in refractive index — from the compression behind the front
     * (Rankine–Hugoniot) at the Kinney–Graham pressure on it, the compression layer's thickness — {@link ShockOptics#DECAY_SHARE} of the length
     * of the positive phase behind the front. While the front is inside the fireball ({@code ball}), it is not visible — the ball is. There is no pressure
     * threshold besides visibility: the jump weakens with pressure by itself, and the pass drops a front that would give no
     * pixel a shift in {@code HeatPass.SHIFT_MIN} (for a Shahed at 1080p — roughly up to 150–250 m from the viewer at the
     * front; with binoculars — farther). In an ordinary explosion the pressure on the sphere is one at all heights: on the ground — the reflected
     * hemisphere (W × 1.8), in the air — a free sphere (⚠ the model does not compute the ground-reflected wave or the Mach stem of an airburst).
     * In {@code out} — the radius, the thickness (m) and the sine of the angle above the middle at the ground; in {@code dn} — the jump
     * at four heights. false — the front is not visible.
     */
    static boolean front(ChemicalFront front, double t, double ball, double hob, double[] out, double[] dn) {
        double r = front.radiusAt(t);
        if (!(r > ball) || r <= 0) return false;
        double l = ShockOptics.DECAY_SHARE * front.phaseLength(r);
        double jump = ShockOptics.indexJump(front.kpa(r), ua.zentix.incoming.client.fx.layer.HeatPass.N0);
        if (!(l > 0) || !(jump > 0)) return false;
        out[0] = r;
        out[1] = l;
        out[2] = Math.max(-1, Math.min(1, -Math.max(0, hob) / r));
        Arrays.fill(dn, jump);
        return true;
    }

    private static boolean front(ChemicalFront front, double t, double ball, double hob, double[] out) {
        return front(front, t, ball, hob, out, FRONT_DN);
    }

    /**
     * Fireball: the body — the ball's animation frame (from flash to soot) over what lies behind it, smaller than a few pixels —
     * a disc of its own light ({@link FarSprites#shaped}); the light around it — the glare and veil at any distance; up close and far — the same
     * frames.
     */
    private static void light(Event e, FarView view, FarSprites out, FxLights lights, double age, double bx, double by, double bz, double d,
                              double line) {
        double t = view.air(d, by);
        double ticks = e.ballTicks();
        if (ticks <= 0 || age >= ticks || t < Sight.THRESHOLD) return;
        double r, half, body;
        FxAtlas.Sprite sprite;
        double fire;
        // frame — by the ball's state: while burning — the share of burn time, cooling — the temperature (frames of the baked simulation
        // with its time and temperature, FxAtlas.fireballFrame); the body melts into smoke by the same share as the smoke shows through: by
        // glow against the sky light on its smoke (CloudModel.glowShare) — earlier by day than at night
        double s = age / 20, frame = FxAtlas.fireballFrame(s / Math.max(e.model.burnSeconds, 1e-6), e.model.ballKelvin(s),
                e.model.ballKelvin(e.model.burnSeconds));
        r = e.model.ballRadius(s);
        half = r * FxAtlas.fireballHalf(frame);
        body = CloudModel.glowShare(e.model.ballKelvin(s), view.skyLuminance());
        sprite = FxAtlas.fireballAt(frame, (e.seed & 1) != 0);
        fire = FxAtlas.fireLightAt(frame) * body;
        // Presentation and the particle light's working limit: a body hidden in smoke does not take up FxLights, as before FLASH.
        // The physical flux onto the world and the volumetric smoke are gathered separately and do not depend on this daytime cutoff.
        if (r <= 0 || body <= 0) return;
        // the wind carries the ball with its smoke (CloudModel.ballX): the picture, light and glow — from the same place
        bx += e.model.ballX(s);
        bz += e.model.ballZ(s);
        double h = e.ballHeight(age), b = e.brightness(age);
        double vis = visible(h, r, line), hidden = 1 - vis * e.open;
        float[] c = e.colour(age);
        // For a visible body, the source's flux onto particles is the same as onto the world.
        // The fire's brightness limit on a puff — presentation from the fire frame, without the day/night multiplier body.
        lights.add(bx, by + h, bz, r, e.emitted(age) * view.exposure(),
                FxAtlas.fireLightAt(frame) * CloudModel.glowShare(e.model.ballKelvin(s)), c[0], c[1], c[2]);
        if (vis > 0) {
            // fire glow (FxBloom): the model's ball is brighter than its frame on screen by this many times (the frame is shown at its own exposure)
            double hdr = b * view.exposure() / Math.pow(Math.max(FxAtlas.fireLightAt(frame), 1e-3), 2.2);
            out.shaped(bx, by + h, bz, r, sprite, half, FxAtlas.fireballMix(frame), b * view.exposure() * t, t, view.pixel(), c[0], c[1], c[2],
                    vis * body, vis * e.open, fire, hdr, LIGHT);
            brightest(view, bx, by + h, bz, vis);
        }
        // the ball is covered by a crest or a building: its light is scattered by the air above the edge — a glow where the place would be
        // visible while the ball glows; at any distance, like the glare (up close, behind a house, one flash alone is not enough on screen)
        if (hidden > 0) halo(view, out, bx, by + Math.max(line, h), bz, HALO_SIZE * r, b * body * hidden, fire, t, c);
    }

    /** The brightest body of the frame — for the log: the numbers of {@link Sight#light} of the last drawn one (in {@link #LIGHT}). */
    private static void brightest(FarView view, double x, double y, double z, double share) {
        if (LIGHT[2] * share <= BRIGHTEST[3] * BRIGHTEST[4]) return;
        double px = Math.sqrt(x * x + y * y + z * z) * view.pixel();
        BRIGHTEST[0] = LIGHT[0] / px;
        BRIGHTEST[1] = LIGHT[3] / px;
        BRIGHTEST[2] = LIGHT[4] / px;
        BRIGHTEST[3] = LIGHT[2];
        BRIGHTEST[4] = share;
    }

    /**
     * Soft light without a body (the glow of a covered ball) centred at (gx, gy, gz): a halo of radius rad — glowing air around
     * the ball; what is nearer to it, the glow covers ({@link FarSprites#glow}); smaller than a point — paler (the flux is the same). Brightness —
     * {@link #behind}.
     *
     * @param light brightness of the ball against a white screen, without the eye's adaptation
     * @param fire  the ball's fire on screen ({@link FxAtlas#fireLightAt}): the glow is not brighter
     */
    private static void halo(FarView view, FarSprites out, double gx, double gy, double gz, double rad, double light, double fire, double t, float[] c) {
        double d = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double floor = Math.max(rad, 0.5 * Sight.MIN_PIXELS * view.pixel() * d), k = rad / floor;
        out.glow(gx, gy, gz, floor, rad, c[0], c[1], c[2], (float) (Math.min(behind(light, view.exposure(), t), fire) * k * k));
    }

    /**
     * Glow brightness of an occluded ball of brightness light against white (with air t and eye adaptation exposure,
     * {@link Sight#exposure}):
     * the share {@link #BEHIND} of its light, but no brighter than the veil of a visible ball
     * ({@link Sight#VEIL_PEAK}) — it is the same scattered light. At night the light is ×1200 ({@link Sight#adapted}),
     * and the glow at full strength would flood half the frame with cream, wider than the veil of the ball itself:
     * an occluded ball would shine brighter than an open one.
     */
    static double behind(double light, double exposure, double t) {
        return Math.min(Sight.VEIL_PEAK, light * BEHIND * exposure * t);
    }

    /**
     * Glow on the clouds above the spot while the ball shines ({@link #cloudGlow}): by day it is not visible; at night
     * the underside of the clouds above the blast flares up, in rain (solid clouds) more strongly. Close by
     * (nearer than three radii of the spot) a flash on the screen replaces it; a dimension without clouds has no glow.
     */
    private static void sky(Event e, FarView view, FarSprites out, double age, double bx, double by, double bz, double d, double line) {
        double r = e.ballRadius(), ticks = e.ballTicks();
        if (r <= 0 || age >= ticks || Double.isNaN(view.clouds())) return;
        double h = Math.max(SKY_LOW, view.clouds() - e.y), rs = SKY_SPREAD * h, share = e.near ? smoothstep(rs, 3 * rs, d) : 1;
        double gy = by + h, dc = Math.sqrt(bx * bx + gy * gy + bz * bz), t = view.air(dc, gy);
        if (share <= 0 || t < Sight.THRESHOLD) return;
        double vis = visible(h, rs, line);
        if (vis <= 0) return;
        double peak = SKY_PEAK * (SKY_CLEAR + (1 - SKY_CLEAR) * view.rain()) * t * vis * share;
        float[] c = e.colour(age);
        out.glow(bx, gy, bz, rs, rs, c[0], c[1], c[2], (float) (peak * cloudGlow(e.emitted(age), r, h, view.exposure())));
    }

    /**
     * Brightness of the underside of clouds at height h above a light source b of radius r against a white screen, with
     * the eye adaptation of the frame exposure ({@link Sight#exposure}), no brighter than white: the ball shines with
     * force b·r² (per unit π), the illuminance of the clouds is force / h², their brightness is
     * {@link #CLOUD_ALBEDO}·E/π. By day this is thousandths of the sky; at night hundreds of times brighter than it.
     */
    static double cloudGlow(double b, double r, double h, double exposure) {
        double k = r / h;
        return Math.min(1, CLOUD_ALBEDO * b * k * k * exposure);
    }

    /**
     * Which share of a puff (centre at height h above the base, radius r) is visible above the terrain line
     * {@code line} (0 — the place is open); the part below the base is in the ground and does not count.
     * Smoothed: the puff goes behind the ridge gradually.
     */
    static double visible(double h, double r, double line) {
        if (line <= 0) return 1;
        double lo = Math.max(0, h - r), hi = h + r;
        if (hi <= lo) return line <= h ? 1 : 0;
        double v = Math.max(0, Math.min(1, (hi - Math.max(line, lo)) / (hi - lo)));
        return v * v * (3 - 2 * v);
    }

    static boolean isEmpty() {
        return EVENTS.isEmpty();
    }

    static void reset() {
        for (Event e : EVENTS) e.alive = false;
        EVENTS.clear();
        SOUNDS.clear();
        lastHeard = null;
    }

    /**
     * For the {@code SCENARIO} line: the last blast — place, material beneath it and water column
     * (height, m; none — 0).
     */
    public static String describeLast() {
        Event e = EVENTS.peekLast();
        if (e == null) return "none";
        BlastTarget t = e.model.target;
        return String.format(Locale.ROOT, "at %.1f %.1f %.1f, %s, %s%s, charge %.1f kg, water column %.1f m", e.x, e.y, e.z, e.ground,
                t.family().name().toLowerCase(Locale.ROOT), t.slab() > 0 ? String.format(Locale.ROOT, " %.1f m, hole %.1f m", t.slab(), t.hole()) : "",
                e.source.chargeKg(), e.water == null ? 0 : e.water.height);
    }

    /**
     * For the scenario log (once a second): how many distant blasts are alive and the last distant sound —
     * delay from the packet to the ear (and d / 17.15 — when the front should have arrived), volume, audibility
     * margin and losses on the lows (barrier, shadow, rain), how much the highs are quieter than the lows and how
     * much of that is from the ground. If nothing distant has happened yet — an empty line.
     */
    public static String describe() {
        String light = BRIGHTEST[4] <= 0 ? "" : String.format(Locale.ROOT, "; brightest: core %.1f px, overexposure %.0f, glare %.1f px, veil %.1f px, "
                + "visible %.2f", BRIGHTEST[0], BRIGHTEST[3], BRIGHTEST[1], BRIGHTEST[2], BRIGHTEST[4]);
        if (lastHeard == null) return EVENTS.isEmpty() ? "" : "blasts " + EVENTS.size() + light;
        Outdoor.Heard h = lastHeard;
        return String.format(Locale.ROOT, "blasts %d; sound: %.0f blocks, delay %d ticks (front %.1f ticks), volume %.3f, margin %.1f dB, barrier %.1f dB, "
                        + "shadow %.1f dB, rain %.1f dB, highs %.1f dB, of which ground %.1f dB", EVENTS.size(), lastDistance, lastDelay,
                lastDistance / Acoustics.SPEED, h.volume(), h.margin(), h.barrier(), h.shadow(), h.masking(), 20 * Math.log10(Math.max(1e-6, h.highs())),
                -h.ground()) + light;
    }

    private static Vec3 camera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

}
