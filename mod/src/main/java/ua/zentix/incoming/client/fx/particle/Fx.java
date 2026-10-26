package ua.zentix.incoming.client.fx.particle;

import org.jetbrains.annotations.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import ua.zentix.incoming.registry.ModParticles;

/**
 * Effects particles: {@code Fx.smoke().at(…).vel(…).size(2, 9).life(200).color(…).spawn(level, x, y, z)}.
 * Configuration — {@link Spec} (spawn repeatedly and modify between launches: each particle takes a snapshot); particle
 * spawns in a custom pool ({@link FxPool}), without packets or vanilla's 32-block distance limit.
 */
public final class Fx {
    /** Trail billow rise at age in ticks, blocks ({@code TrailModel.rise}). */
    public interface Thermal {
        double lift(double ticks);
    }

    private Fx() {}

    public enum Kind {
        SMOKE(true, false, FxBudget.CLOUD), FIRE(false, false, FxBudget.CLOUD), SPARK(false, true, FxBudget.SPARK),
        FLASH(false, true, FxBudget.FLASH), RING(true, false, FxBudget.GROUND), FIREBALL(false, false, FxBudget.CLOUD),
        /** Ash flakes and paper scraps: flat, tumbling (compressed axis), lit by the world. */
        FLAKE(true, false, FxBudget.ASH);

        /** World-lit (smoke, dust), or emissive (fire, sparks, flash, ball — also receive {@code FxBloom}). */
        final boolean lit;
        /** Additive light (sparks, flash). */
        final boolean additive;
        /** Default budget group. */
        final FxBudget budget;

        Kind(boolean lit, boolean additive, FxBudget budget) {
            this.lit = lit;
            this.additive = additive;
            this.budget = budget;
        }
    }

    public static Spec smoke() {
        return new Spec(Kind.SMOKE);
    }

    public static Spec fire() {
        return new Spec(Kind.FIRE).color(0xFFF0C0, 0x801808).alpha(0.9f).fadeFrom(0.3f);
    }

    public static Spec spark() {
        return new Spec(Kind.SPARK).color(0xFFF4D0, 0xB02800).size(0.12f, 0.08f).fadeFrom(0.5f).streak(2);
    }

    public static Spec flash() {
        return new Spec(Kind.FLASH).color(0xFFF6E0, 0xFF9030).fadeFrom(0.1f).alpha(1);
    }

    public static Spec ring() {
        return new Spec(Kind.RING).color(0xE0DCD6, 0xC8C0B6).fadeFrom(0.35f).alpha(0.55f).drag(1);
    }

    /**
     * Fireball: animation frames over particle life, from flash to soot ({@code FxAtlas#fireball}); colour in the frames,
     * size — frame half-size (ball — 0.7 of it). Fades from midlife: subsequent smoke uses world-lit billows.
     */
    public static Spec fireball() {
        return new Spec(Kind.FIREBALL).alpha(1).fadeIn(0).fadeFrom(0.55f).drag(0.94f).wind(0.3f).spin(0.006f);
    }

    /**
     * Ash flakes and paper scraps ({@code paper}): fall slowly, swirl and tumble; drift — {@link Spec#drift}.
     */
    public static Spec flake(boolean paper) {
        return new Spec(Kind.FLAKE).paper(paper).alpha(1).fadeIn(4).fadeFrom(0.85f).drag(0.9f).gravity(0.004f).wind(0).spin(0.12f).collide();
    }

    /**
     * Particle share by Particles setting and camera distance: distant details are invisible and need not spawn.
     */
    public static float density(Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        float k = mc.options.particles().get() == ParticleStatus.ALL ? 1 : mc.options.particles().get() == ParticleStatus.DECREASED ? 0.6f : 0.3f;
        double d = mc.gameRenderer.getMainCamera().getPosition().distanceTo(at);
        if (d > 160) k *= (float) Math.max(0.35, 160 / d);
        return k;
    }

    /** Particle count to spawn from {@code n} at a point (at least one if n > 0). */
    public static int count(int n, Vec3 at) {
        return n <= 0 ? 0 : Math.max(1, Math.round(n * density(at)));
    }

    // ---------------------------------------------------------------- registration

    /** Mod bus: {@code /particle incoming:smoke} spawns a default-configured particle in the pool. */
    public static void registerProviders(RegisterParticleProvidersEvent e) {
        e.registerSpecial(ModParticles.SMOKE.get(), provider(Kind.SMOKE));
        e.registerSpecial(ModParticles.FIRE.get(), provider(Kind.FIRE));
        e.registerSpecial(ModParticles.SPARK.get(), provider(Kind.SPARK));
        e.registerSpecial(ModParticles.FLASH.get(), provider(Kind.FLASH));
        e.registerSpecial(ModParticles.RING.get(), provider(Kind.RING));
        e.registerSpecial(ModParticles.NONE.get(), (type, level, x, y, z, dx, dy, dz) -> null);
    }

