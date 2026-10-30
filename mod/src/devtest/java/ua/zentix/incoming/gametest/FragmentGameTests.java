package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.FragmentStrike;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fragments (S4): Grad bursts one meter above the pad with its axis pointing down — the fragment belt runs over the
 * ground in all directions. Mannequins on two rings, 8 and 16 m: hits on the far ring are fewer — by 1/R² (a mannequin
 * 8 m above the belt catches a larger share of it than one 16 m away, so the ratio is 2.8–5, not exactly 4); behind a
 * stone wall — none. Glass and masonry in the belt: panes are punched through at points, masonry gets pits.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FragmentGameTests {
    private static final BlockPos GRASS = new BlockPos(32, 11, 32), CENTER = GRASS.above();

    private FragmentGameTests() {}

    @GameTest(template = "range", timeoutTicks = 400, batch = "fragments", skyAccess = true)
    public static void fragmentsByInverseSquareNotThroughWalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER)).add(0, 1, 0);
        List<ArmorStand> near = new ArrayList<>(), far = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            // rings from 30° to 285°: at 0° a mannequin behind a wall, at 320° glass and masonry
            double a = Math.toRadians(30 + 30 * i);
            near.add(stand(h, 8 * Math.cos(a), 8 * Math.sin(a)));
            far.add(stand(h, 16 * Math.cos(a + Math.toRadians(15)), 16 * Math.sin(a + Math.toRadians(15))));
        }
        // stone wall 5 × 4 at 5 m along +X, a mannequin behind it at 8 m
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = 0; dy < 4; dy++) h.setBlock(CENTER.offset(5, dy, dz), Blocks.STONE);
        }
        ArmorStand hidden = stand(h, 8, 0);
        // at 320°, 10 m out: a 6 × 4 wall of glass panes, with masonry behind it at 12 m
        List<BlockPos> glass = new ArrayList<>(), bricks = new ArrayList<>();
        double g = Math.toRadians(320);
        for (int k = -3; k <= 2; k++) {
            for (int dy = 0; dy < 4; dy++) {
                BlockPos p = CENTER.offset((int) Math.round(10 * Math.cos(g) - k * Math.sin(g)), dy, (int) Math.round(10 * Math.sin(g) + k * Math.cos(g)));
                h.setBlock(p, Blocks.GLASS);
                glass.add(h.absolutePos(p));
                BlockPos q = CENTER.offset((int) Math.round(12 * Math.cos(g) - k * Math.sin(g)), dy, (int) Math.round(12 * Math.sin(g) + k * Math.cos(g)));
                h.setBlock(q, Blocks.STONE_BRICKS);
                bricks.add(h.absolutePos(q));
            }
        }
        FragmentStrike strike = Warheads.testFragments(level, WeaponType.ROCKET, at, new Vec3(0, -1, 0), 1234);
        h.succeedWhen(() -> {
            h.assertTrue(strike.done(), "fragments have not finished");
            double n = 0, f = 0;
            for (ArmorStand s : near) n += strike.hits(s.getUUID());
            for (ArmorStand s : far) f += strike.hits(s.getUUID());
            n /= near.size();
            f /= far.size();
            int broken = 0, cracked = 0;
            for (BlockPos p : glass) if (level.getBlockState(p).isAir()) broken++;
            for (BlockPos p : bricks) if (level.getBlockState(p).is(Blocks.CRACKED_STONE_BRICKS)) cracked++;
            String got = String.format(Locale.ROOT, "hits per mannequin: 8 m %.1f, 16 m %.1f (×%.2f), behind wall %.0f; panes %d/%d, pits %d/%d",
                    n, f, n / Math.max(f, 1e-9), strike.hits(hidden.getUUID()), broken, glass.size(), cracked, bricks.size());
            Incoming.LOG.info("FRAGBENCH {}", got);
            h.assertTrue(f >= 1, got);
            h.assertTrue(n / f >= 2.8 && n / f <= 5, got);
            h.assertTrue(strike.hits(hidden.getUUID()) == 0, got);
            h.assertTrue(broken > 0, got);
            h.assertTrue(cracked > 0, got);
        });
    }

    private static ArmorStand stand(GameTestHelper h, double dx, double dz) {
        BlockPos p = CENTER.offset((int) Math.round(dx), 0, (int) Math.round(dz));
        ArmorStand s = h.spawn(EntityType.ARMOR_STAND, p);
        s.setInvulnerable(true);
        return s;
    }
}
