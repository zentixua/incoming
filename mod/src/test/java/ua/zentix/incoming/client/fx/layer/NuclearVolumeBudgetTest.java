package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.pipeline.RenderCall;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.GlUtil;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.mockito.MockedStatic;
import ua.zentix.incoming.client.nuclear.NukeBallSurface;
import ua.zentix.incoming.client.nuclear.NukePall;
import ua.zentix.incoming.client.nuclear.MushroomField;
import ua.zentix.incoming.compat.FlashbackReplay;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Run the production submission path with GL calls recorded on a synthetic GPU timeline. */
class NuclearVolumeBudgetTest {
    private final List<String> draws = new ArrayList<>();
    private final Map<Integer, Long> timestamps = new HashMap<>();
    private final List<Runnable> restore = new ArrayList<>();
    private String bound;
    private long gpuNs, wallDrawNs = 100_000_000L;

    private void replace(Class<?> type, Object owner, String name, Object value) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        Object old = field.get(owner);
        field.set(owner, value);
        restore.addFirst(() -> {
            try {
                field.set(owner, old);
            } catch (IllegalAccessException ex) {
                throw new AssertionError(ex);
            }
        });
    }

    private void saveWallResources() throws ReflectiveOperationException {
        for (Field field : DustWallPass.class.getDeclaredFields()) {
            field.setAccessible(true);
            if (!Modifier.isFinal(field.getModifiers())) {
                replace(DustWallPass.class, null, field.getName(), field.get(null));
            } else if (field.get(null) instanceof int[] array) {
                int[] saved = array.clone();
                restore.add(() -> System.arraycopy(saved, 0, array, 0, array.length));
            }
        }
    }

    private ShaderInstance shader(Class<?> owner, String field, String name) throws ReflectiveOperationException {
        ShaderInstance s = mock(ShaderInstance.class);
        when(s.safeGetUniform(anyString())).thenReturn(mock(Uniform.class));
        doAnswer(call -> { bound = name; return null; }).when(s).apply();
        replace(owner, null, field, s);
        return s;
    }

    private void client(MockedStatic<Minecraft> minecraft) throws ReflectiveOperationException {
        Minecraft mc = mock(Minecraft.class);
        minecraft.when(Minecraft::getInstance).thenReturn(mc);
        RenderTarget main = mock(RenderTarget.class);
        main.width = main.height = 12;
        when(mc.getMainRenderTarget()).thenReturn(main);
        GameRenderer renderer = mock(GameRenderer.class);
        replace(Minecraft.class, mc, "gameRenderer", renderer);
        var camera = mock(net.minecraft.client.Camera.class);
        when(renderer.getMainCamera()).thenReturn(camera);
        when(camera.getPosition()).thenReturn(Vec3.ZERO);
        when(mc.getOverlay()).thenReturn(mock(net.minecraft.client.gui.screens.Overlay.class));
        LightTexture light = mock(LightTexture.class);
        when(renderer.lightTexture()).thenReturn(light);
        replace(LightTexture.class, light, "lightTexture", mock(DynamicTexture.class));
        replace(Minecraft.class, mc, "gui", mock(Gui.class, RETURNS_DEEP_STUBS));
    }

    private static void draw(PallPass.Frame frame) {
        assertTrue(PallPass.draw(frame, 8, new Matrix4f(), new Matrix4f(), Vec3.ZERO, new Vector4f()));
    }

    private void warm(PallPass.Quality setting, List<String> programs) {
        for (int i = 0; i < programs.size(); i++) {
            PallPass.beginFrame(setting);
            assertFalse(PallPass.available());
            draw(PallPass.warmupFrame());
            assertEquals(programs.subList(0, i + 1), draws, "one first use per loading frame");
        }
    }

    private void loadOnSeparateFrames(MockedStatic<RenderSystem> render, List<String> programs) {
        var flips = new ArrayDeque<RenderCall>();
        var clientTasks = new ArrayDeque<Runnable>();
        render.when(() -> RenderSystem.recordRenderCall(any())).thenAnswer(call -> flips.add(call.getArgument(0)));
        Minecraft mc = Minecraft.getInstance();
        doAnswer(call -> clientTasks.add(call.getArgument(0))).when(mc).tell(any());
        var loaded = VolumeReload.start();
        for (int i = 0; i < programs.size(); i++) {
            assertFalse(loaded.isDone());
            assertEquals(1, flips.size());
            flips.remove().execute();
            assertTrue(flips.isEmpty(), "the display flip must return before another program can start");
            assertEquals(1, clientTasks.size());
            clientTasks.remove().run();
            assertEquals(programs.subList(0, i + 1), draws);
        }
        assertTrue(loaded.isDone());
        loaded.join();
        assertTrue(flips.isEmpty());
        assertFalse(VolumeReload.active());
    }

    @Test
    @SuppressWarnings("try") // The scoped static mocks isolate GL and Minecraft even without stubbing individual calls.
    void preparationAndTheFirstBurstUseTheSamePassesAndBudget() throws ReflectiveOperationException {
        PallPass.ViewState storage = new PallPass.ViewState();
        try (var minecraft = mockStatic(Minecraft.class); var state = mockStatic(GlStateManager.class);
             var gl11 = mockStatic(GL11.class); var gl12 = mockStatic(GL12.class);
             var gl = mockStatic(GL.class); var gl15 = mockStatic(GL15.class); var gl20 = mockStatic(GL20.class); var gl30 = mockStatic(GL30.class); var gl33 = mockStatic(GL33.class);
             var glUtil = mockStatic(GlUtil.class); var render = mockStatic(RenderSystem.class);
             var uniforms = mockStatic(Uniform.class); var tess = mockStatic(Tesselator.class);
             var uploader = mockStatic(BufferUploader.class); var air = mockStatic(OpenAir.class);
             var smoke = mockStatic(SmokePass.class); var ball = mockStatic(NukeBallSurface.class);
             var depth = mockStatic(SceneDepth.class); var nuke = mockStatic(NukePall.class); var replay = mockStatic(FlashbackReplay.class)) {
            storage.swap();
            // the dust march's three outputs at their declared order (light 0, depth span 1, interval split 2)
            gl30.when(() -> GL30.glGetFragDataLocation(anyInt(), eq("wallDepth"))).thenReturn(1);
            gl30.when(() -> GL30.glGetFragDataLocation(anyInt(), eq("wallSplit"))).thenReturn(2);
            try {
                saveWallResources();
                client(minecraft);
                depth.when(() -> SceneDepth.loadingScene(12, 12)).thenReturn(8);
                nuke.when(NukePall::quality).thenReturn(PallPass.Quality.HIGH);
                replace(PallPass.class, null, "watch", null);
                CompletableFuture<byte[][]> noise = new CompletableFuture<>();
                replace(PallPass.class, null, "textures", noise);
                glUtil.when(GlUtil::getRenderer).thenReturn("Test hardware");
                AtomicInteger ids = new AtomicInteger(100);
                state.when(GlStateManager::_genTexture).thenAnswer(call -> ids.incrementAndGet());
                state.when(GlStateManager::glGenFramebuffers).thenAnswer(call -> ids.incrementAndGet());
                state.when(() -> GlStateManager.glCheckFramebufferStatus(anyInt())).thenReturn(GlConst.GL_FRAMEBUFFER_COMPLETE);
                gl30.when(() -> GL30.glCheckFramebufferStatus(anyInt())).thenReturn(GlConst.GL_FRAMEBUFFER_COMPLETE);
                // Unique query ids, all results ready. Poll reads the timestamps recorded between actual draws.
                GLCapabilities caps = mock(GLCapabilities.class);
                replace(GLCapabilities.class, caps, "OpenGL33", true);
                gl.when(GL::getCapabilities).thenReturn(caps);
                gl15.when(() -> GL15.glGetQueryi(anyInt(), anyInt())).thenReturn(64);
                gl15.when(GL15::glGenQueries).thenAnswer(call -> ids.incrementAndGet());
                PallPass.Detail detail = spy(new PallPass.Detail());
                replace(PallPass.class, null, "DETAIL", detail);
                gl33.when(() -> GL33.glQueryCounter(anyInt(), anyInt())).thenAnswer(call -> {
                    timestamps.put(call.getArgument(0), gpuNs);
                    return null;
                });
                gl15.when(() -> GL15.glGetQueryObjecti(anyInt(), anyInt())).thenReturn(1);
                gl33.when(() -> GL33.glGetQueryObjecti64(anyInt(), anyInt())).thenAnswer(call -> timestamps.get(call.getArgument(0)));
                Tesselator t = mock(Tesselator.class);
                tess.when(Tesselator::getInstance).thenReturn(t);
                BufferBuilder builder = mock(BufferBuilder.class);
                when(t.begin(any(), any())).thenReturn(builder);
                when(builder.build()).thenReturn(mock(MeshData.class));
                int[] viewport = new int[2];
                state.when(() -> GlStateManager._viewport(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
                    viewport[0] = call.getArgument(2);
                    viewport[1] = call.getArgument(3);
                    return null;
                });
                uploader.when(() -> BufferUploader.draw(any())).thenAnswer(call -> {
                    draws.add(bound);
                    // The rest of the pall costs 5 ms; the wall time is split across all three draws.
                    if (bound.equals("composite")) gpuNs += 5_000_000L;
                    if (bound.equals("wall.limit")) {
                        // the rays' end at the dust: the whole frame
                        assertArrayEquals(new int[] {12, 12}, viewport);
                    } else if (bound.startsWith("wall.")) {
                        int side = (12 + DustWallPass.divisor() - 1) / DustWallPass.divisor(), half = (side + 1) / 2;
                        // interleaved rays: the march traces half the lattice (packed), the pack lays its three outputs side by side,
                        // the resolve fills the whole lattice's three thirds
                        int wide = bound.equals("wall.march") ? half : bound.equals("wall.pack") ? 3 * half : 3 * side;
                        assertArrayEquals(new int[] {wide, side}, viewport);
                        gpuNs += wallDrawNs;
                    }
                    return null;
                });
                for (String name : List.of("march", "singleMarch", "froxel", "froxelSum", "footLighting", "mushTaus", "shadow", "resolve", "mix", "composite")) {
                    shader(PallPass.class, name, name);
                }
                for (String name : List.of("march", "pack", "resolve", "limit")) shader(DustWallPass.class, name, "wall." + name);
                Uniform layer = mock(Uniform.class);
                when(PallPass.composite.safeGetUniform("PallLayerFrame")).thenReturn(layer);
                AtomicInteger layerMode = new AtomicInteger();
                doAnswer(call -> { layerMode.set(call.getArgument(0)); return null; }).when(layer).set(anyInt());
                doAnswer(call -> { bound = layerMode.get() == 1 ? "layer" : "composite"; return null; })
                        .when(PallPass.composite).apply();
                Uniform divisor = mock(Uniform.class), frame = mock(Uniform.class), previousDiv = mock(Uniform.class);
                Uniform reproject = mock(Uniform.class);
                when(DustWallPass.resolve.safeGetUniform("DwPrevDiv")).thenReturn(previousDiv);
                when(DustWallPass.resolve.safeGetUniform("DwReproject")).thenReturn(reproject);
                when(DustWallPass.march.safeGetUniform("DwDiv")).thenReturn(divisor);
                when(DustWallPass.march.safeGetUniform("DwFrame")).thenReturn(frame);
                List<String> all = List.of("shadow", "footLighting", "mushTaus", "froxel", "froxelSum", "march",
                        "resolve", "mix", "composite", "singleMarch", "wall.march", "wall.pack", "wall.resolve", "wall.limit", "layer", "composite");
                PallPass.beginFrame(PallPass.Quality.HIGH);
                assertFalse(PallPass.available());
                assertFalse(PallPass.draw(PallPass.warmupFrame(), 8, new Matrix4f(), new Matrix4f(), Vec3.ZERO, new Vector4f()));
                assertTrue(draws.isEmpty());
                noise.complete(new byte[][] {{0}, {0}});
                warm(PallPass.Quality.OFF, all.subList(0, 9));
                gl12.verify(() -> GL12.glTexImage3D(eq(GL12.GL_TEXTURE_3D), eq(0), eq(GL30.GL_R8), eq(128), eq(128), eq(128),
                        eq(0), eq(GL11.GL_RED), eq(GL11.GL_UNSIGNED_BYTE), any(ByteBuffer.class)), never());
                draws.clear();
                state.clearInvocations(); // LOW also has a 3×3 interleaved pall ray target.
                warm(PallPass.Quality.HIGH, all);
                assertTrue(DustWallPass.ready());
                gl12.verify(() -> GL12.glTexImage3D(eq(GL12.GL_TEXTURE_3D), eq(0), eq(GL30.GL_R8), eq(128), eq(128), eq(128),
                        eq(0), eq(GL11.GL_RED), eq(GL11.GL_UNSIGNED_BYTE), any(ByteBuffer.class)));
                // interleaved rays: the march's three outputs hold half the 3×3 lattice (2×3), the pack three times that,
                // the two histories the whole lattice's three thirds (9×3)
                state.verify(() -> GlStateManager._texImage2D(eq(GL11.GL_TEXTURE_2D), eq(0), eq(GL30.GL_RGBA16F),
                        eq(2), eq(3), eq(0), eq(GL11.GL_RGBA), eq(GL11.GL_FLOAT), isNull()), times(3));
                state.verify(() -> GlStateManager._texImage2D(eq(GL11.GL_TEXTURE_2D), eq(0), eq(GL30.GL_RGBA16F),
                        eq(6), eq(3), eq(0), eq(GL11.GL_RGBA), eq(GL11.GL_FLOAT), isNull()), times(1));
                state.verify(() -> GlStateManager._texImage2D(eq(GL11.GL_TEXTURE_2D), eq(0), eq(GL30.GL_RGBA16F),
                        eq(9), eq(3), eq(0), eq(GL11.GL_RGBA), eq(GL11.GL_FLOAT), isNull()), times(2));
                gl20.verify(() -> GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1, GL30.GL_COLOR_ATTACHMENT2}));
                gl11.verify(GL11::glFinish, times(2));

                // A changed pall program also invalidates the shared preparation.
                when(PallPass.footLighting.getId()).thenReturn(8);
                assertFalse(PallPass.available());
                when(PallPass.footLighting.getId()).thenReturn(0);
                assertTrue(PallPass.available());

                // An in-place relink can reorder the GLSL 150 output locations.
                when(DustWallPass.march.getId()).thenReturn(9);
                gl30.when(() -> GL30.glGetFragDataLocation(9, "fragColor")).thenReturn(1);
                gl30.when(() -> GL30.glGetFragDataLocation(9, "wallDepth")).thenReturn(0);
                assertFalse(PallPass.available());
                assertFalse(DustWallPass.ready());
                draws.clear();
                PallPass.beginFrame(PallPass.Quality.HIGH);
                loadOnSeparateFrames(render, all);
                gl20.verify(() -> GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT1, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT2}));

                gl33.verify(() -> GL33.glQueryCounter(anyInt(), anyInt()), never());
                assertTrue(timestamps.isEmpty(), "loading frames must never issue a GPU time sample");
                // A new camera feed owns fresh targets, but uses the prepared programs on its very first frame.
                PallPass.ViewState feed = new PallPass.ViewState();
                feed.swap();
                try (var views = mockStatic(FxViews.class, CALLS_REAL_METHODS)) {
                    views.when(FxViews::secondary).thenReturn(true);
                    assertTrue(PallPass.available());
                    assertFalse(PallPass.cold());
                    draws.clear();
                    PallPass.beginFrame(PallPass.Quality.LOW);
                    draw(new PallPass.Frame());
                    // a drawn frame sums the near volume in two draws (its blocks, then the cells)
                    assertEquals(List.of("froxel", "froxelSum", "froxelSum", "march", "resolve", "mix", "composite"), draws);
                    gl11.verify(GL11::glFinish, times(3));
                } finally {
                    feed.swap();
                    feed.close();
                }

                draws.clear();
                state.clearInvocations();
                gl12.clearInvocations();
                PallPass.Frame burst = new PallPass.Frame();
                burst.quality = PallPass.Quality.HIGH;
                burst.dustWall.active = true;
                burst.slot0.m13(1);
                burst.mushroomCount = 1;
                burst.mushrooms[3] = 1;
                burst.mushrooms[4] = 24;
                int bodyLocation = 47;
                uniforms.when(() -> Uniform.glGetUniformLocation(anyInt(), eq("PallMushBody[0]"))).thenReturn(bodyLocation);
                gl20.when(() -> GL20.glUniform4fv(eq(bodyLocation), any(FloatBuffer.class))).thenAnswer(call -> {
                    FloatBuffer body = call.getArgument(1);
                    assertEquals(4 * MushroomField.FOOT_COLUMNS, body.remaining());
                    for (int i = 0; i < body.remaining(); i++) assertEquals(burst.mushrooms[i], body.get(body.position() + i));
                    return null;
                });
                PallPass.beginFrame(burst.quality);
                assertEquals(GpuWatch.State.WATCHING, PallPass.watch().state());
                draw(burst);
                gl20.verify(() -> GL20.glUniform4fv(eq(bodyLocation), any(FloatBuffer.class)));
                assertEquals(List.of("wall.march", "wall.pack", "wall.resolve", "wall.limit", "mushTaus", "froxel", "froxelSum", "froxelSum", "singleMarch",
                        "resolve", "layer", "composite"), draws);
                state.verify(GlStateManager::_genTexture, never());
                state.verify(() -> GlStateManager._texImage2D(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(),
                        anyInt(), anyInt(), any()), never());
                gl12.verifyNoInteractions();
                // Negligible CPU submission in the audit's hardware timeline.
                replace(PallPass.class, null, "SUBMIT", new long[4]);
                PallPass.beginFrame(burst.quality);
                assertEquals(GpuWatch.State.FLOOR, PallPass.watch().state());
                assertEquals(305, PallPass.watch().cause(), 0);
                clearInvocations(previousDiv, reproject);
                wallDrawNs = 400_000_000L;
                burst.mushrooms[4] = 48;
                draw(burst);
                gl20.verify(() -> GL20.glUniform4fv(eq(bodyLocation), any(FloatBuffer.class)), times(2));
                verify(divisor).set(6f);
                verify(previousDiv).set(4f);
                verify(reproject).set(0f, 0f, 0f, 1f);
                verify(frame).set(anyFloat(), eq(80f));
                replace(PallPass.class, null, "SUBMIT", new long[4]);
                PallPass.beginFrame(burst.quality);
                verify(detail).sample(eq(1205d), eq(0d), eq(400d), eq(800d), anyDouble(), anyInt(), anyLong());
                assertEquals(1205, PallPass.watch().cause(), 0);
                assertEquals(GpuWatch.State.OFF, PallPass.watch().state());
            } finally {
                restore.forEach(Runnable::run);
                storage.swap();
                storage.close();
            }
        }
    }

    @Test
    void singleBurstRayRequiresExactlyTheMediaItDraws() {
        PallPass.Frame f = new PallPass.Frame();
        f.dustWall.active = true;
        f.slot0.m13(1);
        f.mushroomCount = 1;
        assertTrue(f.singleRay(false));
        assertFalse(f.singleRay(true), "conventional smoke in the same frame");
        f.whirlShape.m31(0.1f);
        assertFalse(f.singleRay(false), "whirl in the last column");
        f.whirlShape.zero();
        f.slot0.m33(1);
        assertFalse(f.singleRay(false), "water under the pall");
        f.slot0.m33(0);
        f.slot1.m13(1);
        assertFalse(f.singleRay(false), "second active slot");
        f.slot1.zero();
        f.mushroomCount = 2;
        assertFalse(f.singleRay(false), "second body outside the pall slots");
        f.mushroomCount = 0;
        assertFalse(f.singleRay(false), "a wall without a body");
        f.mushroomCount = 1;
        f.dustWall.active = false;
        assertFalse(f.singleRay(false), "no dust pass");
    }

    @Test
    void wallBudgetUsesEveryRungAndPreservesThePresetPictures() {
        PallPass.Detail detail = new PallPass.Detail();
        int preceding = Integer.MAX_VALUE;
        for (PallPass.Step step : PallPass.LADDER) {
            int work = 3600 * step.wallSteps() / (step.wallDiv() * step.wallDiv());
            assertTrue(work < preceding);
            preceding = work;
        }
        for (PallPass.Quality q : List.of(PallPass.Quality.LOW, PallPass.Quality.MEDIUM, PallPass.Quality.HIGH)) {
            detail.setting(q);
            PallPass.Step step = PallPass.LADDER[detail.level];
            assertEquals(switch (q) { case HIGH -> 4; case MEDIUM -> 5; default -> 6; }, step.wallDiv());
            assertEquals(switch (q) { case HIGH -> 128; case MEDIUM -> 104; default -> 96; }, step.wallSteps());
        }
        detail.floor();
        assertEquals(6, PallPass.LADDER[detail.level].wallDiv());
        assertEquals(80, PallPass.LADDER[detail.level].wallSteps());
    }
}
