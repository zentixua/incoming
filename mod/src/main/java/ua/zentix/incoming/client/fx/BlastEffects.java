package ua.zentix.incoming.client.fx;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.Ejecta;
import ua.zentix.incoming.client.sound.BlastEchoes;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.OwnSources;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.net.S2C;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.util.Particles;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BunkerCover;
import ua.zentix.incoming.warhead.BunkerImpact;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.warhead.GroundMaterial;

/**
 * What players see and hear in nearby explosions: flash from the cloud model ({@link CloudModel}), solid matter as particles —
 * fragments, sparks, soil clods, tongues of flame while fuel burns; fireball, smoke, dust and condensation cloud — the smoke
 * grid volume (the model cloud is a grid source, managed by {@link FarBlasts}). Sound, hearing effects and body impulse arrive together
 * with the shock front (17 blocks per tick). Underground bombs retain their previous visuals.
 */
public final class BlastEffects {
    private BlastEffects() {}

    /**
     * Blast packet: closer than {@link FarBlasts#NEAR} — particles and sound here; visuals beyond render distance and sound
     * beyond {@link FarBlasts#NEAR} are managed by {@link FarBlasts} (packets arrive up to {@code far_range}).
     */
    public static void blast(S2C.Blast p) {
        // one cloud per blast: shared by the volume, distant visuals and the flash with particles here
        CloudModel cloud = FarBlasts.add(p);
        // crater fuel: the server sends its block fire in a separate packet (fuelFire); in replays recorded before that packet and for
        // a blast without a server (sandbox), smoke and rumble remain at the impact point as before
        if (!p.fuelApart() && cloud != null && cloud.poolWatts > 0) legacyPool(p.pos(), cloud.pool);
        if (bodyOnly(p.pos())) Effects.add(new Body(p.pos(), GroundMaterial.byId(p.material()), p.seed(), BlastSource.of(p)));
        else if (FarBlasts.near(p.pos())) {
            GroundMaterial mat = GroundMaterial.byId(p.material());
            if (cloud == null && p.kind() != S2C.Blast.BUNKER) cloud = FarBlasts.model(p);
            // fragments belong only to the weapon warhead; a blast without a weapon in its packet (secondary explosions after the nuke, replays
            // predating this field) has no casing
            Vec3 axis = p.weapon() == S2C.Blast.NO_WEAPON ? null : p.axis();
            switch (p.kind()) {
                case S2C.Blast.MISSILE -> Effects.add(new Missile(p.pos(), mat, p.seed(), axis, cloud));
                case S2C.Blast.BUNKER -> Effects.add(new Bunker(p.pos(), mat, p.surfaceY(), p.seed(), cloud));
                case S2C.Blast.ROCKET -> Effects.add(new Drone(p.pos(), mat, p.seed(), axis, true, cloud));
                default -> Effects.add(new Drone(p.pos(), mat, p.seed(), axis, false, cloud));
            }
        }
    }

    /**
     * An anti-aircraft missile burst in the air ({@link S2C.Intercept}): the burst cloud follows the warhead charge cloud model
     * ({@link FarBlasts#airburst}: flash, products fireball, soot puff — baked effects layer billows; distant rumble); nearby,
     * also fragment sparks; on a kill — the target's burning fuel. The crack and light shake arrive with the front.
     */
    public static void intercept(S2C.Intercept p) {
        BlastSource source = BlastSource.air(Airburst.CHARGE_KG);
        CloudModel cloud = FarBlasts.airburst(p.pos(), source, p.seed());
        if (bodyOnly(p.pos())) Effects.add(new Body(p.pos(), GroundMaterial.STONE, p.seed(), source));
        else if (FarBlasts.near(p.pos()) && cloud != null) Effects.add(new Airburst(p.pos(), p.seed(), p.kill(), cloud));
    }

    /**
     * The camera is far away (projectile camera), but the player's body is closer than {@link FarBlasts#NEAR}: {@link FarBlasts} manages visuals and sound
     * at the camera, while the wave still hits the player's ears and pushes the body ({@link Body}).
     */
    private static boolean bodyOnly(Vec3 pos) {
        Player player = Minecraft.getInstance().player;
        return !FarBlasts.near(pos) && player != null && player.getEyePosition().distanceTo(pos) <= FarBlasts.NEAR;
    }

    /** Only the front at the player's body ({@link BlastHit}): visuals, sound and echoes are handled by the distant model at the camera. */
    static final class Body extends Timeline {
        Body(Vec3 pos, GroundMaterial mat, long seed, BlastSource source) {
            super(pos, mat, seed, source, false);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            return t <= 60;
        }

