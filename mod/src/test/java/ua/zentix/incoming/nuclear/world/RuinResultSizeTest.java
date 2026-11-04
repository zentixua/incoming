package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuinResultSizeTest {
    @Test
    void everyOwnedFieldHasAnExplicitElementLawAndLazyCachesArePrepaid() {
        var plan = new RuinPlan(new int[2], new long[3], new BlockState[4], new BlockState[5], new int[6],
                new int[7], new long[8], new long[9], new long[10], new LongArrayList(new long[11]),
                Arrays.asList(new BlockState[12]), null, new long[13], new ScorchedBiomes.Cells(new byte[16], new int[16]));
        plan.flash(new int[14]); plan.blown(new int[15]); plan.material(new long[16]);
        long expected = 64 + 2048 + 4L * 2 + 8L * 5 + 8L * 4 + 8L * 6 + 4L * 7 + 8L * 8
                + 8L * (3 + 9 + 10 + 11 + 12 + 13 + 16 + 1) + 96 + 4L * (14 + 15);
        assertEquals(expected, plan.bytes());
        long before = plan.bytes(); plan.touches(0); plan.hasFire(0);
        assertEquals(before, plan.bytes());
        assertEquals(before, plan.detached().bytes());
        assertEquals(0, RuinPlan.EMPTY.bytes());
    }

    @Test
    void oldColumnCachePrepaysBothSurfaceAndSupportingTopBands() throws Exception {
        var plan = new RuinPlan(new int[0], new long[0], new BlockState[0], new BlockState[0], new int[0],
                new int[0], new long[0], null, null, null, null, null, new long[0], null);
        long before = plan.bytes();
        int[] tops = plan.oldTops(0);
        assertEquals(2 * 256, tops.length);
        assertTrue(before >= 4L * tops.length);
        assertEquals(before, plan.bytes());
    }

    @Test
    void exhaustiveFieldInventorySeparatesBorrowedAndProducerOwnershipFromPayload() {
        var payload = Set.of("cells", "olds", "states", "fires", "heights", "lightAt", "oldHashes", "prints",
                "normals", "outside", "outsideState", "fluidTicks", "biomes", "flash", "blown", "material");
        var prepaid = Set.of("oldTops", "fireKeys", "sectionBits");
        var borrowedOrScalar = Set.of("ctx", "memoryWaiting", "resultOwner");
        for (var field : RuinPlan.class.getDeclaredFields()) if (!Modifier.isStatic(field.getModifiers()))
            assertTrue(payload.contains(field.getName()) || prepaid.contains(field.getName()) || borrowedOrScalar.contains(field.getName()),
                    "New field needs a byte/domain law: " + field.getName());
        for (var field : RuinResults.class.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()))
            assertTrue(field.getType().isPrimitive() || field.getType() == java.util.concurrent.atomic.AtomicLong.class,
                    "Global result policy must be numeric only: " + field);
    }

    @Test
    void maximumEncodedDimensionsCannotOverflowElementArithmetic() {
        long cells = 256L * 4096, fires = cells + 256, fluids = 18L * 18 * 4096;
        long bytes = RuinPlan.sourceBytes(cells, cells, 1024, fires, 3840, cells, 256, 256, 256,
                cells, cells, fluids, 16, 65536, fires, cells, 4);
        assertTrue(bytes > 0 && bytes < 256L << 20);
    }
}
