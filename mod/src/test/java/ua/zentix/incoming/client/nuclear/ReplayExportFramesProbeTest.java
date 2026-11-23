package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.replay.Replay;
import ua.zentix.incoming.compat.FlashbackReplay;
import ua.zentix.incoming.nuclear.Detonation;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** ExportJob renders directly after runAllTasks. No NeoForge frame event runs between these reads. */
public class ReplayExportFramesProbeTest {
    public record Settings(int startTick) {}
    public static final class Job {
        double tick;
        public double getCurrentTickDouble() { return tick; }
        public Settings getSettings() { return new Settings(1000); }
    }
    public interface Editor { double getPartialReplayTick(); }
    public static Job job;

    // Supply the optional mod's public accessors while exercising the production reflective reader.
    private static void bind(Map<Field, Object> saved, String name, Object value) throws ReflectiveOperationException {
        Field field = FlashbackReplay.class.getDeclaredField(name);
        field.setAccessible(true);
        saved.put(field, field.get(null));
        field.set(null, value);
    }

    @Test
    void exportFramesAdvanceTheReplayAge() throws ReflectiveOperationException {
        WaveEventsTest.bootstrap();
        var client = mock(Minecraft.class);
        var server = mock(IntegratedServer.class, withSettings().extraInterfaces(Editor.class));
        when(client.getSingleplayerServer()).thenReturn(server);
        double[] editorTick = {1000};
        when(((Editor) server).getPartialReplayTick()).thenAnswer(call -> editorTick[0]);
        var saved = new HashMap<Field, Object>();
        try (var mc = mockStatic(Minecraft.class); var compat = mockStatic(FlashbackReplay.class, CALLS_REAL_METHODS)) {
            mc.when(Minecraft::getInstance).thenReturn(client);
            compat.when(() -> FlashbackReplay.isReplay(server)).thenReturn(true);
            compat.when(() -> FlashbackReplay.recording(server)).thenReturn(null);
            bind(saved, "exportJob", getClass().getField("job"));
            bind(saved, "settings", Job.class.getMethod("getSettings"));
            bind(saved, "startTick", Settings.class.getMethod("startTick"));
            bind(saved, "exportPosition", Job.class.getMethod("getCurrentTickDouble"));
            bind(saved, "partialPosition", Editor.class.getMethod("getPartialReplayTick"));
            Replay.reset();
            job = new Job();
            int start = 1000, birth = 1010;
            var clock = new ClientNuclear.AgeClock(0, true, Replay::position, () -> birth);
            var wall = new BlastWall(new Detonation(7, Vec3.ZERO, 0, 15, true, 1000, 0, 0, 20000, 0, 1, false, 1));
            int marker = start;
            Replay.marker(marker, false, () -> {}, () -> {});
            double[] sigma = new double[180];
            double previous = 0;
            for (int f = 0; f < sigma.length; f++) {
                job.tick = f / 3.0;
                while (marker < start + (int) job.tick) Replay.marker(++marker, false, () -> {}, () -> {});
                double age = clock.at(job.tick % 1);
                assertEquals(Math.max(0, start + job.tick - birth), age, "export frame " + f);
                if (f > 30) assertTrue(age > previous, "held export frame " + f);
                sigma[f] = wall.sigmas(age / 20, new double[3]);
                previous = age;
            }
            assertTrue(java.util.Arrays.stream(sigma).max().orElseThrow() > 0);
            job = null;
            // Slow, ordinary and accelerated playback, pause and a backward seek revisit the exported frames.
            for (int stride : new int[]{1, 4, 16}) {
                for (int twelfths = 0; twelfths < 4 * sigma.length; twelfths += stride) {
                    editorTick[0] = start + twelfths / 12.0;
                    Replay.marker((int) editorTick[0] + 1, twelfths == 0, () -> {}, () -> {});
                    assertEquals(Math.max(0, editorTick[0] - birth), clock.at(0), "tick reader at next marker");
                    if (twelfths % 4 == 0)
                        assertEquals(sigma[twelfths / 4], wall.sigmas(clock.at(0.9) / 20, new double[3]));
                }
            }
            editorTick[0] = start + 31.25;
            assertEquals(21.25, clock.at(0));
            clock.tick();
            assertEquals(21.25, clock.at(0.9));
        } finally {
            job = null;
            Replay.reset();
            for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        }
    }
}
