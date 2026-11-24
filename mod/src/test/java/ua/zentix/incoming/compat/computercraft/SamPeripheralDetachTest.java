package ua.zentix.incoming.compat.computercraft;

import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.NotAttachedException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.defense.SamBlockEntity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Review round 1 (S2), kept as the regression test. CC:Tweaked detaches a peripheral from the computer thread when that computer
 * shuts down or reboots (PeripheralAPI.shutdown → wrapper.detach: peripheral.detach(this), then attached = false), and
 * every IComputerAccess call after that throws NotAttachedException (documented on getAttachmentName/queueEvent).
 * SamPeripheral.tracking runs on the server thread inside SamBlockEntity's sweep, reads the computer set without the
 * attach/detach lock and calls getAttachmentName/queueEvent unguarded. A detach landing between the set read and the
 * call throws out of the SAM's block-entity tick: vanilla turns that into a "Ticking block entity" crash report and
 * stops the server.
 * <p>
 * The stand-in computer models that interleaving deterministically: its first getAttachmentName runs the detach (as the
 * computer thread would) and then answers as a detached wrapper does. Failed before the fix (50b78910a).
 */
class SamPeripheralDetachTest {
    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        if (net.neoforged.fml.loading.LoadingModList.get() == null) {
            net.neoforged.fml.loading.LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        }
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void sweepSurvivesAComputerDetachingMidAlert() {
        SamPeripheral p = new SamPeripheral(mock(SamBlockEntity.class));
        IComputerAccess rebooting = mock(IComputerAccess.class);
        when(rebooting.getAttachmentName()).thenAnswer(inv -> {
            p.detach(rebooting); // the computer thread's detach, between the sweep's set read and this call
            throw new NotAttachedException();
        });
        p.attach(rebooting);
        assertDoesNotThrow(() -> p.tracking(1, 120), "a detach during the sweep's alert escapes into the SAM's tick");
    }
}