        @Override
        void arrive(ClientLevel level, int band) {
        }
    }

    // ---------------------------------------------------------------- shared

    /** Within this distance the model blast flash is at full strength, blocks: detonation products fireball radius. */
    static double flashNear(CloudModel m) {
        return m.heBall;
    }

    /** Beyond this distance there is no flash, blocks: the veil falls as fireball radius / d and is below {@link Flash#FLOOR} here. */
    static double flashRange(CloudModel m) {
        return flashNear(m) * FlashFalloff.PEAK / Flash.FLOOR;
    }

    /**
     * Distant rumble of a ground impact with {@code tntKg} energy (bomb entry, roof collapse): the energy share that enters the air
     * ({@link BunkerImpact#airTnt}) produces a muffled underground thud via the distant model, like buried bomb gases.
     */
    static BlastSource thud(double tntKg) {
        return new BlastSource(WeaponType.BUNKER, BunkerImpact.airTnt(tntKg), 0, true, true);
    }

    /**
     * Sound and shock front: triggers once at the listener (camera) on arrival — sound; at the player's
     * body — hearing effects and body sway ({@link BlastHit}). A conventional blast ({@code source} not null) sounds according to its charge
     * ({@link BlastSounds#arrive}); its echoes seek slopes as the front travels ({@link BlastEchoes}).
     */
    abstract static class Timeline implements Effects.Effect {
        final Vec3 pos;
        final GroundMaterial mat;
        final RandomSource random;
        /** Blast seed: sound uses it to choose the recording variant too. */
        final long seed;
        /** What exploded; null — the blast has its own sounds ({@link #arrive}). */
        @Nullable
        final BlastSource source;
        @Nullable
        private final BlastEchoes echoes;
        /**
         * Wave front over time ({@link ChemicalFront}: Kinney–Graham and Rankine–Hugoniot) — sound and body impulse arrive by
         * this front, also visible as a ring ({@code FarBlasts.front}); null — the blast has its own wave (underground bomb): at
         * the speed of sound.
         */
        @Nullable
        private final ChemicalFront front;
        /**
         * Maximum wait for sound to reach the ear, ticks, regardless of the visual event lifetime: {@link FarBlasts} handles sound for an event beyond
         * {@link FarBlasts#NEAR}; sound still travels to an ear that was inside the boundary at birth even
         * if the listener moves away — up to 1 km (retreating from the boundary at up to ~130 m/s, faster than elytra and craft).
         */
        static final int SOUND_WAIT = 60;
        private boolean arrived, hit, over;
        private long bornTick = Long.MIN_VALUE, lastTick = Long.MIN_VALUE;
        /** Blast clock used by echoes and the ear impulse to wait for direct sound to start ({@link BlastSounds#clock}). */
        private final OwnSources.Clock clock = BlastSounds.clock();

        Timeline(Vec3 pos, GroundMaterial mat, long seed, @Nullable BlastSource source) {
            this(pos, mat, seed, source, true);
        }

        /** @param heard sound plays at the camera (echoes seek slopes); otherwise — only the front at the body ({@link Body}) */
        Timeline(Vec3 pos, GroundMaterial mat, long seed, @Nullable BlastSource source, boolean heard) {
            this.pos = pos;
            this.mat = mat;
            this.seed = seed;
            this.random = RandomSource.create(seed);
            this.source = source;
            // an underground blast sends no wave into the air (gases leave the bore in their own packet): no echoes or body impulse
            this.echoes = source == null || !heard || source.underground() ? null : BlastSounds.echoes(source, pos, seed, clock);
            this.front = source == null || source.underground() || source.chargeKg() <= 0 ? null : ChemicalFront.cached(source.chargeKg(), source.surface());
        }

        /** Ticks from the blast to front arrival at {@code d} blocks: nearby the front exceeds sound speed (by 0.7 ticks at 100 m for a Shahed). */
        double arrivalTicks(double d) {
            return FarBlasts.arrivalTicks(front, d);
        }

        /** The front tears off grass and leaves around it ({@link FrontLitter}): model fireball, burst height above ground. */
        void litter(ClientLevel level, CloudModel model) {
            FrontLitter.spawn(level, pos, front, model.ballRadius(0.5 * model.burnSeconds), model.hob, random);
        }

