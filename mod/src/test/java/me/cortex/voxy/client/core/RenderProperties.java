package me.cortex.voxy.client.core;

/** Подделка класса Voxy 0.2.16 для {@code VoxyDepthTest}. */
public record RenderProperties(boolean isZero2One, boolean isReverseZ, boolean useBlockAtlasUVs) {
    public float clearDepth() {
        return isReverseZ ? 0 : 1;
    }
}
