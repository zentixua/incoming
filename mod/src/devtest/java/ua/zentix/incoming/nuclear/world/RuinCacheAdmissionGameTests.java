package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;

/** Held real region writes expose every counted owner before asynchronous refunds. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinCacheAdmissionGameTests {
    private RuinCacheAdmissionGameTests() {}

    @GameTest(template = "range", batch = "nuke_plan_spill_admission", timeoutTicks = 200, skyAccess = true)
    public static void twoPlanWritesRespectTheExactResidentBoundary(GameTestHelper h) throws Exception {
        boundary(h, true);
    }

    @GameTest(template = "range", batch = "nuke_snapshot_spill_admission", timeoutTicks = 200, skyAccess = true)
    public static void twoSnapshotWritesRespectTheExactResidentBoundary(GameTestHelper h) throws Exception {
        boundary(h, false);
    }

    private static void boundary(GameTestHelper h, boolean plans) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var blueprint = RuinStorageGameTests.fixture(h, true);
        var old = blueprint.ctx().d;
        var event = new Detonation(plans ? 150102 : 150103, old.burst(), old.groundY(), old.yieldKt(), old.surface(),
                old.gameTime(), 0, 0, old.visibility(), 0, old.scale(), false, old.wave());
        var world = NuclearWorld.get(level);
        var other = NuclearWorld.get(level.getServer().getLevel(Level.NETHER));
        long previousReservation = other.materialReservation;
        var content = NuclearPrep.class.getDeclaredMethod("content", net.minecraft.server.level.ServerLevel.class);
        content.setAccessible(true);
        var gate = new CompletableFuture<Void>();
        RuinContext[] context = {null};
        RuinProgress.Pass[] pass = {null};
        String[] previousArchive = {null};
        Object[] writer = {null};
        ChunkShot[] shots = new ChunkShot[2];
        long[] keys = {blueprint.centre().toLong(), ChunkPos.asLong(blueprint.centre().x + 1, blueprint.centre().z)};
        long[] completeCost = {0}, fixed = {0};
        int[] stage = {0};
        StrikeGameTests.afterTest(h, () -> {
            gate.complete(null);
            other.materialReservation = previousReservation;
            world.forgetRuinsForTest(event.id());
            if (pass[0] != null) pass[0].archive = previousArchive[0];
        });
        h.onEachTick(() -> {
            try {
                if (stage[0] == 0) {
                    String policy = (String) content.invoke(world.prep(), level);
                    if (policy == null || !RuinProgress.get(level).writable()) return;
                    pass[0] = RuinProgress.get(level).pass(event.id());
                    previousArchive[0] = pass[0].archive;
                    pass[0].archive = UUID.randomUUID() + "/" + UUID.randomUUID() + "/" + policy;
                    var ctx = world.ruins(level, event, level.getGameTime());
                    context[0] = ctx;
                    writer[0] = plans ? ctx.restoredPlans : ctx.archive();
                    var pages = (RuinPages) field(writer[0], "pages").get(writer[0]);
                    // Preserve the real directory probe and writer; only delay the ordered predecessor.
                    var prior = (CompletableFuture<?>) field(pages, "tail").get(pages);
                    field(pages, "tail").set(pages, prior.thenCompose(unused -> gate));
                    for (int i = 0; i < keys.length; i++) {
                        var pos = new ChunkPos(keys[i]);
                        long bytes;
                        if (plans) {
                            var plan = RuinStorageGameTests.plan(ctx.grid(level, pos, false));
                            h.assertTrue(plan.changedBlocks() > 0, "Write fixture has no actual destructive plan");
                            ctx.restoredPlans.put(keys[i], plan);
                            bytes = plan.bytes();
                            completeCost[0] += bytes; // Retained source while this write is pending.
                        } else {
                            shots[i] = ctx.shot(level, pos.x, pos.z);
                            bytes = shots[i].bytes;
                        }
                        completeCost[0] += Math.max(4096, bytes * 3) + 48; // Encoder allowance and receipt entry.
                    }
                    other.materialReservation += NuclearPrep.PLANS_CAP - NuclearPrep.residentMemory(level) - completeCost[0] + 1;
                    fixed[0] = other.materialReservation;
                    stage[0] = 1;
                }
                var ctx = context[0];
                if (stage[0] == 1) {
                    if (plans) world.ruins(level, event, level.getGameTime()); // Actual restored-plan pump/supplier.
                    else for (int i = 0; i < keys.length; i++) ctx.archive().evictable(keys[i], shots[i]);
                    long held = NuclearPrep.residentMemory(level);
                    int writes = ((Map<?, ?>) field(writer[0], "writes").get(writer[0])).size();
                    Incoming.LOG.info("SPILL_BOUNDARY plans={} completeCost={} held={} cap={} writes={} gateDone={}",
                            plans, completeCost[0], held, NuclearPrep.PLANS_CAP, writes, gate.isDone());
                    h.assertTrue(held <= NuclearPrep.PLANS_CAP, "Held spill receipts exceeded known resident cap by " + (held - NuclearPrep.PLANS_CAP));
                    h.assertTrue(writes == 1 && !gate.isDone(), "One-byte-short budget must admit only one of the two held writes");
                    other.materialReservation--; // Explicitly fund the missing byte, not an asynchronous refund.
                    fixed[0]--;
                    if (plans) world.ruins(level, event, level.getGameTime());
                    else for (int i = 0; i < keys.length; i++) ctx.archive().evictable(keys[i], shots[i]);
                    h.assertTrue(((Map<?, ?>) field(writer[0], "writes").get(writer[0])).size() == 2,
                            "Fully funded writes did not both reach the held real writer");
                    h.assertTrue(NuclearPrep.residentMemory(level) == NuclearPrep.PLANS_CAP,
                            "Complete write ownership did not match the exact funded boundary");
                    stage[0] = 2;
                    gate.complete(null);
                }
                h.assertTrue(other.materialReservation == fixed[0], "External owner changed after exact write admission");
                if (plans) {
                    world.ruins(level, event, level.getGameTime());
                    if (ctx.restoredPlans.pendingWrites() != 0) return;
                } else {
                    for (int i = 0; i < keys.length; i++) if (!ctx.archive().evictable(keys[i], shots[i])) return;
                }
                h.assertTrue(NuclearPrep.residentMemory(level) < NuclearPrep.PLANS_CAP,
                        "Completed actual receipts did not release encoder/source ownership");
                h.succeed();
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
    }

    private static Field field(Object owner, String name) throws NoSuchFieldException {
        Field result = owner.getClass().getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
}
