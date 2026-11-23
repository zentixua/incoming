package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SingleRayLinkFailureTest {
    @Test
    @SuppressWarnings("try")
    void failedOptionalLinkLeavesTheHealthyGeneralRaySelected() throws Exception {
        var registrations = new ArrayList<Pair<ShaderInstance, Consumer<ShaderInstance>>>();
        ResourceProvider provider = mock(ResourceProvider.class);
        AtomicInteger failedId = new AtomicInteger(-1), nextId = new AtomicInteger(1);
        String vertex = "test:single_ray_vertex", fragment = "test:single_ray_fragment";
        // Cache a compiled vertex and fragment. The only simulated failure occurs when linking the optional program.
        var vertices = Program.Type.VERTEX.getPrograms();
        var fragments = Program.Type.FRAGMENT.getPrograms();
        Program oldVertex = vertices.put(vertex, mock(Program.class));
        Program oldFragment = fragments.put(fragment, mock(Program.class));
        ShaderInstance oldGeneral = PallPass.march, oldSingle = PallPass.singleMarch;
        when(provider.openAsReader(any())).thenAnswer(call -> {
            ResourceLocation file = call.getArgument(0);
            if (file.getPath().endsWith("/fx_pall_single.json")) failedId.set(nextId.get());
            return new BufferedReader(new StringReader("{\"vertex\":\"" + vertex + "\",\"fragment\":\"" + fragment + "\"}"));
        });
        try (var state = mockStatic(GlStateManager.class); var render = mockStatic(RenderSystem.class);
             var gl = mockStatic(GL20.class); var wall = mockStatic(DustWallPass.class); var views = mockStatic(FxViews.class)) {
            state.when(GlStateManager::glCreateProgram).thenAnswer(call -> nextId.getAndIncrement());
            state.when(() -> GlStateManager.glGetProgrami(anyInt(), eq(GL20.GL_LINK_STATUS)))
                    .thenAnswer(call -> (int) call.getArgument(0) == failedId.get() ? 0 : 1);
            gl.when(() -> GL20.glGetProgrami(anyInt(), eq(GL20.GL_LINK_STATUS)))
                    .thenAnswer(call -> (int) call.getArgument(0) == failedId.get() ? 0 : 1);
            PallPass.march = PallPass.singleMarch = null;
            FxLayer.registerShaders(new RegisterShadersEvent(provider, registrations));
            for (var registration : registrations) {
                ShaderInstance shader = registration.getFirst();
                if (shader.getName().equals("incoming:fx_pall") || shader.getName().equals("incoming:fx_pall_single"))
                    registration.getSecond().accept(shader);
            }
            assertTrue(failedId.get() > 0);
            state.verify(() -> GlStateManager.glGetProgrami(failedId.get(), GL20.GL_LINK_STATUS), atLeastOnce());
            assertNotNull(PallPass.march);
            assertNull(PallPass.singleMarch, "The optional ray remains selectable with failed link status for program " + failedId.get());
        } finally {
            PallPass.march = oldGeneral;
            PallPass.singleMarch = oldSingle;
            if (oldVertex == null) vertices.remove(vertex); else vertices.put(vertex, oldVertex);
            if (oldFragment == null) fragments.remove(fragment); else fragments.put(fragment, oldFragment);
        }
    }
}
