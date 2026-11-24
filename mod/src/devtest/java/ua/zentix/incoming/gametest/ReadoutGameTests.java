package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.info.Readout;
import ua.zentix.incoming.launcher.FixedLauncherBlockEntity;
import ua.zentix.incoming.launcher.Mission;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.target.Target;

import java.util.ArrayList;
import java.util.List;

/**
 * Jade overlay lines ({@link Readout}) as the server builds them for one viewer: the Jade adapter only writes and
 * displays these, so what a viewer without rights receives is decided here (Jade is not in the GameTest runtime; its
 * adapter is checked by {@code compat.jade.LinesTest}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReadoutGameTests {
    private static final BlockPos PAD = new BlockPos(16, 4, 8);
    private static final List<String> PRIVATE = List.of("incoming.info.owner", "incoming.info.owner.none", "incoming.info.mission");

    private ReadoutGameTests() {}

    /**
     * A launcher whose mission (3 Shaheds) its stock (2) cannot cover. A stranger (no team, not an operator) gets no owner,
     * no mission and only "cannot fire", no reason (the shortage names the mission's count); the owner, a teammate and an
     * operator get the owner, the mission and the reason. A refusal about the launcher itself ("no mission") reads for
     * everyone. Placed by command (no owner): owner and mission go to operators only. A Shahed's owner: to the owner's side
     * and operators, nothing to the stranger.
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "readout_rights", skyAccess = true)
    public static void ownerAndMissionOnlyForSideAndOperators(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SamGameTests.Listener owner = SamGameTests.listener(h, "readout_owner", PAD.east(2));
        SamGameTests.Listener mate = SamGameTests.listener(h, "readout_mate", PAD.east(3));
        SamGameTests.Listener stranger = SamGameTests.listener(h, "readout_stranger", PAD.west(2));
        ServerPlayer op = new MunitionGameTests.Listener(level, "readout_op", 2);
        Scoreboard board = level.getScoreboard();
        PlayerTeam team = board.addPlayerTeam("readout_side");
        board.addPlayerToTeam(owner.getScoreboardName(), team);
        board.addPlayerToTeam(mate.getScoreboardName(), team);
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(team));

        FixedLauncherBlockEntity be = FixedLauncherGameTests.launcher(h, PAD, owner.getUUID());
        Vec3 far = Vec3.atBottomCenterOf(h.absolutePos(PAD)).add(0, -1, 1000);
        Mission mission = new Mission(WeaponType.DRONE, 3, 10, new Target.Point(far), far, Waypoints.NONE);
        be.setMission(mission);
        FixedLauncherGameTests.load(h, be, new ItemStack(ModItems.SHAHED.get(), 2));

        List<String> seen = keys(Readout.launcher(level, be, stranger));
        h.assertTrue(PRIVATE.stream().noneMatch(seen::contains), "a stranger sees the owner or the mission: " + seen);
        h.assertTrue(seen.contains("incoming.info.cannot.private") && !seen.contains("incoming.info.cannot"), "a stranger sees the mission's shortage: " + seen);
        h.assertTrue(seen.contains("incoming.info.stock.rounds"), "a stranger does not see the stock: " + seen);
        for (ServerPlayer v : List.of(owner, mate, op)) {
            List<Component> lines = Readout.launcher(level, be, v);
            List<String> k = keys(lines);
            h.assertTrue(k.contains("incoming.info.owner") && k.contains("incoming.info.mission"), v.getScoreboardName() + " lacks owner or mission: " + k);
            h.assertValueEqual(reason(lines), "incoming.munitions.short", v.getScoreboardName() + "'s reason");
        }
        h.assertValueEqual(ownerName(Readout.launcher(level, be, op)), "readout_owner", "the owner line's name");

        be.setMission(null);
        h.assertValueEqual(reason(Readout.launcher(level, be, stranger)), "incoming.fixed_launcher.no_mission.short", "a stranger's public reason");
        be.setMission(mission);

        be.setOwner(null);
        for (ServerPlayer v : List.of(owner, stranger)) {
            List<String> k = keys(Readout.launcher(level, be, v));
            h.assertTrue(PRIVATE.stream().noneMatch(k::contains), v.getScoreboardName() + " sees an ownerless launcher's mission: " + k);
        }
        List<String> k = keys(Readout.launcher(level, be, op));
        h.assertTrue(k.contains("incoming.info.owner.none") && k.contains("incoming.info.mission"), "an operator lacks the ownerless launcher's data: " + k);
        be.setOwner(owner.getUUID());

        DroneEntity drone = SamGameTests.drone(h, 30, owner.getUUID());
        h.assertTrue(Readout.projectile(level.getServer(), drone, stranger).isEmpty(), "a stranger sees the projectile's owner");
        for (ServerPlayer v : List.of(owner, mate, op)) {
            h.assertValueEqual(keys(Readout.projectile(level.getServer(), drone, v)), List.of("incoming.info.owner"), v.getScoreboardName() + "'s projectile lines");
        }
        DroneEntity ownerless = SamGameTests.drone(h, 34, null);
        h.assertTrue(Readout.projectile(level.getServer(), ownerless, owner).isEmpty(), "a player sees an ownerless projectile's owner line");
        h.assertValueEqual(keys(Readout.projectile(level.getServer(), ownerless, op)), List.of("incoming.info.owner.none"), "operator, ownerless projectile");
        h.succeed();
    }

    /**
     * A placed substation reads "working"; knocked out, "repairs start in N h" with N the grid's own restoration time on
     * the world clock; restored by command, "working" again. A block the grid has no node for reads nothing.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "readout_grid", skyAccess = true)
    public static void substationFollowsTheGrid(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        GridGameTests.quiet(level);
        StrikeGameTests.afterTest(h, () -> Blackouts.restore(level, null, 0, 0));
        BlockPos at = GridGameTests.CENTER;
        h.setBlock(at, ModBlocks.SUBSTATION.get());
        BlockPos abs = h.absolutePos(at);
        Node node = PowerGrid.get(level).nodeAt(abs);
        h.assertTrue(node != null, "the substation did not become a grid node");
        h.assertValueEqual(keys(Readout.substation(level, abs)), List.of("incoming.info.substation.working"), "placed");
        Blackouts.knockOut(level, node);
        List<Component> out = Readout.substation(level, abs);
        h.assertValueEqual(keys(out), List.of("incoming.info.substation.knocked_out"), "knocked out");
        long repairIn = PowerGrid.get(level).downOutage(node.id(), Blackouts.now(level)).orElseThrow().restoreAt() - Blackouts.now(level);
        double hours = Double.parseDouble(String.valueOf(((TranslatableContents) out.getFirst().getContents()).getArgs()[0]));
        h.assertTrue(Math.abs(hours - repairIn / 1000.0) <= 0.1, "repairs in " + hours + " h, the grid says " + repairIn / 1000.0);
        h.assertTrue(repairIn / 1000.0 <= IncomingConfig.SERVER.gridRestoreHours.get() + 0.1, "repair time beyond the world's restore hours");
        Blackouts.restore(level, null, 0, 0);
        h.assertValueEqual(keys(Readout.substation(level, abs)), List.of("incoming.info.substation.working"), "restored");
        h.assertTrue(Readout.substation(level, abs.east(2)).isEmpty(), "a position without a node has a line");
        h.succeed();
    }

    /** Translation keys of the lines, in order. */
    static List<String> keys(List<Component> lines) {
        List<String> k = new ArrayList<>();
        for (Component c : lines) k.add(c.getContents() instanceof TranslatableContents t ? t.getKey() : c.getString());
        return k;
    }

    /** The refusal inside the "cannot fire" line; null — none. */
    private static String reason(List<Component> lines) {
        for (Component c : lines) {
            if (c.getContents() instanceof TranslatableContents t && t.getKey().equals("incoming.info.cannot")
                    && t.getArgs()[0] instanceof Component r && r.getContents() instanceof TranslatableContents rt) return rt.getKey();
        }
        return null;
    }

    /** The name in the "owner" line; null — none. */
    private static String ownerName(List<Component> lines) {
        for (Component c : lines) {
            if (c.getContents() instanceof TranslatableContents t && t.getKey().equals("incoming.info.owner") && t.getArgs()[0] instanceof Component n) {
                return n.getString();
            }
        }
        return null;
    }
}
