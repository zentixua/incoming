package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * Nuclear wave on water (DESIGN-nuke §6, "Water"): dark front ring on water — ripples in the wind behind the front, with foam
 * near ground zero (water decal in the effects layer, {@link WaterDecal}: model ring profile evaluated here per frame);
 * drops from wave crests behind the front near the camera (particles, {@link FxBudget#SPRAY}; the pall wall head renders mist there,
 * {@link #front}); a burst on water produces a crowned water column in the mushroom
 * volume ({@link MushroomField}) and a base surge in the pall ({@link PallField#water}). Ground dust does not rise over water:
 * the wall head there is spray and mist ({@link BlastWall}, water from the map beneath the pall, {@link PallGround}); camera dust
 * particles appear over land only ({@link NukeDust#front}).
 * <p>
 * Water is known only from what the client already has: client chunks and terrain-map tiles ({@link FarTerrain#water}).
 * Water beneath ground zero far beyond render distance (no chunk or tile) is inferred from height: level ground exactly at sea level means sea.
 */
public final class NukeWater {
    private static final RandomSource RANDOM = RandomSource.create();
    /**
     * Ripple strip right at the front, ticks: air behind the front blows for the positive phase (about a second at kilometer distances
     * for 15 kt); water then remains rough for another {@link #CALM} ticks before smoothing out.
     */
    static final double BAND = 25, CALM = 600;
    /** Spray-location spacing along the front arc near the camera, blocks; maximum locations per tick. */
    private static final double STEP = 4;
    private static final int MAX_SPOTS = 64;
    /** Maximum front-spray speed, blocks per tick (like dust, {@code NukeDust}). */
    private static final double SPRAY_SPEED = 4;
    /**
     * Guessed water beneath ground zero (no chunk or tile) is resolved again while the detonation on this client is younger than this many
     * local ticks ({@link ClientNuclear.Active#age()}: since packet arrival or replay seek, not the detonation itself):
     * one and a half seconds for a live detonation; five for one received as past (world entry during a detonation, replay): chunks around
     * the joining player take seconds to arrive.
     */
    static final long GUESS_TICKS = 30, GUESS_TICKS_PAST = 100;
    /** Spray color: brighter near the crest, whiter in the air. */
    private static final int SPRAY_DARK = 0xC9D2D8, SPRAY_LIGHT = 0xF2F5F7;

    /** Frame ring profile (render thread; {@link WaterDecal#ring} immediately copies it to the texture). */
    private static final float[] DARK = new float[WaterDecal.SAMPLES], FOAM = new float[WaterDecal.SAMPLES];
    /** For the scenario line: spray locations last tick. */
    private static int lastSpots;

    private NukeWater() {}

    /** Source of knowledge about water beneath ground zero. */
    enum Source { CHUNK, TILES, SEA_LEVEL, NONE }

    /** Water beneath ground zero 0..1 and its source of knowledge. */
    record Under(double water, Source source) {
        boolean sure() {
            return source == Source.CHUNK || source == Source.TILES;
        }
    }

    /**
     * Water beneath ground zero: loaded client chunk (water in the surface block, {@link #surfaceBlock}), otherwise
     * a terrain-map tile, otherwise a height guess: level ground at sea level ({@code groundY} = sea level —
     * first air above sea water) means sea. No water under a ceiling (Nether).
     */
    static Under under(@Nullable ClientLevel level, Detonation d) {
        if (level == null || level.dimensionType().hasCeiling()) return new Under(0, Source.NONE);
        int x = Mth.floor(d.burst().x), z = Mth.floor(d.burst().z), y = surfaceBlock(d.groundY());
        LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
        if (chunk != null) return new Under(chunk.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER) ? 1 : 0, Source.CHUNK);
        OptionalInt depth = TerrainTiles.waterDepth(x, z);
        if (depth.isPresent()) return new Under(depth.getAsInt() > 0 ? 1 : 0, Source.TILES);
        return new Under(guess(d.groundY(), WaterDecal.SEA_LEVEL), Source.SEA_LEVEL);
    }

    /**
     * Guess without data: first air above ground exactly at sea level means sea (shore at this height is a narrow strip).
     * Ground height follows {@link #surfaceBlock}: 62.889 for sea under the crosshair.
     */
    static double guess(double groundY, int seaLevel) {
        return Mth.ceil(groundY) == seaLevel ? 1 : 0;
    }

    /**
     * Surface block beneath ground zero from {@code groundY}: land and heightmaps give the first air above ground (integer), while
     * crosshair, binoculars, missile and B-2 rays including fluids give the surface itself: still water tops out at 8/9 block, 62.889 for sea.
     * The block containing this height (the upper one if on its face) is water or ground.
     */
    static int surfaceBlock(double groundY) {
        return Mth.ceil(groundY) - 1;
    }

    /**
     * Water beneath ground zero is resolved and fixed: known for certain (chunk, tile), or the guess has lasted longer than
     * the deadline — {@code age} local detonation ticks on this client ({@link ClientNuclear.Active#age()}); {@code live} means a live
     * detonation, not one received as past.
     */
    static boolean settles(Under u, long age, boolean live) {
        return u.sure() || age > (live ? GUESS_TICKS : GUESS_TICKS_PAST);
    }

    /** Detonation tick after {@link #resolve}: camera spray. */
    static void tick(ClientNuclear.Active a, ClientLevel level, Vec3 eye) {
        front(a, level, eye);
    }

    /**
     * Re-resolve water beneath ground zero while uncertain and the detonation is young: for pall (base surge, water where its map
     * knows nothing), mushroom (steam and spray) and spray. Also called when creating a detonation, before its pall ground map: otherwise someone joining mid-burst at sea would see brown dust
     * over the sea for the first ticks (land until ground-zero resolution).
     */
    static void resolve(ClientNuclear.Active a, @Nullable ClientLevel level) {
        BurstWater w = a.water;
        if (w.settled()) return;
        Under u = under(level, a.d);
        w.set(u.water(), u.water() * WaterModel.wetBurst(a.d.hobMetres(), a.d.yieldKt()));
        a.pall.water(u.water(), w.wet());
        if (settles(u, a.age(), a.live)) w.settle(u.source());
    }

    /** Whether the top of a loaded chunk column is water (top block of the {@code MOTION_BLOCKING} heightmap is water). */
    static boolean water(LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos pos) {
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) - 1;
        return top >= chunk.getMinBuildHeight() && chunk.getFluidState(pos.set(x, top, z)).is(FluidTags.WATER);
    }

    /**
     * More water than land around a location (center and four points 8 blocks away; loaded chunks): a player on a platform
     * or in a boat in the middle of a lake is over water.
     */
    static boolean waterAround(ClientLevel level, double x, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int wet = 0;
        for (int k = 0; k < 5; k++) {
            int bx = Mth.floor(x) + (k == 1 ? 8 : k == 2 ? -8 : 0), bz = Mth.floor(z) + (k == 3 ? 8 : k == 4 ? -8 : 0);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk != null && water(chunk, bx, bz, pos)) wet++;
        }
        return wet >= 3;
    }

    /** Wind behind the front along the ground at slant range {@code slant} blocks, m/s. */
    private static double wind(Detonation d, double slant) {
        return d.groundWind(d.groundOf(slant));
    }

    /**
     * Camera spray: while the water front is within {@link NukeDust#NEAR} of the camera, drops fly from crests along its arc over water (loaded
     * chunks), denser in stronger wind. The pall volume renders the mist itself up to the height at which wind behind the front
     * tears off crests ({@link WaterModel#sprayHeight}) — the wall head over water
     * ({@link NukePall#drawsHead}), just as for camera dust over land ({@link NukeDust}); billows are only a fallback when
     * the pall does not render it, and are smooth: a billow wider than the screen at the eye, sampled by nearest pixel, makes a staircase of squares across the frame.
     */
    private static void front(ClientNuclear.Active a, ClientLevel level, Vec3 eye) {
        lastSpots = 0;
        if (level.dimensionType().hasCeiling()) return;
        Detonation d = a.d;
        double ticks = a.ticks(0), slant = d.frontRadius(ticks), hob = Math.max(0, d.burst().y - d.groundY());
        if (slant <= hob) return;
        double r = Math.sqrt(slant * slant - hob * hob);
        double ex = eye.x - d.burst().x, ez = eye.z - d.burst().z, l = Math.sqrt(ex * ex + ez * ez);
        if (Math.abs(l - r) > NukeDust.NEAR) return;
        double wind = wind(d, slant), spray = WaterModel.spray(wind);
        if (spray < 0.02) return;
        double height = d.blocks(WaterModel.sprayHeight(wind));
        double half = l < 1 ? Math.PI : Math.acos(Mth.clamp((l * l + r * r - NukeDust.NEAR * NukeDust.NEAR) / (2 * l * r), -1, 1));
        double toward = Math.atan2(ez, ex);
        double speed = Math.min(SPRAY_SPEED, d.frontRadius(ticks + 1) - slant);
        // spray begins behind the dark ring, at the same position as the wall head draws it (BlastWall.slot): behind the dust edge (the
        // front itself) by the head travel during spray rise time (WaterModel.SPRAY_LAG)
        double back = WaterModel.SPRAY_LAG * 20 * d.scale();
        double lag = Math.max(0, slant - d.frontRadius(Math.max(0, ticks - back)));
        int n = Math.min(MAX_SPOTS, (int) Math.round(2 * half * r / STEP * spray * Fx.density(eye)));
        boolean puffs = !NukePall.drawsHead(a, eye);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            double ang = toward + (RANDOM.nextDouble() * 2 - 1) * half;
            double cos = Math.cos(ang), sin = Math.sin(ang);
            double rr = r - lag - RANDOM.nextDouble() * Math.min(10, height * 0.4);
            double x = d.burst().x + cos * rr, z = d.burst().z + sin * rr;
            if ((x - eye.x) * (x - eye.x) + (z - eye.z) * (z - eye.z) > NukeDust.NEAR * NukeDust.NEAR) continue;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null || !water(chunk, bx, bz, pos)) continue;
            double surface = pos.getY() + 0.9;
            lastSpots++;
            // mist: a pall right above water (large transparent billows merge rather than hang as balls), higher up —
            // sparser and larger, rolling with the front more slowly than it
            double up = Math.pow(RANDOM.nextDouble(), 2.2), h = height * 0.85 * up;
            if (puffs) {
                double v = speed * (0.35 + 0.45 * RANDOM.nextDouble());
                Fx.smoke().vel(cos * v, (0.03 + 0.12 * RANDOM.nextDouble()) * (1 - up), sin * v)
                        .size((float) (2 + 0.1 * h), (float) (6 + Math.min(14, height * 0.3) + 4 * RANDOM.nextDouble())).growFast()
                        .life(40 + RANDOM.nextInt(50)).color(SPRAY_DARK, SPRAY_LIGHT).alpha((float) (0.22 + 0.25 * spray)).drag(0.94f)
                        .smooth().wind(0).fadeIn(6).fadeFrom(0.3f).budget(FxBudget.SPRAY).spawn(level, x, surface + 0.3 + h, z);
            }
            // crest drops: carried by wind and falling
            if (RANDOM.nextDouble() < 0.4 + 0.6 * spray) {
                for (int k = 0; k < 2; k++) {
                    double u = Math.min(SPRAY_SPEED, 0.6 + speed) * (0.4 + 0.6 * RANDOM.nextDouble());
                    Fx.smoke().vel(cos * u, 0.15 + 0.45 * spray * RANDOM.nextDouble(), sin * u).size(0.1f, 0.16f).life(18 + RANDOM.nextInt(20))
                            .color(0xDCE6EC, SPRAY_LIGHT).alpha(0.9f).drag(0.98f).gravity(0.06f).collide().wind(0).fadeIn(0).fadeFrom(0.7f)
                            .budget(FxBudget.SPRAY).spawn(level, x, surface + 0.2 + RANDOM.nextDouble() * Math.min(4, h + 1), z);
                }
            }
        }
    }

    /** Water rings from all detonations into the frame ({@link #decal}); after {@code WaterDecal.begin}. */
    public static void decals(float partial) {
        for (ClientNuclear.Active a : ClientNuclear.detonations()) decal(a, partial);
    }

    /**
     * Water detonation ring into the frame ({@link WaterDecal}): water-front radius and profile behind it — ripple darkness
     * ({@link WaterModel#darkness}) and foam ({@link WaterModel#whitecaps}), based on how many ticks ago the front passed
     * this location and the wind behind it.
     */
    static void decal(ClientNuclear.Active a, float partial) {
        if (!WaterDecal.wanted()) return;
        Detonation d = a.d;
        double ticks = a.ticks(partial), slant = d.frontRadius(ticks), hob = Math.max(0, d.burst().y - d.groundY());
        if (slant <= hob) return;
        double r = Math.sqrt(slant * slant - hob * hob);
        if (!profile(d, ticks, r, hob, DARK, FOAM)) return;
        WaterDecal.ring(d.burst().x, d.burst().z, r, r, DARK, FOAM);
    }

    /**
     * Profile of a water ring of radius {@code r} blocks at {@code ticks}: samples behind the front at
     * {@link WaterDecal#sampleAt} blocks. False — no visible ring (water is calm everywhere).
     */
    static boolean profile(Detonation d, double ticks, double r, double hob, float[] dark, float[] foam) {
        float most = 0;
        for (int i = 0; i < dark.length; i++) {
            double behind = WaterDecal.sampleAt(i, r), ground = Math.max(0, r - behind), slant = Math.hypot(ground, hob);
            // samples are at or behind the front: arrival-table rounding cannot place them ahead
            double since = Math.max(0, ticks - d.arrivalTicks(slant)), wind = wind(d, slant);
            dark[i] = (float) WaterModel.darkness(wind, since, BAND, CALM);
            foam[i] = (float) WaterModel.whitecaps(wind, since, CALM);
            most = Math.max(most, Math.max(dark[i], foam[i]));
        }
        return most > 0.004f;
    }

    /**
     * Scenario line: water decal, spray locations per tick, spray and ground-mist particles; per detonation — water beneath it, column
     * (top, outer radius, water fraction not yet fallen; model m), base surge, wall head.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            double t = a.seconds(0);
            WaterModel.Column c = a.water.wet() > 0 ? WaterModel.column(t, a.d.yieldKt()) : null;
            String column = c == null ? "none" : String.format(Locale.ROOT, "top %.0f m, r %.0f m, water %.2f", c.top(), c.outer(), c.water());
            BlastWall.Wall w = a.wall.wall(t);
            sb.append(String.format(Locale.ROOT, " [#%d t=%.1fs wet=%.2f (%s) column %s, surge r %.0f m, wall=%.0f]", a.d.id(),
                    t, a.water.wet(), a.water.source(), column, WaterModel.surgeRadius(t, a.d.yieldKt()) * (a.water.wet() > 0 ? 1 : 0),
                    w.head()));
        }
        Minecraft mc = Minecraft.getInstance();
        int live = mc.level == null ? 0 : ua.zentix.incoming.client.fx.particle.FxPool.INSTANCE.live(FxBudget.SPRAY);
        int dust = mc.level == null ? 0 : ua.zentix.incoming.client.fx.particle.FxPool.INSTANCE.live(FxBudget.GROUND);
        return String.format(Locale.ROOT, "decal %s, spray spots %d, spray particles %d, ground dust particles %d%s", WaterDecal.describe(), lastSpots,
                live, dust, sb);
    }
}