    private static ParticleProvider<SimpleParticleType> provider(Kind kind) {
        return (type, level, x, y, z, dx, dy, dz) -> {
            Spec s = switch (kind) {
                case SMOKE -> smoke().size(1, 4).life(120).rise(0.004f);
                case FIRE -> fire().size(0.8f, 1.6f).life(20).rise(0.01f);
                case SPARK -> spark().life(30).gravity(0.04f);
                case FLASH -> flash().size(4, 6).life(4);
                case RING -> ring().size(1, 20).life(20);
                case FIREBALL -> fireball().size(5, 5.5f).life(16);
                case FLAKE -> flake(false).size(0.06f, 0.06f).life(200);
            };
            s.vel(dx, dy, dz).spawn(level, x, y, z);
            // nothing added to the vanilla engine
            return null;
        };
    }

    // ---------------------------------------------------------------- particle configuration

    /** Particle configuration. Methods mutate this object; {@link #copy()} for template variations. */
    public static final class Spec implements Cloneable {
        final Kind kind;
        double vx, vy, vz;
        float size0 = 1, size1 = 1;
        boolean growFast;
        int life = 40;
        float r0 = 1, g0 = 1, b0 = 1, r1 = 1, g1 = 1, b1 = 1;
        float colorCurve = 1;
        float alpha = 0.8f;
        int fadeIn = 3, fadeAfter;
        float fadeFrom = 0.55f;
        float glow, glowTicks = 10;
        float drag = 0.96f;
        float rise, gravity, wind = 1, spin = 0.03f;
        /** Custom constant drift (blocks/tick²): blast wind, rather than shared particle wind. */
        float driftX, driftZ;
        /** Warm trail rise by its law (instead of {@link #rise}); null — none. */
        @Nullable Thermal thermal;
        /** Flakes: paper scrap (light rectangle), rather than ash. */
        boolean paper;
        /** Colour already lit ({@link #prelit}): lightmap does not darken it. */
        boolean prelit;
        /** Emissive overexposure ({@link #hdr}): light brightness relative to the displayed colour. */
        float hdr = 1;
        boolean collide, smooth;
        float chainX, chainY, chainZ, chainGap;
        float streak;
        Spec trail;
        float trailStep = 0.8f, trailUntil = 0.7f;
        FxBudget budget;

        Spec(Kind kind) {
            this.kind = kind;
            this.budget = kind.budget;
        }

