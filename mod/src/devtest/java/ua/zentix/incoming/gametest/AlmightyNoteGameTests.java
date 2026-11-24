package ua.zentix.incoming.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.grid.Blackouts;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.Outage;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.ServerActions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Almighty note ({@code data/incoming/almighty/notes/strikes.md}) on a real server, through the commands with the game
 * master's rights (level 4, no entity): a raid wave is over when {@code status tag} returns 0, and the grid commands
 * return what the note says. Parsing, the events and the recipes' scripts: {@code AlmightyNoteTest}.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AlmightyNoteGameTests {
    private static final BlockPos RUNWAY_TARGET = new BlockPos(16, 3, 200);
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private AlmightyNoteGameTests() {}

    /**
     * The raid recipe's end condition: a tagged Grad wave by command; {@code status tag} returns more than 0 while any
     * admitted round has not ended (released, flying, or its blast still worked out), returns 0 only once every round
     * has posted its one {@link StrikeRoundEvent.Ended}, all of them impacts, and stays 0.
     */
    @GameTest(template = "runway", timeoutTicks = 2400, batch = "almighty_raid", skyAccess = true)
    public static void raidWaveIsOverWhenStatusTagReturnsZero(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "gmraid1";
        OrderStatusGameTests.Console gm = OrderStatusGameTests.Console.open(h, "almighty_raid");
        UUID[] order = {null};
        Map<UUID, StrikeRoundEvent.Outcome> ended = new HashMap<>();
        Map<UUID, Integer> released = new HashMap<>();
        OrderStatusGameTests.listen(h, StrikeOrderEvent.Accepted.class, a -> {
            if ("almighty_raid".equals(a.getActorName())) order[0] = a.getOrderId();
        });
        OrderStatusGameTests.listen(h, StrikeRoundEvent.Released.class, e -> {
            if (tag.equals(e.getTag())) released.merge(e.getRoundId(), 1, Integer::sum);
        });
        int[] twice = {0};
        // a listener runs in the server's event delivery, not in the test: it only records
        OrderStatusGameTests.listen(h, StrikeRoundEvent.Ended.class, e -> {
            if (tag.equals(e.getTag()) && ended.put(e.getRoundId(), e.getOutcome()) != null) twice[0]++;
        });
        StrikeGameTests.afterTest(h, () -> ServerActions.clearTag(level.getServer(), tag, "test"));
        BlockPos c = h.absolutePos(RUNWAY_TARGET);

        int admitted = gm.run("incoming salvo grad 3 5 at " + c.getX() + " " + c.getZ() + " tag " + tag);
        h.assertValueEqual(admitted, 3, "the wave's result");
        h.assertTrue(order[0] != null, "no Accepted for the game master's order");
        long[] zeroAt = {-1};
        int[] peak = {0};
        h.onEachTick(() -> {
            int left = gm.run("incoming status tag " + tag);
            h.assertValueEqual(twice[0], 0, "rounds that ended twice");
            if (zeroAt[0] >= 0) {
                h.assertValueEqual(left, 0, "status after the wave was over");
                return;
            }
            peak[0] = Math.max(peak[0], left);
            if (left > 0) return;
            // round events are posted at the end of their tick: deliver this tick's before counting
            Orders.deliver(level);
            h.assertValueEqual(ended.size(), admitted, "rounds ended when status returned 0 (" + ended + ")");
            h.assertTrue(ended.values().stream().allMatch(o -> o == StrikeRoundEvent.Outcome.IMPACT), "not every round hit: " + ended);
            h.assertValueEqual(released.size(), admitted, "rounds released");
            zeroAt[0] = level.getGameTime();
        });
        h.succeedWhen(() -> {
            h.assertValueEqual(peak[0], admitted, "status while the wave flew");
            h.assertTrue(zeroAt[0] >= 0 && level.getGameTime() >= zeroAt[0] + 40, "the wave is not over for 40 ticks");
        });
    }

    /**
     * The grid commands' results as the note states them, on the real grid: {@code node add} returns the node number,
     * {@code node list} the dimension's nodes, {@code node knockout} and {@code node remove} 1 (an unknown number fails),
     * {@code status} the outages in all dimensions, {@code blackout} the outage number, {@code restore at} the outages it
     * restores. Positions are explicit, as the game master gives them.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "almighty_grid", skyAccess = true)
    public static void gridCommandsReturnWhatTheNoteSays(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OrderStatusGameTests.Console gm = OrderStatusGameTests.Console.open(h, "almighty_grid");
        PowerGrid grid = PowerGrid.get(level);
        BlockPos c = h.absolutePos(RANGE_CENTER);
        String at = c.getX() + " " + c.getY() + " " + c.getZ();
        int[] made = {-1};
        StrikeGameTests.afterTest(h, () -> {
            if (made[0] > 0) grid.removeNode(made[0]);
            Blackouts.restore(level, Vec3.atBottomCenterOf(c), 1);
        });

        int node = gm.run("incoming grid node add " + at + " 64");
        made[0] = node;
        Node n = grid.node(node);
        h.assertTrue(n != null && n.pos().equals(c) && n.radius() == 64, "node add made " + n + " as #" + node);
        int nodes = 0;
        for (Node ignored : grid.nodes()) nodes++;
        h.assertValueEqual(gm.run("incoming grid node list"), nodes, "node list");
        h.assertValueEqual(gm.run("incoming grid node knockout " + node), 1, "node knockout");
        h.assertTrue(grid.downOutage(node, Blackouts.now(level)).isPresent(), "the node is not down");
        int outages = 0;
        for (ServerLevel l : level.getServer().getAllLevels()) outages += PowerGrid.get(l).outages().size();
        h.assertValueEqual(gm.run("incoming grid status"), outages, "grid status");
        h.assertValueEqual(gm.run("incoming grid restore at " + at + " 1"), 1, "restore at the node");
        h.assertValueEqual(gm.run("incoming grid node remove " + node), 1, "node remove");
        h.assertTrue(grid.node(node) == null, "the node is still there");
        made[0] = -1;
        fails(gm, "incoming grid node knockout " + node);

        int id = gm.run("incoming grid blackout " + at + " 32");
        Outage o = grid.outages().stream().filter(x -> x.id() == id).findFirst().orElse(null);
        h.assertTrue(o != null && o.radius() == 32 && Math.hypot(o.x() - (c.getX() + 0.5), o.z() - (c.getZ() + 0.5)) < 1e-9,
                "blackout returned #" + id + ", outage " + o);
        h.assertValueEqual(gm.run("incoming grid restore at " + at + " 1"), 1, "restore at the blackout");
        h.succeed();
    }

    private static void fails(OrderStatusGameTests.Console gm, String command) {
        try {
            gm.source().getServer().getCommands().getDispatcher().execute(command, gm.source());
        } catch (CommandSyntaxException expected) {
            return;
        }
        throw new GameTestAssertException("/" + command + " did not fail");
    }
}
