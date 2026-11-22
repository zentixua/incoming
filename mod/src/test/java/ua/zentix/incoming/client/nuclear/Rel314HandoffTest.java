package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.DustWallPass;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Rel314HandoffTest {
    /** Drive the real Java handoff and compare the support of its two shader masks on a tangent ray. */
    @Test
    void thePallKeepsItsHeadOutsideTheWallTable() throws Exception {
        var d = new Detonation(1, new Vec3(0, 64, 0), 64, 1, true, 0, 0, 0, 20_000, 7, 1, false, 0.35f);
        var pall = new PallField(d);
        var wall = new BlastWall(d, false);
        var a = mock(ClientNuclear.Active.class);
        setField(a, "d", d);
        setField(a, "pall", pall);
        setField(a, "wall", wall);
        Method handoff = NukePall.class.getDeclaredMethod("youngWall", ClientNuclear.Active.class, Vec3.class, float.class, PallPass.Frame.class);
        handoff.setAccessible(true);
        double reach = pall.reach();
        var frame = new PallPass.Frame();
        float[] head = new float[BlastWall.SLOT];
        double t = d.arrivalTicks(reach) / 20 + 1;
        double front = wall.shock(t);
        wall.slot(t, head);
        double x = Math.max(reach + 1, front - .75 * head[1]);
        when(a.seconds(0)).thenReturn(t);
        handoff.invoke(null, a, new Vec3(x, 65, 0), 0f, frame);
        assertTrue(frame.dustWall.active);
        assertEquals(reach, frame.young.w, 0.001, "last supported radius of the handoff");
        double original = 0;
        int samples = 0;
        for (int z = -300; z < 300; z++) {
            double r = Math.hypot(x, z + .5);
            assertTrue(r > reach);
            double sigma = BlastWall.sigma(head, x, z + .5, 1, 0);
            if (sigma > 0) samples++;
            original += sigma;
            assertTrue(r >= frame.young.w, "the pall owns this head sample");
        }
        String evidence = String.format(Locale.ROOT,
                "t %.3f, front %.2f, table %.2f, tangent x %.2f, %d head samples, original head T %.6f",
                t, front, reach, x, samples, Math.exp(-original));
        System.out.println(evidence);
        assertTrue(samples > 0, evidence);
        assertEquals(0.233159, Math.exp(-original), 1e-5, evidence);
    }

    @Test
    void worldExitReleasesSourcesWithoutWaitingForAnotherFrame() throws ReflectiveOperationException {
        var slotsField = NukePall.class.getDeclaredField("SLOTTED");
        var frameField = NukePall.class.getDeclaredField("FRAME");
        var historyField = DustWallPass.class.getDeclaredField("previousSource");
        slotsField.setAccessible(true);
        frameField.setAccessible(true);
        historyField.setAccessible(true);
        var slots = (ClientNuclear.Active[]) slotsField.get(null);
        var frame = (PallPass.Frame) frameField.get(null);
        var source = mock(ClientNuclear.Active.class);
        java.util.Arrays.fill(slots, source);
        frame.dustWall.source = source;
        historyField.set(null, source);
        NukePall.reset();
        assertNull(frame.dustWall.source);
        assertNull(historyField.get(null));
        for (var slot : slots) assertNull(slot);
    }

    private static void setField(ClientNuclear.Active active, String name, Object value) throws ReflectiveOperationException {
        var field = ClientNuclear.Active.class.getField(name);
        field.setAccessible(true);
        field.set(active, value);
    }
}