        @Override
        public final boolean tick(ClientLevel level, int scheduledTick) {
            long now = BlastTime.ticks();
            if (bornTick == Long.MIN_VALUE) bornTick = now;
            if (now == lastTick) return true; // Effects.add already ran t=0 in this same post tick.
            if (now < bornTick) return false;
            lastTick = now;
            int t = (int) Math.min(Integer.MAX_VALUE, now - bornTick);
            Minecraft mc = Minecraft.getInstance();
            if (!arrived) {
                Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
                double d = ear.distanceTo(pos);
                if (arrivalTicks(d) <= t || t > SOUND_WAIT) {
                    arrived = true;
                    if (t <= SOUND_WAIT) arrive(level, (int) Math.ceil(arrivalTicks(d)));
                } else if (echoes != null) {
                    echoes.approach(ear);
                }
            }
            if (arrived && echoes != null) echoes.tick();
            // body after sound: the ear hears it in its pre-wave state
            LocalPlayer player = mc.player;
            if (!hit && source != null && !source.underground() && player != null) {
                Vec3 body = player.getEyePosition();
                double d = body.distanceTo(pos);
                if (arrivalTicks(d) <= t) {
                    hit = true;
                    BlastHit.hit(source, pos, mat, seed, player, body, t - arrivalTicks(d));
                } else if (t > 60) {
                    hit = true;
                }
            }
            if (!over && !run(level, t)) {
                over = true;
                if (echoes != null) echoes.release();
            }
            // visuals ended while sound still travels to the ear: the event lives until arrival, beyond its visible lifetime
            return !over || !arrived;
        }

        abstract boolean run(ClientLevel level, int t);


        /**
         * Front at the listener: a conventional blast uses charge-based sound ({@link BlastSounds#arrive}).
         *
         * @param band front band: 1 — within 17 blocks, 2 — 17..34 …
         */
        void arrive(ClientLevel level, int band) {
            if (source != null) BlastSounds.arrive(source, pos, seed, clock, echoes);
        }

        /**
         * Screen flash: light is instantaneous; strength follows {@link FlashFalloff} by distance, view direction and line of sight
         * from the camera (on board a projectile and in the observer camera — from where the player looks, rather than their body), and by light
         * around the eye: by day under open sky the screen barely brightens ({@link FlashFalloff#daylight}); at night and under cover — a flash.
         *
         * @param near within this distance — full strength, blocks
         */
        void flash(ClientLevel level, double near, double range, float decay) {
            flash(level, pos, near, range, decay);
        }

        /**
         * Flash from the cloud model: light is the detonation products fireball ({@link CloudModel#heBall}, full strength within its
         * radius; beyond it the veil falls as radius/d and vanishes below {@link Flash#FLOOR}), fading as
         * the ball cools (black body from {@link CloudModel#HE_BALL_HOT} to {@link CloudModel#HE_BALL_END} over its lifetime),
         * with the initial black-body colour.
         */
        void flash(ClientLevel level, CloudModel m) {
            double near = flashNear(m), range = flashRange(m);
            double fall = BlackBody.luminance(CloudModel.HE_BALL_END) / BlackBody.luminance(CloudModel.HE_BALL_HOT);
            float decay = (float) Math.exp(Math.log(fall) / Math.max(1, 20 * m.heBallSeconds));
            double[] c = new double[4];
            BlackBody.light(CloudModel.HE_BALL_HOT, c);
            double top = Math.max(c[0], Math.max(c[1], c[2]));
            int rgb = (int) Math.round(255 * c[0] / top) << 16 | (int) Math.round(255 * c[1] / top) << 8 | (int) Math.round(255 * c[2] / top);
            flash(level, pos, near, range, decay, rgb);
        }

        /** Same, with the light source at {@code at}. */
        void flash(ClientLevel level, Vec3 at, double near, double range, float decay) {
            flash(level, at, near, range, decay, 0xFFF1C8);
        }

