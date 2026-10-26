package ua.zentix.incoming.client.fx.particle;

import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.util.Air;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.FxFrame;
import ua.zentix.incoming.client.fx.layer.FxQuads;

import java.util.List;

/**
 * Effects particle configured by {@link Fx.Spec}: growth slows, air drag brakes it, hot matter rises or settles,
 * wind carries it, it rotates; colour and opacity change over life, incandescence (internal fire glow) fades.
 * Incandescent smoke is emissive and orange-lit — the fireball thus “cools” into black smoke. A spark is a luminous
 * streak along velocity, optionally leaving a smoky tail; the shock ring lies on the ground.
 * <p>
 * Object lives in a pool ({@link FxPool}) and is reused: {@link #init} copies configuration (snapshot — template may be
 * changed and spawned again); flight state has its own fields. Moves and collides like a vanilla particle
 * ({@code Particle.move}: 0.2-block bounds, stops on vertical collision).
 */
public final class FxParticle {
    private static final double MAX_COLLISION_SPEED_SQR = 100 * 100;
    private static final double HALF = 0.1, HEIGHT = 0.2;

    // ---------------------------------------------------------------- configuration (Spec snapshot)
    Fx.Kind kind;
    FxBudget budget;
    private float size0, size1;
    private boolean growFast;
    private int life;
    private float r0, g0, b0, r1, g1, b1, colorCurve, alpha;
    private int fadeIn, fadeAfter;
    private float fadeFrom, glow, glowTicks, drag, rise, gravity, wind, driftX, driftZ;
    private Fx.@org.jetbrains.annotations.Nullable Thermal thermal;
    private boolean paper, prelit;
    /** Emissive overexposure ({@link Fx.Spec#hdr}). */
    private float hdr;
    private boolean collide, smooth;
    /** Billow chain ({@link Fx.Spec#chain}): path direction and billow spacing; step 0 — no chain. */
    private float chainX, chainY, chainZ, chainGap;
    private float streak;
    /** Spark tail template: shared by all salvo sparks, immutable after launch. */
    private Fx.Spec trail;
    private float trailStep, trailUntil;

    // ---------------------------------------------------------------- state
    double x, y, z, xo, yo, zo;
    private double xd, yd, zd;
    private int age;
    private float roll, oRoll, spin;
    /** Billow index in set (smoke — 4 billows × 4 dispersal stages). */
    private int variant;
    private boolean onGround, stopped;
    /** World light at the particle (packed, like {@link LevelRenderer#getLightColor}) — once per tick. */
    private int worldLight;
    boolean dead;

    // ---------------------------------------------------------------- frame appearance
    private float quadSize, rCol, gCol, bCol, aCol;

    void init(Fx.Spec s, double px, double py, double pz, float sizeScale0, float sizeScale1, RandomSource random, ClientLevel level) {
        kind = s.kind;
        budget = s.budget;
        size0 = s.size0 * sizeScale0;
        size1 = s.size1 * sizeScale1;
        growFast = s.growFast;
        life = Math.max(1, s.life);
        r0 = s.r0;
        g0 = s.g0;
        b0 = s.b0;
        r1 = s.r1;
        g1 = s.g1;
        b1 = s.b1;
        colorCurve = s.colorCurve;
        alpha = s.alpha;
        fadeIn = s.fadeIn;
        fadeAfter = s.fadeAfter;
        fadeFrom = s.fadeFrom;
        glow = s.glow;
        glowTicks = s.glowTicks;
        drag = s.drag;
        rise = s.rise;
        gravity = s.gravity;
        wind = s.wind;
        driftX = s.driftX;
        driftZ = s.driftZ;
        thermal = s.thermal;
        paper = s.paper;
        prelit = s.prelit;
        hdr = s.hdr;
        collide = s.collide;
        smooth = s.smooth;
        chainX = s.chainX;
        chainY = s.chainY;
        chainZ = s.chainZ;
        chainGap = s.chainGap;
        streak = s.streak;
        trail = s.trail;
        trailStep = s.trailStep;
        trailUntil = s.trailUntil;
        x = xo = px;
        y = yo = py;
        z = zo = pz;
        xd = s.vx;
        yd = s.vy;
        zd = s.vz;
        age = 0;
        variant = random.nextInt(kind == Fx.Kind.SMOKE ? 4 : 8);
        roll = oRoll = random.nextFloat() * Mth.TWO_PI;
        spin = s.spin * (random.nextFloat() - 0.5f) * 2;
        onGround = stopped = dead = false;
        worldLight = light(level);
        quadSize = size0;
    }

