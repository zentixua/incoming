package me.cortex.voxy.client.core;

/** Counterfeit class Voxy 0.2.16 for {@code VoxyDepthTest}: only what reads {@code VoxyDepth}. */
public interface IGetVoxyRenderSystem {
    static VoxyRenderSystem getNullable() {
        return VoxyRenderSystem.current;
    }
}
