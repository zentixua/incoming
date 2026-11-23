package ua.zentix.incoming.warhead;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.AreaLoader;
import ua.zentix.incoming.strike.ImpactWork;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;

/** A blast area whose setup fails leaves no round counted with nothing to release it (A2 review). */
class ImpactRegistrationTest {
    @Test
    void failedBlastAreaSetupDoesNotLeaveAnUnfinishableRound() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        ServerLevel level = mock(ServerLevel.class);
        StrikeWorld world = spy(new StrikeWorld());
        doReturn(mock(AreaLoader.class)).when(world).areas();
        Vec3 point = new Vec3(0.5, 64.5, 0.5);
        var impact = new ImpactWork.Impact(RoundRef.of(UUID.randomUUID(), 0, "failedImpact"), null, WeaponType.DRONE);

        try (var worlds = mockStatic(StrikeWorld.class); var falls = mockStatic(CraterFalls.class)) {
            CraterFalls zones = mock(CraterFalls.class);
            worlds.when(() -> StrikeWorld.get(level)).thenReturn(world);
            falls.when(() -> CraterFalls.get(level)).thenReturn(zones);
            doThrow(new IllegalStateException("impact setup failed")).when(zones).open(eq(level), any(UUID.class), eq(point), eq(16.0));
            assertThrows(IllegalStateException.class, () -> BlastArea.hold(level, point, 16, impact));
            assertTrue(world.idle(), "the setup failure occurred before the timeline could be queued");
            assertEquals(0, world.impactWork().running().size(), "no work can release the registered impact");
        }
    }
}