        /** Same, with a custom colour. */
        void flash(ClientLevel level, Vec3 at, double near, double range, float decay, int rgb) {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player == null) return;
            Camera camera = mc.gameRenderer.getMainCamera();
            Vec3 eye = camera.getPosition();
            float daylight = FlashFalloff.daylight(FarView.adaptation(level, eye, 1));
            if (daylight <= 0) return;
            Vec3 light = at.add(0, 1.5, 0);
            Vec3 to = light.subtract(eye);
            double d = to.length();
            if (d >= range) return;
            boolean visible = level.clip(new ClipContext(eye, light, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                    .getType() == HitResult.Type.MISS;
            double cos = d < 1e-6 ? 1 : new Vec3(camera.getLookVector()).dot(to) / d;
            float s = FlashFalloff.strength(d, near, range, cos, visible) * daylight;
            if (s > 0) Flash.trigger(s, decay, rgb);
        }

        /**
         * Ejecta by cloud model target ({@link BlastTarget}): soil — crater clods; solid — chips of its (cohesive) bowl;
         * slab over a void — front and rear breach debris; water — jets; airburst — none (no ground there;
         * previously clods also flew from a burst above a roof, front F physics.md). Dust is the model cloud, the grain curtain belongs to its event;
         * only the ejecta's clod share flies as pieces ({@link BlastTarget#clods}).
         */
        void ejecta(ClientLevel level, CloudModel model) {
            BlastTarget t = model.target;
            double tnt = model.source.chargeKg();
            switch (t.family()) {
                case WATER -> splash(level, tnt);
                case SOIL, HARD -> pieces(level, new Ejecta(CraterModel.chargeRadius(tnt, mat.soil()), 0, 0), pos, UP, t.clodVolume(tnt));
                case SLAB -> slab(level, t, tnt);
                case AIR -> {
                }
            }
        }

        /**
         * Ejecta pieces under {@code law}, volume {@code volume} m³, outward {@code up} from the face at {@code origin} ({@link Ejecta#pieces}:
         * launch position, speed and angle match the effects layer curtain). A particle is a piece or handful: count is volume per clod
         * {@link #CLOD_M3} (mass representation count, rather than fragmentation).
         */
        void pieces(ClientLevel level, Ejecta law, Vec3 origin, Vec3 up, double volume) {
            if (law.radius <= 0) return;
            BlockState clod = mat.particleBlock();
            law.pieces(origin, up, count(volume), random::nextDouble, (at, v) -> spawn(level, clod, at, v, 0));
        }

        /**
         * Breach debris from a slab over a void: front chips leave the charge face outward, opposite the hole axis, under the same ejecta law
         * as a crater of the breach radius (est.) (the mod has no dedicated law for front-face chips); rear spall leaves the hole on the rear
         * face along the hole axis ({@link BlastTarget#spall}: pierced face normal — down for a roof, sideways for a wall; plug speed by
         * Gurney). Pieces carry all breach ejecta except dust ({@link BlastTarget#clodVolume}), half at each face (est.) (sources give no
         * split: front F physics.md — front crushing and rear spall, without numbers). No charge position on the face (no support in
         * the packet) — chips from the blast point, no spall.
         */
        void slab(ClientLevel level, BlastTarget t, double tnt) {
            double half = t.clodVolume(tnt) / 2;
            pieces(level, new Ejecta(t.hole(), 0, 0), t.face() != null ? t.face() : pos, t.normal().scale(-1), half);
            BlockState piece = mat.particleBlock();
            // spall flies into the void below the slab — to the floor; lifetime with margin (5 s, as before)
            t.spall(tnt, count(half), random::nextDouble, (p, v) -> spawn(level, piece, p, v, 100));
        }

        /** Particles per volume {@code m3}: clod {@link #CLOD_M3}, with the player's particle density, at most {@link #MAX_CLODS}. */
        private int count(double m3) {
            return (int) Math.min(MAX_CLODS, Math.round(m3 / CLOD_M3 * Fx.density(pos)));
        }

        /**
         * Piece from {@code at} with velocity {@code v} (m/s): lives until it returns to launch height plus one second, but at least
         * {@code least} ticks.
         */
        private void spawn(ClientLevel level, BlockState block, Vec3 at, Vec3 v, int least) {
            Vec3 tick = v.scale(1 / 20.0);
            int life = (int) Math.ceil(2 * Math.max(tick.y, 0) / GRAVITY) + 20;
            Clod p = new Clod(level, at, block, Math.max(life, least));
            p.setParticleSpeed(tick.x, tick.y, tick.z);
            Minecraft.getInstance().particleEngine.add(p);
        }

        /** World up. */
        static final Vec3 UP = new Vec3(0, 1, 0);

        /**
         * Water blast: spray column and mist are the model cloud ({@link ua.zentix.incoming.client.fx.smoke.WaterBlast}
         * at the {@code FarBlasts} event); here — jets and water clumps as particles: launch at up to √(2gH) (H — the column top
         * from the same law), from a cavity of half the column radius, within the law's jet cone, with real gravity. One clump per
         * {@link #SPLASH_PER_KG} kg of column water (E: jets visible on screen, rather than droplets), at most {@link #MAX_CLODS}.
         */
        void splash(ClientLevel level, double tntKg) {
            ua.zentix.incoming.client.fx.smoke.WaterBlast w = FarBlasts.water(level, pos, tntKg, FarBlasts.air(level, pos));
            if (w.height <= 0) return;
            int n = (int) Math.min(MAX_CLODS, Math.round(w.water / SPLASH_PER_KG * Fx.density(pos)));
            double v0 = w.speed / 20;
            for (int i = 0; i < n; i++) {
                double u = random.nextDouble(), v = v0 * Math.sqrt(0.1 + 0.9 * u), az = random.nextDouble() * Math.PI * 2;
                double tilt = w.cone * Math.sqrt(random.nextDouble()), r = 0.5 * w.radius * Math.sqrt(random.nextDouble());
                Vec3 at = pos.add(Math.cos(az) * r, w.depth + 0.3, Math.sin(az) * r);
                Fx.smoke().vel(Math.cos(az) * Math.sin(tilt) * v, Math.cos(tilt) * v, Math.sin(az) * Math.sin(tilt) * v).size(0.3f, 0.7f)
                        .life((int) Math.ceil(2 * v / GRAVITY) + 10).color(0xDCE6EC, 0xF2F5F7).alpha(0.9f).drag(0.995f).gravity((float) GRAVITY)
                        .collide().wind(0).fadeIn(0).fadeFrom(0.8f).budget(FxBudget.SPRAY).spawn(level, at);
            }
        }

        /** Column water per clump particle, kg (est.). */
        static final double SPLASH_PER_KG = 5;

        /** Soil fountain at bomb ground entry (the bunker buster uses its own law in S5). */
        void spray(ClientLevel level, int scale) {
            Particles.burst(level, FxParticles.block(mat), pos.add(0, 1, 0), 1.5, 1.5, 1.5, scale == 1 ? 0.9 : 1.3, scale == 1 ? 350 : 900);
            Particles.burst(level, FxParticles.block(mat), pos.add(0, 3, 0), 4, 3, 4, 0.4, scale == 1 ? 150 : 400);
            if (mat.isWater()) {
                Particles.burst(level, ParticleTypes.SPLASH, pos.add(0, 1, 0), 3, 3, 3, 0.8, scale == 1 ? 700 : 1800);
                Particles.burst(level, ParticleTypes.BUBBLE_POP, pos.add(0, 1, 0), 4, 2, 4, 0.3, scale == 1 ? 150 : 400);
            }
        }
    }