    /** Tick: age, rise, wind, movement with collisions; spark tail. */
    void tick(ClientLevel level, FxPool pool) {
        xo = x;
        yo = y;
        zo = z;
        oRoll = roll;
        if (age++ >= life) {
            dead = true;
            return;
        }
        // hot matter rises quickly, cooled matter barely rises
        if (thermal != null) yd = thermal.lift(age) - thermal.lift(age - 1);
        else yd += rise * (0.15 + 0.85 * Math.exp(-age / 40.0)) - gravity;
        // world wind: air pulls the particle toward its velocity (steady state — wind speed × windage)
        Air air = ClientAir.now();
        double push = wind == 0 ? 0 : air.speed(y) / 20 * wind * (1 - drag) / drag;
        xd += air.wind().x() * push + driftX;
        zd += air.wind().z() * push + driftZ;
        if (kind == Fx.Kind.FLAKE && !onGround) {
            // flakes swirl while falling
            xd += 0.003 * Mth.sin(age * 0.21f + variant);
            zd += 0.003 * Mth.cos(age * 0.17f + variant * 1.3f);
        }
        move(level, xd, yd, zd);
        xd *= drag;
        yd *= drag;
        zd *= drag;
        roll += spin;
        // flakes tumble until settled
        if (kind != Fx.Kind.FLAKE) spin *= 0.985f;
        else if (onGround) spin = 0;
        if (onGround && collide) {
            xd *= 0.7;
            zd *= 0.7;
        }
        worldLight = light(level);
        if (kind == Fx.Kind.SPARK && trail != null) tail(level, pool);
    }

    /** Spark smoke tail: billows along one tick's path while the fragment is hot. */
    private void tail(ClientLevel level, FxPool pool) {
        float f = (float) age / life;
        if (f > trailUntil) return;
        double dx = x - xo, dy = y - yo, dz = z - zo;
        int n = Mth.clamp((int) (Math.sqrt(dx * dx + dy * dy + dz * dz) / trailStep), 1, 6);
        for (int i = 0; i < n; i++) {
            double k = (i + pool.random.nextDouble()) / n;
            pool.spawn(trail, level, xo + dx * k, yo + dy * k, zo + dz * k, 1 - 0.6f * f, 1 - 0.5f * f);
        }
    }

    /** Like {@code Particle.move}: 0.2-block bounds, block collisions; vertical collision stops all further motion. */
    private void move(ClientLevel level, double dx, double dy, double dz) {
        if (stopped) return;
        double ix = dx, iy = dy, iz = dz;
        if (collide && (dx != 0 || dy != 0 || dz != 0) && dx * dx + dy * dy + dz * dz < MAX_COLLISION_SPEED_SQR) {
            Vec3 v = Entity.collideBoundingBox(null, new Vec3(dx, dy, dz), new AABB(x - HALF, y, z - HALF, x + HALF, y + HEIGHT, z + HALF), level,
                    List.of());
            dx = v.x;
            dy = v.y;
            dz = v.z;
        }
        x += dx;
        y += dy;
        z += dz;
        if (Math.abs(iy) >= 1.0E-5F && Math.abs(dy) < 1.0E-5F) stopped = true;
        onGround = iy != dy && iy < 0;
        if (ix != dx) xd = 0;
        if (iz != dz) zd = 0;
    }

    private int light(ClientLevel level) {
        if (!kind.lit) return LightTexture.FULL_BRIGHT;
        BlockPos pos = BlockPos.containing(x, y, z);
        // no client chunk (beyond render distance: salvo smoke the viewer moved away from) — open sky, like the trail
        // ribbon ({@code FarFlightView}); vanilla particles are dark there, making billows black beads on a light ribbon
        return level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
                ? LevelRenderer.getLightColor(level, pos) : LightTexture.FULL_SKY;
    }

    /** Incandescence 0..1: exponential decay over {@code glowTicks}. */
    private float heat(float partial) {
        if (glow <= 0) return 0;
        return glow * (float) Math.exp(-(age + partial) / Math.max(1f, glowTicks));
    }

