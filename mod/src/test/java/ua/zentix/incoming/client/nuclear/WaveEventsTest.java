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
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.compat.FlashbackReplay;
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
    void serverReceiptDeduplicatesEvenWhenShownWindHasExpired() {
        for (int lead : new int[]{0, 19, 20, 40, 106}) {
            var events = new WaveEvents();
            var delivered = new ArrayList<Integer>();
            var ordinals = new ArrayList<Integer>();
            for (int due : new int[]{100, 101, 100, 79, 350}) {
                var receipt = events.receive(batch(due), 100, 5, p -> p.pos().getX());
                if (receipt != null) ordinals.add(receipt.ordinal());
            }
            assertEquals(List.of(0, 1, 2, 3), ordinals);
            for (int t = 100 + lead; t <= 351; t++)
                events.drain(t, (batch, pieces) -> pieces.forEach(p -> delivered.add(p.pos().getX())), null);
            assertEquals(lead <= 20 ? List.of(100, 101, 350) : List.of(350), delivered);
        }
    }

    @Test
    @SuppressWarnings("try")
    void scatterAndLiveClatterShareTheDrawnWindowAndSeekDiscardsPendingWork() throws ReflectiveOperationException {
        var client = mock(Minecraft.class);
        var level = mock(ClientLevel.class);
        var camera = mock(Camera.class);
        var renderer = mock(GameRenderer.class);
        when(renderer.getMainCamera()).thenReturn(camera);
        when(camera.getPosition()).thenReturn(new Vec3(15000, 0, 0));
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
             var clatter = mockConstruction(Clatter.class); var after = mockConstruction(NukeAfter.class);
             var echoes = mockConstruction(NukeEchoes.class); var flash = mockStatic(NukeFlash.class);
             var light = mockStatic(NukeLight.class); var aftermath = mockStatic(FlashAftermath.class);
             var compat = mockStatic(FlashbackReplay.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            air.when(() -> Air.of(level)).thenReturn(Air.CALM);
            density.when(() -> Fx.density(any())).thenReturn(0f);
            double[] position = {100};
            compat.when(() -> FlashbackReplay.isReplay(null)).thenReturn(true);
            compat.when(() -> FlashbackReplay.position(isNull(), anyInt())).thenAnswer(call -> position[0]);
            try {
                for (int lead : new int[]{0, 19, 40, 106}) {
                    Replay.reset();
                    Replay.marker(100, false, () -> {}, () -> {});
                    position[0] = 100;
                    ClientNuclear.detonation(new S2C.NukeDetonation(d, 0), true);
                    var active = ClientNuclear.detonations().getFirst();
                    position[0] += anchor + lead;
                    assertEquals(anchor + lead, active.ticks(0));
                    ClientNuclear.anchor(new S2C.NukeTick(d.gameTime() + anchor));
                    ClientNuclear.debris(packet, false);
                    ClientNuclear.debris(packet, true);
                    ClientNuclear.debris(new S2C.NukeDebris(7, List.copyOf(packet.pieces()), 5), true);
                    boolean scattered = lead <= 19;
                    assertTrue(NukeDust.describe().contains("places " + (scattered ? 1 : 0) + ","), NukeDust.describe());
                    var sound = clatter.constructed().getLast();
                    ClientNuclear.anchor(new S2C.NukeTick(d.gameTime() + anchor + 50));
                    active.tick();
                    active.sounds.tick();
                    verify(sound, never()).add(any());
                    active.tick();
                    active.sounds.tick();
                    active.tick();
                    active.sounds.tick();
                    if (scattered) {
                        var event = ArgumentCaptor.forClass(Clatter.Event.class);
                        verify(sound).add(event.capture());
                        assertEquals(due, event.getValue().startTick(), 1e-9);
                    } else verify(sound, never()).add(any());
                    verify(after.constructed().getLast()).ruins(packet.pieces());
                    ClientNuclear.sync(new S2C.NukeSync(List.of(), List.of()));
                }
                Replay.reset();
                for (int i = 97; i <= 100; i++) Replay.marker(i, false, ClientNuclear::seekStarted, ClientNuclear::rewound);
                position[0] = 100;
                ClientNuclear.detonation(new S2C.NukeDetonation(d, 0), true);
                var old = ClientNuclear.detonations().getFirst();
                var oldSound = clatter.constructed().getLast();
                var nearby = new S2C.NukeDebris(7, List.of(new S2C.NukeDebris.Piece(BlockPos.ZERO,
                        Blocks.GLASS.defaultBlockState())), 1);
                double nearDue = d.arrivalTicks(Vec3.atCenterOf(BlockPos.ZERO).distanceTo(d.burst()));
                assertTrue(nearDue + 0.1 < due);
                position[0] = 100 + nearDue + 0.1;
                ClientNuclear.anchor(new S2C.NukeTick(d.gameTime()));
                ClientNuclear.debris(nearby, true); // already scattered, clatter awaits the two-tick chunk settle
                ClientNuclear.debris(packet, true); // visual and sound still await this crossing
                assertTrue(old.events.pending());
                assertTrue(NukeDust.describe().contains("places 1,"));
                Replay.marker(100, true, ClientNuclear::seekStarted, ClientNuclear::rewound);
                position[0] = 100 + anchor;
                for (int i = 0; i < 3; i++) { old.tick(); old.sounds.tick(); }
                assertFalse(old.events.pending());
                assertTrue(NukeDust.describe().contains("places 1,"));
                verify(oldSound, never()).add(any());
                when(level.getGameTime()).thenReturn(d.gameTime() + anchor);
                Replay.marker((int) position[0], true, ClientNuclear::seekStarted, ClientNuclear::rewound);
                Replay.marker((int) position[0], false, ClientNuclear::seekStarted, ClientNuclear::rewound);
                var rebuilt = ClientNuclear.detonations().getFirst();
                assertTrue(rebuilt.epoch > old.epoch);
                assertFalse(rebuilt.live);
                ClientNuclear.anchor(new S2C.NukeTick(d.gameTime() + anchor));
                ClientNuclear.debris(packet, true);
                for (int i = 0; i < 3; i++) { rebuilt.tick(); rebuilt.sounds.tick(); }
                assertTrue(NukeDust.describe().contains("places 1,"));
                verify(clatter.constructed().getLast(), never()).add(any());
            } finally {
                ClientNuclear.sync(new S2C.NukeSync(List.of(), List.of()));
                Replay.reset();
            }
        }
    }

    static S2C.NukeDebris batch(int due) {
        return new S2C.NukeDebris(7, List.of(new S2C.NukeDebris.Piece(new BlockPos(due, 0, 0),
                Blocks.STONE.defaultBlockState())), 1);
    }
}
