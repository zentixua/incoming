package ua.zentix.incoming.nuclear.world;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MtB7PendingTest {
    @Test
    void nativeBoundaryAndOverlappingUnionMatchMinecraftEnumerationExactly() {
        for (int distance : new int[]{2, 8, 16, 32}) {
            var a = ChunkTrackingView.of(new ChunkPos(-19, 23), distance);
            var b = ChunkTrackingView.of(new ChunkPos(-17, 22), distance);
            var views = new ChunkTrackingView[]{a, ChunkTrackingView.EMPTY, b, a};
            Set<Long> expected = new HashSet<>();
            for (var view : views) view.forEach(p -> expected.add(p.toLong()));
            Set<Long> actual = new HashSet<>();
            int[] cells = {0};
            assertTrue(MtB7Pending.nativeViews(views));
            MtB7Pending.visitViews(views, (x, z, watched) -> {
                cells[0]++;
                if (watched) assertTrue(actual.add(ChunkPos.asLong(x, z)), "Duplicate union member");
                return true;
            });
            assertEquals(expected, actual);
            assertEquals(3 * (2 * distance + 3) * (2 * distance + 3), cells[0]);
            assertTrue(actual.stream().anyMatch(key -> !a.isInViewDistance(ChunkPos.getX(key), ChunkPos.getZ(key))),
                    "Fixture must include the native outer border");
        }
    }

    @Test
    void movedViewHasNoRetainedMembershipAndTraversalHonorsEveryCellStop() {
        Set<Long> before = union(ChunkTrackingView.of(new ChunkPos(0, 0), 2));
        Set<Long> after = union(ChunkTrackingView.of(new ChunkPos(100, -100), 2));
        assertTrue(before.stream().noneMatch(after::contains));
        int[] visits = {0};
        MtB7Pending.visitViews(new ChunkTrackingView[]{ChunkTrackingView.of(new ChunkPos(0, 0), 1000)},
                (x, z, watched) -> ++visits[0] < 33);
        assertEquals(33, visits[0], "Even rejected square corners must honor cooperative stops");
    }

    @Test
    void unknownViewsRequireHolderFallbackWithoutCallingUnboundedForEach() {
        ChunkTrackingView custom = new ChunkTrackingView() {
            @Override public boolean contains(int x, int z, boolean outer) { return true; }
            @Override public void forEach(Consumer<ChunkPos> action) { fail("Unbounded custom enumeration"); }
        };
        assertFalse(MtB7Pending.nativeViews(new ChunkTrackingView[]{custom}));
        assertTrue(MtB7Pending.nativeViews(new ChunkTrackingView[]{ChunkTrackingView.EMPTY}));
    }

    private static Set<Long> union(ChunkTrackingView view) {
        Set<Long> result = new HashSet<>();
        MtB7Pending.visitViews(new ChunkTrackingView[]{view}, (x, z, watched) -> {
            if (watched) result.add(ChunkPos.asLong(x, z));
            return true;
        });
        return result;
    }
}
