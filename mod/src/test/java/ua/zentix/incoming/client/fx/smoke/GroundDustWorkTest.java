package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Lifetime probes must not rebuild their moment work tables at every future time sample. */
class GroundDustWorkTest {
    private static final double[] AGES = {.1, 1, 30, 3, 600, .2, 10, 0};
    private static final CloudModel.Air AIR = new CloudModel.Air(.01, 4, -3, .2, 3, .5);

    @Test
    void lifetimeProbesHaveBoundedAllocationAndRetainRewinds() {
        var platform = ManagementFactory.getThreadMXBean();
        assumeTrue(platform instanceof com.sun.management.ThreadMXBean,
                "Allocation accounting requires the HotSpot management extension");
        var bean = (com.sun.management.ThreadMXBean) platform;
        assumeTrue(bean.isThreadAllocatedMemorySupported());
        boolean enabled = bean.isThreadAllocatedMemoryEnabled();
        if (!enabled) bean.setThreadAllocatedMemoryEnabled(true);
        try {
            var dust = new GroundDust(150, 0, 1, AIR);
            double[] expected = new double[AGES.length];
            for (int i = 0; i < AGES.length; i++) expected[i] = new GroundDust(150, 0, 1, AIR).tau(AGES[i]);
            for (int i = 0; i < 256; i++) dust.tau(AGES[i % AGES.length]);
            int queries = 128;
            long thread = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(thread);
            double sum = 0;
            for (int i = 0; i < queries; i++) sum += dust.tau(AGES[i % AGES.length]);
            long allocated = bean.getThreadAllocatedBytes(thread) - before;
            System.out.println("Ground-dust lifetime work: " + allocated + " allocated bytes for " + queries + " queries");
            assertTrue(sum > 0, "the probe must exercise visible dust");
            for (int i = 0; i < AGES.length; i++) {
                assertEquals(expected[i], dust.tau(AGES[i]), 0, "rewound optical depth at " + AGES[i]);
            }
            // Six 5x8 moment work tables alone exceed 2 KiB/query on the supported HotSpot runtimes.
            // Leave room for the clump edge values and probe callback without fixing object-header sizes.
            assertTrue(allocated < queries * 2048L,
                    "Lifetime probes allocated " + allocated + " bytes for " + queries + " queries");
        } finally {
            if (!enabled) bean.setThreadAllocatedMemoryEnabled(false);
        }
    }
}
