package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlUtil;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLCapabilities;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Review of #666: the {@link GpuWatch} state belongs to the client session, not to a camera view. Secondary views (Vista
 * feeds, {@link FxViews}) swap their own {@link PallPass.ViewState} — timer queries, history and detail — in and out of
 * {@link PallPass}'s statics; the watchdog must survive every swap, and its latch must reach every view's detail.
 * GL is mocked: the timer initializer runs as on a GL 3.3 context.
 */
class PallWatchViewsTest {
    private static final int LAST = PallPass.LADDER.length - 1;
    private MockedStatic<GlUtil> glUtil;
    private MockedStatic<GL> gl;
    private MockedStatic<GL15> gl15;
    private GpuWatch savedWatch;
    private ShaderInstance savedMarch;
    private PallPass.Detail savedDetail;

    @BeforeEach
    void mockGl() throws ReflectiveOperationException {
        glUtil = mockStatic(GlUtil.class);
        glUtil.when(GlUtil::getRenderer).thenReturn("AMD Radeon RX 6600 XT");
        GLCapabilities caps = mock(GLCapabilities.class);
        Field gl33 = GLCapabilities.class.getField("OpenGL33");
        gl33.setAccessible(true);
        gl33.setBoolean(caps, true);
        gl = mockStatic(GL.class);
        gl.when(GL::getCapabilities).thenReturn(caps);
        gl15 = mockStatic(GL15.class);
        gl15.when(() -> GL15.glGetQueryi(anyInt(), anyInt())).thenReturn(64);
        gl15.when(GL15::glGenQueries).thenReturn(7);
        savedWatch = (GpuWatch) field("watch").get(null);
        savedMarch = PallPass.march;
        savedDetail = (PallPass.Detail) field("DETAIL").get(null);
        field("watch").set(null, null);
        ShaderInstance march = mock(ShaderInstance.class);
        when(march.getId()).thenReturn(5);
        PallPass.march = march;
        field("DETAIL").set(null, new PallPass.Detail());
    }

    @AfterEach
    void restore() throws ReflectiveOperationException {
        field("watch").set(null, savedWatch);
        PallPass.march = savedMarch;
        field("DETAIL").set(null, savedDetail);
        gl15.close();
        gl.close();
        glUtil.close();
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field f = PallPass.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static PallPass.Detail detail() throws ReflectiveOperationException {
        return (PallPass.Detail) field("DETAIL").get(null);
    }

    /**
     * Reviewer's scenario 1: the watchdog turns the volume off, then the player looks at an unused camera feed. Its fresh
     * view state has no prepared program (a cold warm-up frame would be drawn) and no timer; initializing that timer
     * replaced the shared watchdog with a new WATCHING one, and the next frame drew the whole nuclear workload again.
     */
    @Test
    void offSurvivesAFreshViewAndItsWarmUp() {
        assertEquals(GpuWatch.State.OFF, PallPass.watch().sample(1200));
        assertFalse(PallPass.available());
        assertFalse(PallPass.cold(), "the primary view warms up a volume that is off");
        PallPass.ViewState feed = new PallPass.ViewState();
        feed.swap();
        try {
            assertFalse(PallPass.cold(), "a new view warms up a volume that is off");
            assertTrue(PallPass.timer(), "the new view's own timer queries");
            assertEquals(GpuWatch.State.OFF, PallPass.watch().state(), "the view's timer reset the session watchdog");
            assertFalse(PallPass.draw(new PallPass.Frame(), 0, new Matrix4f(), new Matrix4f(), Vec3.ZERO, new Vector4f()));
        } finally {
            feed.swap();
        }
        assertEquals(GpuWatch.State.OFF, PallPass.watch().state());
        assertFalse(PallPass.cold());
        assertFalse(PallPass.available());
    }

    /**
     * Reviewer's scenario 2: the LOW secondary feed reports a 300 ms frame first and trips FLOOR; the primary HIGH view's own
     * 300 ms frame is no longer a transition. The primary must still go to the last rung and stay there through settings;
     * a view created later starts there too.
     */
    @Test
    void floorReachesEveryView() throws ReflectiveOperationException {
        PallPass.Detail primary = detail();
        primary.setting(PallPass.Quality.HIGH);
        assertEquals(0, primary.level);
        PallPass.ViewState feed = new PallPass.ViewState();
        feed.swap();
        PallPass.Detail secondary;
        try {
            secondary = detail();
            secondary.setting(PallPass.Quality.LOW);
            PallPass.measured(300);
            assertEquals(GpuWatch.State.FLOOR, PallPass.watch().state());
            assertTrue(secondary.floored);
        } finally {
            feed.swap();
        }
        assertEquals(primary, detail());
        PallPass.measured(300);
        assertTrue(primary.floored, "the primary view kept its rung after the feed tripped the floor");
        assertEquals(LAST, primary.level);
        primary.setting(PallPass.Quality.MEDIUM);
        primary.setting(PallPass.Quality.HIGH);
        assertEquals(LAST, primary.level, "a setting change lifted the session floor");

        PallPass.ViewState later = new PallPass.ViewState();
        later.swap();
        try {
            PallPass.Detail fresh = detail();
            fresh.setting(PallPass.Quality.HIGH);
            PallPass.latch();
            assertEquals(LAST, fresh.level, "a view created after the floor starts above it");
        } finally {
            later.swap();
        }
    }

    /** Without a measurement of its own, a view takes the latch at its next draw: a primary that never sampled a slow frame. */
    @Test
    void floorWithoutASampleOfThisView() throws ReflectiveOperationException {
        PallPass.Detail primary = detail();
        primary.setting(PallPass.Quality.HIGH);
        PallPass.ViewState feed = new PallPass.ViewState();
        feed.swap();
        try {
            PallPass.measured(300);
        } finally {
            feed.swap();
        }
        assertEquals(0, primary.level, "nothing touched the primary yet");
        PallPass.latch();
        assertEquals(LAST, primary.level);
    }
}