    /** Ejecta soil clod volume, m³ (~25 cm clod) (est.), and the maximum clod count per blast (particle budget). */
    static final double CLOD_M3 = 0.25 * 0.25 * 0.25;
    static final int MAX_CLODS = 1200;
    /** Gravity, m/s², and the same in blocks per tick². */
    static final double G = 9.80665, GRAVITY = G / 400;

    /** Ejecta clod: block particle with real gravity and lifetime covering the entire flight (vanilla uses 1.6 g and up to 2 s). */
    static final class Clod extends net.minecraft.client.particle.TerrainParticle {
        Clod(ClientLevel level, Vec3 at, BlockState state, int life) {
            super(level, at.x, at.y, at.z, 0, 0, 0, state);
            // vanilla particles accelerate downward at 0.04 blocks per tick² — 16 m/s²
            this.gravity = (float) (GRAVITY / 0.04);
            this.friction = 0.995f;
            setLifetime(life);
        }
    }

    /**
     * Blast fragment hits ({@link FragmentHits}): rays at the blast tick, hits at each arrival tick. Null axis —
     * no fragments.
     */
    static final class FragmentQueue {
        private final Vec3 pos;
        @Nullable
        private final Vec3 axis;
        private final long seed;
        private final BlastSource source;
        private java.util.List<FragmentHits.Hit> hits;
        private int next;

        FragmentQueue(Vec3 pos, @Nullable Vec3 axis, long seed, BlastSource source) {
            this.pos = pos;
            this.axis = axis;
            this.seed = seed;
            this.source = source;
        }

        void tick(ClientLevel level, int t, RandomSource random) {
            if (hits == null) hits = axis == null ? java.util.List.of() : FragmentHits.of(level, source.weapon(), pos, axis, seed);
            while (next < hits.size() && hits.get(next).tick() <= t) FragmentHits.show(level, hits.get(next++), random);
        }
    }

    // ================================================================ Shahed, Lancet, Grad (fx)

    static final class Drone extends Timeline {
        /** Cloud model: flash, fireball size for fragments and sparks, fuel burning time. */
        private final CloudModel model;
        /** MLRS rocket: its own sound — a dry burst. */
        private final boolean rocket;
        private final FragmentQueue fragments;

