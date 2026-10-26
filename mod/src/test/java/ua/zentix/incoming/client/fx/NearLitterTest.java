package ua.zentix.incoming.client.fx;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.LocalLitter;
import ua.zentix.incoming.nuclear.model.ShockAtBody;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dust near the camera ({@link NearLitter}): the surface of the place is the same as that of the wave dust ({@code FarBlasts.ground}). */
class NearLitterTest {
    private static BlockState AIR;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        AIR = Blocks.AIR.defaultBlockState();
    }

    /** Grass on turf - a patch of grass, holds up to 3 m/s (turf); bare earth, sand and gravel - crumbly, loose. */
    @Test
    void groundCoverPicksTheSurfaceOfThePlace() {
        NearLitter.Cover grass = NearLitter.cover(Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState());
        assertSame(DustModel.Surface.COVERED, grass.surface());
        assertEquals(Fragments.Kind.LEAF, grass.kind());
        assertTrue(grass.torn().is(Blocks.SHORT_GRASS), "a blade of grass, not a cube of earth");
        NearLitter.Cover turf = NearLitter.cover(Blocks.GRASS_BLOCK.defaultBlockState(), AIR);
        assertSame(DustModel.Surface.COVERED, turf.surface());
        assertTrue(turf.torn().is(Blocks.SHORT_GRASS), "the turf is torn by grass, not earth");
        // (sand and foliage are by tags, but tags are not loaded in the unit test: only blocks that are recognizable by themselves)
        for (BlockState loose : new BlockState[]{Blocks.GRAVEL.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState()}) {
            NearLitter.Cover c = NearLitter.cover(loose, AIR);
            assertSame(DustModel.Surface.FIELD, c.surface(), loose.toString());
            assertEquals(Fragments.Kind.CHUNK, c.kind());
            assertSame(loose, c.torn(), "crumb of your block");
        }
    }

    /** Stone and brick are just settled dust (no pieces); water and air - nothing. */
    @Test
    void hardAndWater() {
        for (BlockState hard : new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.BRICKS.defaultBlockState()}) {
            NearLitter.Cover c = NearLitter.cover(hard, AIR);
            assertSame(DustModel.Surface.HARD, c.surface(), hard.toString());
            assertFalse(c.pieces(), "from the solid - only dust");
        }
        assertNull(NearLitter.cover(Blocks.WATER.defaultBlockState(), AIR));
        assertNull(NearLitter.cover(AIR, AIR));
    }

    /**
     * Pieces - only where the front threw them: Shahed in 30 m tears up the grass and throws crumbs, "Grad" in 200 m tears off a share of loose
     *  without throwing - there are no pieces (they would simply appear on the screen), the tomahawk in 100 m does not tear up the grass.
     */
    @Test
    void piecesOnlyWhereTheFrontThrowsThem() {
        ShockAtBody shahed = ShockAtBody.chemical(50, true, 30), grad = ShockAtBody.chemical(6.4, true, 200), missile = ShockAtBody.chemical(150, true, 100);
        double leaf = Fragments.terminal(Fragments.Kind.LEAF, 0, 0), grain = Fragments.terminal(Fragments.Kind.CHUNK, NearLitter.GRAIN_DENSITY, NearLitter.CRUMB_M);
        assertTrue(NearLitter.pieces(LocalLitter.lift(shahed, DustModel.Surface.COVERED, 1, leaf)));
        assertTrue(NearLitter.pieces(LocalLitter.lift(shahed, DustModel.Surface.FIELD, 1, grain)));
        assertFalse(NearLitter.pieces(LocalLitter.lift(grad, DustModel.Surface.FIELD, 1, grain)));
        assertFalse(NearLitter.pieces(LocalLitter.lift(missile, DustModel.Surface.COVERED, 1, leaf)));
    }

    /**
     * The settling of fine dust is the one with which its weakening melts ({@link DustModel#extinctionAloft}: the slope at the beginning is
     * Σ k·fraction·v/Σ k·fraction), and not the speed at which the thrown crumb falls.
     */
    @Test
    void fineDustSettlesAsItsExtinction() {
        double eps = 1e-3, slope = (1 - DustModel.extinctionAloft(eps)) / eps;
        assertEquals(slope, NearLitter.DUST_SETTLE_MPS, 0.002, "attenuation slope " + slope);
    }

    /**
     * The litter at the camera goes through the columns only if the front in the circle raises something visible: Shahed in 30 m - yes, “Grad” in
     * 200 m and rocket in 300 m - no (price: law on loose soil - microseconds per place, places - up to a thousand).
     */
    @Test
    void nothingVisibleNothingSampled() {
        assertTrue(NearLitter.visible(50, true, 30 - NearLitter.REACH));
        assertTrue(NearLitter.visible(3, true, 10 - NearLitter.REACH < 0.5 ? 0.5 : 10 - NearLitter.REACH));
        assertFalse(NearLitter.visible(6.4, true, 200 - NearLitter.REACH));
        assertFalse(NearLitter.visible(150, true, 300 - NearLitter.REACH));
    }

    /** Column: blocks in height, the rest is air. */
    private static java.util.function.IntFunction<BlockState> column(java.util.Map<Integer, BlockState> blocks) {
        return y -> blocks.getOrDefault(y, AIR);
    }

    /**
     * Under the crown (foliage 68–70) the grass at the feet (64) is also an open place, and not just the top of the column; the roof in the 25 blocks above the
     *  eye is outside the sampling ball: the bottom of the column is not taken that is above the eye on {@link NearLitter#REACH}.
     */
    @Test
    void grassUnderTheCrownAndNotTheRoof() {
        java.util.Map<Integer, BlockState> b = new java.util.HashMap<>();
        b.put(63, Blocks.DIRT.defaultBlockState());
        b.put(64, Blocks.GRASS_BLOCK.defaultBlockState());
        b.put(65, Blocks.SHORT_GRASS.defaultBlockState());
        for (int y = 68; y <= 70; y++) b.put(y, Blocks.OAK_LEAVES.defaultBlockState());
        b.put(90, Blocks.STONE.defaultBlockState());
        double eyeY = 65.62;
        int high = (int) Math.floor(eyeY + NearLitter.REACH), low = (int) Math.floor(eyeY - NearLitter.REACH);
        int[] open = NearLitter.exposed(column(b), Math.min(high, 90), low);
        assertArrayEquals(new int[]{70, 64}, open, "crown and grass underneath, without a roof: " + java.util.Arrays.toString(open));
        // a roof over your head in a ball: its top is open, the floor under it is also
        b.put(75, Blocks.STONE.defaultBlockState());
        assertArrayEquals(new int[]{75, 70, 64}, NearLitter.exposed(column(b), 75, low));
    }

    /**
     * A piece flies as the body the law threw: fall speed from {@link LocalLitter#lift} (2 mm soil lump, not the drawn
     * 6–12 cm) is the speed approached by {@link Fragments#step} with its drag.
     */
    @Test
    void piecesFlyAsTheBodyTheLawThrew() {
        ShockAtBody shahed = ShockAtBody.chemical(50, true, 30);
        for (Fragments.Kind kind : new Fragments.Kind[]{Fragments.Kind.CHUNK, Fragments.Kind.LEAF}) {
            LocalLitter.Lift lift = LocalLitter.lift(shahed, kind == Fragments.Kind.LEAF ? DustModel.Surface.COVERED : DustModel.Surface.FIELD, 1,
                    NearLitter.terminal(kind));
            assertEquals(NearLitter.terminal(kind), lift.settlingMps(), 1e-12);
            double[] v = {0, 0, 0};
            for (int t = 0; t < 400; t++) Fragments.step(v, 0, 0, 0, Fragments.drag(lift.settlingMps()));
            // step Fragments at the sheet (set by 3 tick) maintains the falling speed with an accuracy of ~1 %
            assertEquals(-lift.settlingMps() / 20, v[1], 0.02 * lift.settlingMps() / 20, kind + ": does not fall at its own speed");
        }
        double drawn = Fragments.terminal(Fragments.Kind.CHUNK, NearLitter.GRAIN_DENSITY, NearLitter.GRAIN_MIN);
        assertTrue(drawn > 4 * NearLitter.terminal(Fragments.Kind.CHUNK), "the drawn lump would fall many times faster: " + drawn);
    }
}
