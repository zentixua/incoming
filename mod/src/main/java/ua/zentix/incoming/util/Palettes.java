package ua.zentix.incoming.util;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.function.Predicate;

/** Section block queries through its palette, avoiding all 4096 positions where the palette is exact. */
public final class Palettes {
    private Palettes() {}

    /**
     * Whether {@code states} contains a state matching {@code filter}. Palette answers first ({@code maybeHas}): small
     * palettes give an exact “no”. Global palette (over 256 states per section, common in detailed cities) always answers
     * “maybe” ({@code GlobalPalette.maybeHas}); small palettes remember removed states too — then count blocks exactly, as
     * vanilla does ({@code LevelChunkSection.recalcBlockCounts}): faster than testing every position (filter runs per
     * distinct state, not per position). Count may report a state with zero blocks (Lithium counts palette entries) — ignore it.
     */
    public static boolean contains(PalettedContainer<BlockState> states, Predicate<BlockState> filter) {
        if (!states.maybeHas(filter)) return false;
        boolean[] found = {false};
        states.count((state, n) -> found[0] |= n > 0 && filter.test(state));
        return found[0];
    }
}
