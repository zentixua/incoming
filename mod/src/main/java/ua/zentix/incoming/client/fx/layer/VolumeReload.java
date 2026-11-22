package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ua.zentix.incoming.client.nuclear.NukePall;

import java.util.concurrent.CompletableFuture;

/** Run first-use draws on the render thread while the resource loading screen is up. */
public final class VolumeReload {
    private static CompletableFuture<Void> loading;
    private static final Matrix4f VIEW = new Matrix4f(), PROJECTION = new Matrix4f();
    private static final Vector4f HAZE = new Vector4f();

    private VolumeReload() {}

    public static final PreparableReloadListener LISTENER = (barrier, resources, prepareProfile, applyProfile, background, render) ->
            barrier.wait(null).thenComposeAsync(unused -> start(), render);

    static CompletableFuture<Void> start() {
        if (loading != null) return loading;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() == null || !PallPass.cold()) return CompletableFuture.completedFuture(null);
        PallPass.prepare();
        loading = new CompletableFuture<>();
        nextFrame(mc);
        return loading;
    }

    static boolean active() {
        return loading != null;
    }

    private static void nextFrame(Minecraft mc) {
        // flipFrame drains the render-call queue even on the initial loading screen, before NeoForge starts its
        // game event bus. tell queues the work for the next client loop instead of executing it inside that drain.
        RenderSystem.recordRenderCall(() -> mc.tell(() -> stage(mc)));
    }

    private static void stage(Minecraft mc) {
        CompletableFuture<Void> current = loading;
        try {
            if (mc.getOverlay() != null && PallPass.cold()) {
                if (!PallPass.noiseReady() || draw(mc) && PallPass.cold()) {
                    nextFrame(mc);
                    return;
                }
            }
            loading = null;
            current.complete(null);
        } catch (RuntimeException ex) {
            loading = null;
            current.completeExceptionally(ex);
        }
    }

    private static boolean draw(Minecraft mc) {
        RenderTarget main = mc.getMainRenderTarget();
        main.bindWrite(true);
        try {
            PallPass.beginFrame(NukePall.quality());
            int scene = SceneDepth.loadingScene(main.width, main.height);
            if (scene < 0) return false;
            PROJECTION.setPerspective((float) Math.toRadians(70), (float) main.width / main.height, 0.05f, 1024f);
            return PallPass.draw(PallPass.warmupFrame(), scene, VIEW, PROJECTION, mc.gameRenderer.getMainCamera().getPosition(), HAZE);
        } finally {
            main.unbindWrite();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
        }
    }
}
