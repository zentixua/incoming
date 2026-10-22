package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.rendering.Viewport;

/** Подделка класса Voxy 0.2.16 для {@code VoxyDepthTest}. */
public class VoxyRenderSystem {
    public static VoxyRenderSystem current;
    private final AbstractRenderPipeline pipeline = new AbstractRenderPipeline();
    private final Viewport viewport = new Viewport();

    public Viewport getViewport() {
        return viewport;
    }
}
