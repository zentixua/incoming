package ua.zentix.incoming.nuclear.world;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.util.Terrain;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuinMatterRepeatTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field f = RuinMatter.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static RuinRecords<RuinMatter.Piece> queue(RuinMatter ledger) throws ReflectiveOperationException {
        return (RuinRecords<RuinMatter.Piece>) field("moving").get(ledger);
    }

    private static RuinMatter.Piece restored(BlockState state) throws ReflectiveOperationException {
        var constructor = RuinMatter.Piece.class.getDeclaredConstructor(BlockPos.class, BlockState.class,
                CompoundTag.class, Vec3.class, long.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new BlockPos(8, 60, 8), state, null, Vec3.ZERO, 0L, true);
    }

    @Test void fifoRotationKeepsCapturedClassificationWithoutTagQueries() throws Exception {
        var ledger = new RuinMatter();
        BlockState state = mock(BlockState.class);
        when(state.is(BlockTags.LEAVES)).thenReturn(true);
        var piece = restored(state);
        assertTrue(piece.fine);
        queue(ledger).addLast(piece);
        // A reload changes future classifications, not the already captured queue record.
        when(state.is(BlockTags.LEAVES)).thenReturn(false);
        clearInvocations(state);
        for (int i = 0; i < 64; i++) {
            var decoded = queue(ledger).removeFirst();
            assertTrue(decoded.fine, "FIFO rotation reclassified an existing piece");
            queue(ledger).addLast(decoded);
        }
        verifyNoInteractions(state);
    }

    @Test void legacyFlagsClassifyOnceAndRotationKeepsEveryFieldAndCargo() throws Exception {
        for (int outside = 0; outside < 2; outside++) {
            var ledger = new RuinMatter();
            var state = mock(BlockState.class);
            var piece = restored(state);
            CompoundTag cargo = new CompoundTag(); cargo.putString("cargo", "seven diamonds");
            // The restored constructor accepts cargo without block callbacks.
            var constructor = RuinMatter.Piece.class.getDeclaredConstructor(BlockPos.class, BlockState.class,
                    CompoundTag.class, Vec3.class, long.class, boolean.class);
            constructor.setAccessible(true);
            piece = constructor.newInstance(piece.source, state, cargo, new Vec3(1, -2, 3), 17L, true);
            piece.id = 123; piece.scorch = 456; piece.scorchEvent = 789; piece.scorchTime = 42;
            piece.volume = 0.5; piece.mass = 1300; piece.x = -123.25; piece.y = 72.5; piece.z = 345.75;
            piece.tick = 19.5; piece.releasedTick = 17; piece.windX = -3; piece.windZ = 4;
            piece.drag = 5; piece.pulse = 6; piece.outside = outside != 0;
            queue(ledger).addLast(piece);
            byte[] legacy = queue(ledger).snapshot().getFirst();
            assertEquals(157, legacy.length, "Internal row width changed");
            // Original layout: four longs, three ints, then the old 0/1 outside byte.
            legacy[44] = (byte) outside;
            queue(ledger).removeFirst();
            queue(ledger).restore(legacy);
            when(state.is(BlockTags.LEAVES)).thenReturn(true);
            var decoded = queue(ledger).removeFirst();
            assertTrue(decoded.fine, "Legacy row did not classify current tags");
            assertEquals(piece.outside, decoded.outside);
            assertSame(cargo, decoded.contents);
            for (Field f : RuinMatter.Piece.class.getDeclaredFields()) {
                f.setAccessible(true);
                if (!f.getName().equals("fine")) assertEquals(f.get(piece), f.get(decoded), f.getName());
            }
            queue(ledger).addLast(decoded);
            long retained = ledger.retainedBytes();
            clearInvocations(state);
            for (int i = 0; i < 64; i++) {
                var next = queue(ledger).removeFirst();
                assertTrue(next.fine);
                assertEquals(decoded.outside, next.outside);
                assertSame(cargo, next.contents);
                queue(ledger).addLast(next);
            }
            assertEquals(retained, ledger.retainedBytes(), "Rotation grew retained ownership");
            verifyNoInteractions(state);
        }
    }

    @Test void blockedWindowIsPolledOncePerInvocationAndRefreshedInSameTick() throws Exception {
        var ledger = new RuinMatter();
        var level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(100L);
        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getMaxBuildHeight()).thenReturn(320);
        ChunkPos held = new ChunkPos(80, 80);
        field("held").set(ledger, held);
        field("heldRadius").setInt(ledger, 2);
        for (int i = 0; i < 64; i++) queue(ledger).addLast(restored(Blocks.STONE.defaultBlockState()));
        var clock = WorkClock.counting(1);
        try (var tickets = mockStatic(NuclearTickets.class); var terrain = mockStatic(Terrain.class)) {
            clock.start(1000); ledger.work(level, clock);
            tickets.verify(() -> NuclearTickets.neighbourhoodLoaded(level, held, 2), times(1));
            assertEquals(64, ledger.pending());
            assertEquals(0, ledger.deposited());
            for (var p : queue(ledger)) assertEquals(0, p.tick, "Unready material advanced physical time");
            // No game-time change: the next phase must observe newly ready held neighbours.
            tickets.when(() -> NuclearTickets.neighbourhoodLoaded(level, held, 2)).thenReturn(true);
            clock.start(1000); ledger.work(level, clock);
            tickets.verify(() -> NuclearTickets.neighbourhoodLoaded(level, held, 2), times(2));
            tickets.verify(() -> NuclearTickets.holdMatter(level, held, false, 2), times(1));
            tickets.verify(() -> NuclearTickets.holdMatter(level, new ChunkPos(0, 0), true, 1), times(1));
            // Destination checks stay live for every blocked piece, rather than inheriting held readiness.
            terrain.verify(() -> Terrain.ready(level, 0, 0), times(128));
            assertEquals(64, ledger.pending());
        }
    }

    @Test void readinessOutsideWorkAlwaysObservesCurrentWorld() throws Exception {
        var ledger = new RuinMatter();
        var level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(100L);
        var held = new ChunkPos(80, 80);
        field("held").set(ledger, held);
        field("heldRadius").setInt(ledger, 1);
        var destination = new BlockPos(8, 60, 8);
        try (var tickets = mockStatic(NuclearTickets.class); var terrain = mockStatic(Terrain.class)) {
            assertFalse(ledger.ready(level, destination, false));
            tickets.when(() -> NuclearTickets.neighbourhoodLoaded(level, held, 1)).thenReturn(true);
            assertFalse(ledger.ready(level, destination, false));
            tickets.verify(() -> NuclearTickets.neighbourhoodLoaded(level, held, 1), times(2));
            tickets.verify(() -> NuclearTickets.holdMatter(level, held, false, 1), times(1));
            terrain.verify(() -> Terrain.ready(level, 0, 0), times(2));
        }
    }
}
