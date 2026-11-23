package ua.zentix.incoming.nuclear.world;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.radiation.MobFallout;
import ua.zentix.incoming.nuclear.radiation.RadiationTicker;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.util.WorldClock;
import ua.zentix.incoming.work.WorkScheduler;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A dropped crater job does not keep its nuclear round counted (A2 review round 2). */
class CraterImpactTest {
    @Test
    @SuppressWarnings("try")
    void aDroppedCraterDoesNotKeepItsRoundOpenUntilRestart() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        ServerLevel level = mock(ServerLevel.class);
        NuclearWorld world = new NuclearWorld();
        NuclearEvents events = new NuclearEvents();
        long now = 1199;
        when(level.getGameTime()).thenReturn(now);
        Detonation burst = new Detonation(1, new Vec3(0.5, 64, 0.5), 64, 1, true, 0,
                0, 0, 20000, 42, 0.05f, false, 1, Detonation.NO_DAY_TIME, false, false);
        events.add(burst, 0);
        events.impact(burst.id(), RoundRef.of(UUID.randomUUID(), 0, "craterError"), null);
        events.craterProgress(burst.id(), 0, false);
        WorkClock budget = WorkClock.counting(1);

        try (var eventData = mockStatic(NuclearEvents.class); var worldData = mockStatic(NuclearWorld.class, CALLS_REAL_METHODS);
             var worldClock = mockStatic(WorldClock.class); var workers = mockStatic(RuinWorkers.class);
             var tickets = mockStatic(NuclearTickets.class); var packets = mockStatic(PacketDistributor.class);
             var radiation = mockStatic(RadiationTicker.class); var mobFallout = mockStatic(MobFallout.class);
             var scheduler = mockStatic(WorkScheduler.class); var progress = mockStatic(RuinProgress.class);
             var jobs = mockConstruction(CraterJob.class, (job, context) -> {
                 when(job.detonation()).thenReturn(burst);
                 doThrow(new IllegalStateException("chunk block callback failed")).when(job).step(eq(level), nullable(RandomSource.class));
             })) {
            eventData.when(() -> NuclearEvents.get(level)).thenReturn(events);
            worldData.when(() -> NuclearWorld.get(level)).thenReturn(world);
            worldClock.when(() -> WorldClock.now(level)).thenReturn((double) now);
            workers.when(() -> RuinWorkers.adapt(any())).thenAnswer(call -> null);
            tickets.when(() -> NuclearTickets.holdOrigin(eq(level), anyBoolean())).thenAnswer(call -> null);
            packets.when(() -> PacketDistributor.sendToPlayersInDimension(eq(level), any())).thenAnswer(call -> null);
            radiation.when(() -> RadiationTicker.tick(level)).thenAnswer(call -> null);
            scheduler.when(WorkScheduler::prepCapNanos).thenReturn(1L);
            progress.when(() -> RuinProgress.get(level)).thenReturn(new RuinProgress());

            budget.start(3);
            NuclearStrikes.work(List.of(level), budget);
            assertEquals(1, jobs.constructed().size(), "loaded the crater from saved progress");
            verify(jobs.constructed().getFirst()).release(level);
            assertEquals(0, world.craterJobs(), "the failed crater is off the live queue");
            assertFalse(world.frontBusy(burst, now), "the wave has passed");
            assertEquals(0, events.impacts().size(), "no live job can finish the counted impact");
        }
    }
}
