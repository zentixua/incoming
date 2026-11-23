package ua.zentix.incoming.client.nuclear;

import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.fx.Effects;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.Puffs;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.ThermalShadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class FlashAftermathClockTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearSmoke() {
        SmokeGrid.reset();
    }

    @Test
    void openHayEmitsBeforeEitherClockReachesTheFront() throws Exception {
        var clock = new ClientNuclear.AgeClock(1, false);
        assertEquals(1, probeHay(clock, new Vec3(0, 664, 0), distance -> distance < 400 ? 5 : 30, 1).size());
    }

    @Test
    void stalledFrontStillAllowsEmissionAndControlsExpiry() throws Exception {
        var clock = new ClientNuclear.AgeClock(0, true);
        clock.anchor(0);
        for (int tick = 0; tick < 2400; tick++) {
            clock.tick();
            SmokeGrid.tick();
        }
        assertEquals(200, clock.shown(), 1e-9);
        Puffs smoke = probeHay(clock, new Vec3(0, 64, 0), distance -> 205, 11);
        assertEquals(1, smoke.size(), "hay still emits at the lead brake with the front arriving at 205");

        double width = smoke.puffGrowth(SmokeGrid.seconds(0));
        for (int tick = 0; tick < 40; tick++) {
            clock.tick();
            SmokeGrid.tick();
        }
        SmokeGrid.Sink sink = mock(SmokeGrid.Sink.class);
        smoke.emit(SmokeGrid.seconds(0), sink);
        assertEquals(1, smoke.size(), "the local smoke clock ran two more seconds during the stall");
        assertEquals(width, smoke.puffGrowth(SmokeGrid.seconds(0)), 1e-9);

        for (int anchor = 1; anchor <= 200 && clock.shown() < 205; anchor++) {
            clock.anchor(anchor);
            clock.tick();
            SmokeGrid.tick();
            smoke.emit(SmokeGrid.seconds(0), sink);
            assertEquals(clock.shown() < 205 ? 1 : 0, smoke.size(), "shown age " + clock.shown());
        }
        assertTrue(clock.shown() >= 205, "the resumed front reaches the hay");
    }

    @Test
    void airburstUsesDistanceToTheSampledFace() throws Exception {
        // The horizontal front has passed at age 5. The sampled face is reached at age 30.
        var clock = new ClientNuclear.AgeClock(6, false);
        assertEquals(1, probeHay(clock, new Vec3(0, 664, 0), distance -> distance < 400 ? 5 : 30, 6).size());
    }

    @Test
    void wispClockFollowsTheSameDetonationAcrossSync() {
        Minecraft client = mock(Minecraft.class);
        ClientLevel level = mock(ClientLevel.class);
        client.level = level;
        when(level.dimensionType()).thenReturn(mock(DimensionType.class));
        long burstTick = 100;
        Detonation det = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, burstTick, 0, 5, 20_000, 0, 1, false, 1);
        S2C.NukeSync empty = new S2C.NukeSync(List.of(), List.of());
        try (var minecraft = mockStatic(Minecraft.class); var flash = mockStatic(FlashAftermath.class);
             var water = mockStatic(NukeWater.class); var light = mockStatic(NukeLight.class);
             var echoes = mockConstruction(NukeEchoes.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            when(level.getGameTime()).thenReturn(burstTick);
            try {
                ClientNuclear.detonation(new S2C.NukeDetonation(det, 0, 1), true);
                water.verify(() -> NukeWater.resolve(any(), eq(level)));
                light.verify(() -> NukeLight.detonation(level));
                assertEquals(1, echoes.constructed().size());
                var clock = ArgumentCaptor.forClass(DoubleUnaryOperator.class);
                flash.verify(() -> FlashAftermath.detonation(eq(det), clock.capture(), eq(0.0)));
                DoubleUnaryOperator age = clock.getValue();
                assertEquals(0, age.applyAsDouble(0), 1e-9);

                when(level.getGameTime()).thenReturn(burstTick + 6);
                ClientNuclear.sync(new S2C.NukeSync(List.of(det.withWave(det.wave())), List.of()));
                assertEquals(6, age.applyAsDouble(0), 1e-9);

                ClientNuclear.sync(new S2C.NukeSync(List.of(det.withWave(0.5f)), List.of()));
                assertEquals(Double.POSITIVE_INFINITY, age.applyAsDouble(0));
                ClientNuclear.sync(empty);
                assertEquals(Double.POSITIVE_INFINITY, age.applyAsDouble(0));
            } finally {
                ClientNuclear.sync(empty);
            }
        }
    }

    private static Puffs probeHay(ClientNuclear.AgeClock clock, Vec3 burst, DoubleUnaryOperator arrival, int effectTick)
            throws ReflectiveOperationException {
        Minecraft client = mock(Minecraft.class);
        GameRenderer renderer = mock(GameRenderer.class);
        Camera camera = mock(Camera.class);
        Vec3 eye = new Vec3(100, 66, 0);
        var gameRenderer = Minecraft.class.getDeclaredField("gameRenderer");
        gameRenderer.setAccessible(true);
        gameRenderer.set(client, renderer);
        when(renderer.getMainCamera()).thenReturn(camera);
        when(camera.getPosition()).thenReturn(eye);

        ClientLevel level = mock(ClientLevel.class);
        client.level = level;
        when(level.getMinBuildHeight()).thenReturn(-64);
        when(level.getHeight(any(), anyInt(), anyInt())).thenReturn(65);
        when(level.getBlockState(any(BlockPos.class))).thenAnswer(call -> {
            BlockPos pos = call.getArgument(0);
            return pos.getY() == 64 && pos.getX() >= 96 && pos.getX() < 108 && pos.getZ() >= 0 && pos.getZ() < 12
                    ? Blocks.HAY_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
        });

        Detonation det = mock(Detonation.class);
        when(det.burst()).thenReturn(burst);
        when(det.seed()).thenReturn(hayRoofSeed());
        when(det.ruinRadius()).thenReturn(1000.0);
        when(det.yieldKt()).thenReturn(15.0);
        when(det.fluence(any())).thenReturn(100.0);
        when(det.arrivalTicks(anyDouble())).thenAnswer(call -> arrival.applyAsDouble(call.getArgument(0)));

        try (var minecraft = mockStatic(Minecraft.class); var shadow = mockStatic(ThermalShadow.class);
             var air = mockStatic(FarBlasts.class); var effects = mockStatic(Effects.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            shadow.when(() -> ThermalShadow.aim(det)).thenReturn(burst.add(0, 250, 0));
            shadow.when(() -> ThermalShadow.share(eq(level), eq(det), any())).thenReturn(1.0);
            air.when(() -> FarBlasts.air(level, eye)).thenReturn(new CloudModel.Air(0.01, 0, 0, 0, 3, 0.5));
            FlashAftermath.detonation(det, clock::at, 0);
            var effect = ArgumentCaptor.forClass(Effects.Effect.class);
            effects.verify(() -> Effects.add(effect.capture()));
            effect.getValue().tick(level, effectTick);
        }
        List<SmokeGrid.Source> sources = new ArrayList<>();
        SmokeGrid.quads(sources::add);
        assertEquals(1, sources.size());
        return (Puffs) sources.getFirst();
    }

    /** Pick a seed with an upward face in hay cell (8, 0), which is in the first probe batch. */
    private static long hayRoofSeed() {
        long cell = 8L << 32;
        for (long seed = 0; ; seed++) {
            RandomSource random = RandomSource.create(seed ^ 0x5307E ^ cell * 0x9E3779B97F4A7C15L);
            random.nextDouble();
            random.nextDouble();
            if (!random.nextBoolean()) return seed;
        }
    }
}
