package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.nuclear.model.Yield;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.FlashScorch;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.RuinPlanner;
import ua.zentix.incoming.nuclear.world.RuinWorkers;
import ua.zentix.incoming.nuclear.world.ScarQueue;
import ua.zentix.incoming.nuclear.world.WorkClock;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A trace of the light pulse on the ground: scorched grass, smoke, burnt plants and scorched biomes - in steps from the center to the
 *  edge, with a torn edge (curves of defeat with the noise of the world), and not a ring along the threshold.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScorchGameTests {
    private ScorchGameTests() {}

    private static final ResourceKey<Biome> SINGED = key("singed"), SCORCHED = key("scorched"), CHARRED = key("charred");
    /**
     * The epicenter is at the western edge of the site, at the level of its ground (structure block under the template: template turf - y 11, top
     *  - 12): the wall of barriers around the site would otherwise cover the nearby ground from the ball.
     */
    private static final BlockPos GROUND_ZERO = new BlockPos(2, 12, 32);
    /** Rings by distance from the epicenter, blocks: from 0 to 72. */
    private static final int RING = 8, RINGS = 9, FIRST = 0;
    /** The height of checking the biome underground: the cell there is entirely below {@code ScorchedBiomes.BELOW} from the top of the turf. */
    private static final int DEEP = -1;

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Incoming.id(name));
    }

    /**
     * 15 kt at the optimal height on a scale where the site is from the epicenter (light ~100 cal/cm², the turf is torn off) to ~3.5 km
     * (~3 cal/cm²). The turf burns out at the center and survives at the edge, and in between there are spots (a ring where there is both grass and earth); burning —
     *  only closer to the center; the grass and flowers above the ground were burned at the center (the upper halves of the tall ones - together with the lower ones,
     *  part of it was a dry bush), standing at the edge. Biome - in steps from charred to scorched, seas, rivers, desert and
     * snowy plains are untouched, underground - too; in the chunk data there are new biomes.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_scorch", skyAccess = true)
    public static void scorchFadesOutwardInPatches(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // biomes: near rows - plain (its light changes), distant stripes - snow, river, desert (there are none)
        fillBiome(h, 0, 39, Biomes.PLAINS);
        fillBiome(h, 40, 47, Biomes.SNOWY_PLAINS);
        fillBiome(h, 48, 55, Biomes.RIVER);
        fillBiome(h, 56, 63, Biomes.DESERT);
        plants(h);
        // biome in 12 blocks underground (under the platform stone) - before impact; Is the ground flat: the cells of the biome are 4×4 according to the
        //  world grid, and the site stands as it should - at the border of the stripes in the “plain” row there is a snow cell
        int[] deep = new int[64 * 64];
        for (int x = 0; x < 64; x += 4) for (int z = 0; z < 64; z += 4) deep[x * 64 + z] = biomeAt(h, x, DEEP, z).hashCode();
        boolean[] plains = new boolean[64 * 64];
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) plains[x * 64 + z] = biomeAt(h, x, 12, z).is(Biomes.PLAINS);
        double scale = 0.0182;
        Detonation d = NuclearGameTests.detonation(h, GROUND_ZERO, Yield.optimalBurstHeight(15) * scale, 15, (float) scale);
        NuclearGameTests.scarAll(h, d, new ColumnScar.Budget(true));

        int[] grass = new int[RINGS], burnt = new int[RINGS], charred = new int[RINGS], plants = new int[RINGS], plantsLeft = new int[RINGS];
        int[] cells = new int[RINGS], levels = new int[RINGS];
        int bushes = 0;
        double[] fluence = new double[RINGS], psi = new double[RINGS];
        int[] coarse = new int[RINGS], seen = new int[RINGS];
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                int ring = (int) ((Math.hypot(x - GROUND_ZERO.getX(), z - GROUND_ZERO.getZ()) - FIRST) / RING);
                if (ring < 0 || ring >= RINGS) continue;
                BlockState top = h.getBlockState(new BlockPos(x, 11, z)), above = h.getBlockState(new BlockPos(x, 12, z));
                BlockState upper = h.getBlockState(new BlockPos(x, 13, z));
                if (top.is(Blocks.GRASS_BLOCK)) grass[ring]++;
                else if (top.is(Blocks.PODZOL)) charred[ring]++;
                else if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT)) burnt[ring]++;
                plants[ring]++;
                if (above.is(Blocks.SHORT_GRASS) || above.is(Blocks.TALL_GRASS)) plantsLeft[ring]++;
                if (above.is(Blocks.DEAD_BUSH)) bushes++;
                // the top half without the bottom would hang in the air
                if (upper.is(Blocks.TALL_GRASS) && !above.is(Blocks.TALL_GRASS)) {
                    h.fail("top of tall grass with no bottom in " + x + ", " + z + " (below " + above + ")");
                }
                net.minecraft.world.phys.Vec3 at = net.minecraft.world.phys.Vec3.atCenterOf(h.absolutePos(new BlockPos(x, 12, z)));
                fluence[ring] = Math.max(fluence[ring], d.fluence(at));
                psi[ring] = Math.max(psi[ring], d.psi(at));
                if (top.is(Blocks.COARSE_DIRT)) coarse[ring]++;
                if (ua.zentix.incoming.nuclear.world.ThermalShadow.visible(level, d.burst(), at.add(0, -0.5, 0))) seen[ring]++;
                // biome - near the plain, without cells at the edge of the site (there are also columns behind the barrier wall, in the shadows)
                if (plains[x * 64 + z] && z >= 4 && x >= 4 && x < 60) {
                    cells[ring]++;
                    levels[ring] += level(biomeAt(h, x, 12, z));
                }
            }
        }
        StringBuilder table = new StringBuilder();
        for (int r = 0; r < RINGS; r++) {
            int n = grass[r] + burnt[r] + charred[r];
            table.append(String.format(Locale.ROOT, "%n %d–%d blocks, light up to %.1f, pressure up to %.1f, see ball %d: turf %d, earth %d (large %d), burnt %d; plants %d from %d; biome %.2f",
                    FIRST + r * RING, FIRST + (r + 1) * RING, fluence[r], psi[r], seen[r], grass[r], burnt[r], coarse[r], charred[r], plantsLeft[r], plants[r],
                    cells[r] == 0 ? 0 : levels[r] / (double) cells[r]));
            h.assertTrue(n == plants[r], "in the ring " + r + " the top of the platform is not turf or earth: " + (plants[r] - n) + table);
        }
        String why = table.toString();
        Incoming.LOG.info("Light trail along the rings:{}", why);
        double first = 1 - grass[0] / (double) plants[0], last = 1 - grass[RINGS - 1] / (double) plants[RINGS - 1];
        h.assertTrue(first >= 0.8, "near the center the turf survived: " + why);
        h.assertTrue(last <= 0.05, "the turf at the edge is burnt out: " + why);
        boolean patchy = false;
        double prev = 1;
        for (int r = 0; r < RINGS; r++) {
            double share = 1 - grass[r] / (double) plants[r];
            if (share >= 0.1 && share <= 0.9) patchy = true;
            h.assertTrue(share <= prev + 0.1, "there is more burnt out further from the center than closer (ring " + r + "): " + why);
            prev = share;
        }
        h.assertTrue(patchy, "the edge of the burnt - in a ring along the threshold, without stains: " + why);
        h.assertTrue(charred[1] + charred[2] + charred[3] > 0, "the center has no burning: " + why);
        h.assertTrue(charred[RINGS - 1] + charred[RINGS - 2] + charred[RINGS - 3] == 0, "burning at the edge: " + why);
        h.assertTrue(plantsLeft[0] <= plants[0] * 0.1, "the grass near the center did not burn: " + why);
        h.assertTrue(plantsLeft[RINGS - 1] + plantsLeft[RINGS - 2] >= (plants[RINGS - 1] + plants[RINGS - 2]) * 0.75, "the grass at the edge was burnt: " + why);
        h.assertTrue(bushes > 0, "not a single dry bush: " + why);
        // biome: charred at the center, gradually weaker towards the edge
        double prevLevel = 4;
        for (int r = 0; r < RINGS; r++) {
            if (cells[r] < 16) continue;
            double mean = levels[r] / (double) cells[r];
            h.assertTrue(mean <= prevLevel + 0.3, "the biome further from the center is more scorched (ring " + r + "): " + why);
            prevLevel = mean;
        }
        h.assertTrue(levels[0] >= cells[0] * 2.5, "near the center the biome is not charred: " + why);
        h.assertTrue(levels[RINGS - 2] <= cells[RINGS - 2] * 1.5, "near the edge the biome is heavily scorched: " + why);
        // stripes that light does not recolor, and biomes underground; biome cells at the edge of the site are taken and columns behind its
        //  wall (the site stands in chunks as necessary) - their land and biome are not the same as those at the site, so there is an indentation from the edge
        for (int x = 4; x < 60; x += 2) {
            assertBiome(h, x, 44, Biomes.SNOWY_PLAINS);
            assertBiome(h, x, 51, Biomes.RIVER);
            assertBiome(h, x, 60, Biomes.DESERT);
        }
        for (int x = 4; x < 60; x += 4) {
            for (int z = 4; z < 60; z += 4) {
                if (biomeAt(h, x, DEEP, z).hashCode() != deep[x * 64 + z]) h.fail("the underground biome has changed to " + x + ", " + z + ": " + biomeAt(h, x, DEEP, z));
            }
        }
        // in the data of the chunk near the center there are new biomes (for saving the ruin chunk is marked with a ruins mark)
        LevelChunk chunk = level.getChunkAt(h.absolutePos(new BlockPos(2, 11, 32)));
        String saved = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk).toString();
        h.assertTrue(saved.contains(CHARRED.location().toString()), "there is no charred biome in the chunk data");
        h.succeed();
    }

    /**
     * The trace of light is a property of the terrain, not the detonation: the ruins are built in advance at the site of the detonation without its seed
     *  ({@code NuclearWarhead.geometry}), and the chunk loaded after the ruins of preparation are forgotten is based on the
     *  detonation itself; their spots and biomes are the same, otherwise at the border of such chunks the trail would end straight.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_scorch_seed", skyAccess = true)
    public static void scorchSameForAnySeed(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        double scale = 0.0182;
        Detonation d = NuclearGameTests.detonation(h, GROUND_ZERO, Yield.optimalBurstHeight(15) * scale, 15, (float) scale);
        Detonation other = new Detonation(NuclearGameTests.IDS.incrementAndGet(), d.burst(), d.groundY(), d.yieldKt(), d.surface(),
                d.gameTime(), d.windDir(), d.windSpeed(), d.visibility(), d.seed() * 31 + 12345, d.scale(), d.fallout(), d.wave());
        int changed = 0;
        for (LevelChunk chunk : NuclearGameTests.chunks(h)) {
            RuinPlan a = RuinPlanner.plan(level, d, chunk), b = RuinPlanner.plan(level, other, chunk);
            String diff = b.differs(a);
            if (diff != null) h.fail("chunk " + chunk.getPos() + ": the trace of another seed is not the same (another | this; y - from the bottom of the world): " + diff);
            changed += a.changedBlocks();
        }
        h.assertTrue(changed > 0, "there's no trace");
        h.succeed();
    }

    /**
     * The trail without ruins ({@code RuinPlanner.scorch}: LOD in the distance behind the heavy zone) is the same as the plan of the ruins, where the wave
     *  did not break anything: a field of grass on flat ground behind the heavy zone, from ~1,4 km (the turf burns out) to ~6 km. Light, slope,
     * spots, plants, fires and biomes are the same; plan without ruins - based on a photo of one chunk, without the neighbors' window. Detonation -
     * over the corner of the site farthest from the block of the structure: the light from the detonation beyond its edge would go through the buildings of neighboring batches
     *  (their order and place in each part of CI is different - a shadow on the entire site). The chunks are compared entirely on the site (at the extreme
     *  - the wall of barriers and neighbors), where the wave does not break even the plants: at the nearest column the pressure is lower than that from which the reflection on the open face reaches their threshold (~3,1 psi, at 15 kt - 2,1 km). Scale (1 block - 71 m) -
     *  so that there are at least 5 such chunks for any shift of the site to the grid of chunks (the location of the site is random).
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_scorch_far", skyAccess = true)
    public static void scorchWithoutRuinsSameAsRuins(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fillBiome(h, 0, 63, Biomes.PLAINS);
        plants(h);
        double scale = 0.014;
        Detonation d = NuclearGameTests.detonation(h, new BlockPos(60, 12, 60), Yield.optimalBurstHeight(15) * scale, 15, (float) scale);
        int changed = 0, biomes = 0, compared = 0;
        double plantBreak = RuinPlanner.testPlantBreakPsi();
        BlockPos a = h.absolutePos(BlockPos.ZERO), b = h.absolutePos(new BlockPos(63, 0, 63));
        for (LevelChunk chunk : NuclearGameTests.chunks(h)) {
            net.minecraft.world.level.ChunkPos c = chunk.getPos();
            if (c.getMinBlockX() < Math.min(a.getX(), b.getX()) || c.getMaxBlockX() > Math.max(a.getX(), b.getX())
                    || c.getMinBlockZ() < Math.min(a.getZ(), b.getZ()) || c.getMaxBlockZ() > Math.max(a.getZ(), b.getZ())) continue;
            double nx = Math.clamp(d.burst().x, c.getMinBlockX(), c.getMaxBlockX() + 1), nz = Math.clamp(d.burst().z, c.getMinBlockZ(), c.getMaxBlockZ() + 1);
            if (d.psi(new Vec3(nx, d.groundY() + 1, nz)) >= plantBreak) continue;
            compared++;
            RuinPlan ruins = RuinPlanner.plan(level, d, chunk), scorch = RuinPlanner.testScorch(level, d, chunk);
            String diff = scorch.differs(ruins);
            if (diff != null) h.fail("chunk " + chunk.getPos() + ": the trail without the ruins is not the same as the one with the ruins (trail | ruins; y - from the bottom of the world): " + diff);
            changed += ruins.changedBlocks();
            if (ruins.recolours()) biomes++;
        }
        h.assertTrue(compared >= 4 && changed > 0 && biomes > 0, "the trail is not clear: chunks " + compared + ", places " + changed + ", chunks with biomes " + biomes);
        Incoming.LOG.info("The trail without ruins coincided with the ruins of: chunks {}, places {}, chunks with biomes {}", compared, changed, biomes);
        h.succeed();
    }

    /**
     * Light - with a flash, wave - then: chunk A at ~15 cal/cm² is scorched and burns before the wave reaches it (layer
     *  of {@code FlashScorch} flash), and after the wave there is the same world in it, which would give one plan of ruins for the world before the flash - via
     * queue of ruins (plan in the background based on pictures of chunks on which there is already a layer). Chunk B, closest to the epicenter of those to which the wave reaches
     *  after the layer (a stronger wave knocks down part of the fire), is a plan ready before the outbreak in the ruins of the explosion
     *  (like the preparation of ruins during the ICBM flight): it does not become obsolete from the layer and gives the same world. Detonation - around the corner of three by three chunks
     *  platform, place - from the grid of chunks ({@link #gridSide});
     *  scale - such that the ground in the middle A has 15 cal/cm². Layer - only chunks entirely on the site: the fire behind
     *  its edge would burn in the neighboring batch.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_flash_scorch", skyAccess = true)
    public static void flashScorchesBeforeTheWaveAndRuinsMatch(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fillBiome(h, 0, 63, Biomes.PLAINS);
        plants(h);
        // Chunks and detonation are aligned to the chunk grid, not the test platform: the platform can start anywhere
        // relative to the grid. At the test scale, the layer at A appears on tick 1 (light reaches the threshold in
        // 0,1 tick), the wave reaches chunks after 1–3 ticks, and the wave deadline (ScarQueue.due) for A neighbours
        // is either 1 or 2: for ~5 % of platform offsets (CI 19.10, release 3.4.1), no chunk except A received the wave
        // after the layer. Alignment to the grid gives the same layout for every batch arrangement: 3 × 3 chunks
        // fully inside the platform and a detonation on their diagonal 5–6 blocks beyond the corner: B receives the
        // wave after 2,0 ticks, two A neighbours after 2,3, and A after 2,8.
        BlockPos lo = h.absolutePos(BlockPos.ZERO), hi = h.absolutePos(new BlockPos(63, 0, 63));
        int[] sx = gridSide(Math.min(lo.getX(), hi.getX()), Math.max(lo.getX(), hi.getX()));
        int[] sz = gridSide(Math.min(lo.getZ(), hi.getZ()), Math.max(lo.getZ(), hi.getZ()));
        BlockPos g = new BlockPos(sx[1], h.absolutePos(new BlockPos(0, 12, 0)).getY(), sz[1]);
        List<LevelChunk> inside = new ArrayList<>();
        for (int cx = sx[0]; cx < sx[0] + 3; cx++) {
            for (int cz = sz[0]; cz < sz[0] + 3; cz++) inside.add(level.getChunk(cx, cz));
        }
        inside.sort(Comparator.comparingDouble(c -> Math.hypot(c.getPos().getMiddleBlockX() - g.getX(), c.getPos().getMiddleBlockZ() - g.getZ())));
        LevelChunk a = inside.get(inside.size() - 1);
        Vec3 midA = new Vec3(a.getPos().getMiddleBlockX() + 0.5, g.getY() + 1, a.getPos().getMiddleBlockZ() + 0.5);
        // scale: 15 cal/cm² near the ground in the middle A (smaller scale - more meters in a block, less light)
        double sLo = 0.002, sHi = 1;
        for (int i = 0; i < 50; i++) {
            double s = Math.sqrt(sLo * sHi);
            Detonation probe = new Detonation(0, new Vec3(g.getX() + 0.5, g.getY() + Yield.optimalBurstHeight(15) * s, g.getZ() + 0.5), g.getY(), 15, false,
                    0, 0, 0, 20_000, 7, (float) s, false, Detonation.OLD_WAVE);
            if (probe.fluence(midA) > 15) sHi = s;
            else sLo = s;
        }
        Detonation d = NuclearGameTests.detonationAt(h, g, Yield.optimalBurstHeight(15) * sHi, 15, (float) sHi);
        // layer A appears when the light at the point A closest to the ball reaches the ignition threshold (as in FlashScorch); nearby chunks - no later
        double nx = Mth.clamp(d.burst().x, a.getPos().getMinBlockX(), a.getPos().getMaxBlockX() + 1);
        double nz = Mth.clamp(d.burst().z, a.getPos().getMinBlockZ(), a.getPos().getMaxBlockZ() + 1);
        long flashAt = d.gameTime() + (long) Math.ceil(d.flashTicks(d.fluence(new Vec3(nx, d.groundY() + 1, nz)), ThermalModel.IGNITE));
        // B - the chunk closest to the epicenter, to which the wave reaches after the layer (in the game it reaches the closest ones before the end of the
        //  pulse, and they get everything in ruins; the scale of the check compresses the time into a tick)
        LevelChunk b = null;
        for (LevelChunk c : inside) {
            if (c != a && ScarQueue.due(d, c.getPos()) > flashAt) {
                b = c;
                break;
            }
        }
        h.assertTrue(b != null, "there is no chunk to which the wave reaches after the layer");
        Vec3 midB = new Vec3(b.getPos().getMiddleBlockX() + 0.5, g.getY() + 1, b.getPos().getMiddleBlockZ() + 0.5);
        // windows of the ruins A and B - in memory immediately: behind the edge of the site, chunks are loaded in the background
        for (LevelChunk c : List.of(a, b)) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.getPos().x + dx, c.getPos().z + dz);
            }
        }
        // the world before the outbreak: what ruins alone would have made it (from scratch, your own ruins of an explosion)
        LevelChunkSection[] expectA = RuinPlanner.planFresh(level, d, a, false).testAppliedCopy(level, a);
        LevelChunkSection[] expectB = RuinPlanner.planFresh(level, d, b, false).testAppliedCopy(level, b);
        RuinPlan preparedB = RuinPlanner.plan(level, d, b);
        long[] keys = inside.stream().mapToLong(c -> c.getPos().toLong()).toArray();
        FlashScorch flash = FlashScorch.forTest(d, keys, new ColumnScar.Budget(true));
        WorkClock clock = WorkClock.counting(1_000_000L);
        long waveA = ScarQueue.due(d, a.getPos()), waveB = ScarQueue.due(d, b.getPos());
        String setup = String.format(Locale.ROOT, "scale %.4f, light for A %.1f, for B %.1f cal/cm², pressure for A %.2f, for B %.2f psi; impulse %.2f tick, layer A on %d, wave at A %d, at B %d",
                sHi, d.fluence(midA), d.fluence(midB), d.psi(midA), d.psi(midB), d.pulseTicks(), flashAt - d.gameTime(), waveA - d.gameTime(), waveB - d.gameTime());
        h.assertTrue(Math.abs(d.fluence(midA) - 15) < 0.5, "the light on the A is not the same: " + setup);
        h.assertTrue(flashAt < waveA, "the wave of A is before the layer - the check means nothing: " + setup);
        ScarQueue q = new ScarQueue();
        try {
            // layer of all chunks whose time has come to layer A (there are no further ones, except for A: the rest - not later)
            boolean done = false;
            for (int i = 0; i < 20 && !done; i++) {
                clock.start(10_000_000_000L);
                done = flash.work(level, flashAt, clock);
                if (!done) RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
            }
            h.assertTrue(flash.appliedChunks() >= 2, "the flash layer is stuck in " + flash.appliedChunks() + " chunks of " + keys.length + "; " + setup);
            int[] before = new int[2];
            for (int k = 0; k < 2; k++) {
                LevelChunk c = k == 0 ? a : b;
                String name = k == 0 ? "A" : "B";
                h.assertTrue(c.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) < d.id(), "ruins " + name + " stood up with a flash");
                int[] n = surface(h, c);
                before[k] = n[1];
                h.assertTrue(n[0] > 0, "before the wave in " + name + " no scorched earth; " + setup);
                h.assertTrue(n[1] > 0, "before the wave in " + name + " no fires; " + setup);
                h.assertTrue(n[2] < 256, "before the wave in " + name + " the grass is intact; " + setup);
                // layer fire - no tick until ruins: does not grow and does not go out while the plan of ruins around the world waits for a wave before the outbreak
                int[] f = fires(h, c, 4);
                h.assertTrue(f[1] == 0, "before the wave " + f[1] + " from " + f[0] + " fires in " + name + " there is already a tick");
                h.assertTrue(c.getExistingData(ModAttachments.FLASH_LAYER).orElse(0) == d.id(), "at " + name + " no flash layer marker");
            }
            // wave: A - a queue of ruins (plan in the background), B - a plan ready before the outbreak
            q.offer(a, d);
            for (long now = waveA - 1; now < waveA + 20 && a.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) != d.id(); now++) {
                clock.start(10_000_000_000L);
                q.work(level, now, clock);
                RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
            }
            h.assertTrue(a.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == d.id(), "there are no ruins A: " + q.diagState(a.getPos().toLong(), waveA + 20));
            h.assertTrue(preparedB.apply(level, b, d, new ColumnScar.Budget(true)), "plan B, ready before flash, outdated from flash layer");
            String diffA = differ(a, expectA), diffB = differ(b, expectB);
            if (diffA != null) h.fail("A after the wave is not the same as from some ruins (world | ruins): " + diffA + "; " + setup);
            if (diffB != null) h.fail("B after the wave is not the same as from some ruins (world | ruins): " + diffB + "; " + setup);
            int[] afterA = surface(h, a), afterB = surface(h, b);
            h.assertTrue(afterA[1] <= before[0] && afterB[1] <= before[1], "after a wave of fires more than with an outbreak: A " + afterA[1] + " from " + before[0]
                    + ", B " + afterB[1] + " from " + before[1]);
            if (d.psi(midB) >= 2.5) h.assertTrue(afterB[1] < before[1], "the wave did not knock down the fire in B: " + afterB[1] + " from " + before[1] + "; " + setup);
            // with ruins the fire burns further: tick - to each, the layer is unchecked
            for (LevelChunk c : List.of(a, b)) {
                int[] f = fires(h, c, 4);
                h.assertTrue(f[0] == f[1], "after the ruins in " + c.getPos() + " without tick " + (f[0] - f[1]) + " from " + f[0] + " fires");
                h.assertTrue(c.getExistingData(ModAttachments.FLASH_LAYER).isEmpty(), "after the ruins " + c.getPos() + " flash layer mark remains");
            }
            Incoming.LOG.info("Flash layer before the wave, ruins after - the same world: {}; fires in A {} → {}, in B {} → {}", setup, before[0], afterA[1], before[1], afterB[1]);
        } finally {
            q.clear(level);
            // fire of the platform - away: then it would burn and tick in the neighboring batch
            for (LevelChunk c : inside) {
                for (int x = c.getPos().getMinBlockX(); x <= c.getPos().getMaxBlockX(); x++) {
                    for (int z = c.getPos().getMinBlockZ(); z <= c.getPos().getMaxBlockZ(); z++) {
                        BlockPos p = new BlockPos(x, g.getY(), z);
                        if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        h.succeed();
    }

    /**
     * Ruins on top of the flash layer, when the ruins of the explosion no longer have pictures before the flash (displaced, taken again): window shots -
     *  over the world with a layer that editing the image returns to the world before the flash ({@code RuinPlan.unflash}: fire - air,
     *  layer places - old, column height maps - from a copy). A field of grass and wooden houses (fire flashes on the roofs; at the
     *  epicenter the wave blows off the roofs) in all chunks entirely on the site; layer - in all at once. The plan of each chunk - in the
     *  server stream, in the background and from the disk - is the same as in the world before the outbreak (from the disk - as from the disk before the outbreak), and the world after the
     *  ruins is the same as what the ruins alone would have given: the fire of the layer that the ruins do not have has gone out, the rest is burning with a tick; a place
     *  which the ruins do not change - like after a layer (light near the wall, which was then demolished by a wave), the stages of the biome - no weaker than the
     *  layer. Chunk, for whom the ruins of the explosion are completely forgotten (after an idle time, a restart), knows about the layer by the mark: fire in the
     *  image is air, the ruins stand up, all the fire is ticked, the mark is removed; in the count of explosion fires - the fire that burns
     *  further (after idle time, the counter is alive, and the fire of the chunk layer that it took leaves the count; after a restart, the counter
     *  is new). The whole score is the fire of chunks with ruins and the fire of the layer in the score where there are no ruins. A chunk with a mark, whose detonation
     *  is forgotten and there will be no ruins, gives the tick itself to the fire.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "nuke_flash_unflash", skyAccess = true)
    public static void ruinsOverFlashWithoutShots(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fillBiome(h, 0, 63, Biomes.PLAINS);
        plants(h);
        BlockPos zero = new BlockPos(60, 12, 60), g = h.absolutePos(zero);
        int ground = h.absolutePos(new BlockPos(0, 11, 0)).getY();
        List<LevelChunk> inside = inside(h, g);
        h.assertTrue(inside.size() >= 4, "chunks entirely on site " + inside.size());
        // house 6×6 in the middle of each chunk: walls of three blocks, roof made of boards, empty inside
        for (LevelChunk c : inside) {
            for (int lx = 5; lx <= 10; lx++) {
                for (int lz = 5; lz <= 10; lz++) {
                    boolean wall = lx == 5 || lx == 10 || lz == 5 || lz == 10;
                    for (int dy = 1; dy <= 4; dy++) {
                        BlockPos p = new BlockPos(c.getPos().getMinBlockX() + lx, ground + dy, c.getPos().getMinBlockZ() + lz);
                        BlockState st = dy == 4 || wall ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        level.setBlock(p, st, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        LevelChunk far = inside.get(inside.size() - 1);
        Detonation d = NuclearGameTests.detonation(h, zero, Yield.optimalBurstHeight(15) * scaleFor(g, far, 15), 15, (float) scaleFor(g, far, 15));
        for (LevelChunk c : inside) {
            for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.getPos().x + dx, c.getPos().z + dz);
            }
        }
        // ruins around the world before the outbreak: the plan, the world after it, the plan from the disk
        int n = inside.size();
        RuinPlan[] before = new RuinPlan[n], beforeDisk = new RuinPlan[n];
        LevelChunkSection[][] expect = new LevelChunkSection[n][];
        for (int i = 0; i < n; i++) {
            LevelChunk c = inside.get(i);
            before[i] = RuinPlanner.planFresh(level, d, c, false);
            expect[i] = before[i].testAppliedCopy(level, c);
            beforeDisk[i] = RuinPlanner.testPlanOverFlash(level, d, c, 2);
            String same = beforeDisk[i].differs(before[i]);
            if (same != null) Incoming.LOG.info("Chunk {}: plan from disk before flash is not the same as from memory (disk | memory): {}", c.getPos(), same);
        }
        int[][] world = new int[n][];
        for (int i = 0; i < n; i++) world[i] = blocks(inside.get(i));
        long[] keys = inside.stream().mapToLong(c -> c.getPos().toLong()).toArray();
        // explosion fire counter - one per layer and ruins, as in the game (ScarQueue.budget)
        ColumnScar.Budget budget = new ColumnScar.Budget(true);
        FlashScorch flash = FlashScorch.forTest(d, keys, budget);
        WorkClock clock = WorkClock.counting(1_000_000L);
        try {
            boolean done = false;
            for (int i = 0; i < 40 && !done; i++) {
                clock.start(10_000_000_000L);
                done = flash.work(level, d.gameTime() + 10_000, clock);
                if (!done) RuinWorkers.awaitForTest(RuinWorkers.Priority.NEAR, 60_000);
            }
            h.assertTrue(done && flash.appliedChunks() == n, "the flash layer is stuck in " + flash.appliedChunks() + " chunks of " + n);
            int roofFires = 0, lit = 0;
            LevelChunk marked = null, forgotten = null, idle = null;
            for (LevelChunk c : inside) {
                int[] f = fires(h, c, 8);
                h.assertTrue(f[1] == 0, "fire layer with tick to ruins in " + c.getPos() + ": " + f[1] + " from " + f[0]);
                lit += f[0];
                for (int lx = 5; lx <= 10; lx++) {
                    for (int lz = 5; lz <= 10; lz++) {
                        if (level.getBlockState(new BlockPos(c.getPos().getMinBlockX() + lx, ground + 5, c.getPos().getMinBlockZ() + lz)).getBlock() instanceof BaseFireBlock) roofFires++;
                    }
                }
                if (f[0] > 0) {
                    idle = forgotten;
                    forgotten = marked;
                    marked = c;
                }
            }
            h.assertTrue(roofFires > 0, "there are no fire flashes on the roofs: there is nothing at home (there are only fires " + lit + ")");
            // stages of the biome after the layer: ruins do not lower them (there was light before), where the wave replaced the ground - they remain
            int[][] flashBiomes = new int[n][], flashed = new int[n][];
            for (int i = 0; i < n; i++) {
                flashBiomes[i] = biomeLevels(inside.get(i).getSections());
                flashed[i] = blocks(inside.get(i));
            }
            h.assertTrue(idle != null, "the flash layer lit the fire in less than three chunks");
            String roofs = roofs(inside, ground, expect);
            // plan of each chunk without pictures before the flash - in three ways
            RuinPlan[] background = new RuinPlan[n];
            String[] ways = {"in the server thread", "in the background", "from disk"};
            for (int i = 0; i < n; i++) {
                LevelChunk c = inside.get(i);
                for (int way = 0; way < 3; way++) {
                    RuinPlan p = RuinPlanner.testPlanOverFlash(level, d, c, way);
                    String diff = p.differs(way == 2 ? beforeDisk[i] : before[i]);
                    if (diff != null) h.fail("chunk " + c.getPos() + ": plan on top of layer " + ways[way] + " not the one around the world before the flash (above | to; y - from the bottom of the world): "
                            + diff + "; " + roofs);
                    if (way == 1) background[i] = p;
                }
            }
            // ruins: all chunks, except three, are plans in the background; two more - when the ruins of the explosion are forgotten (only a mark):
            //  after idle time and after a restart; the last one is completely forgotten
            int[] kept = new int[1];
            for (int i = 0; i < n; i++) {
                LevelChunk c = inside.get(i);
                if (c == marked || c == forgotten || c == idle) continue;
                h.assertTrue(background[i].apply(level, c, d, budget), "plan " + c.getPos() + " on top of layer is obsolete");
                // the place that the ruins do not change, but the layer has changed (the light near the wall, which was then demolished by the wave - the slope towards the
                //  ball on the ground before the wave), remains a trace of the layer; layer fire - no
                String diff = overFlash(c, expect[i], world[i], flashed[i], kept), biomes = differ(c, expect[i], flashBiomes[i], false);
                if (biomes != null) diff = (diff == null ? "" : diff + "; ") + biomes;
                if (diff != null) h.fail("chunk " + c.getPos() + " after the ruins on top of the layer is not the same as from the ruins alone (world | ruins): " + diff + "; " + roofs);
                int[] f = fires(h, c, 8);
                h.assertTrue(f[0] == f[1], "after the ruins in " + c.getPos() + " without tick " + (f[0] - f[1]) + " from " + f[0] + " fires");
                h.assertTrue(c.getExistingData(ModAttachments.FLASH_LAYER).isEmpty(), "after the ruins " + c.getPos() + " layer mark remains");
            }
            NuclearWorld.get(level).forgetRuinsForTest(d.id());
            // the ruins of the explosion are forgotten after being idle (RUINS_IDLE), and the counter of explosion fires is alive: the fire of the layer of the chunk that he took,
            // , leaves the count, the one that burns further enters (otherwise the remaining fire of the layer would be counted twice, and the limit
            //  would cut out new fires ahead of schedule)
            h.assertTrue(FlashScorch.marked(idle, d.id()), "the layer fire chunk has no mark");
            int layerIdle = budget.layerForTest(idle.getPos()), others = budget.firesForTest() - layerIdle;
            h.assertTrue(layerIdle > 0, "at " + idle.getPos() + " there is no layer fire in the fire count: there is nothing to check");
            h.assertTrue(RuinPlanner.plan(level, d, idle).apply(level, idle, d, budget), "chunk plan with layer mark (ruins forgotten, counter alive) is outdated");
            int[] f = fires(h, idle, 8);
            h.assertTrue(f[0] == f[1], "ruins by layer mark with live counter: no tick " + (f[0] - f[1]) + " from " + f[0] + " fires");
            h.assertTrue(budget.firesForTest() - others == f[0], "ruins by layer mark with live counter: lit " + f[0] + ", in the count of explosion fires per chunk "
                    + (budget.firesForTest() - others) + " (layer taken " + layerIdle + ")");
            h.assertFalse(FlashScorch.marked(idle, d.id()), "ruins according to the layer mark with a live counter: the mark remains");
            // the entire account of the detonation is what burns in the chunks with ruins, and the fire of the layer that he took, where there are no ruins yet
            int counted = 0;
            for (LevelChunk c : inside) counted += c == marked || c == forgotten ? budget.layerForTest(c.getPos()) : fires(h, c, 8)[0];
            h.assertTrue(budget.firesForTest() == counted, "counting explosion fires " + budget.firesForTest() + ", but burns in cans of ruins "
                    + "together with the fire of the layer in the account where there are no ruins, " + counted);
            h.assertTrue(FlashScorch.marked(marked, d.id()), "the layer fire chunk has no mark");
            RuinPlan alone = RuinPlanner.plan(level, d, marked);
            // the counter for explosion fires after a restart is new: the fire of the layer that burns next is included in it
            ColumnScar.Budget restarted = new ColumnScar.Budget(true);
            h.assertTrue(alone.apply(level, marked, d, restarted), "chunk plan with layer mark (ruins forgotten) is outdated");
            f = fires(h, marked, 8);
            h.assertTrue(f[0] == f[1], "ruins by layer mark: no tick " + (f[0] - f[1]) + " from " + f[0] + " fires");
            h.assertTrue(f[0] > 0 && restarted.firesForTest() == f[0], "ruins by layer mark: lit " + f[0] + ", counting explosion fires "
                    + restarted.firesForTest());
            h.assertFalse(FlashScorch.marked(marked, d.id()), "ruins by layer mark: mark left");
            // chunk with a mark whose detonation is forgotten (the detonation check does not have in NuclearEvents): there will be no ruins - tick fire immediately
            h.assertTrue(FlashScorch.burnOutForTest(level, forgotten), "the chunk with the mark of the layer of the forgotten explosion did not give the fire a tick");
            f = fires(h, forgotten, 8);
            h.assertTrue(f[0] > 0 && f[0] == f[1], "fire layer of forgotten detonation: no tick " + (f[0] - f[1]) + " from " + f[0]);
            h.assertTrue(forgotten.getExistingData(ModAttachments.FLASH_LAYER).isEmpty(), "fire of the forgotten explosion layer: the mark remains");
            Incoming.LOG.info("Ruins on top of the flash layer without pictures before the flash are the same: {} chunks, fires of the {} layer (on the roofs of {}), places with a trace of the layer that the ruins do not change, {}; "
                    + "ruins according to the mark with a live counter: the layer took {}, {} is on; {}", n, lit, roofFires, kept[0], layerIdle, budget.firesForTest() - others, roofs);
        } finally {
            NuclearWorld.get(level).forgetRuinsForTest(d.id());
            unfire(level, inside, ground, 8);
        }
        h.succeed();
    }

    /**
     * Chunk blocks after ruins on top of the layer: each place - as in {@code expected} (only ruins) or, if the ruins do not change it
     *  ({@code expected} = {@code world}, world before the outbreak), - as after the layer ({@code flashed}), except fire. null —
     * so; {@code kept} - how many places with a layer trace.
     */
    @Nullable
    private static String overFlash(LevelChunk chunk, LevelChunkSection[] expected, int[] world, int[] flashed, int[] kept) {
        int[] live = blocks(chunk);
        int n = 0;
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < live.length; k++) {
            int i = k >> 12, c = k & 4095;
            int want = Block.getId(expected[i].getBlockState(c & 15, c >> 8, (c >> 4) & 15));
            if (live[k] == want) continue;
            if (want == world[k] && live[k] == flashed[k] && !(Block.stateById(live[k]).getBlock() instanceof BaseFireBlock)) {
                kept[0]++;
                continue;
            }
            if (n++ < 4) out.append(String.format(Locale.ROOT, " [x %d y %d z %d: %s | %s, before flash %s, after layer %s]", c & 15,
                    chunk.getMinBuildHeight() + (i << 4) + (c >> 8), (c >> 4) & 15, Block.stateById(live[k]), Block.stateById(want),
                    Block.stateById(world[k]), Block.stateById(flashed[k])));
        }
        return n == 0 ? null : n + " places:" + out;
    }

    /** All chunk locations (state numbers), from bottom to top in sections. */
    private static int[] blocks(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        int[] out = new int[sections.length * 4096];
        for (int i = 0; i < sections.length; i++) {
            for (int k = 0; k < 4096; k++) out[i * 4096 + k] = Block.getId(sections[i].getBlockState(k & 15, k >> 8, (k >> 4) & 15));
        }
        return out;
    }

    /** Chunks are entirely on the site, from the closest one to {@code g}. */
    private static List<LevelChunk> inside(GameTestHelper h, BlockPos g) {
        BlockPos lo = h.absolutePos(BlockPos.ZERO), hi = h.absolutePos(new BlockPos(63, 0, 63));
        List<LevelChunk> inside = new ArrayList<>();
        for (LevelChunk chunk : NuclearGameTests.chunks(h)) {
            ChunkPos c = chunk.getPos();
            if (c.getMinBlockX() >= Math.min(lo.getX(), hi.getX()) && c.getMaxBlockX() <= Math.max(lo.getX(), hi.getX())
                    && c.getMinBlockZ() >= Math.min(lo.getZ(), hi.getZ()) && c.getMaxBlockZ() <= Math.max(lo.getZ(), hi.getZ())) inside.add(chunk);
        }
        inside.sort(Comparator.comparingDouble(c -> Math.hypot(c.getPos().getMiddleBlockX() - g.getX(), c.getPos().getMiddleBlockZ() - g.getZ())));
        return inside;
    }

    /** The scale is 15 kt at the optimal height above {@code g}, at which near the ground in the middle of the chunk {@code c} is {@code cal} cal/cm². */
    private static double scaleFor(BlockPos g, LevelChunk c, double cal) {
        Vec3 mid = new Vec3(c.getPos().getMiddleBlockX() + 0.5, g.getY() + 1, c.getPos().getMiddleBlockZ() + 0.5);
        double lo = 0.002, hi = 1;
        for (int i = 0; i < 50; i++) {
            double s = Math.sqrt(lo * hi);
            Detonation probe = new Detonation(0, new Vec3(g.getX() + 0.5, g.getY() + Yield.optimalBurstHeight(15) * s, g.getZ() + 0.5), g.getY(), 15, false,
                    0, 0, 0, 20_000, 7, (float) s, false, Detonation.OLD_WAVE);
            if (probe.fluence(mid) > cal) hi = s;
            else lo = s;
        }
        return hi;
    }

    /** How many roof boards are demolished by one ruin ({@code expect} - chunks after them), by chunk: line for log and messages. */
    private static String roofs(List<LevelChunk> inside, int ground, LevelChunkSection[][] expect) {
        StringBuilder out = new StringBuilder("roofs demolished:");
        for (int i = 0; i < inside.size(); i++) {
            LevelChunk c = inside.get(i);
            LevelChunkSection[] after = expect[i];
            int gone = 0;
            for (int lx = 5; lx <= 10; lx++) {
                for (int lz = 5; lz <= 10; lz++) {
                    int y = ground + 4, s = c.getSectionIndex(y);
                    if (!after[s].getBlockState(lx, y & 15, lz).is(Blocks.OAK_PLANKS)) gone++;
                }
            }
            out.append(' ').append(gone);
        }
        return out.toString();
    }

    /** Fire in a chunk from the top of the site to {@code up} blocks above it: total and how many with tick in the schedule. */
    private static int[] fires(GameTestHelper h, LevelChunk c, int up) {
        ServerLevel level = h.getLevel();
        int ground = h.absolutePos(new BlockPos(0, 11, 0)).getY();
        int[] n = new int[2];
        for (int x = c.getPos().getMinBlockX(); x <= c.getPos().getMaxBlockX(); x++) {
            for (int z = c.getPos().getMinBlockZ(); z <= c.getPos().getMaxBlockZ(); z++) {
                for (int y = ground; y <= ground + up; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (!(st.getBlock() instanceof BaseFireBlock)) continue;
                    n[0]++;
                    if (level.getBlockTicks().hasScheduledTick(p, st.getBlock())) n[1]++;
                }
            }
        }
        return n;
    }

    /** The fire of the chunks is gone (then it would burn and tick in the neighboring batches). */
    private static void unfire(ServerLevel level, List<LevelChunk> chunks, int ground, int up) {
        for (LevelChunk c : chunks) {
            for (int x = c.getPos().getMinBlockX(); x <= c.getPos().getMaxBlockX(); x++) {
                for (int z = c.getPos().getMinBlockZ(); z <= c.getPos().getMaxBlockZ(); z++) {
                    for (int y = ground; y <= ground + up; y++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /**
     * Along one axis of the {@code [a, b]} site: the first of the three chunks is entirely on it and the place of detonation in the 5–6 blocks behind them is on the
     *  side, where the site has a larger reserve (of the 16 blocks in excess of three chunks, there are at least 8 on this side). Indents
     * 6 and 5 - the same distance to the middle of the chunks on both sides ({@code getMiddleBlockX} + 0,5 - not the middle of the chunk).
     */
    private static int[] gridSide(int a, int b) {
        int first = (a + 15) >> 4, last = ((b + 1) >> 4) - 1;
        int after = b - ((first + 3) << 4) + 1, before = ((last - 2) << 4) - a;
        return after >= before ? new int[]{first, ((first + 3) << 4) + 5} : new int[]{last - 2, ((last - 2) << 4) - 5};
    }

    /** The top of the site in a chunk: scorched earth (earth, burning), fires above it, surviving plants. */
    private static int[] surface(GameTestHelper h, LevelChunk c) {
        ServerLevel level = h.getLevel();
        int ground = h.absolutePos(new BlockPos(0, 11, 0)).getY();
        int[] n = new int[3];
        for (int x = c.getPos().getMinBlockX(); x <= c.getPos().getMaxBlockX(); x++) {
            for (int z = c.getPos().getMinBlockZ(); z <= c.getPos().getMaxBlockZ(); z++) {
                BlockState top = level.getBlockState(new BlockPos(x, ground, z)), above = level.getBlockState(new BlockPos(x, ground + 1, z));
                if (top.is(Blocks.DIRT) || top.is(Blocks.COARSE_DIRT) || top.is(Blocks.PODZOL)) n[0]++;
                if (above.getBlock() instanceof BaseFireBlock) n[1]++;
                if (above.is(Blocks.SHORT_GRASS) || above.is(Blocks.TALL_GRASS)) n[2]++;
            }
        }
        return n;
    }

    /** Places and cells of the biome where the chunk is not the same as sections {@code expected} (before 4 examples), or null. */
    @Nullable
    private static String differ(LevelChunk chunk, LevelChunkSection[] expected) {
        return differ(chunk, expected, null, true);
    }

    /** Stages of the scorched biome ({@link #level}) section cells: section × 64 + cell. */
    private static int[] biomeLevels(LevelChunkSection[] sections) {
        int[] out = new int[sections.length * 64];
        for (int i = 0; i < sections.length; i++) {
            for (int k = 0; k < 64; k++) out[i * 64 + k] = level(sections[i].getNoiseBiome(k & 3, k >> 4, (k >> 2) & 3));
        }
        return out;
    }

    /**
     * Same; {@code floor} - stages of the biome that are already standing (flare layer): the cell is no weaker than them and the one in
     * {@code expected} (ruins do not lower the level), and without a stage - biome {@code expected}. {@code withBlocks} - false:
     * biome cells only.
     */
    @Nullable
    private static String differ(LevelChunk chunk, LevelChunkSection[] expected, @Nullable int[] floor, boolean withBlocks) {
        LevelChunkSection[] live = chunk.getSections();
        int blocks = 0, biomes = 0;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < live.length; i++) {
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState was = live[i].getBlockState(x, y, z), want = expected[i].getBlockState(x, y, z);
                        if (withBlocks && was != want && blocks++ < 4) out.append(String.format(Locale.ROOT, " [x %d y %d z %d: %s | %s]", x, y0 + y, z, was, want));
                    }
                }
            }
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        Holder<Biome> was = live[i].getNoiseBiome(x, y, z), want = expected[i].getNoiseBiome(x, y, z);
                        int least = floor == null ? 0 : Math.max(level(want), floor[i * 64 + (y << 4 | z << 2 | x)]);
                        boolean same = least == 0 ? was.equals(want) : level(was) == least;
                        if (!same && biomes++ < 4) out.append(String.format(Locale.ROOT, " [biome x %d y %d z %d: %s | %s%s]", x * 4, y0 + y * 4, z * 4,
                                was.unwrapKey().map(k -> k.location().toString()).orElse("?"), want.unwrapKey().map(k -> k.location().toString()).orElse("?"),
                                least > level(want) ? ", layer - step " + least : ""));
                    }
                }
            }
        }
        return blocks + biomes == 0 ? null : blocks + " places, " + biomes + " biome cells:" + out;
    }

    /** Plants above the turf of the site: grass, after two - tall grass in two blocks. */
    private static void plants(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                BlockPos p = h.absolutePos(new BlockPos(x, 12, z));
                if ((x + z) % 3 == 0) {
                    level.setBlock(p, Blocks.TALL_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    level.setBlock(p.above(), Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
                } else {
                    level.setBlock(p, Blocks.SHORT_GRASS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /** The cell biome of the {@code z0..z1} rows of the platform at full height is like {@code /fillbiome}. */
    static void fillBiome(GameTestHelper h, int z0, int z1, ResourceKey<Biome> key) {
        ServerLevel level = h.getLevel();
        Holder<Biome> biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(key);
        BlockPos a = h.absolutePos(new BlockPos(0, -16, z0)), b = h.absolutePos(new BlockPos(63, 39, z1));
        int qx0 = QuartPos.fromBlock(Math.min(a.getX(), b.getX())), qx1 = QuartPos.fromBlock(Math.max(a.getX(), b.getX()));
        int qy0 = QuartPos.fromBlock(Math.min(a.getY(), b.getY())), qy1 = QuartPos.fromBlock(Math.max(a.getY(), b.getY()));
        int qz0 = QuartPos.fromBlock(Math.min(a.getZ(), b.getZ())), qz1 = QuartPos.fromBlock(Math.max(a.getZ(), b.getZ()));
        for (int cx = qx0 >> 2; cx <= qx1 >> 2; cx++) {
            for (int cz = qz0 >> 2; cz <= qz1 >> 2; cz++) {
                LevelChunk chunk = level.getChunk(cx, cz);
                chunk.fillBiomesFromNoise((x, y, z, s) -> x >= qx0 && x <= qx1 && y >= qy0 && y <= qy1 && z >= qz0 && z <= qz1 ? biome
                        : chunk.getNoiseBiome(x, y, z), level.getChunkSource().randomState().sampler());
                chunk.setUnsaved(true);
            }
        }
    }

    private static Holder<Biome> biomeAt(GameTestHelper h, int x, int y, int z) {
        BlockPos p = h.absolutePos(new BlockPos(x, y, z));
        return h.getLevel().getNoiseBiome(QuartPos.fromBlock(p.getX()), QuartPos.fromBlock(p.getY()), QuartPos.fromBlock(p.getZ()));
    }

    private static int level(Holder<Biome> b) {
        return b.is(CHARRED) ? 3 : b.is(SCORCHED) ? 2 : b.is(SINGED) ? 1 : 0;
    }

    private static void assertBiome(GameTestHelper h, int x, int z, ResourceKey<Biome> key) {
        Holder<Biome> b = biomeAt(h, x, 12, z);
        if (!b.is(key)) h.fail("biome in " + x + ", " + z + " — " + b.unwrapKey().map(k -> k.location().toString()).orElse("?") + ", not " + key.location());
    }
}
