package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Vector3f;
import org.joml.Vector4f;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Client pall after the shock wave ({@link PallField}): what the effects layer renders ({@link #frame} — up to two most
 * noticeable detonations, table and frame light for {@link PallPass}, with frame mushrooms in the same rays, {@link MushroomField}),
 * and what pall does at the camera — muffles sound ({@link #muffle}) and sheds ash ({@link NukeAsh}). No separate packet:
 * the field follows the detonation model.
 */
public final class NukePall {
    /** Noise tile at 15 kt, blocks per scale meter: billows 60 m, small edges 20. */
    static final double TILE_M = 256;
    /** Dust density at camera (1/block) → muffling: 0.9 in the densest dust (visibility 40 m). */
    static final double MUFFLE_PER_SIGMA = 25;

    private static final PallPass.Frame FRAME = new PallPass.Frame();
    private static final float[] ROW = new float[PallField.ROWS * PallPass.WIDTH * 4];
    private static final double[] DRIFT = new double[2], SURGE = new double[3];
    private static final Vector3f LO = new Vector3f(), HI = new Vector3f(), CULL = new Vector3f();
    private static final float[] GLOW = new float[8];
    /** Slot dust-wall parameters ({@link BlastWall#slot}) for the frame and camera. */
    private static final float[] WALL = new float[BlastWall.SLOT], WALL_TICK = new float[BlastWall.SLOT];
    /** Thick-dust color ({@code DUST} in {@code fx_pall.glsl}, dust-wall color). */
    public static final double[] DUST = {0.60, 0.53, 0.45};
    /** Thick-smoke color above fires ({@code SMOKE} in {@code fx_pall.glsl}) and on-screen fire flame ({@code FIRE}). */
    static final double[] SMOKE = {0.30, 0.27, 0.24}, FIRE = {1.0, 0.541, 0.180};
    /** Dust asymmetry after delta-Eddington and per-channel dust and smoke albedos ({@code PALL_DUST_G}, {@code PALL_DUST_W}, {@code PALL_SMOKE_W}). */
    private static final double DUST_G = PallLight.truncated(PallLight.DUST_G);
    private static final double[] DUST_W = albedo(DUST), SMOKE_W = albedo(SMOKE);
    /** Frame light and wall for camera dust ({@link #dustColor}). */
    private static final Sky SKY = new Sky();
    private static final float[] DUST_WALL = new float[BlastWall.SLOT], DUST_GLOW = new float[8];
    /** Frame-slot detonations for the firestorm after frame lighting. */
    private static final ClientNuclear.Active[] SLOTTED = new ClientNuclear.Active[2];
    /**
     * Frustum planes used to cull pall — all except far: the far plane is 4 render distances,
     * while pall is visible farther (above Distant Horizons LOD, on the horizon).
     */
    private static final int SIDES_AND_NEAR = FrustumIntersection.PLANE_MASK_NX | FrustumIntersection.PLANE_MASK_PX
            | FrustumIntersection.PLANE_MASK_NY | FrustumIntersection.PLANE_MASK_PY | FrustumIntersection.PLANE_MASK_NZ;
    /** Camera extinction (1/block), its mist component, and muffling 0..1 for the sound thread. */
    private static volatile float sigma, mist, muffle;
    /** Vertical dust optical depth at camera, above and below down to ground (pall and wall head): ray reach ({@link #frame}). */
    private static volatile float tauUp, tauDown;
    private static volatile double earX, earY, earZ;

    private NukePall() {}

    /** Quality from player settings. */
    static PallPass.Quality quality() {
        return switch (IncomingConfig.CLIENT.nukeDustQuality.get()) {
            case OFF -> PallPass.Quality.OFF;
            case LOW -> PallPass.Quality.LOW;
            case MEDIUM -> PallPass.Quality.MEDIUM;
            case HIGH -> PallPass.Quality.HIGH;
        };
    }

    /**
     * Whether pall muffles sounds: only while on screen (not OFF), and with "Muffle distant and occluded sounds"
     * enabled (Sound Physics Remastered does not override it: it has no dust knowledge).
     */
    static boolean muffles() {
        return quality() != PallPass.Quality.OFF && IncomingConfig.CLIENT.soundMuffling.get();
    }

    /** Client tick: pall at camera — sound and ash; volume noise (pall and mushroom) in the background from the first detonation. */
    static void tick(ClientLevel level, Vec3 cam) {
        List<ClientNuclear.Active> all = ClientNuclear.detonations();
        FireStorms.tick(all);
        if (all.isEmpty()) {
            unmuffle();
            sigma = mist = tauUp = tauDown = 0;
            NukeAsh.tick(level, cam, 0, 0, null);
            return;
        }
        PallPass.prepare();
        double s = 0, wet = 0, densest = 0, dust = 0, headMax = 0, headWet = 0, up = 0, down = 0;
        // ash and wind from the detonation with densest camera pall; screen veil from the densest wall head
        ClientNuclear.Active nearest = null, headAt = null;
        for (ClientNuclear.Active a : all) {
            double ticks = a.ticks(0);
            Detonation d = a.d;
            double dx = cam.x - d.burst().x, dz = cam.z - d.burst().z;
            // eye above ground zero and terrain below it (pall map)
            double eye = cam.y - d.groundY(), floor = a.ground.at(cam.x, cam.z);
            // dust-wall head at camera — mean, as in the frame (terrain and water below camera from pall map)
            double head = 0, wetHere = 0;
            if (a.wall.slot(a.seconds(0), WALL_TICK) && WALL_TICK[3] > 0) {
                wetHere = a.ground.hasWater() ? a.ground.water(cam.x, cam.z) : 0;
                head = BlastWall.sigma(WALL_TICK, dx, dz, eye - floor, wetHere);
                up += BlastWall.tau(WALL_TICK, dx, dz, eye - floor, Double.POSITIVE_INFINITY, wetHere);
                down += BlastWall.tau(WALL_TICK, dx, dz, 0, eye - floor, wetHere);
                if (head > headMax) {
                    headMax = head;
                    headWet = wetHere;
                    headAt = a;
                }
            }
            boolean pall = a.pall.visible(ticks);
            if (!pall && head <= 0) continue;
            PallField.Water water = water(a);
            double here = head + (pall ? a.pall.sigma(dx, dz, eye, floor, ticks, water) : 0);
            if (pall) {
                up += a.pall.tau(dx, dz, eye, Double.POSITIVE_INFINITY, floor, ticks, water);
                down += a.pall.tau(dx, dz, floor, eye, floor, ticks, water);
            }
            if (here > densest) {
                densest = here;
                nearest = a;
            }
            s += here;
            // over water, head is spray
            wet += head * wetHere + (pall ? a.pall.mist(dx, dz, eye, floor, ticks, water) : 0);
            dust = Math.max(dust, a.pall.ashRate(dx, dz, ticks, water));
        }
        // no underground dust: at eye use outdoor-air fraction, like the pall ray (sky light / 15, fx/layer/OpenAir; all air in a
        // skyless dimension) — in a mine tunnel dust does not muffle sound, clip the ray or form a camera veil
        double open = level.dimensionType().hasSkyLight() ? level.getBrightness(LightLayer.SKY, BlockPos.containing(cam)) / 15.0 : 1;
        s *= open;
        wet *= open;
        headMax *= open;
        sigma = (float) s;
        mist = (float) wet;
        tauUp = (float) up;
        tauDown = (float) down;
        if (headAt != null && !drawsHead(headAt, cam)) NukeDust.veil(headAt, cam, headMax, headWet > 0.5);
        if (muffles()) {
            // muffling changes smoothly: pall covers over seconds
            float target = (float) (1 - Math.exp(-MUFFLE_PER_SIGMA * s));
            muffle += (target - muffle) * 0.1f;
        } else {
            unmuffle();
        }
        earX = cam.x;
        earY = cam.y;
        earZ = cam.z;
        // debris comes from dust: spray haze carries none
        NukeAsh.tick(level, cam, dust, s - wet, nearest);
    }

    /** Water fraction beneath detonation pall (dx, dz from ground zero), from its {@link PallGround} map. */
    static PallField.Water water(ClientNuclear.Active a) {
        double x0 = a.d.burst().x, z0 = a.d.burst().z;
        return (dx, dz) -> a.ground.water(x0 + dx, z0 + dz);
    }

    static void reset() {
        unmuffle();
        sigma = mist = tauUp = tauDown = 0;
        PallPass.reset();
        NukeAsh.reset();
    }

    /** Clear pall muffling at once (replay seek, logout, setting): loops receive a filter without it. */
    private static void unmuffle() {
        if (muffle > 0.01f) SoundFilters.restoreLoops();
        muffle = 0;
    }

    /**
     * How much pall muffles this sound: 0 — none, up to 0.9 in the densest dust, from ear density and path through dust
     * (not for sound at the ear or UI; pall disabled or distant sound not muffled — {@link #muffles}). Called from
     * the sound thread.
     */
    public static float muffle(SoundInstance sound) {
        float m = muffle;
        if (m <= 0.01f || sound.getSource() == SoundSource.MUSIC || sound.getSource() == SoundSource.RECORDS
                || sound.getSource() == SoundSource.MASTER && sound.isRelative()) return 0;
        double d = sound.isRelative() ? Math.sqrt(sound.getX() * sound.getX() + sound.getY() * sound.getY() + sound.getZ() * sound.getZ())
                : Math.sqrt(Mth.square(sound.getX() - earX) + Mth.square(sound.getY() - earY) + Mth.square(sound.getZ() - earZ));
        return m * (float) (1 - Math.exp(-sigma * Math.max(8, d)));
    }

    /**
     * Frame pall and mushrooms for the effects layer: pall from up to two detonations nearest the camera by their pall or wall-head edge
     * ({@link #outside}), among those
     * with anything in their table (empty before and after the front) and bounds in frame ({@code frustum} — eye frustum
     * without far plane); mushrooms — {@link MushroomField#frame}. Pall disabled in settings means
     * mushrooms only at LOW (no alternative mushroom). null — no pall or mushroom in frame, nor a ball lighting the world
     * ({@link MushroomField#balls}): the layer spends no passes on them.
     */
    @Nullable
    public static PallPass.Frame frame(ClientLevel level, Vec3 eye, float partial, FrustumIntersection frustum) {
        if (!PallPass.available()) return null;
        List<ClientNuclear.Active> all = ClientNuclear.detonations();
        if (all.isEmpty()) return null;
        PallPass.Quality q = quality();
        PallPass.Frame f = FRAME;
        double range = Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial)), air = Sight.KOSCHMIEDER / range;
        // air to mushrooms, pall and wall in model meters (all detonations share one scale)
        MushroomField.air(all.get(0).d, eye, range, f.air);
        // frame light before mushrooms: by day include mushrooms whose shadow enters the frame (MushroomField.frame)
        light(level, eye, partial, f);
        f.mushroomCount = MushroomField.frame(all, eye, partial, frustum, f.sun, level.getMinBuildHeight(), level.getMaxBuildHeight(), f.mushrooms);
        // ball light on world from all detonations: a ball behind the viewer lights ground ahead
        openSky(level, f.openSky, f.faceShade);
        double open = 0.2126 * PallLight.linear(f.openSky.x) + 0.7152 * PallLight.linear(f.openSky.y) + 0.0722 * PallLight.linear(f.openSky.z);
        f.ballsLit = MushroomField.balls(all, eye, partial, air, f.exposure, open, f.balls, f.ballLight);
        List<ClientNuclear.Active> visible = new ArrayList<>();
        if (q != PallPass.Quality.OFF) for (ClientNuclear.Active a : all) if (visible(a, partial)) visible.add(a);
        if (visible.isEmpty() && f.mushroomCount == 0 && !f.ballsLit) return null;
        visible.sort(Comparator.comparingDouble(a -> outside(a, eye, partial)));
        f.quality = q == PallPass.Quality.OFF ? PallPass.Quality.LOW : q;
        f.slot0.zero();
        f.slot1.zero();
        f.glow0.zero();
        f.glow1.zero();
        f.hot0.zero();
        f.hot1.zero();
        f.ground0.clear();
        f.ground1.clear();
        f.water0.zero();
        f.water1.zero();
        f.wall0.zero();
        f.wall1.zero();
        f.wilson0.zero();
        f.wilson1.zero();
        // frame world — chunks within render distance (chunk-edge margin): a ray into empty space crossed them without hitting anything
        f.floorFrom = Math.max(16, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 - 24);
        f.min.set(Float.MAX_VALUE);
        f.max.set(-Float.MAX_VALUE);
        int k = 0;
        float bottom = (float) (level.getMinBuildHeight() - eye.y);
        for (int i = 0; i < visible.size() && k < 2; i++) if (slot(visible.get(i), k, eye, partial, frustum, bottom, f)) SLOTTED[k++] = visible.get(i);
        if (k == 0 && f.mushroomCount == 0 && !f.ballsLit) return null;
        // without pall use zero bounds: the ray cannot enter (inverted bounds would span the entire ray)
        if (k == 0) {
            f.min.zero();
            f.max.zero();
        }
        MushroomField.fire(partial, f);
        float clouds = level.effects().getCloudHeight();
        FireStorms.frame(SLOTTED, k, eye, partial, frustum, Float.isNaN(clouds) ? 1e6f : (float) (clouds - eye.y), f);
        // ray reach from eye dust and optical depth above and below it (pallClip); disabled pall is not rendered, and
        // does not hide the mushroom behind it
        if (q == PallPass.Quality.OFF) f.eyeDust.zero();
        else f.eyeDust.set(sigma, tauUp, tauDown);
        return f;
    }

    /**
     * Pall-pass frame without the nuke, for conventional explosion smoke ({@code client/fx/smoke/BlastClouds}): air, sun, sky,
     * on-screen sky light and eye adaptation match {@link #frame}; no pall, mushrooms, balls, storm, wall or water (their
     * parameters are zero). Quality matches mushrooms with pall disabled: at least LOW (no alternative smoke).
     */
    public static PallPass.Frame lightFrame(ClientLevel level, Vec3 eye, float partial) {
        PallPass.Frame f = FRAME;
        double range = Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial));
        Sight.Air a = Sight.Air.at(eye.y - ua.zentix.incoming.client.fx.layer.WaterDecal.SEA_LEVEL, range);
        f.air.set((float) a.haze(), (float) a.clean(), (float) a.weather(), (float) (1 / Sight.HAZE_HEIGHT));
        light(level, eye, partial, f);
        openSky(level, f.openSky, f.faceShade);
        java.util.Arrays.fill(f.mushrooms, 0f);
        f.mushroomCount = 0;
        f.balls.zero();
        f.ballLight.zero();
        f.ballsLit = false;
        PallPass.Quality q = quality();
        f.quality = q == PallPass.Quality.OFF ? PallPass.Quality.LOW : q;
        f.slot0.zero();
        f.slot1.zero();
        f.glow0.zero();
        f.glow1.zero();
        f.hot0.zero();
        f.hot1.zero();
        f.ground0.clear();
        f.ground1.clear();
        f.water0.zero();
        f.water1.zero();
        f.wall0.zero();
        f.wall1.zero();
        f.wilson0.zero();
        f.wilson1.zero();
        f.fire0.clear();
        f.fire1.clear();
        f.fireMin.set(1);
        f.fireMax.set(-1);
        f.whirlPos.zero();
        f.whirlShape.zero();
        f.whirlLean.zero();
        f.floorFrom = Math.max(16, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 - 24);
        f.min.zero();
        f.max.zero();
        f.eyeDust.zero();
        return f;
    }

    /**
     * Open-sky light on a white block as drawn by Minecraft, on screen, into {@code out}: frame lightmap
     * (sky 15, block light 0; time of day, brightness setting, potions, dimension), and dimension block-face shading
     * into {@code shade} (top, bottom, north and south, west and east). Used by frame output to infer world color under ball light:
     * a pixel divided by sky light and its face shading gives the block's own color.
     */
    private static void openSky(ClientLevel level, Vector3f out, Vector4f shade) {
        int c = Minecraft.getInstance().gameRenderer.lightTexture().lightPixels.getPixelRGBA(0, 15);
        out.set((c & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c >> 16 & 0xFF) / 255f);
        shade.set(level.getShade(Direction.UP, true), level.getShade(Direction.DOWN, true), level.getShade(Direction.NORTH, true),
                level.getShade(Direction.EAST, true));
    }

    /** Whether the detonation has anything to draw: pall, dust-wall head or Wilson cloud. */
    private static boolean visible(ClientNuclear.Active a, float partial) {
        double t = a.seconds(partial);
        return a.pall.visible(a.ticks(partial)) || a.wall.reach(t) > 0 || a.wall.wilsonReach(t) > 0;
    }

    /**
     * How far the camera is outside detonation pall and wall head (along ground), blocks; 0 — inside. Pall alone would make a head
     * that reached the camera beyond the pall lose its slot to detonations whose pall contains the camera, so it would not render at the eye.
     */
    private static double outside(ClientNuclear.Active a, Vec3 eye, float partial) {
        double r = Math.max(a.pall.extent(a.ticks(partial)), a.wall.reach(a.seconds(partial)));
        return Math.max(0, Math.hypot(eye.x - a.d.burst().x, eye.z - a.d.burst().z) - r);
    }

    /**
     * Slot k ({@code pall.glsl}): detonation field, table rows, base surge and bounds; false — slot unneeded (table
     * empty and no surge, or bounds out of frame), leaving it free for the next detonation. Land or water absent beneath pall
     * ({@link PallGround}) is also absent from the table: a detonation at open sea uses no slot once spray has settled and the surge
     * has faded. {@code bottom} is world bottom relative to eye: include a detonation even if only ground beneath the pall is visible,
     * because by day it receives the pall shadow ({@code fx_pall_composite}), and a valley or quarry below ground zero may be deeper than the pall bottom.
     */
    private static boolean slot(ClientNuclear.Active a, int k, Vec3 eye, float partial, FrustumIntersection frustum, float bottom,
                                PallPass.Frame f) {
        Detonation d = a.d;
        PallField p = a.pall;
        double ticks = a.ticks(partial);
        boolean water = a.ground.hasWater();
        p.row(ticks, a.ground.hasLand(), water, ROW);
        double top = 1;
        boolean any = false;
        for (int i = 0; i < PallField.ROWS * PallPass.WIDTH; i++) {
            // dust, spray or haze
            any |= ROW[4 * i] > 0;
            top = Math.max(top, ROW[4 * i + 1]);
        }
        if (p.surge(ticks, SURGE)) {
            any = true;
            top = Math.max(top, SURGE[1]);
        }
        // dust wall: head and Wilson cloud in the same volume
        double seconds = a.seconds(partial);
        boolean wall = a.wall.slot(seconds, WALL);
        any |= wall;
        if (!any) return false;
        p.drift(ticks, DRIFT);
        double plume = p.plume(ticks);
        float cx = (float) (d.burst().x - eye.x), cz = (float) (d.burst().z - eye.z), ground = (float) (d.groundY() - eye.y);
        // bounds: dust drift and haze plume in any direction from ground zero; valleys below it over full height; above
        // hills the layer starts at their slopes (pallField); wall head follows map terrain (pallWallSpans), Wilson cloud
        // surrounds the detonation point
        float r = (float) Math.max(p.extent(ticks), Math.max(a.wall.reach(seconds) + 1, a.wall.wilsonReach(seconds)));
        LO.set(cx - r, (float) (ground - top), cz - r);
        HI.set(cx + r, (float) (ground + Math.max(a.ground.high(), 0) + top * (1 + PallField.BUMPS) * 1.05), cz + r);
        if (WALL[3] > 0) {
            LO.y = Math.min(LO.y, ground + Math.min(a.ground.low(), 0) - 0.5f * WALL[2]);
            HI.y = Math.max(HI.y, ground + Math.max(a.ground.high(), 0) + WALL[2]);
        }
        if (WALL[18] > 0) HI.y = Math.max(HI.y, (float) (ground + WALL[19] + a.wall.wilsonReach(seconds)));
        CULL.set(LO.x, Math.min(LO.y, bottom), LO.z);
        if (frustum.intersectAab(CULL, HI, SIDES_AND_NEAR) >= 0) return false;
        System.arraycopy(ROW, 0, f.table, k * ROW.length, ROW.length);
        double ys = Math.cbrt(d.yieldKt() / 15);
        org.joml.Matrix4f m = k == 0 ? f.slot0 : f.slot1;
        m.m00(cx).m01(cz).m02(ground).m03((float) p.reach());
        m.m10((float) DRIFT[0]).m11((float) DRIFT[1]).m12((float) plume).m13(1);
        m.m20((float) Math.cos(d.windDir())).m21((float) Math.sin(d.windDir()));
        m.m30((float) (PallField.SIGMA_DENSE / d.scale())).m31((float) Math.max(48, d.blocks(TILE_M * ys))).m32((float) (ticks / 20.0));
        tableRows(m, k);
        // slot water: base surge, and whether water exists beneath pall (otherwise shader skips the water map)
        (k == 0 ? f.water0 : f.water1).set((float) SURGE[0], (float) SURGE[1], (float) SURGE[2], water ? 1 : 0);
        // ball and fire light on dust (CloudPuffs.pallLight)
        a.puffs.pallLight(MushroomField.shape(a, partial), GLOW);
        (k == 0 ? f.glow0 : f.glow1).set(GLOW[0], GLOW[1], GLOW[2], GLOW[3]);
        (k == 0 ? f.hot0 : f.hot1).set(GLOW[4], GLOW[5], GLOW[6], GLOW[7]);
        a.ground.frame(k == 0 ? f.ground0 : f.ground1);
        (k == 0 ? f.wall0 : f.wall1).set(WALL);
        (k == 0 ? f.wilson0 : f.wilson1).set(WALL[16], WALL[17], WALL[18], WALL[19]);
        f.min.min(LO);
        f.max.max(HI);
        return true;
    }

    /**
     * Frame sun, sky and fires. Sun and sky light are linear ({@link PallLight}); a white horizontal wall under
     * noon sun and sky is 1, like the top of a white block by day. Sky is Minecraft's drawn sky in linear space
     * ({@link PallLight#SKY}: about a fifth of daylight at noon); sun supplies the rest to white: scattering removes sky blue
     * from its beam, together they make white daylight. Sun light is its illumination of a white wall facing the sun; sky light
     * is its illumination of a white wall under the whole sky.
     */
    private static void light(ClientLevel level, Vec3 eye, float partial, PallPass.Frame f) {
        sky(level, eye, partial, SKY);
        f.sun.set(SKY.sx, SKY.sy, 0, SKY.day);
        f.skyLight.set(SKY.sky[0], SKY.sky[1], SKY.sky[2]);
        f.sunLight.set(SKY.sun[0], SKY.sun[1], SKY.sun[2]);
        f.base = SKY.base;
        f.exposure = SKY.exposure;
        f.glowOnSmoke = SKY.glowOnSmoke;
        // fire glow from below on white pall, on screen above sky light (fx_pall uses its difference, pallLift; dust
        // and haze use their own albedo): barely visible by day,
        // ten times brighter than night sky at night, but not like fire (brighter made all haze above the burning city
        // uniform orange)
        float fire = 0.6f * (1 - 0.75f * SKY.day);
        f.fireLight.set(fire, fire * 0.45f, fire * 0.16f);
        // on-screen thin-dust color (PallLight.thin) for layer quads with almost no pall in front
        float mu = Math.max(SKY.sy, 0.12f);
        f.ambient.set(thinDust(0, mu, f), thinDust(1, mu, f), thinDust(2, mu, f), 1);
    }

    /** Frame sun and sky ({@link #light}): sun direction and daylight strength, sun and sky light, on-screen sky light. */
    static final class Sky {
        /** Sun direction (x, y; z = 0) and daylight strength 0..1. */
        float sx, sy, day;
        /** Per-channel sun and sky light, linear: white horizontal wall at noon = 1. */
        final float[] sun = new float[3], sky = new float[3];
        /**
         * On-screen sky light (white wall), eye adaptation to it ({@code Sight.adapted}: mushroom glow and ball light
         * into the frame), and resulting fire glow strength on dust ({@link CloudPuffs#glowOnSmoke}).
         */
        float base, exposure, glowOnSmoke;
    }

    /** Sun and sky at {@code eye} into {@code into} (as in {@link #light}). */
    static void sky(ClientLevel level, Vec3 eye, float partial, Sky into) {
        float angle = level.getTimeOfDay(partial) * Mth.TWO_PI;
        into.sx = -Mth.sin(angle);
        into.sy = Mth.cos(angle);
        boolean sky = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        float day = sky ? smooth(-0.06f, 0.18f, into.sy) * (1 - 0.55f * level.getRainLevel(partial)) * (1 - 0.35f * level.getThunderLevel(partial)) : 0;
        into.day = day;
        Vec3 skyColor = level.getSkyColor(eye, partial);
        float night = 1 - day;
        // at night, no darker than sky light on wall and mushroom billows (FarView.ambient, 0.17 on screen): night sky color is almost
        // black, so pall behind the front would form a black curtain over the city while the dust wall and world remain visible
        float ambient = FarView.ambient(level, partial);
        float floor = (float) PallLight.linear(nightSky(ambient, night));
        into.sky[0] = skyLight(skyColor.x, floor);
        into.sky[1] = skyLight(skyColor.y, floor);
        into.sky[2] = skyLight(skyColor.z, floor);
        // low sun is redder (long path through air): screen 1, 0.63, 0.42 relative to noon
        float low = Mth.clamp(into.sy / 0.35f, 0, 1);
        into.sun[0] = sunLight(1, into.sky[0], day);
        into.sun[1] = sunLight((0.62 + 0.36 * low) / 0.98, into.sky[1], day);
        into.sun[2] = sunLight((0.38 + 0.52 * low) / 0.9, into.sky[2], day);
        // wall reflection and fire glow (on screen) add to on-screen sky light — this value
        into.base = ambient;
        // mushroom glow is a black body at real brightness (noon white wall = 1): into frame with eye adaptation to sky
        into.exposure = (float) Sight.adapted(1, ambient);
        into.glowOnSmoke = (float) CloudPuffs.glowOnSmoke(ambient);
    }

    /**
     * Pall renders detonation {@code a}'s wall head and dust at {@code eye} as a volume: quality not OFF, its shaders compiled
     * and noise intact, and detonation among the two pall slots in the frame ({@link #frame}: nearest pall or head edge,
     * list order breaks ties; no frustum — billows outlive the gaze). Otherwise a screen veil and camera billows
     * ({@link NukeDust}) show the head at the eye.
     */
    static boolean drawsHead(ClientNuclear.Active a, Vec3 eye) {
        if (quality() == PallPass.Quality.OFF || !PallPass.available()) return false;
        double mine = outside(a, eye, 0);
        int before = 0;
        boolean earlier = true;
        for (ClientNuclear.Active o : ClientNuclear.detonations()) {
            if (o == a) {
                earlier = false;
                continue;
            }
            if (!visible(o, 0)) continue;
            double theirs = outside(o, eye, 0);
            if (theirs < mine || theirs == mine && earlier) before++;
        }
        return before < 2;
    }

    /**
     * On-screen wall-head dust color at world (x, y, z), r, g, b in 0..1, into {@code into} ({@link #dustLight}): camera dust
     * ({@link NukeDust}) matches the wall itself there. false — no wall.
     */
    static boolean dustColor(ClientNuclear.Active a, Sky sky, double x, double y, double z, float[] into) {
        double t = a.seconds(0);
        if (!a.wall.slot(t, DUST_WALL) || DUST_WALL[3] <= 0) return false;
        a.puffs.pallLightNow(a, DUST_GLOW);
        Detonation d = a.d;
        double gy = y - d.groundY();
        dustLight(DUST_WALL, DUST_GLOW, sky, d.windDir(), x - d.burst().x, gy, z - d.burst().z, gy - a.ground.at(x, z), into);
        return true;
    }

    /**
     * On-screen wall-head dust color, as lit by {@code fx_pall} ({@code wall}, light in {@code main}), for the mean
     * head without billows (their shadows go both ways, averaging to zero), outside water (camera spray over water,
     * {@link NukeWater}): diffuse sun and sky light at the smaller optical depth to head top or leading
     * edge; ball light beneath the nearest ball-lit side, toward ball or from above if its beam to the top over the point does not pass through
     * the head (ball behind the head, closer to ground zero and low); sun and ball beams pass through head optical depth toward them; fires
     * light from below. Thus by day dust is brown in sun and sky light rather than ball light from behind the wall;
     * at night, the wall side away from the ball is in shadow, the top in its light. Beam phase is averaged over directions: the billow is seen from
     * every side.
     *
     * @param wall  slot wall ({@link BlastWall#slot})
     * @param glow  wall light ({@link CloudPuffs#pallLight})
     * @param wind  wind direction (radians, {@link Detonation#windDir}): cap center drifts along it
     * @param gx    point x from ground zero (blocks), gy above ground zero, gz along z
     * @param up    point height above its terrain (blocks)
     */
    static void dustLight(float[] wall, float[] glow, Sky sky, double wind, double gx, double gy, double gz, double up, float[] into) {
        double rx = gx, rz = gz, len = Math.hypot(rx, rz);
        double top = wall[2], reach = BlastWall.HANDOFF_TO * wall[1] + top, s = wall[0] - len, toTop = Math.max(top - up, 0);
        double ox = len > 1e-3 ? rx / len : 1, oz = len > 1e-3 ? rz / len : 0;
        // above point, to local wedge top; toward edge, until wedge falls below point; toward light, no higher than head top (fx_pall: wall)
        double above = chord(wall, rx, rz, up, 0, 1, 0, Math.max(top * BlastWall.top(s / Math.max(wall[1], 1e-3)) - up, 0), 4);
        double side = chord(wall, rx, rz, up, ox, 0, oz, Math.max(s - wall[1] * Math.pow(Mth.clamp(up / Math.max(top, 1e-3), 0, 1), 1.2), 0), 4);
        double below = chord(wall, rx, rz, up, 0, -1, 0, Math.max(up, 0), 2);
        double depth = Math.min(above, side), slab = depth + below;
        double toSun = chordNear(wall, rx, rz, up, sky.sx, sky.sy, 0, toLight(sky.sy, toTop, reach), 4);
        double lx = Math.cos(wind) * glow[2] - gx, ly = glow[7] - gy, lz = Math.sin(wind) * glow[2] - gz;
        double ll = Math.max(Math.sqrt(lx * lx + ly * ly + lz * lz), 1e-3);
        double toBall = chordNear(wall, rx, rz, up, lx / ll, ly / ll, lz / ll, toLight(ly / ll, toTop, reach), 4);
        // diffuse ball light enters through the nearest ball-lit head side (fx_pall: capDeep): toward ball, its optical depth;
        // from above, optical depth over the point plus head optical depth in front of the ball still crossed by its beam toward the top above the point
        // (ball low and behind head: the beam to the top at the leading edge crosses the whole head)
        double muS = Math.max(sky.sy, 0.12), muB = Mth.clamp(ly / ll, 0.12, 1);
        double deepB = Math.min(toBall, above + Math.max(toBall - above / muB, 0)), slabB = deepB + below;
        double r = Math.hypot(gx, gz), fireR = Math.max(glow[3], 1e-3);
        // ball light is a beam from cap center, like the mushroom body (CloudPuffs.ball), × eye adaptation
        double lit = CloudPuffs.ball(sky.exposure, glow[0], ll);
        double burn = CloudPuffs.burn(r, 0, glow[1], glow[3]) * (1 - 0.75 * sky.day);
        double smoke = 0.77 * (1 - CloudPuffs.smooth(0.92 * fireR, 1.04 * fireR, r));
        double[] lin = new double[3];
        for (int c = 0; c < 3; c++) {
            double w = Mth.lerp(smoke, DUST_W[c], SMOKE_W[c]);
            double fire = FIRE[c];
            double ball = glow[4 + c] * lit;
            double field = PallLight.diffuse(w, DUST_G, depth, slab, PallLight.LAND, muS, sky.sun[c], 1, 0, sky.sky[c], 0, 0)
                    + PallLight.diffuse(w, DUST_G, deepB, slabB, PallLight.LAND, muS, 0, muB, ball, 0, 0, 0);
            double beams = 0.25 * (sky.sun[c] * Math.exp(-toSun) + ball * Math.exp(-toBall));
            double from = 0;
            if (burn > 0) {
                double flame = PallLight.flame(sky.base, fire * burn * sky.glowOnSmoke / DUST[c], fire);
                from = Math.min(Math.max(PallLight.diffuse(w, DUST_G, depth, slab, PallLight.LAND, 1, 0, 1, 0, 0, flame, 0), 0), PallLight.linear(fire));
            }
            lin[c] = w * (Math.max(field, 0) + beams + from);
        }
        // to screen like pall (fx_pall: toward white, shoulder)
        PallLight.screen(lin, into);
    }

    /**
     * Table rows of slot k ({@link PallField#row}) in its mat4 (column-major, like shader {@code mat4}): dust — S[2].z, haze
     * — S[2].w, spray — S[3].w ({@code pallField}).
     */
    static void tableRows(org.joml.Matrix4f m, int k) {
        m.m22(tableRow(k, PallField.DUST_ROW)).m23(tableRow(k, PallField.HAZE_ROW)).m33(tableRow(k, PallField.SPRAY_ROW));
    }

    /** Center of slot k table row {@code row} in the pall-table texture, height 0..1. */
    static float tableRow(int k, int row) {
        return (PallField.ROWS * k + row + 0.5f) / PallPass.ROWS;
    }

    /**
     * Ray length toward light through the head: along direction with rise {@code vy}, up to its top ({@code toTop} above point), capped at
     * {@code reach}; nearly along ground — {@code reach}. Wedge is low and long: steps across its full length would pass above it.
     */
    private static double toLight(double vy, double toTop, double reach) {
        return vy > 0.05 ? Math.min(reach, toTop / vy) : reach;
    }

    /** Mean wall-head optical depth ({@link BlastWall#sigma}) from point along (vx, vy, vz) for length len, n steps ({@code wallChord}). */
    private static double chord(float[] wall, double rx, double rz, double up, double vx, double vy, double vz, double len, int n) {
        double sum = 0, ds = len / n;
        for (int i = 0; i < n; i++) {
            double l = (i + 0.5) * ds;
            sum += BlastWall.sigma(wall, rx + vx * l, rz + vz * l, up + vy * l, 0);
        }
        return sum * ds;
    }

    /** {@link #chord}, with cells denser near the point (boundaries quadratic in index), like {@code wallChordNear}: toward light. */
    private static double chordNear(float[] wall, double rx, double rz, double up, double vx, double vy, double vz, double len, int n) {
        double sum = 0, nn = n * n;
        for (int i = 0; i < n; i++) {
            double a = i * i / nn, b = (i + 1) * (i + 1) / nn, l = 0.5 * (a + b) * len;
            sum += BlastWall.sigma(wall, rx + vx * l, rz + vz * l, up + vy * l, 0) * (b - a);
        }
        return sum * len;
    }

    /**
     * On-screen thin-dust color in channel c for the frame: light it scatters ({@link PallLight#thin}, albedo from linear
     * dust color in {@code fx_pall}) per unit extinction — its source function, converted to a screen value.
     */
    private static float thinDust(int c, float mu, PallPass.Frame f) {
        return (float) PallLight.display(PallLight.thin(DUST_W[c], f.sunLight.get(c), f.skyLight.get(c), mu, PallLight.LAND));
    }

    /** Per-channel albedo ω' from on-screen thick-layer color ({@link PallLight#albedo}). */
    private static double[] albedo(double[] display) {
        double[] w = new double[3];
        for (int c = 0; c < 3; c++) w[c] = PallLight.albedo(PallLight.linear(display[c]), DUST_G);
        return w;
    }

    /** Channel sky light: Minecraft screen sky color converted to linear, fraction {@link PallLight#SKY}, at least floor. */
    static float skyLight(double display, float floor) {
        return Math.max((float) (PallLight.SKY * PallLight.linear(display)), floor);
    }

    /** Channel sun light: remainder up to white after sky sky, reddening red (on screen), daylight strength day. */
    static float sunLight(double red, float sky, float day) {
        return day * (float) (PallLight.linear(red) * Math.max(1 - sky, 0));
    }

    /** On-screen sky light on pall at night is at least this ({@code night} — night fraction 0..1): like sky light on wall billows. */
    static float nightSky(float ambient, float night) {
        return ambient * night;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** Firestorm in frame on or off: client scenario measures pall with and without it (always on in game). */
    public static void firestorm(boolean on) {
        FireStorms.enabled = on;
    }

    /** Pall extinction at camera, 1/block (for the scenario line). */
    public static float sigma() {
        return sigma;
    }

    /** Scenario line: pall at camera and in frame. */
    public static String describe() {
        Minecraft mc = Minecraft.getInstance();
        PallPass.Frame f = FRAME;
        List<ClientNuclear.Active> all = ClientNuclear.detonations();
        String under = "-";
        if (!all.isEmpty()) {
            // water where camera pall was lofted (point minus drift) determines its material at the camera
            ClientNuclear.Active a = all.getLast();
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            double[] w = new double[2], from = new double[2];
            a.pall.drift(a.ticks(0), w);
            a.pall.source(cam.x - a.d.burst().x, cam.z - a.d.burst().z, a.ticks(0), from);
            under = String.format(java.util.Locale.ROOT, "%.2f water (land %b, water %b), wind to %.0f° drift %.0f blocks, water where the pall at the camera rose %.2f",
                    a.ground.waterShare(), a.ground.hasLand(), a.ground.hasWater(), Math.toDegrees(a.d.windDir()), Math.hypot(w[0], w[1]),
                    a.ground.water(a.d.burst().x + from[0], a.d.burst().z + from[1]));
        }
        return String.format(java.util.Locale.ROOT, "sigma at camera %.4f /block (visibility %.0f blocks; mist %.4f), depth up %.2f down %.2f, muffle %.2f, ash %d, quality %s, "
                        + "light: sky %.3f, fires %.2f × %.2f, ball r %.0f blocks, black body %.3g %.3g %.3g, under the pall: %s, surge r %.0f top %.0f sigma %.4f, "
                        + "frames: %s; mushrooms %d (exposure %.0f); firestorm: %s",
                sigma, sigma > 1e-5f ? 3.912 / sigma : Double.POSITIVE_INFINITY, mist, tauUp, tauDown, muffle, NukeAsh.alive(), quality(),
                f.skyLight.y, f.glowOnSmoke, f.glow0.y, f.glow0.x, f.hot0.x, f.hot0.y, f.hot0.z, under, f.water0.x, f.water0.y, f.water0.z,
                mc.level == null ? "-" : PallPass.describe(), f.mushroomCount, f.exposure, FireStorms.describe());
    }
}