        Drone(Vec3 pos, GroundMaterial mat, long seed, @Nullable Vec3 axis, boolean rocket, CloudModel model) {
            super(pos, mat, seed, model.source);
            this.rocket = rocket;
            this.model = model;
            this.fragments = new FragmentQueue(pos, axis, seed, model.source);
        }

        /** Model fireball radius, blocks: drives fragments, sparks and tongues of flame. */
        float radius() {
            return (float) Math.max(0.5 * model.fuelBall, model.heBall);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            fragments.tick(level, t, random);
            if (t == 0) {
                flash(level, model);
                Explosions.solids(level, pos, radius(), model.source.fuelKg() > 0, random);
                litter(level, model);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 2, 1, 2, 0, 30);
                return true;
            }
            if (t == 1) ejecta(level, model);
            // fuel not consumed by the fireball burns in the crater as Minecraft block fire (server, `FuelFires`): its smoke and sound
            // arrive in a packet with those blocks (`fuelFire`)
            crater(model, pos, t, rocket ? 1.25f : 1.1f);
            return t < 240;
        }

    }

    /**
     * Crater sound from the cloud model: ejecta fall back after one second (E: ejecta flight to a height of about the crater
     * diameter); mass — crater material excluding airborne dust. Burning fuel rumble comes from its block fire ({@link #fuelFire}).
     */
    static void crater(CloudModel model, Vec3 pos, int t, float pitch) {
        double charge = model.source.chargeKg();
        if (t == 20 && charge > 0) {
            // mass of material destroyed at the target (same budget as cloud dust and ejecta); airbursts and water blasts — 0
            double kg = model.target.ballistic(charge);
            if (kg > 0) BlastSounds.debris(pos, kg, 2 * CraterModel.chargeRadius(charge, model.target.material().soil()), pitch);
        }
    }

    // ================================================================ anti-aircraft missile airburst

    static final class Airburst extends Timeline {
        /** Burst charge, kg TNT, for cloud, sound, hearing and body impulse: SAM warhead from its spec. */
        static final double CHARGE_KG = InterceptorSpec.SAM.passport().warhead().tnt();
        /** Model burst cloud: flash, fireball, puff ({@link FarBlasts#airburst}). */
        private final CloudModel model;
        private final boolean kill;

