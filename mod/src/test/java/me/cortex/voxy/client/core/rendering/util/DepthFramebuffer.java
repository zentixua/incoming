package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlTexture;

/** Подделка класса Voxy 0.2.16 для {@code VoxyDepthTest}. */
public class DepthFramebuffer {
    private final GlTexture depthBuffer = new GlTexture(17);

    public GlTexture getDepthTex() {
        return depthBuffer;
    }
}
