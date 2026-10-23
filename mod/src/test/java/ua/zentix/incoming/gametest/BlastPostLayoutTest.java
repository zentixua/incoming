package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.strike.WeaponType;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The #437 layout regression: preserve inner/outer radius checks across translated threshold hashes. */
class BlastPostLayoutTest {
    @Test
    void nearbyPostsAvoidRayLatticeGapsAcrossThresholdHashes() {
        double tnt = WeaponType.MISSILE.spec().charge();
        for (int i = 0; i < 32; i++) {
            BlockPos c = new BlockPos(-10982969 + i * 997, 177, -15764973 + i * 613);
            Vec3 at = Vec3.atBottomCenterOf(c);
            Set<BlockPos> samples = BlastGameTests.sampledPosts(c, at, 22);
            assertFalse(samples.contains(c.offset(0, 0, 9)), "regression must demonstrate a gap between rays");
            List<BlastGameTests.Post> bricks = BlastGameTests.layPosts(c, at, 12, tnt,
                    BlastDamage.breakRange(BlastModel.kpa(12), tnt), List.of(), b -> b < 0);
            List<BlastGameTests.Post> wood = BlastGameTests.layPosts(c, at, 4, tnt,
                    BlastDamage.breakRange(BlastModel.kpa(4), tnt), bricks, b -> b > 0);
            assertFalse(bricks.isEmpty());
            assertFalse(wood.isEmpty());
            assertTrue(wood.stream().anyMatch(p -> p.share() <= 0.65), "retain the strict 0.6 R inner plank");
            assertTrue(wood.stream().anyMatch(p -> p.share() >= 1.3), "retain the outer plank");
            for (var post : wood) assertTrue(samples.contains(post.pos()), "unsampled plank " + post);
            for (var post : bricks) assertTrue(samples.contains(post.pos()), "unsampled brick " + post);
        }
    }
}