        Airburst(Vec3 pos, long seed, boolean kill, CloudModel model) {
            super(pos, GroundMaterial.STONE, seed, model.source);
            this.model = model;
            this.kill = kill;
        }

        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                flash(level, model);
                // warhead casing fragments as sparks
                for (int i = 0; i < 30; i++) {
                    Fx.spark().vel(Explosions.dir(random, -1).scale(0.6 + random.nextDouble() * 1.2)).life(8 + random.nextInt(14)).gravity(0.03f)
                            .spawn(level, pos);
                }
                if (kill) Explosions.cookoff(level, pos, 2.0f, random);
                litter(level, model);
            }
            return t < 61;
        }

    }

    // ================================================================ cruise missile (mfx)

    static final class Missile extends Timeline {
        /** Cloud model: flash, fireball size, fuel burning time. */
        private final CloudModel model;

        private final FragmentQueue fragments;

        Missile(Vec3 pos, GroundMaterial mat, long seed, @Nullable Vec3 axis, CloudModel model) {
            super(pos, mat, seed, model.source);
            this.model = model;
            this.fragments = new FragmentQueue(pos, axis, seed, model.source);
        }

        float radius() {
            return (float) Math.max(0.5 * model.fuelBall, model.heBall);
        }

        @Override
        boolean run(ClientLevel level, int t) {
            fragments.tick(level, t, random);
            if (t == 0) {
                flash(level, model);
                Explosions.solids(level, pos, radius(), model.source.fuelKg() > 0, random);
                litter(level, model);
                Particles.burst(level, ParticleTypes.LAVA, pos.add(0, 1, 0), 4, 2, 4, 0, 80);
                return true;
            }
            if (t == 1) ejecta(level, model);
            // no secondary explosions: secondary effects are crater fuel (server block fire) and server burning debris
            crater(model, pos, t, 0.9f);
            return t < 340;
        }

    }

    // ================================================================ bunker-busting bomb (bfx)

    static final class Bunker extends Timeline {
        /** Charge position; effect base ({@link #pos}) is the surface above it: the flash, clods and sound originate there. */
        private final Vec3 charge;
        private final Vec3 surface;
        private final int depth;
        /** Underground blast by scaled depth. */
        private final BuriedBlast law;
        /** Breakout cloud ({@link FarBlasts#add}); null — underground blast. */
        @Nullable
        private final CloudModel cloud;

        Bunker(Vec3 pos, GroundMaterial mat, float surfaceY, long seed, @Nullable CloudModel cloud) {
            super(new Vec3(pos.x, surfaceY, pos.z), mat, seed, BlastSource.buried(pos.y, surfaceY));
            this.charge = pos;
            this.surface = new Vec3(pos.x, surfaceY, pos.z);
            this.depth = BunkerCover.depth((int) surfaceY, charge.y);
            this.law = BlastSource.law(pos.y, surfaceY);
            this.cloud = cloud;
        }

        /**
         * Breakout — flash and crater clods for the charge share that enters the air (cloud at {@link FarBlasts}); deeper down,
         * the flash is visible only inside the cavity. The surface in the cracking zone heaves, chips fall from nearby bore ceilings.
         */
        @Override
        boolean run(ClientLevel level, int t) {
            if (t == 0) {
                if (cloud != null) {
                    // flash above the crater, clods from the law's ejecta crater (dug by the server)
                    flash(level, cloud);
                    CraterModel.Soil soil = mat.soil();
                    pieces(level, new Ejecta(law.craterRadius(soil), 0, 0), pos, UP, law.craterVolume(soil));
                } else {
                    // underground, the flash is visible only inside the cavity and nearby
                    flash(level, charge, 8, 70, 0.8f);
                }
                return true;
            }
            if (t % 2 == 0 && t >= 2 && t <= 220 && cloud == null) {
                // stone chips from the cavity ceiling and nearby bores
                Particles.burst(level, FxParticles.fallingDust(mat), charge.add(0, 7, 0), 7, 1, 7, 0, 14);
            }
            if (t <= 16 && depth <= law.fracture()) heave(level, t * 2);
            // crater ejecta fall back as for a surface blast crater ({@link #crater}): soil in the law's bowl excluding airborne
            // dust, from a height of about the crater diameter; no ejecta underground or in water
            if (t == 20 && cloud != null && !mat.isWater()) {
                CraterModel.Soil soil = mat.soil();
                BlastSounds.debris(surface, law.craterVolume(soil) * CloudModel.SOIL_DENSITY * (1 - CloudModel.DUST_LOFTED),
                        2 * law.craterRadius(soil), 0.75f);
            }
            return t < 240;
        }

        private void heave(ClientLevel level, double r) {
            if (r == 0) Fx.ring().size(1, 36).life(16).alpha(0.4f).color(Explosions.rgb(mat), Explosions.rgb(mat)).spawn(level, surface.add(0, 0.3, 0));
            for (double a = 0; a < 360; a += 15) {
                Particles.burst(level, FxParticles.block(mat), FxParticles.ground(level, surface, a, r).add(0, 0.2, 0), 0.7, 0.1, 0.7, 0.3, 10);
            }
        }

        /** Breakout — blast sound for the charge share that enters the air ({@link BlastSounds#arrive}); deeper down — a muffled thud. */
        @Override
        void arrive(ClientLevel level, int band) {
            if (!source.underground()) {
                super.arrive(level, band);
                return;
            }
            Player player = Minecraft.getInstance().player;
            boolean sky = player != null && level.canSeeSky(BlockPos.containing(player.getEyePosition()));
            BlastSounds.buried(source, pos, !sky);
        }
    }

    // ================================================================ bomb entry, gas venting, collapse

    /**
     * Bomb entered the ground: entry crater ejecta dust cloud ({@link BunkerImpact#entryVolume}, no heat — near the ground, with
     * the wind; drawn by the layer) and clods; a dull thud arrives at sound speed — here within {@link FarBlasts#NEAR}, farther away —
     * as cloud rumble for the impact energy share that entered the air ({@link #thud}), as for blasts.
     */
    public static void bunkerImpact(S2C.BunkerImpact p) {
        GroundMaterial mat = GroundMaterial.byId(p.material());
        Vec3 pos = p.pos();
        ClientLevel world = Minecraft.getInstance().level;
        double tnt = BunkerImpact.equivalentTnt(WeaponType.BUNKER.spec().striker().article().dryMass(), p.speed());
        if (world != null && !mat.isWater()) {
            // into water — no soil dust (spray particles below)
            double d = WeaponType.BUNKER.spec().striker().article().diameter();
            BlastSource none = new BlastSource(WeaponType.BUNKER, 0, 0, true, false);
            FarBlasts.cloud(world, none, CloudModel.dust(none, FarBlasts.air(world, pos), BunkerImpact.entryVolume(d), 2 * d), pos, mat,
                    Double.doubleToLongBits(pos.x * 31 + pos.z), thud(tnt));
        }
        if (!FarBlasts.near(pos)) return;
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                if (t == 0) {
                    if (mat.isWater()) Particles.burst(level, ParticleTypes.SPLASH, pos.add(0, 1, 0), 2, 2, 2, 0.8, 400);
                    else spray(level, 1);
                }
                return t < 30;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.bunkerImpact(pos, tnt);
            }
        });
    }

    /**
     * Impact fuel burns on blocks (server, {@code warhead/FuelFires}): the world draws the flame; the fire source
     * ({@link FireSmoke#fuel}: smoke, flame light on the world and smoke, distant flame) and its rumble are client-side, following the weapon's
     * fuel law on packet cells ({@code WeaponSpec.pool(lit)}: deposited on a smaller area — longer, weaker burning, as on the
     * server). A packet also arrives on changes (drained, buried, flooded) — the source and rumble follow them.
     */
    public static void fuelFire(S2C.FuelFire p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || p.cells().isEmpty()) return;
        ua.zentix.incoming.nuclear.model.FuelPool pool = WeaponType.byId(p.weapon()).spec().pool(p.cells().size());
        long age = Math.max(0, level.getGameTime() - p.blastTick());
        if (pool.seconds() <= 0 || age / 20.0 >= pool.seconds()) return;
        FuelBurn burn = FireSmoke.fuel(level, p.id(), pool, p.cells(), p.smothered(), age / 20.0);
        // rumble for fires within {@link FarBlasts#NEAR}, as before (no crater sound is started farther away)
        if (burn != null && FarBlasts.near(burn.centre())) BlastSounds.fire(burn, (int) age);
    }

    /** Crater fuel smoke and rumble at the impact point (no server block fire: old recording, sandbox without a server). */
    private static void legacyPool(Vec3 pos, ua.zentix.incoming.nuclear.model.FuelPool pool) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        FuelBurn burn = FuelBurn.at(pool, BlockPos.containing(pos));
        FireSmoke.pool(level, burn, 0);
        if (FarBlasts.near(burn.centre())) BlastSounds.fire(burn, 0);
    }

    /**
     * Blast gases vent through the bore: a cloud of the charge share that entered the air ({@code airKg}), from the bore mouth, using
     * the same cloud model (heat, soot, dust; drawn by the layer); distant rumble is muffled, underground; nearby — vent roar.
     * One voice per vent: beyond {@link FarBlasts#NEAR} the cloud starts its rumble ({@link FarBlasts#cloud}); nearby —
     * the roar here.
     */
    public static void vent(S2C.Vent p) {
        GroundMaterial mat = GroundMaterial.byId(p.material());
        Vec3 pos = p.pos();
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null) {
            BlastSource gas = new BlastSource(WeaponType.BUNKER, p.airKg(), 0, true, true);
            FarBlasts.cloud(world, gas, new CloudModel(gas, FarBlasts.air(world, pos), mat.isWater()), pos, mat, Double.doubleToLongBits(pos.x * 17 + pos.z));
        }
        if (!FarBlasts.near(pos)) return;
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                return t < 40;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.vent(pos, p.airKg());
            }
        });
    }

    /**
     * The roof above the cavity collapsed to the surface: dust above the collapse (cavity-volume soil, no heat — near the ground, with the wind; drawn
     * by the layer) and collapse rumble.
     */
    public static void collapse(S2C.Collapse p) {
        Vec3 pos = p.pos();
        GroundMaterial mat = GroundMaterial.byId(p.material());
        ClientLevel world = Minecraft.getInstance().level;
        double tnt = BuriedBlast.collapseTnt(p.volume());
        if (world != null) {
            BlastSource none = new BlastSource(WeaponType.BUNKER, 0, 0, true, true);
            double r = Math.cbrt(3 * p.volume() / (4 * Math.PI));
            FarBlasts.cloud(world, none, CloudModel.dust(none, FarBlasts.air(world, pos), p.volume(), r), pos, mat,
                    Double.doubleToLongBits(pos.x * 13 + pos.z), thud(tnt));
        }
        if (!FarBlasts.near(pos)) return;
        Effects.add(new Timeline(pos, mat, 0, null) {
            @Override
            boolean run(ClientLevel level, int t) {
                return t < 20;
            }

            @Override
            void arrive(ClientLevel level, int band) {
                BlastSounds.collapse(pos, tnt);
            }
        });
    }
}
