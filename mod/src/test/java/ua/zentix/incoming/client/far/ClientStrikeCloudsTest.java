package ua.zentix.incoming.client.far;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.StrikeClouds;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.WorldClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's side of rule 10 (time note): a late joiner, a replay seek and live packets give one set of clouds, each at
 * its motion age, none twice.
 */
class ClientStrikeCloudsTest {
    private record Built(S2C.StrikeClouds.Cloud cloud, long ticks) {}

    private final ClientStrikeClouds.Ledger ledger = new ClientStrikeClouds.Ledger();
    private final List<Built> built = new ArrayList<>();

    private static S2C.Blast blast(int i) {
        return new S2C.Blast(S2C.Blast.DRONE, new Vec3(100 + i, 64, -40), 3, 64, i, Optional.empty(), WeaponType.DRONE.id());
    }

    private static S2C.StrikeClouds.Cloud cloud(long born, S2C.Blast b) {
        return new S2C.StrikeClouds.Cloud(S2C.StrikeClouds.BLAST, born, 0, Air.CALM, Float.NaN, b);
    }

    private static S2C.StrikeClouds.Cloud fuel(long born) {
        return new S2C.StrikeClouds.Cloud(S2C.StrikeClouds.FUEL, born, 0, Air.CALM, Float.NaN,
                new S2C.FuelFire(4, List.of(new BlockPos(100, 63, -40)), 0, WeaponType.MISSILE.id(), born));
    }

    private void tick(long now) {
        tick(now, 0, c -> true);
    }

    /** Fake clock: each build takes a millisecond. */
    private long nanos;

    private void tick(long now, double counted, Predicate<S2C.StrikeClouds.Cloud> ready) {
        ledger.tick(now, counted, ready, c -> ClientStrikeClouds.position(c.packet()).x, (c, ticks) -> {
            built.add(new Built(c, ticks));
            nanos += 1_000_000;
        }, () -> nanos);
    }

    /** A friend joining at minute 10 gets the strike as it is at minute 10. */
    @Test
    void aJoinerAtMinuteTenBuildsTheCloudTenMinutesOld() {
        S2C.StrikeClouds.Cloud c = cloud(1000, blast(1));
        ledger.sync(List.of(c), 13_000);
        tick(13_000);
        assertEquals(List.of(new Built(c, 12_000)), built);
        tick(13_001);
        assertEquals(1, built.size(), "built once");
    }

    /** A cloud seen live is not built again from the list that follows it. */
    @Test
    void aLiveCloudIsNotBuiltAgain() {
        S2C.Blast b = blast(1);
        ledger.live(b, 1000);
        ledger.sync(List.of(cloud(1000, b)), 1020);
        tick(1020);
        assertTrue(built.isEmpty(), built.toString());
    }

    /** A cloud past its law's life is not built; nor one that a counted hour has aged past it. */
    @Test
    void anEndedCloudIsNotBuilt() {
        long life = Math.round(StrikeClouds.LIFE_S * 20);
        ledger.sync(List.of(cloud(0, blast(1))), life);
        tick(life);
        assertTrue(built.isEmpty(), "past its life");
        ledger.sync(List.of(cloud(1000, blast(2))), 1200);
        tick(1200, 3600 / WorldClock.SECONDS_PER_TICK, c -> true);
        assertTrue(built.isEmpty(), "after a counted hour");
        assertEquals(0, ledger.waiting());
    }

