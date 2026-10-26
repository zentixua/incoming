package ua.zentix.replayscan.film;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterColorDepthTextureCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiTextureCreatedParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * With what depth does DH draw the opaque LOD in the frame (the class is loaded only if DH is set to: {@link FrameLog#DH}): before the
 *  pass - the depth texture of the frame into which DH draws ({@code GL_DRAW_FRAMEBUFFER}). Own
 * ({@code getDhDepthTextureGlId}) - correct; depth of the world (main frame Minecraft) - LOD writes the depth into the
 *  world buffer with its projection and lies on top of the near world in stripes. This happens under Iris, when the output of Flashback recreates the game frame, and the
 *  textures of DH are already in its size: Iris attaches a new depth of the world to all its frames, and to the LOD frame too, and the depth DH
 *  hooks back only when DH has recreated it, - DH recreates it in the LOD pass if the game frame size is not the same as
 * . Therefore, the output starts only when the textures DH are not in the size of the plan frame ({@link #textureIs}): the window
 *  is of a different size, and after the last output DH was already drawing in the window (after rewinding the replay enters the world again, and LOD
 *  is not drawn for a while). The passage of shadows draws into its frame - neither one nor the other.
 */
final class DhDepth {
    private static boolean own, world;
    /** Size of color and depth textures DH: 0 - not yet created. */
    private static int width, height;

    private DhDepth() {}

    static void subscribe() {
        DhApiEventRegister.on(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
            @Override
            public void beforeRender(DhApiEventParam<DhApiRenderParam> event) {
                EDhApiRenderPass pass = event.value.renderPass;
                if (pass != EDhApiRenderPass.OPAQUE && pass != EDhApiRenderPass.OPAQUE_AND_TRANSPARENT) return;
                if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == 0) return;
                int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                if (type != GL11.GL_TEXTURE) return;
                int texture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                DhApiResult<Integer> dh = DhApi.Delayed.renderProxy.getDhDepthTextureGlId();
                if (dh.success && dh.payload != null && texture == dh.payload) own = true;
                else if (texture == Minecraft.getInstance().getMainRenderTarget().getDepthTextureId()) world = true;
            }
        });
        DhApiEventRegister.on(DhApiAfterColorDepthTextureCreatedEvent.class, new DhApiAfterColorDepthTextureCreatedEvent() {
            @Override
            public void onResize(DhApiEventParam<DhApiTextureCreatedParam> event) {
                width = event.value.newWidth;
                height = event.value.newHeight;
                Film.LOG.info("Film: Distant Horizons textures {}x{} -> {}x{}", event.value.previousWidth, event.value.previousHeight, width, height);
            }
        });
    }

    /** The DH textures are now this size. */
    static boolean textureIs(int w, int h) {
        return width == w && height == h;
    }

    /**
     * Result passes LOD from the last call and reset: 1 - with its own depth, −1 - with the depth of the world, 0 - LOD was not drawn or
     *  was drawn with the depth of neither one nor the other.
     */
    static int take() {
        int r = world ? -1 : own ? 1 : 0;
        own = world = false;
        return r;
    }
}
