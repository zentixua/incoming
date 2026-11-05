package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;

/** Real 1-Mt header layout: consumed reverse edges must not retain the transition's memory. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinHeaderLifetimeGameTests {
    private RuinHeaderLifetimeGameTests() {}

    @GameTest(template = "pad", batch = "nuke_header_lifetime", timeoutTicks = 100)
    @SuppressWarnings("unchecked")
    public static void headerRepliesReleaseTheirIndexWithoutChangingTileMasks(GameTestHelper h) throws Exception {
        var geometry = new Detonation(-1, new Vec3(3104.5, 63, 560.5), 63, 1000,
                true, 0, 0, 0, 20000, 0, 1, false, .25f);
        NuclearPrep prep = new NuclearPrep();
        Class<?> type = Class.forName(NuclearPrep.class.getName() + "$Prep");
        var constructor = type.getDeclaredConstructor(int.class, long.class, LongConsumer.class);
        constructor.setAccessible(true);
        Object request = constructor.newInstance(1, 0L, (LongConsumer) ignored -> {});
        ((List<Object>) get(prep, "preps")).add(request);
        method("install", type, NuclearPrep.Layout.class).invoke(null, request, NuclearPrep.layout(geometry));
        var input = (LongArrayList) get(request, "toScan");
        int count = input.size();
        h.assertTrue(count == 277736, "The native 1-Mt header cohort changed");
        long first = prep.retainedBytes(), last = input.getLong(count - 1);
        Method answer = method("answer", type, long.class, boolean.class);
        for (int i = 0; i < count - 1; i++) {
            long key = input.getLong(i);
            answer.invoke(null, request, key, whole(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
        long partial = prep.retainedBytes();
        Incoming.LOG.info("HEADER_LIFETIME headers {} charged {} -> {} with one reply pending", count, first, partial);
        h.assertTrue(first - partial > 20L << 20, "Consumed header reply lists still own the transition room");
        h.assertTrue(get(request, "toScan") == input, "An outstanding reply lost its scan owner");
        field(request, "nextScan").setInt(request, count);
        field(request, "scanning").setInt(request, 1);
        ((ConcurrentLinkedQueue<long[]>) get(request, "scanned")).add(new long[]{last,
                whole(ChunkPos.getX(last), ChunkPos.getZ(last)) ? 1 : 0});
        Method scan = method("scan", net.minecraft.server.level.ServerLevel.class, type);
        scan.invoke(null, h.getLevel(), request);
        long complete = prep.retainedBytes();
        h.assertTrue(((Long2ObjectOpenHashMap<?>) get(request, "askedBy")).isEmpty(), "Completed scan retains its reverse index");
        h.assertTrue(((LongArrayList) get(request, "toScan")).isEmpty(), "Completed scan retains its input backing");
        h.assertTrue(first - complete > 40L << 20, "Completed 1-Mt headers retain their obsolete workspace");
        h.assertTrue((int) get(request, "nextScan") == count && (int) get(request, "scanning") == 0,
                "Retirement changed completed header provenance");
        for (Object tile : (List<?>) get(request, "tiles")) {
            ChunkPos centre = (ChunkPos) get(tile, "centre");
            int expected = 0;
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                boolean loadable = true;
                for (int dx = -2; dx <= 2 && loadable; dx++) for (int dz = -2; dz <= 2; dz++)
                    if (!whole(centre.x + x + dx, centre.z + z + dz)) { loadable = false; break; }
                if (!loadable) expected |= 1 << ((x + 2) * 5 + z + 2);
            }
            h.assertTrue((int) get(tile, "unknown") == 0 && (int) get(tile, "partial") == expected,
                    "Header reclamation changed the complete world-availability mask at " + centre);
            h.assertTrue(get(tile, "state").toString().equals(expected == (1 << 25) - 1 ? "SKIP" : "WAIT"),
                    "Header reclamation changed tile admission at " + centre);
        }
        scan.invoke(null, h.getLevel(), request);
        h.assertTrue(prep.retainedBytes() == complete, "Completed scan re-admitted storage or refunded backing twice");
        Incoming.LOG.info("HEADER_LIFETIME complete {} tiles; charged {} -> {} -> {}; reclaimed {} bytes",
                ((List<?>) get(request, "tiles")).size(), first, partial, complete, first - complete);
        h.succeed();
    }

    private static boolean whole(int x, int z) { return Math.floorMod(x * 31 + z * 17, 97) != 0; }

    private static Method method(String name, Class<?>... arguments) throws Exception {
        Method method = NuclearPrep.class.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private static Object get(Object owner, String name) throws Exception { return field(owner, name).get(owner); }

    private static Field field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
