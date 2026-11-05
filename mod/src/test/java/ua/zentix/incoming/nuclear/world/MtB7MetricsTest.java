package ua.zentix.incoming.nuclear.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MtB7MetricsTest {
    @TempDir Path world;
    @Test void sameLedgerClassInTwoDimensionsKeepsFileAndServerLifetimeInEveryPhase() {
        String overworld = MtB7Metrics.fileKey(world.resolve("data/incoming_ruin_matter.dat").toFile());
        String nether = MtB7Metrics.fileKey(world.resolve("DIM-1/data/incoming_ruin_matter.dat").toFile());
        assertNotEquals(overworld, nether);
        assertEquals(overworld, MtB7Metrics.fileKey(world.resolve("data/../data/incoming_ruin_matter.dat").toFile()));
        for (String phase : new String[]{"enqueue_return", "worker_start_queue", "atomic_write_complete", "freeze_complete"}) {
            var first = MtB7Metrics.saveRow(1, 7, overworld, "RuinMatter", phase, 1, true).split(",");
            var second = MtB7Metrics.saveRow(2, 7, nether, "RuinMatter", phase, 2, true).split(",");
            assertEquals("7", first[2]); assertEquals("7", second[2]);
            assertEquals("1", first[3]); assertEquals("2", second[3]);
            assertNotEquals(first[4], second[4]); assertEquals(first[5], second[5]);
            assertEquals(phase, first[6]); assertEquals(phase, second[6]);
        }
    }
}
