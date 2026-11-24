package ua.zentix.incoming.nuclear.world;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;

import static org.junit.jupiter.api.Assertions.*;

/** Review regression: each fine world cell outside preparation owns a representative stored chunk. */
class MaterialCoverageProbeTest {
    @Test void geometry() {
        for (double kt : new double[]{1, 15, 100, 1000}) for (boolean surface : new boolean[]{true, false}) {
            double hob = surface ? 0 : ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(kt);
            var d = new Detonation(1, new Vec3(0.5, 64 + hob, 0.5), 64, kt, surface, 20, 0, 0, 20000, 1L, 1f, false, 0.35f);
            var b = DustGrid.Bounds.of(d);
            var grid = new DustGrid(b);
            double inner = NuclearPrep.heavyRadius(d) + RuinPlanner.REACH * 16;
            long[] sparse = MaterialSurvey.sparse(d);
            var represented = new java.util.BitSet(grid.count());
            for (long key : sparse) {
                int x = net.minecraft.world.level.ChunkPos.getX(key) * 16 + 8;
                int z = net.minecraft.world.level.ChunkPos.getZ(key) * 16 + 8;
                int cell = grid.index(x, z);
                assertFalse(represented.get(cell), "two sparse reads for one cell");
                represented.set(cell);
            }
            grid.visit((i, x, z, size) -> {
                if (size == DustGrid.FINE && Math.hypot(x - b.x(), z - b.z()) > inner)
                    assertTrue(represented.get(i), "fine cell without a read");
            });
        }
    }

    @Test void preparationsKeepIndependentWindowsUnderMaterialContention() {
        var reserved = new java.util.concurrent.atomic.AtomicLong();
        var material = new RuinInbox<Integer>(reserved::addAndGet, NuclearPrep.READ_BYTES);
        int first = 0, second = 0, fetched = 0;
        for (int tick = 0; tick < 20; tick++) {
            // Both preparations independently complete a full read window each tick. One stays full while
            // the other refills. The material inbox can still admit its entire share and reuse it next tick.
            int a = NuclearPrep.preparationSlots(0, true);
            int b = NuclearPrep.preparationSlots(0, true);
            assertEquals(0, NuclearPrep.preparationSlots(a, true));
            first += a;
            second += b;
            int available = NuclearPrep.materialSlots(reserved.get());
            for (int i = 0; i < available; i++) material.expect().accept(i);
            assertEquals(0, NuclearPrep.materialSlots(reserved.get()));
            while (material.poll() != null) fetched++;
        }
        assertEquals(640, first);
        assertEquals(640, second);
        assertEquals(640, fetched);
        assertEquals(64, NuclearPrep.preparationSlots(0, false));
    }
}
