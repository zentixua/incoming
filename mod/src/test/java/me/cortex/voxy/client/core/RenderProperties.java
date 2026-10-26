package me.cortex.voxy.client.core;

/** Counterfeit class Voxy 0.2.16 for {@code VoxyDepthTest}. */
public record RenderProperties(boolean isZero2One, boolean isReverseZ, boolean useBlockAtlasUVs) {
    public float clearDepth() {
        return isReverseZ ? 0 : 1;
    }

    public float inverseClearDepth() {
        return isReverseZ ? 1 : 0;
    }
}
