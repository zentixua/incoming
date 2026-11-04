package ua.zentix.incoming.nuclear.world;

import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;

/** Real registered preparation admission; gates surround actual layout construction, never a fake READY result. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinLayoutAdmissionGameTests {
    private RuinLayoutAdmissionGameTests() {}

    @GameTest(template = "range", batch = "nuke_layout_admission", timeoutTicks = 1200, skyAccess = true)
    @SuppressWarnings("unchecked")
    public static void highYieldHandlesWaitForCancelledActualBuilderExit(GameTestHelper h) throws Exception {
        StrikeGameTests.gameSpeed(h);
        var level = h.getLevel();
        var prep = NuclearWorld.get(level).prep();
        int yield = IncomingConfig.SERVER.nukeMaxYield.get();
        int threads = IncomingConfig.SERVER.nukeRuinThreads.get();
        IncomingConfig.SERVER.nukeMaxYield.set(1000); // Test-only envelope; no player config or production ceiling change.
        IncomingConfig.SERVER.nukeRuinThreads.set(256);
        var builderField = NuclearPrep.class.getDeclaredField("layoutBuilder");
        builderField.setAccessible(true);
        var original = (Function<Detonation, NuclearPrep.Layout>) builderField.get(prep);
        var built = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        builderField.set(prep, (Function<Detonation, NuclearPrep.Layout>) d -> {
            int call = calls.incrementAndGet();
            var result = original.apply(d);
            if (call == 1) {
                built.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("test did not release actual layout");
                } catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return result;
        });
        UUID[] owners = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        UUID[] handles = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        StrikeGameTests.afterTest(h, () -> {
            release.countDown();
            try { builderField.set(prep, original); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            for (UUID handle : handles) RuinPreparation.cancel(level, handle);
            IncomingConfig.SERVER.nukeMaxYield.set(yield);
            IncomingConfig.SERVER.nukeRuinThreads.set(threads);
        });
        var target = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32))).add(0, 500, 0);
        for (int i = 0; i < handles.length; i++)
            RuinPreparation.prepare(level, handles[i], owners[i], target, 1000, true, false);
        int[] phase = {0};
        Object[] first = {null};
        long[] reservation = {0};
        h.onEachTick(() -> {
            for (int i = 0; i < handles.length; i++) RuinPreparation.keepAlive(level, handles[i], owners[i]);
            if (phase[0] == 0) {
                if (built.getCount() != 0) return;
                var a = RuinPreparation.status(level, handles[0]);
                var b = RuinPreparation.status(level, handles[1]);
                var c = RuinPreparation.status(level, handles[2]);
                if (b.resolvedTarget() == null || c.resolvedTarget() == null) return;
                h.assertTrue(a.stage() == RuinPreparation.Stage.PLANNING, "running layout reported READY: " + a);
                h.assertTrue(b.stage() == RuinPreparation.Stage.PAUSED && c.stage() == RuinPreparation.Stage.PAUSED,
                        "geometry without admitted layout bypassed admission: " + b + "; " + c);
                h.assertTrue(calls.get() == 1, "several high-yield builders entered the same workspace");
                first[0] = request(prep, handles[0]);
                reservation[0] = NuclearPrep.layoutReservation((Detonation) get(first[0], "geometry"));
                h.assertTrue(a.residentBytes() >= reservation[0], "in-flight layout lost its owner");
                h.assertTrue(NuclearPrep.memory(level) <= NuclearPrep.PLANS_CAP, "layout admission exceeded shared cap");
                RuinPreparation.cancel(level, handles[0]);
                phase[0] = 1;
            } else if (phase[0] == 1) {
                if (get(first[0], "layout") != null) return; // Registered tick has detached the cancelled preparation.
                h.assertTrue(prep.retainedBytes() >= reservation[0], "detached running allocation vanished from accounting");
                h.assertTrue(calls.get() == 1, "replacement builder entered before actual exit");
                h.assertTrue(RuinPreparation.status(level, handles[1]).stage() == RuinPreparation.Stage.PAUSED,
                        "cancelled Future prematurely reopened admission");
                release.countDown();
                phase[0] = 2;
            } else if (calls.get() > 1) {
                h.assertTrue(NuclearPrep.memory(level) <= NuclearPrep.PLANS_CAP, "replacement ignored shared ownership");
                h.succeed();
            }
        });
    }

    @GameTest(template = "range", batch = "nuke_layout_backing", timeoutTicks = 100, skyAccess = true)
    public static void metadataChargesBackingAfterLogicalCollectionsClear(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var prep = NuclearWorld.get(level).prep();
        UUID handle = UUID.randomUUID(), owner = UUID.randomUUID();
        RuinPreparation.prepare(level, handle, owner, Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 1, 32))), 1, true, false);
        StrikeGameTests.afterTest(h, () -> RuinPreparation.cancel(level, handle));
        Object p = request(prep, handle);
        long before = prep.retainedBytes();
        var todo = (it.unimi.dsi.fastutil.longs.LongArrayList) get(p, "todo");
        var considered = (it.unimi.dsi.fastutil.longs.LongOpenHashSet) get(p, "considered");
        for (int i = 0; i < 10000; i++) { todo.add(i); considered.add(i); }
        long grown = prep.retainedBytes();
        h.assertTrue(grown > before + 160000, "grown list/set backing arrays were omitted");
        todo.clear();
        considered.clear();
        h.assertTrue(prep.retainedBytes() == grown, "clear() hid still-retained backing arrays");
        h.succeed();
    }

    private static Object request(NuclearPrep prep, UUID handle) {
        try {
            var method = NuclearPrep.class.getDeclaredMethod("request", UUID.class);
            method.setAccessible(true);
            return method.invoke(prep, handle);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Object get(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
