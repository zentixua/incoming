package ua.zentix.incoming.client.nuclear;

import net.minecraft.SharedConstants;
import net.neoforged.fml.loading.LoadingModList;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.sound.Clatter;
import ua.zentix.incoming.util.Air;
import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WaveEventsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    void receiptLatchesFreshnessAndEpochOwnsPendingAndConsumedEvents() {
        for (int lead : new int[]{0, 19, 40, 106}) {
            var clock = new ClientNuclear.AgeClock(100, true);
            for (int i = 0; i < 40; i++) clock.tick();
            assertEquals(100, clock.confirmed());
            var first = batch(100);
            var second = batch(101);
            for (int epoch = 0; epoch < 2; epoch++) {
                var events = new WaveEvents();
                var delivered = new ArrayList<Integer>();
                var admitted = new ArrayList<Integer>();
                for (var packet : List.of(first, second, batch(100), batch(79), batch(350))) {
                    var receipt = events.receive(packet, clock.confirmed(), p -> p.pos().getX());
                    if (receipt != null) for (var p : receipt.eligible()) admitted.add(p.pos().getX());
                    events.drain(100 + lead, (batch, pieces) -> pieces.forEach(p -> delivered.add(p.pos().getX())));
                }
                assertEquals(lead == 0 ? List.of(100) : List.of(100, 101), delivered);
                events.drain(220, (batch, pieces) -> pieces.forEach(p -> delivered.add(p.pos().getX())));
                events.drain(220, (batch, pieces) -> fail("duplicate delivery"));
                assertEquals(List.of(100, 101), delivered);
                assertEquals(List.of(100, 101, 350), admitted);
                // Seek discards the queued 350 and admits the same recorded batches in the next epoch.
            }
        }
    }

    @Test
    void realConsumersKeepTimelyPacketsThroughDeferredClatter() throws ReflectiveOperationException {
        var client = mock(Minecraft.class);
        var level = mock(ClientLevel.class);
        var camera = mock(Camera.class);
        var renderer = mock(GameRenderer.class);
        when(renderer.getMainCamera()).thenReturn(camera);
        when(camera.getPosition()).thenReturn(Vec3.ZERO);
        when(level.getChunkSource()).thenReturn(mock(ClientChunkCache.class));
        var field = Minecraft.class.getDeclaredField("gameRenderer");
        field.setAccessible(true);
        field.set(client, renderer);
        client.level = level;
        when(level.dimensionType()).thenReturn(mock(net.minecraft.world.level.dimension.DimensionType.class));
        var d = new Detonation(7, Vec3.ZERO, 0, 15, true, 1000, 0, 0, 20_000, 0, 1, false, 1);
        var packet = new S2C.NukeDebris(7, List.of(new S2C.NukeDebris.Piece(new BlockPos(100, 3, 0),
                Blocks.GLASS.defaultBlockState())), 5);
        double due = d.arrivalTicks(Vec3.atCenterOf(packet.pieces().getFirst().pos()).distanceTo(d.burst()));
        long anchor = (long) Math.ceil(due);
        try (var minecraft = mockStatic(Minecraft.class); var water = mockStatic(NukeWater.class);
             var air = mockStatic(Air.class); var density = mockStatic(Fx.class);
             var clatter = mockConstruction(Clatter.class); var after = mockConstruction(NukeAfter.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            air.when(() -> Air.of(level)).thenReturn(Air.CALM);
            density.when(() -> Fx.density(any())).thenReturn(0f);
            for (int lead : new int[]{0, 19, 40, 106}) {
                when(level.getGameTime()).thenReturn(d.gameTime() + anchor + lead);
                ClientNuclear.sync(new S2C.NukeSync(List.of(d), List.of()));
                try {
                    var active = ClientNuclear.detonations().getFirst();
                    water.verify(() -> NukeWater.resolve(active, level));
                    assertEquals(anchor + lead, active.ticks(0));
                    ClientNuclear.anchor(new S2C.NukeTick(d.gameTime() + anchor));
                    ClientNuclear.debris(packet, false);
                    ClientNuclear.debris(packet, true);
                    ClientNuclear.debris(new S2C.NukeDebris(7, List.copyOf(packet.pieces()), 5), true);
                    assertTrue(NukeDust.describe().contains("places 1,"), NukeDust.describe());
                    var sound = clatter.constructed().getLast();
                    // Later anchors and world settle cannot change eligibility at receipt.
                    ClientNuclear.anchor(new S2C.NukeTick(d.gameTime() + anchor + 50));
                    active.tick();
                    active.sounds.tick();
                    verify(sound, never()).add(any());
                    active.tick();
                    active.sounds.tick();
                    active.tick();
                    active.sounds.tick();
                    var event = ArgumentCaptor.forClass(Clatter.Event.class);
                    verify(sound).add(event.capture());
                    assertEquals(due, event.getValue().startTick(), 1e-9);
                    verify(after.constructed().getLast()).ruins(packet.pieces());
                } finally {
                    ClientNuclear.sync(new S2C.NukeSync(List.of(), List.of()));
                }
            }
        }
    }

    private static S2C.NukeDebris batch(int due) {
        return new S2C.NukeDebris(7, List.of(new S2C.NukeDebris.Piece(new BlockPos(due, 0, 0),
                Blocks.STONE.defaultBlockState())), 1);
    }
}
