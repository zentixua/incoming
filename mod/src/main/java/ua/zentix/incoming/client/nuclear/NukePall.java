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
    private static final double[] DRIFT = new double[2];
    /** Ground box of the drawn smoke parcels around ground zero ({@link PallField#hazeBounds}). */
    private static final double[] BAND = new double[4];
    /**
     * Smoke parcels are marched no farther from the eye than this many optical depths of the frame's air: behind it a tenth of a
     * percent of their light reaches the eye ({@code pallAir}), and a box over the whole band (up to ~100 km) would spread the
     * ray's steps thin over the near haze.
     */
    static final double AIR_DEPTH = 6.9;
    /** {@link #AIR_DEPTH} in blocks this frame (clear air: 35 km). */
    private static double airReach = 1e9;
    private static final Vector3f LO = new Vector3f(), HI = new Vector3f(), CULL = new Vector3f();
    private static final float[] GLOW = new float[8];
    /** Slot dust-wall parameters ({@link BlastWall#slot}): the Wilson cloud for the frame, the head at the camera. */
    private static final float[] WALL = new float[BlastWall.SLOT], WALL_TICK = new float[BlastWall.SLOT];
    /** Thick-dust color ({@code DUST} in {@code fx_pall.glsl}, dust-wall color): the reference dry loamy soil under grass and forest. */
    public static final double[] DUST = {0.60, 0.53, 0.45};
    /**
     * Desert-dust single-scattering albedo ω at the red, green and blue display channels (610, 550, 465 nm), linear between
     * AERONET desert-dust ω 0.92 at 440 nm and 0.97 at 670 nm (Dubovik et al. 2002, J. Atmos. Sci. 59: Bahrain, Solar Village, Cape Verde),
     * and its thick-layer color ({@code SAND} in {@code fx_pall.glsl}: R∞ of ω after delta-Eddington with g = {@link PallLight#DUST_G}):
     * dust lofted from sand.
     */
    static final double[] SAND_OMEGA = {0.957, 0.944, 0.925}, SAND = {0.705, 0.669, 0.626};
    /** Thick-smoke color above fires ({@code SMOKE} in {@code fx_pall.glsl}) and on-screen fire flame ({@code FIRE}). */
    static final double[] SMOKE = {0.30, 0.27, 0.24}, FIRE = {1.0, 0.541, 0.180};
    /** Dust asymmetry after delta-Eddington and per-channel dust and smoke albedos ({@code PALL_DUST_G}, {@code PALL_DUST_W}, {@code PALL_SMOKE_W}). */
    private static final double DUST_G = PallLight.truncated(PallLight.DUST_G);
    private static final double[] DUST_W = albedo(DUST), SAND_W = albedo(SAND), SMOKE_W = albedo(SMOKE);
    /** Sun through the air for {@link #sky} (client thread). */
    private static final double[] THROUGH_AIR = new double[3];
    /** Frame light ({@link #frame}). */
    private static final Sky SKY = new Sky();
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
    public static PallPass.Quality quality() {
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
        double s = 0, wet = 0, densest = 0, dust = 0, up = 0, down = 0;
        // ash and wind from the detonation with densest camera pall
        ClientNuclear.Active nearest = null;
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
                double hard = a.ground.hasHard() ? a.ground.hard(cam.x, cam.z) : 0;
                head = BlastWall.sigma(WALL_TICK, dx, dz, eye - floor, wetHere, hard);
                up += BlastWall.tau(WALL_TICK, dx, dz, eye - floor, Double.POSITIVE_INFINITY, wetHere, hard);
                down += BlastWall.tau(WALL_TICK, dx, dz, 0, eye - floor, wetHere, hard);
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
        // skyless dimension) — in a mine tunnel dust does not muffle sound or clip the ray
        double open = level.dimensionType().hasSkyLight() ? level.getBrightness(LightLayer.SKY, BlockPos.containing(cam)) / 15.0 : 1;
        s *= open;
        wet *= open;
        sigma = (float) s;
        mist = (float) wet;
        tauUp = (float) up;
        tauDown = (float) down;
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

    /** Water and hard-ground fractions beneath detonation pall (dx, dz from ground zero), from its {@link PallGround} map. */
    static PallField.Water water(ClientNuclear.Active a) {
        double x0 = a.d.burst().x, z0 = a.d.burst().z;
        PallGround g = a.ground;
        return new PallField.Water() {
            @Override
            public double at(double dx, double dz) {
                return g.water(x0 + dx, z0 + dz);
            }

            @Override
            public double hard(double dx, double dz) {
                return g.hasHard() ? g.hard(x0 + dx, z0 + dz) : 0;
            }
        };
    }

    static void reset() {
        unmuffle();
        sigma = mist = tauUp = tauDown = 0;
        java.util.Arrays.fill(SLOTTED, null);
        FRAME.dustWall.source = null;
        FRAME.dustWall.active = false;
        FRAME.ground0.clear();
        FRAME.ground1.clear();
        FRAME.fire0.clear();
        FRAME.fire1.clear();
        ua.zentix.incoming.client.fx.layer.DustWallPass.clearHistory();
        ua.zentix.incoming.client.fx.layer.DustWallPass.clearSlots();
        DustSources.reset();
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
     * Frame pall and mushrooms for the effects layer: pall slots for up to two detonations nearest the camera by their haze or Wilson
     * cloud edge ({@link #outside}), among those with haze in their table, a Wilson cloud, a mushroom or a firestorm ({@link #slot})
     * and bounds in frame ({@code frustum} — eye frustum without far plane); mushrooms — {@link MushroomField#frame}. Pall disabled in settings means
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
        airReach = AIR_DEPTH / air;
        // air to mushrooms, pall and wall in model meters (all detonations share one scale)
        MushroomField.air(all.get(0).d, eye, range, f.air);
        // frame light before mushrooms: by day include mushrooms whose shadow enters the frame (MushroomField.frame)
        light(level, eye, partial, f);
        // the dust wave: every detonation's ground dust, independent of the pall's slots (DustSources)
        boolean dust = DustSources.frame(all, eye, partial, f);
        f.mushroomCount = MushroomField.frame(all, eye, partial, frustum, f.sun, f.sunUp(), level.getMinBuildHeight(), level.getMaxBuildHeight(), f.mushrooms);
        // ball light on world from all detonations: a ball behind the viewer lights ground ahead
        openSky(level, f.openSky, f.faceShade);
        double open = 0.2126 * PallLight.linear(f.openSky.x) + 0.7152 * PallLight.linear(f.openSky.y) + 0.0722 * PallLight.linear(f.openSky.z);
        f.ballsLit = MushroomField.balls(all, eye, partial, air, f.exposure, open, f.balls, f.ballLight);
        flash(all, eye, partial, f);
        List<ClientNuclear.Active> visible = new ArrayList<>();
        if (q != PallPass.Quality.OFF) for (ClientNuclear.Active a : all) if (visible(a, partial)) visible.add(a);
        if (visible.isEmpty() && f.mushroomCount == 0 && !f.ballsLit && !dust) return null;
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
        f.wilson0.zero();
        f.wilson1.zero();
        // frame world — chunks within render distance (chunk-edge margin): a ray into empty space crossed them without hitting anything
        f.floorFrom = (float) Math.max(16, ua.zentix.incoming.client.fx.layer.FxViews.world(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16) - 24);
        f.min.set(Float.MAX_VALUE);
        f.max.set(-Float.MAX_VALUE);
        int k = 0;
        float bottom = (float) (level.getMinBuildHeight() - eye.y);
        for (int i = 0; i < visible.size() && k < 2; i++) if (slot(visible.get(i), k, eye, partial, frustum, bottom, f)) SLOTTED[k++] = visible.get(i);
        if (k == 0 && f.mushroomCount == 0 && !f.ballsLit && !dust) return null;
        // without pall use zero bounds: the ray cannot enter (inverted bounds would span the entire ray)
        if (k == 0) {
            f.min.zero();
            f.max.zero();
        }
        MushroomField.fire(partial, f);
        float clouds = level.effects().getCloudHeight();
        FireStorms.frame(SLOTTED, k, eye, partial, frustum, Float.isNaN(clouds) ? 1e6f : (float) (clouds - eye.y), f);
        // ray reach from the eye's haze and its optical depth above and below (pallClip): only what the pall draws, never the
        // ground dust the dust pass draws; disabled pall is not rendered, and does not hide the mushroom behind it
        if (q == PallPass.Quality.OFF) f.eyeDust.zero();
        else eyeDust(level, eye, partial, f);
        return f;
    }

    /** Optical medium at a camera; never updates the player's ash, hearing or dust veil. */
    private static void eyeDust(ClientLevel level, Vec3 camera, float partial, PallPass.Frame f) {
        Vector3f haze = new Vector3f();
        for (ClientNuclear.Active active : ClientNuclear.detonations()) {
            Detonation d = active.d;
            double ticks = active.ticks(partial);
            double dx = camera.x - d.burst().x, dz = camera.z - d.burst().z;
            double eye = camera.y - d.groundY(), floor = active.ground.at(camera.x, camera.z);
            if (active.pall.hazeVisible(ticks)) active.pall.addHazeEye(dx, dz, eye, floor, ticks, water(active), haze);
            // the dust wave's drawn dust ends the pall's rays too (it hides what lies behind it); with dust OFF it is not drawn
            // (on the dust's own ground: the eye's tiles where they know it, as the march lays it)
            if (f.dustWall.active && DustSources.visible(active, partial)) active.pall.addDrawnEye(dx, dz, eye,
                    DustGround.floor(camera.x, camera.z, d.groundY() + floor) - d.groundY(), ticks, water(active), haze);
        }
        double open = level.dimensionType().hasSkyLight() ? level.getBrightness(LightLayer.SKY, BlockPos.containing(camera)) / 15.0 : 1;
        f.eyeDust.set((float) (haze.x * open), haze.y, haze.z);
    }

    /**
     * Pall-pass frame without the nuke, for conventional explosion smoke ({@code client/fx/smoke/BlastClouds}): air, sun, sky,
     * on-screen sky light and eye adaptation match {@link #frame}; no pall, mushrooms, balls, storm, wall or water (their
     * parameters are zero). Quality matches mushrooms with pall disabled: at least LOW (no alternative smoke).
     */
    public static PallPass.Frame lightFrame(ClientLevel level, Vec3 eye, float partial) {
        PallPass.Frame f = FRAME;
        // no detonation: no dust wave (the shared frame keeps the last nuclear frame's inputs otherwise)
        f.dustWall.active = false;
        double range = Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial));
        Sight.Air a = Sight.Air.at(eye.y - ua.zentix.incoming.client.fx.ClientAir.now().sea(), range);
        f.air.set((float) a.haze(), (float) a.clean(), (float) a.weather(), (float) (1 / Sight.HAZE_HEIGHT));
        light(level, eye, partial, f);
        openSky(level, f.openSky, f.faceShade);
        java.util.Arrays.fill(f.mushrooms, 0f);
        f.mushroomCount = 0;
        f.balls.zero();
        f.ballLight.zero();
        f.ballsLit = false;
        f.flash.set(1, 0, 0, 0);
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
        f.wilson0.zero();
        f.wilson1.zero();
        f.fire0.clear();
        f.fire1.clear();
        f.fireMin.set(1);
        f.fireMax.set(-1);
        f.whirlPos.zero();
        f.whirlShape.zero();
        f.whirlLean.zero();
        f.floorFrom = (float) Math.max(16, ua.zentix.incoming.client.fx.layer.FxViews.world(Minecraft.getInstance().options.getEffectiveRenderDistance() * 16) - 24);
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

    /**
     * Whether the detonation needs a pall slot: haze over its fire zone, a Wilson cloud, a mushroom (its foot inflow and ball light
     * read the slot) or a firestorm (its fire map is the slot's). Ground dust, spray and the base surge are the dust pass's.
     */
    private static boolean visible(ClientNuclear.Active a, float partial) {
        return a.pall.hazeVisible(a.ticks(partial)) || a.wall.wilsonReach(a.seconds(partial)) > 0 || MushroomField.shape(a, partial) != null
                || stormy(a);
    }

    /** The detonation's firestorm has a fire map ({@code FireStorms}: half-side and a filled version). */
    private static boolean stormy(ClientNuclear.Active a) {
        return a.storm.half() > 0 && a.storm.version() > 0;
    }

    /**
     * How far the camera is outside the detonation's haze and Wilson cloud (along ground), blocks; 0 — inside: the matter the
     * camera stands in takes a slot before matter elsewhere.
     */
    private static double outside(ClientNuclear.Active a, Vec3 eye, float partial) {
        double r = Math.max(a.pall.hazeExtent(a.ticks(partial)), a.wall.wilsonReach(a.seconds(partial)));
        return Math.max(0, Math.hypot(eye.x - a.d.burst().x, eye.z - a.d.burst().z) - r);
    }

    /**
     * Slot k ({@code pall.glsl}): detonation field, haze table row, Wilson cloud and bounds; false — slot unneeded (no haze in the
     * table, no Wilson cloud, mushroom or firestorm — {@link #visible} — or bounds out of frame), leaving it free for the next
     * detonation. Land absent beneath pall ({@link PallGround}) is also absent from the table: no haze over open sea.
     * {@code bottom} is world bottom relative to eye: include a detonation even if only ground beneath the pall is visible,
     * because by day it receives the pall shadow ({@code fx_pall_composite}), and a valley or quarry below ground zero may be deeper than the pall bottom.
     */
    private static boolean slot(ClientNuclear.Active a, int k, Vec3 eye, float partial, FrustumIntersection frustum, float bottom,
                                PallPass.Frame f) {
        Detonation d = a.d;
        PallField p = a.pall;
        double ticks = a.ticks(partial);
        boolean water = a.ground.hasWater();
        p.row(ticks, a.ground.hasLand(), ROW);
        double top = 1;
        boolean shape = false, carried = false;
        for (int i = 0; i < PallField.ROWS * PallPass.WIDTH; i++) {
            shape |= ROW[4 * i] > 0;
            carried |= ROW[4 * i + 3] > 0;
            top = Math.max(top, ROW[4 * i + 1]);
        }
        // haze: the fire zone's smoke shape and a drawn smoke parcel carrying it within the air's reach of the eye
        boolean band = shape && carried && p.hazeBounds(ticks, eye.x - d.burst().x, eye.z - d.burst().z, airReach, BAND);
        boolean any = band;
        // Wilson cloud in the same volume; the mushroom's foot inflow and ball light, the firestorm's fire map read the slot
        double seconds = a.seconds(partial);
        boolean wall = a.wall.slot(seconds, WALL);
        any |= wall && WALL[18] > 0 || MushroomField.shape(a, partial) != null || stormy(a);
        if (!any) return false;
        p.drift(ticks, DRIFT);
        double length = p.parcelLength(ticks);
        float cx = (float) (d.burst().x - eye.x), cz = (float) (d.burst().z - eye.z), ground = (float) (d.groundY() - eye.y);
        // bounds: the fire zone and the drawn smoke parcels downwind; valleys below ground zero over full height; above hills the
        // layer starts at their slopes (pallField); the Wilson cloud surrounds the detonation point
        float r = (float) Math.max(1, a.wall.wilsonReach(seconds));
        LO.set(cx - r, (float) (ground - top), cz - r);
        HI.set(cx + r, (float) (ground + Math.max(a.ground.high(), 0) + top * 1.05), cz + r);
        if (band) {
            LO.x = Math.min(LO.x, (float) (cx + BAND[0]));
            LO.z = Math.min(LO.z, (float) (cz + BAND[1]));
            HI.x = Math.max(HI.x, (float) (cx + BAND[2]));
            HI.z = Math.max(HI.z, (float) (cz + BAND[3]));
        }
        if (WALL[18] > 0) HI.y = Math.max(HI.y, (float) (ground + WALL[19] + a.wall.wilsonReach(seconds)));
        CULL.set(LO.x, Math.min(LO.y, bottom), LO.z);
        if (frustum.intersectAab(CULL, HI, SIDES_AND_NEAR) >= 0) return false;
        System.arraycopy(ROW, 0, f.table, PallField.hazeOffset(k, PallPass.WIDTH), ROW.length);
        double ys = Math.cbrt(d.yieldKt() / 15);
        org.joml.Matrix4f m = k == 0 ? f.slot0 : f.slot1;
        m.m00(cx).m01(cz).m02(ground).m03((float) p.reach());
        // S[1].w: 1 — slot in use, 2 — and hard ground or sand beneath its pall (the shader samples the soil only then)
        m.m10((float) DRIFT[0]).m11((float) DRIFT[1]).m12((float) length).m13(a.ground.hasHard() || a.ground.hasSand() ? 2 : 1);
        m.m20((float) Math.cos(d.windDir())).m21((float) Math.sin(d.windDir()));
        // S[3].x: the fire zone's radius, blocks (the parcels widen by SmokeParcels.SPREAD of their shift over it)
        m.m30((float) p.fireReach()).m31((float) Math.max(48, d.blocks(TILE_M * ys))).m32((float) (ticks / 20.0));
        tableRows(m, k, water);
        // ball and fire light on dust (CloudPuffs.pallLight)
        a.puffs.pallLight(MushroomField.shape(a, partial), a.pall.fireLight(ticks), GLOW);
        (k == 0 ? f.glow0 : f.glow1).set(GLOW[0], GLOW[1], GLOW[2], GLOW[3]);
        (k == 0 ? f.hot0 : f.hot1).set(GLOW[4], GLOW[5], GLOW[6], GLOW[7]);
        a.ground.frame(k == 0 ? f.ground0 : f.ground1);
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
        sky(level, eye, partial, litTop(partial), SKY);
        f.sun.set(SKY.sx, SKY.sy, 0, SKY.day);
        f.skyLight.set(SKY.sky[0], SKY.sky[1], SKY.sky[2]);
        f.sunLight.set(SKY.sun[0], SKY.sun[1], SKY.sun[2]);
        f.sunTop.set(SKY.top[0], SKY.top[1], SKY.top[2], SKY.eyeUp);
        // airlight: the sky vanilla draws behind distant matter, without its sun, moon and stars (fx_pall: pallAirLight)
        float[] fog = ua.zentix.incoming.client.fx.layer.FxLayer.fogColor();
        // the sky behind matter dims with the sky itself under the flash (fx_pall_composite: PallFlash), in screen values
        float shownDay = (float) PallLight.display(NukeLight.daylight());
        f.airSky.set(SKY.shown[0] * shownDay, SKY.shown[1] * shownDay, SKY.shown[2] * shownDay);
        f.airFog.set(fog[0] * shownDay, fog[1] * shownDay, fog[2] * shownDay, Minecraft.getInstance().gameRenderer.getRenderDistance());
        f.base = SKY.base;
        f.exposure = SKY.exposure;
        f.bodyExposure = (float) NukeLight.bodyExposure();
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
        /** Per-channel sun light on the ground and sky light, linear: white horizontal wall at noon = 1. */
        final float[] sun = new float[3], sky = new float[3];
        /**
         * Per-channel sun light above the air ({@link #sunAt} before the air), linear: weather and the remainder to white after sky
         * light; zero once the sun is below the horizon of {@link Sight#SUN_TOP}.
         */
        final float[] top = new float[3];
        /** Eye altitude above sea level (the world's, {@code util/Terrain.sea}), blocks: fx_pall's sun height is p.y plus it. */
        float eyeUp;

        /**
         * Sun light on matter at altitude {@code height} blocks (= m) above sea level (the world's, {@code util/Terrain.sea}: one reference for
         * every detonation, cap, pall and wall), per channel, into {@code into}: {@link #top} through the air of its beam
         * ({@link Sight#sunThroughAir}); the copy in {@code fx_pall} is {@code pallSunBeam(pallSunHeight(p))}.
         */
        void sunAt(double height, double[] into) {
            if (top[0] <= 0 && top[1] <= 0 && top[2] <= 0) {
                java.util.Arrays.fill(into, 0, 3, 0);
                return;
            }
            Sight.sunThroughAir(sy, height, into);
            for (int c = 0; c < 3; c++) into[c] *= top[c];
        }
        /** Minecraft's sky colour on screen ({@code ClientLevel.getSkyColor}): the sky disc vanilla draws. */
        final float[] shown = new float[3];
        /**
         * On-screen sky light (white wall), eye adaptation to it ({@code Sight.adapted}: mushroom glow and ball light
         * into the frame), and resulting fire glow strength on dust ({@link CloudPuffs#glowOnSmoke}).
         */
        float base, exposure, glowOnSmoke;
    }

    /**
     * Altitude above sea level (the world's, {@code util/Terrain.sea}) of the highest matter of all detonations, blocks: the mushroom's top with its billows ({@link MushroomField#top}),
     * or {@link Sight#SUN_TOP} while one has no mushroom (pall and wall only; their sun branches cost per sample, no shadow map).
     * The frame computes sunlight while the sun stands above this height's horizon.
     */
    private static double litTop(float partial) {
        double top = 0;
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            ua.zentix.incoming.nuclear.model.MushroomModel.Shape s = MushroomField.shape(a, partial);
            if (s == null) return Sight.SUN_TOP;
            top = Math.max(top, a.d.groundY() - ua.zentix.incoming.client.fx.ClientAir.now().sea() + a.d.blocks(MushroomField.top(s)));
        }
        return Math.min(top, Sight.SUN_TOP);
    }

    /** Sun and sky at {@code eye} into {@code into} (as in {@link #light}), sunlight computed up to {@link Sight#SUN_TOP}. */
    static void sky(ClientLevel level, Vec3 eye, float partial, Sky into) {
        sky(level, eye, partial, Sight.SUN_TOP, into);
    }

    /**
     * Sun and sky at {@code eye} into {@code into}: sunlight above the air while the sun stands above the horizon of altitude
     * {@code top} (blocks above sea level), zero below it.
     */
    static void sky(ClientLevel level, Vec3 eye, float partial, double top, Sky into) {
        into.eyeUp = (float) (eye.y - ua.zentix.incoming.client.fx.ClientAir.now().sea());
        float angle = level.getTimeOfDay(partial) * Mth.TWO_PI;
        into.sx = -Mth.sin(angle);
        into.sy = Mth.cos(angle);
        boolean sky = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        // clouds of rain and thunder dim the sun everywhere; vanilla's daylight at the eye fades from 10° to −3.4° (smooth)
        float weather = sky ? (1 - 0.55f * level.getRainLevel(partial)) * (1 - 0.35f * level.getThunderLevel(partial)) : 0;
        float day = smooth(-0.06f, 0.18f, into.sy) * weather;
        into.day = day;
        Vec3 skyColor = level.getSkyColor(eye, partial);
        into.shown[0] = (float) skyColor.x;
        into.shown[1] = (float) skyColor.y;
        into.shown[2] = (float) skyColor.z;
        float night = 1 - day;
        // at night, no darker than sky light on wall and mushroom billows (FarView.ambient, 0.17 on screen): night sky color is almost
        // black, so pall behind the front would form a black curtain over the city while the dust wall and world remain visible
        float ambient = FarView.ambient(level, partial);
        float floor = (float) PallLight.linear(nightSky(ambient, night));
        into.sky[0] = skyLight(skyColor.x, floor);
        into.sky[1] = skyLight(skyColor.y, floor);
        into.sky[2] = skyLight(skyColor.z, floor);
        // direct sunlight above the air while it reaches the highest matter (earth's shadow, not the eye's daylight: a cap kilometres
        // up stays sunlit after ground sunset); on the ground, through the air along its slant column (Sight.sunThroughAir: Rayleigh +
        // clear-day haze, every elevation and height), relative to the overhead sun: white at noon, redder and dimmer as it sinks
        boolean up = weather > 0 && Sight.sunAbove(into.sy, top);
        Sight.sunThroughAir(into.sy, 0, THROUGH_AIR);
        for (int c = 0; c < 3; c++) {
            into.top[c] = up ? sunLight(1, into.sky[c], weather) : 0;
            into.sun[c] = (float) (THROUGH_AIR[c] * into.top[c]);
        }
        // wall reflection and fire glow (on screen) add to on-screen sky light — this value
        into.base = ambient;
        // mushroom glow is a black body at real brightness (noon white wall = 1): into frame at the frame's one exposure
        // (NukeLight.frameExposure; the ball's own film curve cancels it, so only the light it casts changes)
        into.exposure = (float) NukeLight.frameExposure();
        // daylight on matter under the flash: the same one exposure (DESIGN fireball-light §4.1), 1 without a flash or at night
        float daylight = (float) NukeLight.daylight();
        for (int c = 0; c < 3; c++) {
            into.sky[c] *= daylight;
            into.top[c] *= daylight;
            into.sun[c] *= daylight;
        }
        into.glowOnSmoke = (float) CloudPuffs.glowOnSmoke(ambient);
    }

    /** The eye's veil below this share of white is not drawn (under half a screen step at its peak). */
    static final double VEIL_FLOOR = 0.002;
    /** Copy of the composite's {@code FLASH_VEIL}: Stiles–Holladay 10·E/θ² over a white wall E/π is 31.4·F/θ², × this. */
    static final double FLASH_VEIL = 0.35;
    private static final org.joml.Vector4f COLUMN = new org.joml.Vector4f();

    /**
     * Peak of the composite's eye veil ({@code flashVeil}) this frame: at each ball's disc edge, 31.4·F/θ²·{@link #FLASH_VEIL}
     * with F the ball's light at the eye at the frame's exposure; the brightest ball.
     */
    static double veilPeak(PallPass.Frame f) {
        double peak = 0;
        for (int i = 0; i < 4; i++) {
            f.balls.getColumn(i, COLUMN);
            float r = COLUMN.w;
            if (r <= 0) continue;
            double d2 = COLUMN.x * COLUMN.x + COLUMN.y * COLUMN.y + COLUMN.z * COLUMN.z;
            double disc = Math.toDegrees(Math.atan(r / Math.sqrt(d2)));
            f.ballLight.getColumn(i, COLUMN);
            double light = 0.2126 * COLUMN.x + 0.7152 * COLUMN.y + 0.0722 * COLUMN.z;
            peak = Math.max(peak, FLASH_VEIL * 31.4 / (disc * disc) * light * r * r / Math.max(d2, r * r));
        }
        return peak;
    }

    /**
     * The frame's one exposure for the composite (DESIGN fireball-light §4.1): the daylight multiplier, the daylight share
     * that gives the eye's veil (only while a ball lights the frame), and the strongest amber dome with its direction, whose
     * light the composite keeps out of the daylight dimming.
     */
    static void flash(List<ClientNuclear.Active> all, Vec3 eye, float partial, PallPass.Frame f) {
        float amber = 0;
        Vec3 toward = new Vec3(0, 1, 0);
        for (ClientNuclear.Active a : all) {
            float k = NukeSky.amber(a, eye, partial);
            if (k > amber) {
                amber = k;
                toward = a.d.burst().subtract(eye).normalize();
            }
        }
        // the eye's veil only while it can show: its peak (at the ball's disc) above VEIL_FLOOR of white, else the composite
        // skips its per-pixel term (+0.24 ms at 1080p while it runs, RTX 4060 under the shader pack)
        boolean veil = f.ballsLit && veilPeak(f) >= VEIL_FLOOR;
        f.flash.set((float) NukeLight.daylight(), veil ? (float) NukeLight.day() : 0, amber, 0);
        f.amber.set((float) toward.x, (float) toward.y, (float) toward.z, 0);
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

    /**
     * Channel sun light above the air: remainder up to white after sky light {@code sky}, colour factor {@code red} (on screen),
     * strength {@code weather} (rain and thunder clouds); {@link Sky#sunAt} passes it through the air ({@link Sight#sunThroughAir}).
     */
    static float sunLight(double red, float sky, float weather) {
        return weather * (float) (PallLight.linear(red) * Math.max(1 - sky, 0));
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
        // sun terms of the frame (light): elevation, eye daylight, sky, beam above the air, on the ground and at the last cap's height
        String cap = "no cap";
        if (!all.isEmpty()) {
            ClientNuclear.Active last = all.getLast();
            ua.zentix.incoming.nuclear.model.MushroomModel.Shape shape = MushroomField.shape(last, 0);
            if (shape != null) {
                double up = last.d.groundY() - ua.zentix.incoming.client.fx.ClientAir.now().sea() + last.d.blocks(shape.capY());
                double top = last.d.groundY() - ua.zentix.incoming.client.fx.ClientAir.now().sea() + last.d.blocks(MushroomField.top(shape));
                double[] atCap = new double[3], atTop = new double[3];
                SKY.sunAt(up, atCap);
                SKY.sunAt(top, atTop);
                // air from the eye to the cap centre: the share the haze shows there and the airlight it shows (pallAir, pallAirLight)
                Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
                double dx = last.d.burst().x - eye.x, dy = last.d.groundY() + last.d.blocks(shape.capY()) - eye.y, dz = last.d.burst().z - eye.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                Sight.Air air = Sight.Air.at(eye.y - ua.zentix.incoming.client.fx.ClientAir.now().sea(), Sight.range(mc.level == null ? 0 : mc.level.getRainLevel(1),
                        mc.level == null ? 0 : mc.level.getThunderLevel(1)));
                double haze = 1 - air.transmittance(dist, dy);
                double[] airLight = NukeExposure.airLight(dx / dist, dy / dist, dz / dist, new double[] {f.airSky.x, f.airSky.y, f.airSky.z},
                        new double[] {f.airFog.x, f.airFog.y, f.airFog.z}, f.airFog.w);
                cap = String.format(java.util.Locale.ROOT, "at cap centre %.0f blocks above sea %.4f %.4f %.4f, at top %.0f blocks above sea %.4f %.4f %.4f, "
                                + "haze toward the cap centre %.3f (%.0f blocks), airlight there %.3f %.3f %.3f",
                        up, atCap[0], atCap[1], atCap[2], top, atTop[0], atTop[1], atTop[2], haze, dist, airLight[0], airLight[1], airLight[2]);
            }
        }
        String sun = String.format(java.util.Locale.ROOT, "sun %.2f° day %.3f, sky %.4f %.4f %.4f, beam above air %.4f %.4f %.4f, on ground %.4f %.4f %.4f, %s",
                Math.toDegrees(Math.asin(Mth.clamp(SKY.sy, -1, 1))), SKY.day, SKY.sky[0], SKY.sky[1], SKY.sky[2], SKY.top[0], SKY.top[1], SKY.top[2],
                SKY.sun[0], SKY.sun[1], SKY.sun[2], cap);
        return String.format(java.util.Locale.ROOT, "sigma at camera %.4f /block (visibility %.0f blocks; mist %.4f), depth up %.2f down %.2f, muffle %.2f, ash %d, quality %s, "
                        + "light: %s, fires %.2f × %.2f, ball r %.0f blocks, black body %.3g %.3g %.3g, under the pall: %s, "
                        + "frames: %s; mushrooms %d (exposure %.6g); firestorm: %s; nuclear camera: %s; young wall: %s; pall box %.0f %.0f %.0f .. %.0f %.0f %.0f (table reach %.0f)",
                sigma, sigma > 1e-5f ? 3.912 / sigma : Double.POSITIVE_INFINITY, mist, tauUp, tauDown, muffle, NukeAsh.alive(), quality(),
                sun, f.glowOnSmoke, f.glow0.y, f.glow0.x, f.hot0.x, f.hot0.y, f.hot0.z, under,
                mc.level == null ? "-" : PallPass.describe(), f.mushroomCount, f.exposure, FireStorms.describe(), NukeLight.describe(), youngDescribe(f),
                f.min.x, f.min.y, f.min.z, f.max.x, f.max.y, f.max.z, f.slot0.m03());
    }

    /** The dust wave's pass and sources this frame ({@link DustSources}). */
    private static String youngDescribe(PallPass.Frame f) {
        ua.zentix.incoming.client.fx.layer.DustWallPass.Frame w = f.dustWall;
        if (!w.active) return "off";
        return String.format(java.util.Locale.ROOT, "pass %s; %s; t %.2f s, adapt %.2f; sun %.2f %.2f light %.3f %.3f %.3f, sky %.3f %.3f %.3f, ground %.3f %.3f %.3f",
                ua.zentix.incoming.client.fx.layer.DustWallPass.describe(), DustSources.describe(w), w.eye[3], w.adapt, w.sunDir.x, w.sunDir.y,
                w.sunLight.x, w.sunLight.y, w.sunLight.z, w.skyLight.x, w.skyLight.y, w.skyLight.z, w.groundLight.x, w.groundLight.y, w.groundLight.z);
    }

    /**
     * Haze table row of slot k ({@link PallField#row}) in its mat4 (column-major, like shader {@code mat4}): S[2].w; S[2].z, the
     * slot's half of the slots' shared terrain texture ({@code pall.glsl}: {@code pallGroundUv}); and S[3].w, 1 — water beneath
     * the pall ({@code pallField} reads the map's water channel only then), 0 — land everywhere.
     */
    static void tableRows(org.joml.Matrix4f m, int k, boolean water) {
        m.m22(k).m23(tableRow(k)).m33(water ? 1 : 0);
    }

    /** Center of slot k's haze table row in the pall-table texture, height 0..1. */
    static float tableRow(int k) {
        return (PallField.ROWS * k + 0.5f) / PallPass.ROWS;
    }

}
