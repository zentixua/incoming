package me.cortex.voxy.client.core;

/** Подделка класса Voxy 0.2.16 для {@code VoxyDepthTest}: только то, что читает {@code VoxyDepth}. */
public interface IGetVoxyRenderSystem {
    static VoxyRenderSystem getNullable() {
        return VoxyRenderSystem.current;
    }
}
