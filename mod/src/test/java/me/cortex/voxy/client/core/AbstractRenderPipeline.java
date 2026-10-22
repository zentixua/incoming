package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;

/** Подделка класса Voxy 0.2.16 для {@code VoxyDepthTest}. */
public class AbstractRenderPipeline {
    public RenderProperties properties = new RenderProperties(false, false, false);
    public final DepthFramebuffer fb = new DepthFramebuffer();
}