    /**
     * A replay seek clears every cloud; the last list is built again at the new position's ages, whichever comes first,
     * the list read in the seek or the reset. A strike after the position waits for it and then comes live, once.
     */
    @Test
    void aSeekRebuildsAtTheNewAges() {
        S2C.Blast early = blast(1), late = blast(2);
        ledger.live(early, 1000);
        ledger.live(late, 3000);
        ledger.sync(List.of(cloud(1000, early), cloud(3000, late)), 3020);
        tick(3020);
        assertTrue(built.isEmpty());
        // seek back to 2000: the snapshot's last list (read before the reset) and the reset
        ledger.sync(List.of(cloud(1000, early)), 2000);
        ledger.rewound(2000);
        tick(2000);
        assertEquals(List.of(new Built(cloud(1000, early), 1000)), built);
        // the same seek, the reset first and the list after it
        built.clear();
        ledger.rewound(2000);
        ledger.sync(List.of(cloud(1000, early)), 2000);
        tick(2000);
        assertEquals(List.of(new Built(cloud(1000, early), 1000)), built);
        // seek back again with a list that already holds a later strike: it waits for its moment
        built.clear();
        ledger.sync(List.of(cloud(1000, early), cloud(3000, late)), 2000);
        ledger.rewound(2000);
        tick(2000);
        assertEquals(1, built.size());
        tick(2999);
        assertEquals(1, built.size(), "the later strike is not up before it happens");
        ledger.live(late, 3000);
        tick(3000);
        assertEquals(1, built.size(), "the later strike came live, not built twice");
    }

    /** A joiner's chunks come after the list: a cloud waits for the ground under it, at most {@code CHUNK_WAIT}. */
    @Test
    void aCloudWaitsForItsChunk() {
        S2C.StrikeClouds.Cloud c = cloud(0, blast(1));
        ledger.sync(List.of(c), 600);
        tick(600, 0, x -> false);
        assertTrue(built.isEmpty());
        tick(650, 0, x -> true);
        assertEquals(List.of(new Built(c, 650)), built, "built at its age when the chunk came");
        built.clear();
        S2C.StrikeClouds.Cloud far = cloud(0, blast(2));
        ledger.sync(List.of(far), 700);
        tick(700 + ClientStrikeClouds.Ledger.CHUNK_WAIT - 1, 0, x -> false);
        assertTrue(built.isEmpty());
        tick(700 + ClientStrikeClouds.Ledger.CHUNK_WAIT, 0, x -> false);
        assertEquals(1, built.size(), "a chunk that never comes holds it no longer");
    }

    /** A full list is built over ticks within the time budget, the nearest first. */
    @Test
    void aFullListSpreadsOverTicksNearestFirst() {
        List<S2C.StrikeClouds.Cloud> all = new ArrayList<>();
        for (int i = S2C.StrikeClouds.MAX - 1; i >= 0; i--) all.add(cloud(i, blast(i)));
        ledger.sync(all, 1000);
        tick(1000);
        long perTick = ClientStrikeClouds.Ledger.BUILD_NANOS / 1_000_000;
        assertEquals(perTick, built.size(), "builds within the budget");
        for (int i = 0; i < built.size(); i++) assertEquals(blast(i), built.get(i).cloud().packet(), "the nearest first");
        for (long t = 1001; ledger.waiting() > 0; t++) tick(t);
        assertEquals(S2C.StrikeClouds.MAX, built.size());
    }

    /** At least one a tick, however slow a build. */
    @Test
    void oneBuildsEvenOverTheBudget() {
        ledger.sync(List.of(cloud(0, blast(1)), cloud(0, blast(2))), 100);
        ledger.tick(100, 0, c -> true, c -> 0, (c, ticks) -> {
            built.add(new Built(c, ticks));
            nanos += 50_000_000;
        }, () -> nanos);
        assertEquals(1, built.size());
    }

    /** A crater fire is a state: applied from every list and again after a seek, never before its strike. */
    @Test
    void aCraterFireFollowsEveryList() {
        ledger.sync(List.of(fuel(500)), 400);
        tick(400);
        assertTrue(built.isEmpty(), "before the strike");
        tick(500);
        assertEquals(1, built.size());
        ledger.sync(List.of(fuel(500)), 520);
        tick(520);
        assertEquals(2, built.size(), "a later list updates it");
        ledger.rewound(600);
        tick(600);
        assertEquals(3, built.size(), "a seek restores it");
    }
}
