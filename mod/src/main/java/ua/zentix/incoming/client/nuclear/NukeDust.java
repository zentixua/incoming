package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.Fragments;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.radiation.Shielding;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Shock-wave dust wall up close. While the wall head is within {@link #NEAR} of the camera, scraps of litter and material torn
 * from the ground fly along its nearby arc. Blocks demolished by the wave (named by the server, {@code S2C.NukeDebris}) break
 * into pieces of their material when the front reaches them, carried by wind behind the front ({@link BlastGust}): leaves, boards,
 * glass, rubble ({@code Fragments}); pulverized material leaves the breach as a downwind streak of effects-layer billows
 * ({@link BlastDust}). While the wall passes the player, ground litter is carried away from ground zero. The pall volume renders
 * the layer dust at the camera ({@link NukePall#drawsHead}), like the distant wall head: one medium, one rendering.
 * Billow quads a step or two from the eye and wider than the frame would add a second dust over it — texture pixels stair-stepping across the
 * screen, dark balls above the wall top. Only when the pall is not rendered (quality OFF, broken noise) does camera dust use
 * a fallback: billows along the head arc over its full height, billows around the player while the wall head is there (no higher than its top), and
 * a full-screen veil (covers in a few ticks, fades in a few seconds).
 */
public final class NukeDust {
    private static final RandomSource RANDOM = RandomSource.create();
    /** Within this many blocks of the camera, the wall head spawns dust and debris (farther particles are hidden by the wall itself). */
    static final double NEAR = 160;
    /** Dust-location spacing along the front arc near the camera, blocks. */
    private static final double STEP = 9;
    /** Maximum dust locations per tick. */
    private static final int MAX_SPOTS = 40;
    /**
     * Ground litter begins at this minimum front pressure (dynamic pressure ~0.1 psi), psi: grass, litter, foliage are
     * light plates ({@code Fragments.Kind.LEAF}) that wind can carry; clods of dirt are too heavy.
     */
    private static final double TEARS_PSI = 1.5;
    /** Maximum front-dust speed, blocks per tick: near ground zero the front travels tens of blocks per tick. */
    private static final double DUST_SPEED = 4;
    /**
     * Demolished debris: all within this many blocks of the camera; farther away, sparser by distance squared (distant chunks would
     * otherwise occupy every fragment slot before the front reaches nearby ones).
     */
    private static final double DEBRIS_FULL = 48;
    /** Breach dust within {@link #DEBRIS_FULL}: one streak per cluster in a cell this many blocks wide; beyond — one per chunk. */
    private static final int CELL = 4;
    /**
     * Mineral material ({@link Shielding#material}: soil 1600, stone and brick 2300) starts at this density, kg/m³; metal
     * (7800) starts at {@link #METAL}: only mineral material crumbles into dust, and only masonry, not natural ground ({@link #ground}).
     */
    private static final double MINERAL = 1500, METAL = 7000;
    /** Chunk ruins appeared more than this many ticks after the front: no flying debris, because the wave has already passed. */
    static final int DEBRIS_LATE = 20;
    /**
     * Dark dust billows (with soil and litter): share of wall color at the start and end of life; smoke during wall passage is
     * halfway grayer than the wall, and darker.
     */
    private static final float DARK_FROM = 0.67f, DARK_TO = 0.74f, SMOKE_GREY = 0.5f, SMOKE_FROM = 0.55f, SMOKE_TO = 0.75f;
    /**
     * Eye veil ({@link #veil}): coverage gained and lost per tick (fraction); opacity is
     * dust over VEIL_DEPTH blocks in front of the eye, capped at VEIL_MAX.
     */
    private static final float VEIL_RISE = 0.12f, VEIL_DECAY = 0.96f, VEIL_MAX = 0.85f;
    private static final double VEIL_DEPTH = 6;
    /** On-screen veil, its target (decays by VEIL_DECAY per tick), and what the head at the eye requested last tick. */
    private static float veil, veilTarget, veilWanted;
    /** Veil color over water — gray-white spray; over land — wall color at the eye. */
    private static final float[] VEIL_SPRAY = {0xAE / 255f, 0xB8 / 255f, 0xBE / 255f};
    private static final float[] veilRgb = new float[3];
    /** Wall slot parameters for fallback billows around the player ({@link #gust}). */
    private static final float[] GUST_WALL = new float[BlastWall.SLOT];
    /** Camera breach dust per detonation ({@link BlastDust}): cloud source while it contains dust. */
    private static final Map<ClientNuclear.Active, BlastDust> DUST = new IdentityHashMap<>();
    /** For the scenario line: debris packets, their locations, pieces by material, and dust streaks since the last reset. */
    private static int packets, places, streaks;
    private static final int[] TORN = new int[Fragments.Kind.values().length];

    private NukeDust() {}

    /**
     * Wall head at the eye (tick, {@link NukePall#tick}): mean head there with extinction {@code sigma} (1/block) — full-screen veil
     * in wall color at the eye ({@link NukePall#dustColor}); over water, spray. Only while pall is disabled
     * (quality OFF) or not rendered (broken noise): otherwise the pall itself renders the head at the eye and the screen would darken twice. Nothing where the head has not arrived or wind
     * no longer lofts dust (beyond ~0.3 psi only sound reaches the eye).
     */
    static void veil(ClientNuclear.Active a, Vec3 eye, double sigma, boolean spray) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || sigma <= 0) return;
        if (spray) {
            System.arraycopy(VEIL_SPRAY, 0, veilRgb, 0, 3);
        } else {
            NukePall.Sky sky = new NukePall.Sky();
            NukePall.sky(level, eye, 0, sky);
            if (!NukePall.dustColor(a, sky, eye.x, eye.y, eye.z, veilRgb)) return;
        }
        veilWanted = Math.max(veilWanted, (float) Math.min(VEIL_MAX, 1 - Math.exp(-sigma * VEIL_DEPTH)));
    }

    /**
     * Tick: wall head at the camera. Billows spawn along the front arc within {@link #NEAR} of the camera — denser near ground, larger above,
     * moving away from ground zero more slowly than the front and trailing behind it, rising near ground (roller); where the wave is stronger,
     * dark scraps of litter and material torn from the ground surface (turf, litter, foliage) fly with them. Terrain uses the heightmap of
     * loaded client chunks (under a ceiling, ground-zero height). Billows use wall color at their location
     * ({@link #wallColor}: frame wall light), not the lightmap.
     */
    static void front(ClientNuclear.Active a, Vec3 eye) {
        Detonation d = a.d;
        BlastWall.Wall w = a.wall.wall(a.seconds(0));
        if (w.density() < 0.05) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        double ex = eye.x - d.burst().x, ez = eye.z - d.burst().z, l = Math.sqrt(ex * ex + ez * ez), r = w.head();
        if (Math.abs(l - r) > NEAR) return;
        // front arc within NEAR of the camera: half-angle measured from the camera direction
        double half = l < 1 ? Math.PI : Math.acos(Mth.clamp((l * l + r * r - NEAR * NEAR) / (2 * l * r), -1, 1));
        double toward = Math.atan2(ez, ex);
        double ticks = a.ticks(0);
        double speed = Math.min(DUST_SPEED, d.frontRadius(ticks + 1) - d.frontRadius(ticks));
        // under a ceiling (Nether) the heightmap top is the ceiling: dust at ground-zero height, no surface pieces
        boolean ceiling = level.dimensionType().hasCeiling();
        int floor = level.getMinBuildHeight() + 1;
        int n = Math.min(MAX_SPOTS, (int) Math.round(2 * half * r / STEP * w.density() * Fx.density(eye)));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        NukePall.Sky sky = new NukePall.Sky();
        NukePall.sky(level, eye, 0, sky);
        float[] from = new float[3], to = new float[3];
        boolean puffs = !NukePall.drawsHead(a, eye);
        for (int i = 0; i < n; i++) {
            double ang = toward + (RANDOM.nextDouble() * 2 - 1) * half;
            double cos = Math.cos(ang), sin = Math.sin(ang);
            double rr = r - RANDOM.nextDouble() * Math.min(12, w.height() * 0.3);
            double x = d.burst().x + cos * rr, z = d.burst().z + sin * rr;
            if ((x - eye.x) * (x - eye.x) + (z - eye.z) * (z - eye.z) > NEAR * NEAR) continue;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null) continue;
            // no dust over water: spray there (NukeWater)
            if (!ceiling && NukeWater.water(chunk, bx, bz, m)) continue;
            int top = ceiling ? floor : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 1;
            boolean surface = top > floor;
            if (!surface) top = Mth.floor(d.groundY());
            if (puffs) {
                // denser near ground; higher billows are larger; near ground they rise, at the top they move back with the wall
                double up = Math.pow(RANDOM.nextDouble(), 1.6), h = w.height() * 0.8 * up;
                double v = speed * (0.25 + 0.45 * RANDOM.nextDouble());
                boolean dark = RANDOM.nextFloat() < 0.35f;
                // wall-head color at the billow location; dispersing billow brightens like the wall higher up
                double y = top + 0.5 + h;
                if (!wallColor(a, sky, x, y, z, dark ? DARK_FROM : 1, from)
                        || !wallColor(a, sky, x, y + 0.4 * w.height(), z, 1.05f * (dark ? DARK_TO : 1), to)) continue;
                Fx.smoke().vel(cos * v, (0.04 + 0.2 * RANDOM.nextDouble()) * (1 - up), sin * v)
                        .size((float) (1.5 + 0.06 * h), (float) (5 + Math.min(10, w.height() * 0.08) + 3 * RANDOM.nextDouble())).growFast()
                        .life(50 + RANDOM.nextInt(50)).color(from, to).prelit().alpha(0.8f).drag(0.95f).smooth()
                        .collide().wind(0).fadeIn(3).fadeFrom(0.45f).budget(FxBudget.GROUND).spawn(level, x, y, z);
            }
            Vec3 at = new Vec3(x, top, z);
            double psi = d.psi(at);
            if (psi < 1) continue;
            double thrown = Detonation.throwSpeed(d.dynamicPressureKpa(at));
            if (RANDOM.nextFloat() < 0.6f) {
                // litter scraps: small, dark, heavy — travel farther than dust and fall
                double u = thrown * (0.4 + 0.7 * RANDOM.nextDouble());
                Fx.smoke().vel(cos * u, u * (0.2 + 0.4 * RANDOM.nextDouble()), sin * u).size(0.12f, 0.2f).life(30 + RANDOM.nextInt(30))
                        .color(0x2A2420, 0x3A322C).alpha(0.95f).drag(0.97f).gravity(0.05f).collide().wind(0).fadeIn(0).fadeFrom(0.8f).spin(0.3f)
                        .budget(FxBudget.DEBRIS).spawn(level, x, top + 0.5 + RANDOM.nextDouble() * 2, z);
            }
            if (surface && psi >= TEARS_PSI && RANDOM.nextFloat() < 0.5f) {
                // ground-surface material starts at rest and is picked up by wind behind the front
                m.set(bx, top - 1, bz);
                BlockState cover = chunk.getBlockState(m);
                if (litter(cover)) Fragments.torn(level, cover, m, Fragments.Kind.LEAF, x, top + 0.3, z, 0, 0, 0, 0, BlastGust.at(a, at), 0, false);
            }
        }
    }

    /**
     * Blocks demolished by the wave (server to chunk viewers, {@code S2C.NukeDebris}: sampled locations and total demolished
     * count): each sampled location breaks into initially stationary pieces of its material when reached by the front, then local wind behind
     * the front carries them ({@link BlastGust}); sparser at distance. Pulverized masonry dust forms a downwind
     * streak ({@link BlastDust}): mass accounts for every demolished location represented by the sample.
     */
    static void debris(ClientNuclear.Active a, S2C.NukeDebris packet) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Detonation d = a.d;
        double now = a.ticks(0), weight = packet.weight();
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        // breach dust: clustered by cells near the camera, farther away one per chunk (mass, center and position spread)
        Map<Long, double[]> heaps = new HashMap<>();
        packets++;
        for (S2C.NukeDebris.Piece p : packet.pieces()) {
            Vec3 c = Vec3.atCenterOf(p.pos());
            double wait = d.arrivalTicks(c.distanceTo(d.burst())) - now;
            if (wait < -DEBRIS_LATE) continue;
            int delay = Mth.clamp((int) Math.ceil(wait), 0, Fragments.DELAY_MAX);
            BlastGust gust = BlastGust.at(a, c);
            Fragments.Kind kind = Fragments.Kind.of(level, p.pos(), p.state());
            double dist = c.distanceTo(eye);
            // small pieces are invisible at distance: spawn fewer
            double expect = kind.perBlock * Fx.density(c) * Math.min(1, DEBRIS_FULL * DEBRIS_FULL / (dist * dist));
            int n = (int) expect + (RANDOM.nextDouble() < expect - (int) expect ? 1 : 0);
            places++;
            for (int i = 0; i < n; i++) {
                if (Fragments.torn(level, p.state(), p.pos(), kind, c.x + (RANDOM.nextDouble() - 0.5) * 0.6, c.y + (RANDOM.nextDouble() - 0.5) * 0.6,
                        c.z + (RANDOM.nextDouble() - 0.5) * 0.6, 0, 0, 0, 0.25f + 0.35f * RANDOM.nextFloat(), gust, delay, !ground(p.state()))) TORN[kind.ordinal()]++;
            }
            if (dist > NEAR || kind != Fragments.Kind.CHUNK) continue;
            // masonry crumbles into dust — stone, brick, concrete; wood, ice and metal do not; natural soil and rock are dust of
            // the pall itself (ground entrainment, DustModel), not debris
            double material = Shielding.material(level, p.pos(), p.state());
            if (material < MINERAL || material >= METAL || ground(p.state())) continue;
            double mass = weight * BlastDust.FINES * Shielding.density(level, p.pos(), p.state());
            long key = dist <= DEBRIS_FULL ? BlockPos.asLong(Math.floorDiv(p.pos().getX(), CELL), Math.floorDiv(p.pos().getY(), CELL),
                    Math.floorDiv(p.pos().getZ(), CELL)) : Long.MIN_VALUE;
            double[] h = heaps.computeIfAbsent(key, k -> new double[5]);
            h[0] += mass;
            h[1] += mass * c.x;
            h[2] += mass * c.y;
            h[3] += mass * c.z;
            h[4] += mass * c.lengthSqr();
        }
        if (heaps.isEmpty()) return;
        BlastDust dust = DUST.get(a);
        if (dust == null || dust.ended()) {
            DUST.put(a, dust = new BlastDust(a));
            SmokeGrid.add(dust);
        }
        for (double[] h : heaps.values()) {
            if (h[0] <= 0) continue;
            Vec3 mid = new Vec3(h[1] / h[0], h[2] / h[0], h[3] / h[0]);
            // cluster position spread is its initial size (σ on each axis)
            dust.add(mid, h[0], Math.sqrt(Math.max(0, h[4] / h[0] - mid.lengthSqr()) / 3), eye);
            streaks++;
        }
    }

    /**
     * Natural ground and rock: soil, sand and sandstone, gravel, clay, stone, deepslate, ores, calcite and terrain dripstone,
     * plateau terracotta, snow (and hay bales, not masonry despite their earthy sound). The wave tears away
     * terrain crests and ledges, but ground dust is pall entrainment ({@code DustModel}: soil lofted by wind behind the front); model debris
     * dust represents clay walls and building plaster, supplied by masonry. Without this, torn hill ledges produced tons
     * of dust streaks with the camera inside ({@code film} scene).
     */
    private static boolean ground(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER)
                || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.SNOW) || s.is(net.neoforged.neoforge.common.Tags.Blocks.SANDSTONE_UNCOLORED_BLOCKS)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.SANDSTONE_RED_BLOCKS) || s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.GRAVELS) || s.is(net.minecraft.world.level.block.Blocks.CLAY)
                || s.is(net.minecraft.world.level.block.Blocks.CALCITE) || s.is(net.minecraft.world.level.block.Blocks.DRIPSTONE_BLOCK)
                || s.is(net.minecraft.world.level.block.Blocks.HAY_BLOCK);
    }

    /**
     * Whether top block {@code s} has material wind can tear off: turf, litter, crown foliage — light plates. No pieces from stone, sand,
     * gravel or snow: sand and snow enter the layer as dust and spray (pall), while stone is too heavy for wind.
     */
    private static boolean litter(BlockState s) {
        return s.is(BlockTags.LEAVES) || s.is(BlockTags.DIRT);
    }

    /**
     * Camera dust color: dust-wall color at world point (x, y, z) ({@link NukePall#dustColor}) × {@code dark}; false — the wall
     * is gone.
     */
    private static boolean wallColor(ClientNuclear.Active a, NukePall.Sky sky, double x, double y, double z, float dark, float[] into) {
        if (!NukePall.dustColor(a, sky, x, y, z, into)) return false;
        for (int i = 0; i < 3; i++) into[i] = Math.min(1, into[i] * dark);
        return true;
    }

    /**
     * One tick of wall passage: ground litter flies away from ground zero; fallback dust and smoke billows (wall color at the player)
     * exist only inside the wall head, based on its mean density at the billow position: no dust above its top, only wind reaches there.
     */
    static void gust(ClientNuclear.Active a, Vec3 ear, double psi, int age) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Detonation d = a.d;
        boolean ceiling = level.dimensionType().hasCeiling();
        // over water — spray and mist, no ground litter
        boolean wet = !ceiling && NukeWater.waterAround(level, ear.x, ear.z);
        boolean puffs = !NukePall.drawsHead(a, ear);
        if (!puffs && (ceiling || wet)) return;
        float[] dust0 = new float[3], dust1 = new float[3], smoke0 = new float[3], smoke1 = new float[3];
        if (puffs) {
            if (!a.wall.slot(a.seconds(0), GUST_WALL)) return;
            NukePall.Sky sky = new NukePall.Sky();
            NukePall.sky(level, ear, 0, sky);
            double higher = 0.5 * GUST_WALL[2];
            if (!wallColor(a, sky, ear.x, ear.y, ear.z, 1.05f, dust0) || !wallColor(a, sky, ear.x, ear.y + higher, ear.z, 1, dust1)) return;
            grey(dust0, SMOKE_FROM, smoke0);
            grey(dust1, SMOKE_TO, smoke1);
        }
        // wave wind at the player carries ground litter
        BlastGust wind = BlastGust.at(a, ear);
        double[] air = new double[3];
        wind.at(ear.x, ear.y, ear.z, air);
        Vec3 away = new Vec3(ear.x - d.burst().x, 0, ear.z - d.burst().z);
        away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
        double speed = Mth.clamp(0.4 + psi * 0.25, 0.4, 2.5) * (1 - age / 80.0);
        int n = (int) (Mth.clamp(psi * 12, 12, 90) * (1 - age / 60.0));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            // spawn upwind and to the sides, fly past
            double along = -14 + RANDOM.nextDouble() * 20, side = (RANDOM.nextDouble() - 0.5) * 30, up = -2 + RANDOM.nextDouble() * 8;
            Vec3 p = ear.add(away.scale(along)).add(-away.z * side, up, away.x * side);
            double v = speed * (0.7 + RANDOM.nextDouble() * 0.6);
            if (i % 5 == 0 && !ceiling && !wet) {
                // upwind ground litter — grass, foliage, litter — already travels with the wind and tumbles
                int bx = Mth.floor(p.x), bz = Mth.floor(p.z);
                LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
                if (chunk == null) continue;
                m.set(bx, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15), bz);
                BlockState cover = chunk.getBlockState(m);
                if (litter(cover)) Fragments.torn(level, cover, m, Fragments.Kind.LEAF, p.x, Math.max(p.y, m.getY() + 1.2), p.z, air[0], 0, air[2], 0,
                        wind, 0, false);
            } else if (puffs && RANDOM.nextDouble() < inHead(a, GUST_WALL, p)) {
                // brown dust and gray smoke billows are the wall passing through the player (wall color, with light); over
                // water — spray and mist
                boolean smoke = i % 5 == 1;
                Fx.Spec puff = Fx.smoke().vel(away.x * v, 0.01 + RANDOM.nextDouble() * 0.04, away.z * v).size(1.2f, 5 + RANDOM.nextFloat() * 3).growFast();
                if (wet) puff.life(40 + RANDOM.nextInt(40)).color(0xC4CDD3, 0xEEF2F4).alpha(0.55f).budget(FxBudget.SPRAY);
                else puff.life(70 + RANDOM.nextInt(90)).color(smoke ? smoke0 : dust0, smoke ? smoke1 : dust1).prelit().alpha(0.7f).budget(FxBudget.GROUND);
                puff.drag(0.97f).smooth().collide().wind(0).fadeIn(2).fadeFrom(0.35f).spawn(level, p);
            }
        }
    }

    /**
     * Mean wall-head share at world point {@code p}, 0..1 ({@link BlastWall#sigma} relative to its density there): {@code slot} contains
     * wall slot parameters ({@link BlastWall#slot}); terrain and water below the point come from the pall map.
     */
    private static double inHead(ClientNuclear.Active a, float[] slot, Vec3 p) {
        double wet = a.ground.hasWater() ? a.ground.water(p.x, p.z) : 0;
        double k = slot[3] + (slot[7] - slot[3]) * wet;
        if (k <= 0) return 0;
        Detonation d = a.d;
        return BlastWall.sigma(slot, p.x - d.burst().x, p.z - d.burst().z, p.y - d.groundY() - a.ground.at(p.x, p.z), wet) / k;
    }

    /** Wall smoke: its color halfway toward gray of equal brightness ({@link #SMOKE_GREY}), darkened by factor {@code k}. */
    private static void grey(float[] c, float k, float[] into) {
        float luma = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        for (int i = 0; i < 3; i++) into[i] = Mth.lerp(SMOKE_GREY, c[i], luma) * k;
    }

    static void tick() {
        DUST.values().removeIf(BlastDust::ended);
        // head at the eye holds the veil; once gone, the veil fades (fading while held would flicker every other tick)
        veilTarget = Math.max(veilTarget * VEIL_DECAY, veilWanted);
        veilWanted = 0;
        // wall covers the screen over a few ticks, not in a flash
        veil = veil < veilTarget ? Math.min(veilTarget, veil + VEIL_RISE) : veilTarget;
        if (veil < 0.01f) veil = veilTarget = 0;
    }

    /**
     * Scenario line: debris packets and their locations, pieces by material (rubble, board, glass, leaf), living pieces,
     * total and current breach-dust streaks (with maximum transverse optical depth).
     */
    public static String describe() {
        StringBuilder live = new StringBuilder();
        for (BlastDust d : DUST.values()) live.append(' ').append(d.describe());
        return String.format(java.util.Locale.ROOT, "debris packets %d, places %d, torn chunk %d board %d pane %d leaf %d (alive %d), dust streaks %d;%s",
                packets, places, TORN[0], TORN[1], TORN[2], TORN[3], Fragments.alive(), streaks, live);
    }

    static void reset() {
        veil = veilTarget = veilWanted = 0;
        for (BlastDust dust : DUST.values()) dust.end();
        DUST.clear();
        packets = places = streaks = 0;
        java.util.Arrays.fill(TORN, 0);
    }

    /** Veil over the world (under the flash): wall color at the eye, gray-white over water. */
    static void render(GuiGraphics g) {
        if (veil <= 0) return;
        int a = (int) (veil * 255), rgb = 0;
        for (int i = 0; i < 3; i++) rgb = rgb << 8 | Mth.clamp(Math.round(veilRgb[i] * 255), 0, 255);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | rgb);
    }
}
