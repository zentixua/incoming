package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.ClaimQuery;
import ua.zentix.incoming.claim.Claims;
import ua.zentix.incoming.claim.NuclearClaims;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claim protection for every block-changing path (roadmap B2, {@link Claims}): a test claim handler filters {@code
 * ExplosionEvent.Detonate} the way claim mods do (by chunk). Every probe is its own batch: the handler is server-wide.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClaimGameTests {
    private ClaimGameTests() {}

    /**
     * Conventional strikes of every weapon, the B-2's bomb dropped as an entity (bore, cavity, fractures) and a Shahed shot
     * down over the claimed side (its wreck): no block changes in the claimed chunks of an arena of glass, planks, wool,
     * hay, grass, stone bricks and a plank shed across the claim edge; the unclaimed side right beside it loses blocks,
     * glass included.
     */
    @GameTest(template = "range", timeoutTicks = 20000, batch = "claim_strikes", skyAccess = true)
    public static void claimedChunksKeepTheirBlocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int edge = edge(h);
        LongOpenHashSet claimed = claimWest(h, edge);
        arena(h, edge);
        Map<BlockPos, BlockState> was = RuleGameTests.snapshot(h);
        Handler handler = Handler.install(h, claimed);
        long[] before = Claims.counters();

        List<RoundRef> rounds = new ArrayList<>();
        for (int i = 0; i < 6; i++) rounds.add(RoundRef.of(UUID.randomUUID(), i, "b2claim"));
        Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(edge + 3, 12, 14))), null, null, rounds.get(0));
        Warheads.detonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(edge + 4, 12, 40))), null, null, rounds.get(1));
        Warheads.detonate(level, WeaponType.ROCKET, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(edge + 2, 12, 26))), null, null, rounds.get(2));
        Warheads.detonate(level, WeaponType.LOITER, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(edge + 3, 12, 54))), null, null, rounds.get(3));
        // the bomb on the claimed side: its bore, cavity and fracture work all ask
        Vec3 surface = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(edge - 4, 12, 20)));
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        h.assertTrue(bomb != null, "no bomb");
        bomb.drop(surface.add(0, 26, 0), new Vec3(0, -4, 0), surface, null, null);
        bomb.attachRound(rounds.get(4));
        level.addFreshEntity(bomb);
        // a Shahed shot down over the claimed side: its wreck lands there
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        h.assertTrue(drone != null, "no drone");
        drone.moveTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(edge - 8, 34, 46))));
        drone.attachRound(rounds.get(5));
        level.addFreshEntity(drone);
        drone.intercepted(level, "claim_strikes", null, null);

        boolean[] done = {false};
        long start = level.getGameTime();
        h.onEachTick(() -> {
            if (!done[0] && bomb.isRemoved() && level.getGameTime() > start + 2 && StrikeWorld.get(level).idle()) done[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(done[0], "strikes still running");
            h.assertTrue(level.getEntitiesOfClass(DebrisEntity.class, RuleGameTests.overPlatform(h)).isEmpty(), "debris still falling");
            List<String> inClaim = new ArrayList<>();
            int outside = 0, glass = 0;
            for (var e : was.entrySet()) {
                BlockState now = h.getBlockState(e.getKey());
                if (now.equals(e.getValue())) continue;
                BlockPos abs = h.absolutePos(e.getKey());
                if (claimed.contains(ChunkPos.asLong(abs.getX() >> 4, abs.getZ() >> 4))) {
                    inClaim.add(e.getKey().toShortString() + " " + e.getValue() + " -> " + now);
                } else {
                    outside++;
                    if (e.getValue().is(Blocks.GLASS) || e.getValue().is(Blocks.GLASS_PANE)) glass++;
                }
            }
            h.assertTrue(inClaim.isEmpty(), inClaim.size() + " blocks changed in claimed chunks, first " + inClaim.subList(0, Math.min(8, inClaim.size())));
            h.assertTrue(outside > 20, "only " + outside + " blocks changed beside the claim: the strikes did not reach it");
            h.assertTrue(glass > 0, "no glass broke beside the claim");
            long[] after = Claims.counters();
            h.assertTrue(after[2] > before[2], "no claim query was refused: the follow-up writers never asked");
            h.assertTrue(handler.realRefused > 0, "the real charges' own Detonate lists were not filtered");
            h.assertTrue(handler.queriesWithEntities == 0, "a claim query carried entities");
        });
    }

    /**
     * A real charge keeps its own {@code ExplosionEvent.Start} veto: a handler cancelling the start of every charge over
     * the arena (and filtering nothing) leaves every block, glass included, as it was.
     */
    @GameTest(template = "range", timeoutTicks = 20000, batch = "claim_start", skyAccess = true)
    public static void cancelledStartChangesNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int edge = edge(h);
        arena(h, edge);
        Map<BlockPos, BlockState> was = RuleGameTests.snapshot(h);
        Handler handler = Handler.install(h, new LongOpenHashSet());
        handler.cancelStart = true;
        Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 24))), null, null,
                RoundRef.of(UUID.randomUUID(), 0, "b2start"));
        Warheads.detonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 40))), null, null,
                RoundRef.of(UUID.randomUUID(), 1, "b2start"));
        long start = level.getGameTime();
        h.succeedWhen(() -> {
            h.assertTrue(level.getGameTime() > start + 2 && StrikeWorld.get(level).idle(), "strikes still running");
            h.assertTrue(handler.cancelled >= 2, "only " + handler.cancelled + " starts reached the handler");
            List<String> changed = RuleGameTests.changes(h, was);
            h.assertTrue(changed.isEmpty(), changed.size() + " blocks changed after a cancelled start, first " + changed.subList(0, Math.min(8, changed.size())));
        });
    }

    /**
     * A claim query is not an explosion: posted beside a substation it cuts no power (the blackout listener skips it), it
     * carries no entities and zero radius, and a position in a chunk not in memory is refused without any handler seeing it
     * and without loading the chunk.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "claim_query", skyAccess = true)
    public static void claimQueryIsNoExplosion(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos substation = new BlockPos(32, 12, 32);
        h.setBlock(substation, ModBlocks.SUBSTATION.get());
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(substation));
        h.assertTrue(node != null, "the substation is not a grid node");
        Handler handler = Handler.install(h, new LongOpenHashSet());
        ClaimQuery q = new ClaimQuery(level, null, ModDamageTypes.source(level, ModDamageTypes.STRIKE, null, null), Vec3.atCenterOf(h.absolutePos(substation)));
        h.assertTrue(Claims.allows(level, q, h.absolutePos(substation.east())), "an unclaimed position was refused");
        h.assertTrue(handler.queries == 1, handler.queries + " claim queries reached the handler, expected 1");
        h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isEmpty(), "the claim query knocked the substation out");
        // far beyond anything loaded: refused unasked, still not loaded
        BlockPos far = h.absolutePos(substation).offset(1 << 20, 0, 1 << 20);
        h.assertTrue(level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null, "the far chunk is loaded");
        h.assertFalse(Claims.allows(level, q, far), "a position in a chunk not in memory was allowed");
        h.assertTrue(handler.queries == 1, "a handler was asked about a chunk not in memory");
        h.assertTrue(level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null, "the query loaded the far chunk");
        // cost per position (logged, not asserted: wall time on shared runners), with nothing claimed and all claimed
        long[] at = new long[16 * 4096];
        int n = 0;
        for (int x = 16; x < 48 && n < at.length; x++) for (int z = 16; z < 48 && n < at.length; z++) for (int y = 12; y < 76 && n < at.length; y++) {
            at[n++] = h.absolutePos(new BlockPos(x, y, z)).asLong();
        }
        // warm (the first passes run interpreted): the fastest of eight passes each
        long unclaimed = Long.MAX_VALUE, full = Long.MAX_VALUE;
        int open = -1, shut = -1;
        for (int pass = 0; pass < 8; pass++) {
            long t0 = System.nanoTime();
            open = Claims.offer(level, q, at, 0, n, null, false);
            unclaimed = Math.min(unclaimed, System.nanoTime() - t0);
        }
        for (int i = 0; i < n; i++) handler.claimed.add(ChunkPos.asLong(BlockPos.getX(at[i]) >> 4, BlockPos.getZ(at[i]) >> 4));
        for (int pass = 0; pass < 8; pass++) {
            long t0 = System.nanoTime();
            shut = Claims.offer(level, q, at, 0, n, null, false);
            full = Math.min(full, System.nanoTime() - t0);
        }
        h.assertTrue(open == 0 && shut == n, "refused " + open + " unclaimed and " + shut + " of " + n + " claimed positions");
        Incoming.LOG.info("CLAIMBENCH {} positions: {} ns a position unclaimed, {} ns claimed (test handler, batches of {})", n,
                unclaimed / n, full / n, Claims.BATCH);
        handler.claimed.clear();
        ua.zentix.incoming.grid.Blackouts.restore(level, null, 0, 0);
        PowerGrid.get(level).removeNode(node.id());
        h.succeed();
    }

    /**
     * A nuclear detonation over a claimed chunk: its flash layer, ruins and crater leave every block of the claimed chunk as
     * it was, while the unclaimed chunk beside it loses its glass; the denial is stored for that detonation only (another
     * detonation is asked on its own), and it holds after the claim is lifted.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "claim_nuke", skyAccess = true)
    public static void claimedChunkKeepsItsBlocksUnderANuke(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos centre = NuclearGameTests.CENTER;
        LevelChunk home = level.getChunkAt(h.absolutePos(centre));
        LevelChunk beside = level.getChunkAt(h.absolutePos(centre.east(16)));
        BlockPos glassHome = glassIn(h, home), glassBeside = glassIn(h, beside);
        long key = home.getPos().toLong();
        Map<BlockPos, BlockState> was = chunkBlocks(level, home);
        Handler handler = Handler.install(h, new LongOpenHashSet(new long[]{key}));
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(centre)), 1, false, null, 0.1f);
        h.succeedWhen(() -> {
            h.assertTrue(scarred(level, home, d) && scarred(level, beside, d), "ruins not done yet");
            h.assertTrue(h.getLevel().getBlockState(glassBeside).isAir(), "the unclaimed chunk kept its glass: the wave did not reach it");
            h.assertTrue(level.getBlockState(glassHome).is(Blocks.GLASS), "the claimed chunk lost its glass");
            List<String> changed = new ArrayList<>();
            for (var e : was.entrySet()) {
                BlockState now = level.getBlockState(e.getKey());
                if (!now.equals(e.getValue())) changed.add(e.getKey().toShortString() + " " + e.getValue() + " -> " + now);
            }
            h.assertTrue(changed.isEmpty(), changed.size() + " blocks changed in the claimed chunk, first " + changed.subList(0, Math.min(8, changed.size())));
            NuclearClaims claims = NuclearClaims.get(level);
            h.assertTrue(claims.denied(d.id(), key), "the denial was not stored for the detonation");
            h.assertFalse(claims.denied(d.id(), beside.getPos().toLong()), "the unclaimed chunk was denied");
            h.assertFalse(claims.denied(d.id() + 1, key), "another detonation inherited the denial");
            // the claim lifted: the stored denial still answers, no handler asked
            handler.claimed.clear();
            int asked = handler.queries;
            long[] at = {h.absolutePos(centre).asLong()};
            h.assertFalse(claims.allows(level, d, key, at, 1), "a lifted claim reopened a chunk the detonation was denied");
            h.assertTrue(handler.queries == asked, "a stored denial asked the handlers again");
            h.assertTrue(handler.queriesWithEntities == 0, "a claim query carried entities");
        });
    }

    /**
     * A claim placed after the flash keeps the wave out: the first question (the flash layer) is allowed, the chunk is
     * claimed, the second question (the ruins) is refused and stored; a claim lifted after a refusal keeps the chunk denied.
     * A stored denial (taken for a LOD copy before a restart, saved with the world) answers without asking and survives a
     * save and load of the record.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "claim_nuke_change", skyAccess = true)
    public static void claimChangeBetweenFlashAndWave(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Handler handler = Handler.install(h, new LongOpenHashSet());
        Detonation d = new Detonation(NuclearGameTests.IDS.incrementAndGet(), Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER)),
                h.absolutePos(NuclearGameTests.CENTER).getY(), 1, true, level.getGameTime(), 0f, 0f, 1f, 0L, 0.1f, false, 0.35f);
        NuclearClaims claims = NuclearClaims.get(level);
        LevelChunk a = level.getChunkAt(h.absolutePos(NuclearGameTests.CENTER)), b = level.getChunkAt(h.absolutePos(NuclearGameTests.CENTER.east(16)));
        long[] inA = {h.absolutePos(NuclearGameTests.CENTER).asLong()}, inB = {h.absolutePos(NuclearGameTests.CENTER.east(16)).asLong()};
        // a: allowed at the flash, claimed before the wave
        h.assertTrue(claims.allows(level, d, a.getPos().toLong(), inA, 1), "an unclaimed chunk was denied at the flash");
        handler.claimed.add(a.getPos().toLong());
        h.assertFalse(claims.allows(level, d, a.getPos().toLong(), inA, 1), "a claim placed after the flash did not keep the wave out");
        // b: claimed at the flash, lifted before the wave
        handler.claimed.add(b.getPos().toLong());
        h.assertFalse(claims.allows(level, d, b.getPos().toLong(), inB, 1), "a claimed chunk was allowed at the flash");
        handler.claimed.clear();
        h.assertFalse(claims.allows(level, d, b.getPos().toLong(), inB, 1), "a claim lifted after the flash let the wave in");
        // saved and loaded (restart): the denials hold for this detonation only
        var tag = claims.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess());
        NuclearClaims loaded = NuclearClaims.load(tag, level.registryAccess());
        h.assertTrue(loaded.denied(d.id(), a.getPos().toLong()) && loaded.denied(d.id(), b.getPos().toLong()), "a denial was lost over a save and load");
        h.assertFalse(loaded.denied(d.id() + 1, a.getPos().toLong()), "a denial moved to another detonation");
        int asked = handler.queries;
        h.assertFalse(loaded.allows(level, d, a.getPos().toLong(), inA, 1), "a loaded denial was not followed");
        h.assertTrue(handler.queries == asked, "a loaded denial asked the handlers again");
        h.succeed();
    }

    // ---------------------------------------------------------------- fixture

    /** Test claim handler: filters every {@code Detonate} by claimed chunk, as Open Parties and Claims and FTB Chunks do. */
    static final class Handler {
        private static Handler active;
        private static boolean listening;
        final LongOpenHashSet claimed;
        boolean cancelStart;
        int queries, queriesWithEntities, realRefused, cancelled;

        private Handler(LongOpenHashSet claimed) {
            this.claimed = claimed;
        }

        static Handler install(GameTestHelper h, LongOpenHashSet claimed) {
            if (!listening) {
                listening = true;
                NeoForge.EVENT_BUS.addListener(Handler::onStart);
                NeoForge.EVENT_BUS.addListener(Handler::onDetonate);
            }
            Handler handler = new Handler(claimed);
            active = handler;
            StrikeGameTests.afterTest(h, () -> {
                if (active == handler) active = null;
            });
            return handler;
        }

        private static void onStart(ExplosionEvent.Start e) {
            Handler h = active;
            if (h == null || !h.cancelStart || e.getExplosion() instanceof ClaimQuery || Warheads.chargeOf(e.getExplosion()) <= 0 && Warheads.rulesOf(e.getExplosion()) == null) return;
            e.setCanceled(true);
            h.cancelled++;
        }

        private static void onDetonate(ExplosionEvent.Detonate e) {
            Handler h = active;
            if (h == null) return;
            boolean query = e.getExplosion() instanceof ClaimQuery;
            if (query) {
                h.queries++;
                if (!e.getAffectedEntities().isEmpty()) h.queriesWithEntities++;
            }
            if (h.claimed.isEmpty()) return;
            int before = e.getAffectedBlocks().size();
            e.getAffectedBlocks().removeIf(p -> h.claimed.contains(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4)));
            if (!query) h.realRefused += before - e.getAffectedBlocks().size();
        }
    }

    /** Local x of the first chunk edge past x = 29: chunks west of it are claimed, east of it are not. */
    private static int edge(GameTestHelper h) {
        for (int x = 30; x < 48; x++) if ((h.absolutePos(new BlockPos(x, 0, 0)).getX() & 15) == 0) return x;
        throw new IllegalStateException("no chunk edge");
    }

    /** Claim every platform chunk west of {@code edge}. */
    private static LongOpenHashSet claimWest(GameTestHelper h, int edge) {
        LongOpenHashSet out = new LongOpenHashSet();
        for (int x = 0; x < edge; x++) {
            for (int z = 0; z < 64; z++) {
                BlockPos a = h.absolutePos(new BlockPos(x, 0, z));
                out.add(ChunkPos.asLong(a.getX() >> 4, a.getZ() >> 4));
            }
        }
        return out;
    }

    /** The rule arena's fixture plus stone bricks (fragment dents) and a plank shed across the claim edge (collapse). */
    private static void arena(GameTestHelper h, int edge) {
        for (int x = 2; x <= 62; x += 4) {
            for (int z = 2; z <= 62; z += 4) {
                BlockPos base = new BlockPos(x, 12, z);
                if (Math.abs(x - edge) < 2) continue;
                h.setBlock(base, Blocks.OAK_PLANKS);
                h.setBlock(base.above(), Blocks.GLASS);
                h.setBlock(base.east(), Blocks.WHITE_WOOL);
                h.setBlock(base.south(), Blocks.HAY_BLOCK);
                h.setBlock(base.west(), Blocks.GLASS_PANE);
                h.setBlock(base.north(), Blocks.SHORT_GRASS);
                h.setBlock(base.above(2), Blocks.STONE_BRICKS);
            }
        }
        // shed over the missile: posts on both sides of the edge, a plank roof across it
        for (int x = edge - 5; x <= edge + 7; x++) for (int z = 36; z <= 44; z++) h.setBlock(new BlockPos(x, 16, z), Blocks.OAK_PLANKS);
        for (int x : new int[]{edge - 5, edge + 7}) for (int z : new int[]{36, 44}) for (int y = 12; y < 16; y++) h.setBlock(new BlockPos(x, y, z), Blocks.OAK_LOG);
    }

    /** Glass pane in the middle of {@code chunk} at the platform floor, returns its world position. */
    private static BlockPos glassIn(GameTestHelper h, LevelChunk chunk) {
        BlockPos at = new BlockPos(chunk.getPos().getMinBlockX() + 8, h.absolutePos(new BlockPos(0, 13, 0)).getY(), chunk.getPos().getMinBlockZ() + 8);
        h.getLevel().setBlock(at.below(), Blocks.OAK_PLANKS.defaultBlockState(), 2);
        h.getLevel().setBlock(at, Blocks.GLASS.defaultBlockState(), 2);
        return at;
    }

    /** Every block of the chunk from the platform floor 20 up. */
    private static Map<BlockPos, BlockState> chunkBlocks(ServerLevel level, LevelChunk chunk) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        int y0 = level.getMinBuildHeight();
        for (BlockPos p : BlockPos.betweenClosed(chunk.getPos().getMinBlockX(), y0, chunk.getPos().getMinBlockZ(),
                chunk.getPos().getMaxBlockX(), Math.min(level.getMaxBuildHeight() - 1, y0 + 384), chunk.getPos().getMaxBlockZ())) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir()) out.put(p.immutable(), s);
        }
        return out;
    }

    private static boolean scarred(ServerLevel level, LevelChunk chunk, Detonation d) {
        return chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id();
    }
}
