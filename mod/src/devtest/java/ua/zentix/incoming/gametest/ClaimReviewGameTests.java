package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.ClaimQuery;
import ua.zentix.incoming.claim.NuclearClaims;
import ua.zentix.incoming.nuclear.Detonation;

/**
 * Review probe (B2, review 1): a {@code Detonate} listener that reads the world around the explosion centre, as Create
 * Diesel Generators 1.3.15 does in the host pack ({@code GameEvents.onExplosion}: {@code Level.getFluidState} at every
 * position within 2 blocks of {@code explosion.center()}, config "Combustibles blow up", default and host value true),
 * must never be handed a claim query whose centre lies in a chunk not in memory: that read loads the chunk synchronously
 * on the server thread. A nuclear query is centred on the burst point, which is not in memory while ruins far from it
 * (settlement, on-load ruins, a player watching from beyond view distance) ask for their chunk.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ClaimReviewGameTests {
    private ClaimReviewGameTests() {}

    private static boolean listening;
    private static int[] active;

    @GameTest(template = "range", timeoutTicks = 200, batch = "claim_review_centre", skyAccess = true)
    public static void nuclearQueryCentreIsInMemory(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener(ClaimReviewGameTests::onDetonate);
        }
        int[] seen = new int[2];
        active = seen;
        StrikeGameTests.afterTest(h, () -> active = null);
        // burst far from the platform, as ground zero is when a distant ruin chunk is asked
        BlockPos far = h.absolutePos(NuclearGameTests.CENTER).offset(1 << 16, 0, 0);
        h.assertTrue(level.getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4) == null, "ground zero is in memory");
        Detonation d = new Detonation(NuclearGameTests.IDS.incrementAndGet(), Vec3.atBottomCenterOf(far), far.getY(), 1, true,
                level.getGameTime(), 0f, 0f, 1f, 0L, 0.1f, false, 0.35f);
        LevelChunk home = level.getChunkAt(h.absolutePos(NuclearGameTests.CENTER));
        long[] at = {h.absolutePos(NuclearGameTests.CENTER).asLong()};
        // the ruin queue's question for a chunk in memory (ScarQueue.claimed, FlashScorch.place, CraterJob gate)
        NuclearClaims.get(level).allows(level, d, home.getPos().toLong(), at, 1);
        active = null;
        h.assertTrue(seen[0] > 0, "no claim query was posted");
        h.assertTrue(seen[1] == 0, seen[1] + " of " + seen[0] + " claim queries were centred in a chunk not in memory: a listener reading at the"
                + " explosion centre (Create Diesel Generators) loads it on the server thread");
        h.succeed();
    }

    private static void onDetonate(ExplosionEvent.Detonate e) {
        int[] seen = active;
        if (seen == null || !(e.getExplosion() instanceof ClaimQuery q) || !(e.getLevel() instanceof ServerLevel level)) return;
        seen[0]++;
        Vec3 c = q.center();
        // not read here (the probe must not load it); Create Diesel Generators reads getFluidState at c ± 2
        if (level.getChunkSource().getChunkNow((int) Math.floor(c.x) >> 4, (int) Math.floor(c.z) >> 4) == null) seen[1]++;
    }
}