    /** Size, colour and opacity at {@code age + partial}. */
    private void update(float partial) {
        float f = Mth.clamp((age + partial) / life, 0, 1);
        float grow = 1 - (1 - f) * (1 - f) * (1 - f) * (growFast ? (1 - f) : 1);
        quadSize = size0 + (size1 - size0) * grow;
        float cf = (float) Math.pow(f, colorCurve);
        float r = Mth.lerp(cf, r0, r1), g = Mth.lerp(cf, g0, g1), b = Mth.lerp(cf, b0, b1);
        float heat = heat(partial);
        if (heat > 0 && kind.lit) {
            // internal fire lighting
            r = Math.min(1, r + heat * 1.0f);
            g = Math.min(1, g + heat * 0.45f);
            b = Math.min(1, b + heat * 0.12f);
        }
        if (prelit) {
            float k = openSky();
            r *= k;
            g *= k;
            b *= k;
        }
        rCol = r;
        gCol = g;
        bCol = b;
        float since = age + partial - fadeAfter;
        float in = since < 0 ? 0 : fadeIn <= 0 ? 1 : Mth.clamp(since / fadeIn, 0, 1);
        float out = f < fadeFrom ? 1 : 1 - (f - fadeFrom) / (1 - fadeFrom);
        aCol = alpha * in * Math.max(0, out);
    }

    /**
     * Local particle light relative to open sky 0..1 ({@link Fx.Spec#prelit}: colour already sky-lit): under a roof —
     * by skylight and block light, using vanilla brightness curve (level f → f / (4 − 3f)).
     */
    private float openSky() {
        float f = Math.max(LightTexture.sky(worldLight), LightTexture.block(worldLight)) / 15f;
        return f / (4 - 3 * f);
    }

    /** Lightmap input: world light, incandescent smoke no darker than fire glow; prelit matter bypasses it. */
    private int lightColor(float partial) {
        if (!kind.lit || prelit) return LightTexture.FULL_BRIGHT;
        int block = Math.max(LightTexture.block(worldLight), (int) (heat(partial) * 15));
        return LightTexture.pack(block, LightTexture.sky(worldLight));
    }

    /** Maximum particle radius (frame culling): entire quad, including a spark's streak. */
    double reach() {
        double v = Math.sqrt(xd * xd + yd * yd + zd * zd);
        return Math.max(size0, size1) + v * (1 + streak);
    }

    /** Dense billow region — about 0.7 of its quad (soft smoke texture). */
    static final float DENSE = 0.7f;

