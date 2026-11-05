package ua.zentix.incoming.strike;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.world.NuclearReadyFixture;
import ua.zentix.incoming.nuclear.world.RuinPreparation;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NuclearSalvoPreparationGameTests {
    private NuclearSalvoPreparationGameTests() {}

    @GameTest(template = "range", batch = "nuke_prepared_site", skyAccess = true)
    public static void preparedGroundSiteWaitKeepsExactResolvedHeight(GameTestHelper h) {
        var level = h.getLevel();
        boolean carriers = IncomingConfig.SERVER.carrierNukes.get();
        IncomingConfig.SERVER.carrierNukes.set(true);
        var block = h.absolutePos(new BlockPos(32, 1, 32));
        Vec3 point = new Vec3(block.getX() + 0.5, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                block.getX(), block.getZ()), block.getZ() + 0.5);
        var owner = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "prep_site"));
        var loadout = Loadout.DEFAULT.withWeapon(WeaponType.MISSILE).withNuke(new Loadout.Nuke(1, false, true));
        var aim = new ServerActions.Aim(new Target.Ground(point), point, null);
        UUID handle = NuclearReadyFixture.prepare(h, owner, loadout, aim);
        try {
            SalvoData.startPrepared(level, WeaponType.MISSILE, aim.target(), point, 0, owner.getUUID(), loadout.nuke(),
                    Waypoints.NONE, null, false, handle);
            SalvoData.get(level).tick(level);
            var projectile = VirtualFlights.get(level).flights().stream().filter(p -> owner.getUUID().equals(p.ownerId())).findFirst().orElseThrow();
            h.assertTrue(projectile.nuclearPreparation() >= 0, "surface refinement invalidated the exact prepared order");
            h.assertTrue(RuinPreparation.status(level, handle).stage() == RuinPreparation.Stage.READY, "launch dropped prepared work");
        } finally {
            SalvoData.get(level).cancel(level, owner.getUUID(), java.util.List.of(owner));
            VirtualFlights.get(level).clear(level, p -> owner.getUUID().equals(p.ownerId()));
            RuinPreparation.cancel(level, handle);
            IncomingConfig.SERVER.carrierNukes.set(carriers);
        }
        h.succeed();
    }
}