        public Spec copy() {
            try {
                return (Spec) clone();
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }

        public Spec vel(double x, double y, double z) {
            vx = x;
            vy = y;
            vz = z;
            return this;
        }

        public Spec vel(Vec3 v) {
            return vel(v.x, v.y, v.z);
        }

        /** Half-size in blocks: at birth and end of life. */
        public Spec size(float from, float to) {
            size0 = from;
            size1 = to;
            return this;
        }

        /** Grows almost instantly (blast billow), rather than gradually (chimney smoke). */
        public Spec growFast() {
            growFast = true;
            return this;
        }

        public Spec life(int ticks) {
            life = ticks;
            return this;
        }

        public Spec color(int from, int to) {
            r0 = (from >> 16 & 0xFF) / 255f;
            g0 = (from >> 8 & 0xFF) / 255f;
            b0 = (from & 0xFF) / 255f;
            r1 = (to >> 16 & 0xFF) / 255f;
            g1 = (to >> 8 & 0xFF) / 255f;
            b1 = (to & 0xFF) / 255f;
            return this;
        }

        public Spec color(float r, float g, float b) {
            r0 = r1 = r;
            g0 = g1 = g;
            b0 = b1 = b;
            return this;
        }

        /** Colour from {@code from} to {@code to} (r, g, b in 0..1). */
        public Spec color(float[] from, float[] to) {
            r0 = from[0];
            g0 = from[1];
            b0 = from[2];
            r1 = to[0];
            g1 = to[1];
            b1 = to[2];
            return this;
        }

        /**
         * Colour already includes open-sky light (camera dust — its dust wall light, {@code NukePall.dustColor}):
         * lightmap does not darken it; frame fireball light is not added — already in the colour. Under a roof,
         * darkens by local skylight and block light, like the lightmap relative to open sky.
         */
        public Spec prelit() {
            prelit = true;
            return this;
        }

        /**
         * Emissive matter (fire, sparks) is {@code k} times brighter than screen white; displayed colour is clipped ({@code Sight.onScreen}):
         * bloom ({@code FxBloom}) takes the clipped part. 1 — light as displayed.
         */
        public Spec hdr(double k) {
            hdr = (float) Math.max(1, k);
            return this;
        }

        /** Colour changes by {@code f^curve} over life fraction: > 1 keeps the initial colour longer. */
        public Spec colorCurve(float curve) {
            colorCurve = curve;
            return this;
        }

        public Spec alpha(float a) {
            alpha = a;
            return this;
        }

        public Spec fadeIn(int ticks) {
            fadeIn = ticks;
            fadeAfter = 0;
            return this;
        }

        /** Invisible for the first {@code after} ticks, then appears over {@code ticks} (smoke emerging from the fireball). */
        public Spec fadeIn(int ticks, int after) {
            fadeIn = ticks;
            fadeAfter = Math.max(0, after);
            return this;
        }

        /** Life fraction where fading starts. */
        public Spec fadeFrom(float f) {
            fadeFrom = Math.min(f, 0.99f);
            return this;
        }

        /** Incandescence: internal fire glow (0..1), fading over {@code ticks}. */
        public Spec glow(float g, float ticks) {
            glow = g;
            glowTicks = ticks;
            return this;
        }

        /** Velocity fraction retained per tick (air drag). */
        public Spec drag(float d) {
            drag = d;
            return this;
        }

        /** Hot-gas rise (blocks/tick²), gradually weakening. */
        public Spec rise(float r) {
            rise = r;
            return this;
        }

        public Spec gravity(float g) {
            gravity = g;
            return this;
        }

        /** Billow rises with its trail ({@link Thermal}). */
        public Spec thermal(@Nullable Thermal t) {
            thermal = t;
            return this;
        }

        public Spec wind(float w) {
            wind = w;
            return this;
        }

        public Spec spin(float s) {
            spin = s;
            return this;
        }

        /** Constant horizontal acceleration (blocks/tick²): with {@link #drag}, drift speed a·d/(1 − d). */
        public Spec drift(double ax, double az) {
            driftX = (float) ax;
            driftZ = (float) az;
            return this;
        }

        /** Flakes are paper scraps, rather than ash ({@link Fx#flake}). */
        public Spec paper(boolean p) {
            paper = p;
            return this;
        }

        /** Collides with blocks (hugs ground, does not pass through walls). */
        public Spec collide() {
            collide = true;
            return this;
        }

        /**
         * Smooth even nearby, without a faint rim: billow belongs to a smooth trail ribbon, rather than a separate cloud. Nearest-texel
         * sampling with a 0.1 cutoff would leave only a bright, hard-edged centre of a faint billow — white flakes
         * on the ribbon.
         */
        public Spec smooth() {
            smooth = true;
            return this;
        }

        /**
         * Billow from a chain spaced by {@code gap} blocks along path {@code (dx, dy, dz)} (trail volume billows: one per nozzle
         * path per tick). Visible only when overlapping neighbours on screen — looking along the trail; from the side, a screen gap
         * between billows makes it fade, leaving only the ribbon ({@link FxParticle#chainShare}). Otherwise young billows,
         * smaller than one tick's path, would form a chain of light dots on a thin ribbon. Step 0 disables the rule.
         */
        public Spec chain(double dx, double dy, double dz, double gap) {
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6 || gap <= 0) {
                chainGap = 0;
                return this;
            }
            chainX = (float) (dx / len);
            chainY = (float) (dy / len);
            chainZ = (float) (dz / len);
            chainGap = (float) gap;
            return this;
        }

        /** Particle's slot group ({@link FxBudget}). */
        public Spec budget(FxBudget b) {
            budget = b;
            return this;
        }

        /** Spark: streak length equals the path over this many ticks. */
        public Spec streak(float ticks) {
            streak = ticks;
            return this;
        }

        /**
         * Spark leaves a smoky tail of these billows every {@code step} blocks until life fraction {@code until}.
         * Spark tail template used as is (no snapshot): do not modify after launch.
         */
        public Spec trail(Spec puff, float step, float until) {
            trail = puff;
            trailStep = step;
            trailUntil = until;
            return this;
        }

        public void spawn(Level level, double x, double y, double z) {
            if (level instanceof ClientLevel cl) FxPool.INSTANCE.spawn(this, cl, x, y, z, 1, 1);
        }

        public void spawn(Level level, Vec3 p) {
            spawn(level, p.x, p.y, p.z);
        }
    }
}
