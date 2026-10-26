package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.Locale;

/**
 * Water beneath the detonation ({@link NukeWater#resolve}): whether ground zero is over water and how much of a "water burst" it is
 * ({@link WaterModel#wetBurst}); while unresolved, check each tick, then keep it fixed: the water column does not appear or
 * disappear midway through the detonation. The crowned column is in the mushroom volume ({@link MushroomField}), the base surge in the pall
 * ({@link PallField#water}).
 */
final class BurstWater {
    /** Water beneath ground zero 0..1. */
    private double under;
    /** How much of a "water burst" it is, 0..1 ({@link WaterModel#wetBurst} × water beneath ground zero). */
    private double wet;
    /** Source of knowledge about water beneath ground zero; null — still resolving. */
    private NukeWater.Source source;

    void set(double under, double wet) {
        this.under = under;
        this.wet = wet;
    }

    double under() {
        return under;
    }

    double wet() {
        return wet;
    }

    /** Water beneath ground zero is resolved: it stays fixed from now on. */
    void settle(NukeWater.Source source) {
        this.source = source;
    }

    boolean settled() {
        return source != null;
    }

    /** Source of knowledge about water beneath ground zero (for the scenario line); "?" — still resolving. */
    String source() {
        return source == null ? "?" : source.name().toLowerCase(Locale.ROOT);
    }
}
