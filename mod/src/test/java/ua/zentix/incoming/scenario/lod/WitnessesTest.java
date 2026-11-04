package ua.zentix.incoming.scenario.lod;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WitnessesTest {
    @Test
    void heldDatabaseReadCannotBlockProducerOrReset() throws Exception {
        var pending = new ArrayList<String>();
        pending.add("old epoch");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reading = executor.submit(() -> Witnesses.check(pending, value -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return true;
            }, value -> fail("cancelled witness must not confirm a new epoch")));
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS));
                var producer = executor.submit(() -> {
                    synchronized (pending) { pending.clear(); pending.add("new epoch"); return pending.size(); }
                });
                assertEquals(1, producer.get(1, TimeUnit.SECONDS), "producer progresses while external I/O remains held");
            } finally { release.countDown(); }
            reading.get(1, TimeUnit.SECONDS);
            assertEquals(java.util.List.of("new epoch"), pending);
        }
    }
}
