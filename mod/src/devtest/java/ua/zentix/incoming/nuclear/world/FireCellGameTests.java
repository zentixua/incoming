package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.model.Blowout;

/**
 * Several fuels lighting one fire cell. Their ignition combines first (the cell lights if any fuel does), then the
 * cell's blowout ({@link Blowout#keep}) applies once — in the flash layer and in ruins alike, so the layer's blown list and the ruin
 * fire agree. Applying blowout per fuel before combining would give the ruins 1 − (1 − 0.5)² = 0.75 at 5 psi for two certain
 * ignitions, where the flash keeps 0.5.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FireCellGameTests {
    private FireCellGameTests() {}

    @GameTest(template = "pad", batch = "nuke_flash_kindling")
    public static void fuelsSharingACellCombineBeforeOneBlowout(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        double keep = Blowout.keep(5);
        h.assertTrue(Math.abs(keep - 0.5) < 1e-9, "blowout at 5 psi " + keep);
        for (boolean flash : new boolean[]{true, false}) {
            RuinPlanner.Sections s = new RuinPlanner.Sections(ChunkShot.take(h.getLevel().getChunkAt(at), null, null, false), Blast.SERVER);
            s.fire(at.getX(), at.getY(), at.getZ(), 1, keep, flash);
            s.fire(at.getX(), at.getY(), at.getZ(), 1, keep, flash);
            s.fire(at.getX(), at.getY(), at.getZ(), 0.6, keep, flash);
            h.assertTrue(s.fires.size() == 1, "one candidate per cell: " + s.fires.size());
            double chance = s.fireChance.getDouble(0), kept = chance * s.fireKeep.getDouble(0);
            h.assertTrue(Math.abs(chance - 1) < 1e-9 && Math.abs(kept - 0.5) < 1e-9,
                    (flash ? "flash" : "ruins") + ": ignition " + chance + ", kept " + kept + " (want 1, 0.5)");
            s.fire(at.getX(), at.getY() + 1, at.getZ(), 0.6, keep, flash);
            s.fire(at.getX(), at.getY() + 1, at.getZ(), 0.6, keep, flash);
            double two = s.fireChance.getDouble(1) * s.fireKeep.getDouble(1);
            h.assertTrue(Math.abs(two - 0.84 * 0.5) < 1e-9, (flash ? "flash" : "ruins") + ": two 0.6 fuels keep " + two + " (want 0.42)");
        }
        h.succeed();
    }
}
