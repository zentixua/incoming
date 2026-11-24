package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.info.Automation;
import ua.zentix.incoming.info.Readout;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The mission of an owned launcher reaches a viewer without rights by no path: Jade ({@link Readout#launcher}), the
 * computer table ({@link Automation#status}, any computer anyone attaches) and the empty-hand status line
 * ({@code FixedLauncherBlockEntity.status(viewer)}); the owner's status line keeps it. Review round 1 (S1), kept as the
 * regression test; failed before the fix (50b78910a) at the computer table.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MissionPrivacyGameTests {
    private static final BlockPos PAD = new BlockPos(16, 4, 8);

    private MissionPrivacyGameTests() {}

    @GameTest(template = "runway", timeoutTicks = 40, batch = "readout_privacy", skyAccess = true)
    public static void missionReachesNoStranger(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SamGameTests.Listener stranger = SamGameTests.listener(h, "probe_stranger", PAD.west(2));
        SamGameTests.Listener owner = SamGameTests.listener(h, "probe_owner", PAD.east(2));
        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, PAD, owner.getUUID());
        // a recognisable target: 1234 blocks toward +Z
        Vec3 aim = Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(0, -1, 1234);
        be.setMission(new Mission(WeaponType.DRONE, 3, 10, new Target.Point(aim), aim, Waypoints.NONE));
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.SHAHED.get(), 1));
        long z = Math.round(aim.z);

        // 1. Jade: hidden (passes)
        boolean jadeMission = Readout.launcher(level, be, stranger).stream()
                .anyMatch(c -> c.getContents() instanceof TranslatableContents t && t.getKey().equals("incoming.info.mission"));
        h.assertFalse(jadeMission, "Jade gives a stranger the mission");

        // 2. A computer has no viewer identity, so whoever attaches one reads the exact target and count
        Map<String, Object> status = Automation.status(level, be);
        h.assertFalse(status.containsKey("mission"), "a computer anyone attaches reads the mission: " + status.get("mission"));
        // and the private refusal text Jade withholds (here the shortage names the mission's count)
        h.assertFalse(status.containsKey("message"), "a computer reads the private refusal: " + status.get("message"));

        // 3. The empty-hand status line (FixedLauncherBlock.useWithoutItem) has no viewer check either
        String line = be.status(stranger).getString();
        h.assertFalse(line.contains(Long.toString(z)), "the empty-hand status line gives anyone the target: " + line);
        h.assertValueEqual(status.get("reason"), Automation.PRIVATE, "the computer's reason for a shortage of the mission");
        h.assertValueEqual(List.of(Automation.fire(level, be)), List.of(false, Automation.PRIVATE), "the computer's fire refusal");
        String own = be.status(owner).getString();
        h.assertTrue(own.contains(Long.toString(z)), "the owner's status line lacks the target: " + own);
        h.succeed();
    }
}