    /**
     * Opacity share of a chain billow ({@link Fx.Spec#chain}). Screen neighbour spacing — {@code gap · sin θ}
     * (θ — path/view-ray angle; eye distance cancels), dense billow width — {@code width}.
     * Gap between billows (width at most spacing) — no billow: a chain of dots; at two-step width, where every point
     * is covered by two billows, fully visible.
     */
    static float chainShare(float width, float gap, float sin) {
        float step = gap * sin;
        if (step <= 1e-6f) return 1;
        float t = Mth.clamp(width / step - 1, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Add custom quad to the frame (camera-relative coordinates); skip transparent quads. */
    void emit(FxFrame frame, FxQuads out, float partial) {
        update(partial);
        if (aCol <= 0.004f) return;
        double px = Mth.lerp(partial, xo, x) - frame.cam.x, py = Mth.lerp(partial, yo, y) - frame.cam.y, pz = Mth.lerp(partial, zo, z) - frame.cam.z;
        double d = Math.sqrt(px * px + py * py + pz * pz);
        // same air haze as distant visuals; toward the render boundary distant visuals replace the particle
        float a = aCol * frame.haze(d, py) * frame.near(d);
        if (a <= 0.004f) return;
        if (chainGap > 0) {
            double cos = d < 1e-6 ? 1 : (px * chainX + py * chainY + pz * chainZ) / d;
            a *= chainShare(2 * DENSE * quadSize, chainGap, (float) Math.sqrt(Math.max(0, 1 - cos * cos)));
            if (a <= 0.004f) return;
        }
        int light = lightColor(partial);
        float fog = frame.fog(px, py, pz);
        // light already opacity-premultiplied; sparks and flashes only emit (additive light)
        float r = rCol * a, g = gCol * a, b = bCol * a, cover = kind.additive ? 0 : a;
        FxAtlas.Sprite sprite = sprite(partial);
        // layer part (GPU timing by part): smoky debris tails, fire and ball, other particles
        FxQuads.Part was = out.part(budget == FxBudget.DEBRIS ? FxQuads.Part.DEBRIS
                : kind == Fx.Kind.FIRE || kind == Fx.Kind.FIREBALL ? FxQuads.Part.FIRE : FxQuads.Part.PARTICLES);
        out.nearestTexels(!smooth);
        out.opacity(a);
        // emissive matter blooms (FxBloom): fire, sparks, flash, ball; smoke, dust, ring and ash do not
        out.emission(kind.lit ? 0 : hdr);
        switch (kind) {
            case SPARK -> streak(out, sprite, (float) px, (float) py, (float) pz, (float) d, r, g, b, light, fog);
            case FLAKE -> {
                // tumbling: one quad axis alternates between full width and edge-on
                float turn = Mth.lerp(partial, oRoll, roll);
                float edge = Math.max(0.12f, Math.abs(Mth.cos(turn * 2.3f + variant)));
                out.billboard((float) px, (float) py, (float) pz, (float) d, quadSize, quadSize * edge * (paper ? 0.7f : 1), turn, sprite, r, g, b, cover,
                        0, 1, light, fog);
            }
            case RING -> ring(out, sprite, (float) px, (float) py, (float) pz, (float) d, r, g, b, cover, light, fog);
            default -> {
                // fireball is a volume: quad at the front half, as at distance (FarSprites.BALL_FRONT)
                float k = kind == Fx.Kind.FIREBALL ? (float) FarSprites.front(d, FarSprites.BALL_FRONT * quadSize / FarSprites.FIREBALL) : 1;
                out.billboard((float) px * k, (float) py * k, (float) pz * k, (float) d, quadSize * k, Mth.lerp(partial, oRoll, roll), sprite, r, g, b, cover,
                        quadSize * FxQuads.SOFT, 1, light, fog);
            }
        }
        out.emission(0);
        out.part(was);
    }

    private FxAtlas.Sprite sprite(float partial) {
        return switch (kind) {
            // dispersal stage: billow fades in the second half of life
            case SMOKE -> FxAtlas.smoke(variant * 4 + Mth.clamp((int) ((((float) age / life) - 0.35f) / 0.65f * 4), 0, 3));
            case FIRE -> FxAtlas.fire(variant);
            case SPARK -> FxAtlas.spark();
            case FLASH -> FxAtlas.flash();
            case RING -> FxAtlas.ring();
            case FIREBALL -> FxAtlas.ball((age + partial) / life);
            case FLAKE -> paper ? FxAtlas.paper() : FxAtlas.flake();
        };
    }

    /** Spark: backward streak over {@code streak} ticks' path, fading tail; short streak — normal quad. */
    private void streak(FxQuads out, FxAtlas.Sprite sprite, float px, float py, float pz, float d, float r, float g, float b,
                        int light, float fog) {
        Vector3f dir = new Vector3f((float) xd, (float) yd, (float) zd).mul(-streak);
        float len = dir.length(), w = quadSize;
        if (len < w * 1.5f) {
            out.billboard(px, py, pz, d, w, 0, sprite, r, g, b, 0, w, 1, light, fog);
            return;
        }
        Vector3f side = new Vector3f(dir).cross(-px, -py, -pz);
        if (side.lengthSquared() < 1e-8f) return;
        side.normalize().mul(w);
        float tx = px + dir.x, ty = py + dir.y, tz = pz + dir.z;
        float u0 = sprite.u0(), u1 = sprite.u1(), v0 = sprite.v0(), v1 = sprite.v1(), um = (u0 + u1) / 2;
        out.quad(d);
        out.vertex(px - side.x, py - side.y, pz - side.z, u0, v1, r, g, b, 0, w, 1, light, fog);
        out.vertex(px + side.x, py + side.y, pz + side.z, u0, v0, r, g, b, 0, w, 1, light, fog);
        // tail thinner than the head and fading: luminous trail
        out.opacity(0);
        out.vertex(tx + side.x * 0.3f, ty + side.y * 0.3f, tz + side.z * 0.3f, um, v0, 0, 0, 0, 0, w, 1, light, fog);
        out.vertex(tx - side.x * 0.3f, ty - side.y * 0.3f, tz - side.z * 0.3f, um, v1, 0, 0, 0, 0, w, 1, light, fog);
    }

    /** Shock ring: flat, on the ground (visible from above and below — no face culling). */
    private void ring(FxQuads out, FxAtlas.Sprite sprite, float px, float py, float pz, float d, float r, float g, float b, float cover,
                      int light, float fog) {
        // soft edge thinner than height above ground (0.25–0.4): viewed from above, ring/ground ray separation is fractions of a block
        float s = quadSize, soft = 0.125f;
        float u0 = sprite.u0(), u1 = sprite.u1(), v0 = sprite.v0(), v1 = sprite.v1();
        out.quad(d);
        out.vertex(px - s, py, pz - s, u0, v0, r, g, b, cover, soft, 1, light, fog);
        out.vertex(px - s, py, pz + s, u0, v1, r, g, b, cover, soft, 1, light, fog);
        out.vertex(px + s, py, pz + s, u1, v1, r, g, b, cover, soft, 1, light, fog);
        out.vertex(px + s, py, pz - s, u1, v0, r, g, b, cover, soft, 1, light, fog);
    }
}
