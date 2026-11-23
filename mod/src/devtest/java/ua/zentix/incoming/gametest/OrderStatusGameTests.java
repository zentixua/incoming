package ua.zentix.incoming.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.strike.Loadout;
import ua.zentix.incoming.strike.NuclearKeys;
import ua.zentix.incoming.strike.OrderStatus;
import ua.zentix.incoming.strike.Orders;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.TargetMode;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.Waypoints;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Command results, {@code status} and tags (roadmap A2): every probe of the contract in its pull request. A strike command
 * returns the rounds its order admitted on the tick it is given, though a salvo launches them later; {@code status} counts
 * a tag's unfinished rounds down to 0 through a failed launch and a recall, then every round has ended; a second-key order
 * is queued with its arrival unknown; tags survive a restart of salvos, off-world flights and scheduled ICBMs; a
 * non-operator's {@code clear tag} leaves other owners' rounds; {@code execute store result} reads the counts; a round's
 * impact work counts until its jobs finish, a nuclear one until its front has passed. Each test has its own batch and tag.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OrderStatusGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private OrderStatusGameTests() {}

    /**
     * Console salvos by command: {@code salvo drone 4 20 … tag} returns 4 on the tick it is given, nothing launched yet, and
     * tells the order id its Accepted event carries; {@code execute store result} reads a salvo's admitted count and the
     * tag's status; the count stays while the salvo launches over later ticks.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "status_command", skyAccess = true)
    public static void salvoCommandReturnsItsAdmittedCount(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "a2cmd";
        Console console = Console.open(h, "status_command");
        UUID[] accepted = {null};
        listen(h, StrikeOrderEvent.Accepted.class, a -> {
            if ("status_command".equals(a.getActorName()) && accepted[0] == null) accepted[0] = a.getOrderId();
        });
        StrikeGameTests.afterTest(h, () -> ServerActions.clearTag(level.getServer(), tag, "test"));
        BlockPos c = h.absolutePos(RANGE_CENTER);
        String at = " at " + c.getX() + " " + c.getY() + " " + c.getZ();

        h.assertValueEqual(console.run("incoming salvo drone 4 20" + at + " tag " + tag), 4, "salvo command result");
        h.assertTrue(accepted[0] != null, "no Accepted for the command's order");
        h.assertTrue(console.mentions(accepted[0].toString()), "the feedback does not tell the order id " + accepted[0] + ": " + console.said);
        List<OrderStatus.Round> now = rounds(level, tag);
        h.assertValueEqual(now.size(), 4, "unfinished rounds on the command's tick");
        h.assertTrue(now.stream().allMatch(r -> r.phase() == OrderStatus.Phase.QUEUED), "launched on the command's tick: " + now);

        Objective o = objective(level, "a2st");
        console.perform("execute store result score #salvo a2st run incoming salvo drone 2 0" + at + " tag " + tag);
        h.assertValueEqual(score(level, o, "#salvo"), 2, "execute store result of a salvo");
        console.perform("execute store result score #status a2st run incoming status tag " + tag);
        h.assertValueEqual(score(level, o, "#status"), 6, "execute store result of status");
        h.assertValueEqual(console.run("incoming status tag " + tag), 6, "status result");
        h.succeedWhen(() -> {
            List<OrderStatus.Round> later = rounds(level, tag);
            h.assertValueEqual(later.size(), 6, "unfinished rounds while launching");
            h.assertTrue(later.stream().anyMatch(r -> r.phase() == OrderStatus.Phase.FLIGHT && r.ref().order().equals(accepted[0])),
                    "the salvo has not launched yet");
        });
    }

    /**
     * An operator's tagged salvo of three: the first launch fails (its entry into the world refused), the rest fly; {@code
     * status tag} never grows, counts the failed round until the salvo settles, and an operator's {@code clear tag} takes it
     * to 0 at once; by then each of the three rounds has ended exactly once, one as failed.
     */
    @GameTest(template = "runway", timeoutTicks = 1200, batch = "status_countdown", skyAccess = true)
    public static void statusCountsDownThroughFailedLaunchAndRecall(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "a2count";
        MunitionGameTests.Listener op = new MunitionGameTests.Listener(level, "status_countdown", 2);
        op.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60))), 0, 0);
        int[] refused = {0};
        Consumer<EntityJoinLevelEvent> veto = e -> {
            if (e.getEntity() instanceof StrikeProjectile p && op.getUUID().equals(p.ownerId()) && refused[0] == 0) {
                refused[0]++;
                e.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(veto);
        UUID[] order = {null};
        List<StrikeRoundEvent.Ended> ended = new ArrayList<>();
        listen(h, StrikeRoundEvent.Ended.class, e -> {
            if (e.getOrderId() != null && e.getOrderId().equals(order[0])) ended.add(e);
        });
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(veto);
            ServerActions.clearTag(level.getServer(), tag, "test");
            level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64)).forEach(Entity::discard);
        });
        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 3, 200))).add(0, 0.5, 0);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(point), point, null);
        h.assertTrue(ServerActions.strike(op, WeaponType.DRONE, 3, 10, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false, tag,
                o -> order[0] = o.id()), "salvo not admitted");
        h.assertValueEqual(count(level, tag), 3, "unfinished rounds once admitted");
        int[] last = {3};
        boolean[] cleared = {false};
        h.onEachTick(() -> {
            if (cleared[0]) return;
            int n = count(level, tag);
            h.assertTrue(n <= last[0], "status grew from " + last[0] + " to " + n);
            last[0] = n;
            if (refused[0] == 0 || rounds(level, tag).stream().noneMatch(r -> r.phase() == OrderStatus.Phase.FLIGHT)) return;
            h.assertValueEqual(n, 3, "a failed launch left the count before its salvo settled, or a round ended early");
            int recalled = run(h, op.createCommandSourceStack(), "incoming clear tag " + tag);
            h.assertTrue(recalled >= 1, "clear tag recalled nothing");
            h.assertValueEqual(count(level, tag), 0, "unfinished rounds after clear tag");
            cleared[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(cleared[0], "no launch failed and flew before the timeout");
            Orders.deliver(level);
            h.assertValueEqual(ended.size(), 3, "rounds ended");
            h.assertValueEqual(ended.stream().map(StrikeRoundEvent.Ended::getRoundId).distinct().count(), 3L, "a round ended twice");
            h.assertValueEqual(ended.stream().filter(e -> e.getOutcome() == StrikeRoundEvent.Outcome.FAILED).count(), 1L, "failed rounds");
        });
    }

    /** A nuclear order waiting for its second key: one queued round, its arrival unknown; {@code clear tag} cancels it. */
    @GameTest(template = "runway", timeoutTicks = 40, batch = "status_key", skyAccess = true)
    public static void secondKeyOrderIsQueuedWithArrivalUnknown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "a2key";
        StrikeGameTests.afterTest(h, () -> NuclearKeys.cancel(level.getServer(), List.of(), tag, null, "test"));
        MunitionGameTests.Listener a = MunitionGameTests.player(h, "status_key_launcher");
        a.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60))), 0, 0);
        MunitionGameTests.Listener near = MunitionGameTests.player(h, "status_key_near");
        near.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(19, 4, 60))), 0, 0);
        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 3, 200))).add(0, 0.5, 0);
        Loadout l = ServerActions.clamp(new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", new Loadout.Nuke(15, true)));
        h.assertTrue(NuclearKeys.order(a, List.of(a, near), l, new ServerActions.Aim(new Target.Point(point), point, null), Waypoints.NONE, null, null,
                RoundRef.of(UUID.randomUUID(), 0, tag)), "order not recorded");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 1, "orders waiting for the key");
        List<OrderStatus.Line> lines = OrderStatus.lines(rounds(level, tag));
        h.assertValueEqual(lines.size(), 1, "orders");
        OrderStatus.Line line = lines.getFirst();
        h.assertTrue(line.queued() == 1 && line.flight() == 0 && line.impact() == 0, "phases " + line);
        h.assertValueEqual(line.eta(), OrderStatus.UNKNOWN, "arrival");
        h.assertValueEqual(line.owner(), a.getUUID(), "owner");
        Console console = Console.open(h, "status_key");
        h.assertValueEqual(console.run("incoming status tag " + tag), 1, "status result");
        h.assertTrue(console.mentions("incoming.status.eta.unknown"), "status does not say the arrival is unknown: " + console.said);
        h.assertValueEqual(console.run("incoming clear tag " + tag), 1, "clear tag result");
        h.assertValueEqual(NuclearKeys.get(level).pending(), 0, "the key wait survived clear tag");
        h.assertValueEqual(count(level, tag), 0, "unfinished rounds after clear tag");
        h.succeed();
    }

    /**
     * A tag survives a restart: a console salvo (queued), a single Shahed in off-world flight and a scheduled ICBM, all
     * tagged, read back from their saves count the same rounds with the same ids and tag.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "status_restart", skyAccess = true)
    public static void tagSurvivesRestart(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "a2save";
        boolean enabled = IncomingConfig.SERVER.nukeEnabled.get();
        int flight = IncomingConfig.SERVER.nukeFlightTime.get();
        StrikeGameTests.afterTest(h, () -> {
            ServerActions.clearTag(level.getServer(), tag, "test");
            IncomingConfig.SERVER.nukeEnabled.set(enabled);
            IncomingConfig.SERVER.nukeFlightTime.set(flight);
        });
        IncomingConfig.SERVER.nukeEnabled.set(true);
        IncomingConfig.SERVER.nukeFlightTime.set(6000);
        Console console = Console.open(h, "status_restart");
        BlockPos c = h.absolutePos(RANGE_CENTER);
        String at = " at " + c.getX() + " " + c.getY() + " " + c.getZ();
        h.assertValueEqual(console.run("incoming salvo drone 3 20" + at + " tag " + tag), 3, "salvo");
        h.assertValueEqual(console.run("incoming salvo drone 1 0" + at + " tag " + tag), 1, "single Shahed");
        h.assertValueEqual(console.run("incoming nuke" + at + " 1 air tag " + tag), 1, "ICBM");
        h.assertValueEqual(count(level, tag), 5, "unfinished rounds");
        boolean[] restarted = {false};
        h.onEachTick(() -> {
            if (restarted[0] || VirtualFlights.get(level).flights().stream().noneMatch(p -> p.round() != null && tag.equals(p.round().tag()))) return;
            List<String> before = snapshot(level, tag);
            h.assertValueEqual(before.size(), 5, "unfinished rounds before the restart: " + before);
            SalvoData.reloadForTest(level);
            VirtualFlights.reloadForTest(level);
            NuclearEvents.reloadForTest(level);
            List<String> after = snapshot(level, tag);
            h.assertValueEqual(after, before, "rounds after the restart");
            h.assertTrue(VirtualFlights.get(level).flights().stream().anyMatch(p -> p.round() != null && tag.equals(p.round().tag())),
                    "no tagged off-world flight after the restart");
            h.assertTrue(NuclearEvents.get(level).scheduled().stream().anyMatch(s -> s.round().map(r -> tag.equals(r.tag())).orElse(false)),
                    "no tagged ICBM after the restart");
            restarted[0] = true;
        });
        h.succeedWhen(() -> h.assertTrue(restarted[0], "the single Shahed never flew off-world"));
    }

    /**
     * A non-operator's {@code clear tag} recalls only their own rounds: another player's tagged salvo stays, counted; each
     * sees only their own rounds in {@code status}; {@code status <player>} is for operators; the owner recalls their own.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "status_clear", skyAccess = true)
    public static void clearTagLeavesOtherOwnersRounds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "a2clear";
        boolean everyone = IncomingConfig.SERVER.designatorForEveryone.get();
        StrikeGameTests.afterTest(h, () -> {
            ServerActions.clearTag(level.getServer(), tag, "test");
            IncomingConfig.SERVER.designatorForEveryone.set(everyone);
        });
        IncomingConfig.SERVER.designatorForEveryone.set(true);
        MunitionGameTests.Listener a = MunitionGameTests.player(h, "status_clear_a");
        MunitionGameTests.Listener b = MunitionGameTests.player(h, "status_clear_b");
        Vec3 point = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(1000, 0.5, 0);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(point), point, null);
        h.assertTrue(ServerActions.strike(a, WeaponType.DRONE, 2, 10, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false, tag), "salvo of a");
        h.assertValueEqual(count(level, tag), 2, "unfinished rounds");

        h.assertValueEqual(run(h, b.createCommandSourceStack(), "incoming clear tag " + tag), 0, "another player's clear tag result");
        h.assertValueEqual(count(level, tag), 2, "another player's clear tag recalled rounds");
        h.assertValueEqual(run(h, b.createCommandSourceStack(), "incoming status tag " + tag), 0, "another player sees rounds that are not theirs");
        h.assertValueEqual(run(h, a.createCommandSourceStack(), "incoming status tag " + tag), 2, "the owner's status");
        try {
            level.getServer().getCommands().getDispatcher().execute("incoming status " + a.getGameProfile().getName(), b.createCommandSourceStack());
            throw new GameTestAssertException("a player without operator rights read another player's status");
        } catch (CommandSyntaxException expected) {
            // the branch needs operator rights
        }
        h.assertValueEqual(run(h, a.createCommandSourceStack(), "incoming clear tag " + tag), 2, "the owner's clear tag result");
        h.assertValueEqual(count(level, tag), 0, "unfinished rounds after the owner's clear tag");
        h.succeed();
    }

    /**
     * A conventional round's impact work: counted, as impact, from the hit until the last job of its blast (crater,
     * collapse, fuel, debris, fragments, glass) is done; when the count reaches 0 nothing of it is queued.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "status_impact", skyAccess = true)
    public static void impactWorkCountsUntilItsJobsFinish(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "a2impact";
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER));
        Warheads.detonate(level, WeaponType.DRONE, at, null, null, RoundRef.of(UUID.randomUUID(), 0, tag));
        List<OrderStatus.Round> hit = rounds(level, tag);
        h.assertValueEqual(hit.size(), 1, "unfinished rounds at the hit");
        h.assertValueEqual(hit.getFirst().phase(), OrderStatus.Phase.IMPACT, "phase");
        long start = level.getGameTime();
        h.succeedWhen(() -> {
            h.assertValueEqual(count(level, tag), 0, "impact work");
            h.assertTrue(level.getGameTime() > start, "the impact work ended on its own tick");
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "the round left the count with its jobs still queued");
        });
    }

    /**
     * A tagged {@code nuke now}: one round in impact from the burst, through a restart mid-front, until the front has passed
     * its last hit reach; then 0, and it stays 0.
     */
    @GameTest(template = "range", timeoutTicks = 3000, batch = "status_nuke", skyAccess = true)
    public static void nuclearImpactEndsWithItsFront(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        String tag = "a2nuke";
        boolean enabled = IncomingConfig.SERVER.nukeEnabled.get();
        StrikeGameTests.afterTest(h, () -> {
            NuclearStrikes.clear(level);
            IncomingConfig.SERVER.nukeEnabled.set(enabled);
        });
        IncomingConfig.SERVER.nukeEnabled.set(true);
        Console console = Console.open(h, "status_nuke");
        BlockPos c = h.absolutePos(RANGE_CENTER);
        h.assertValueEqual(console.run("incoming nuke now at " + c.getX() + " " + c.getY() + " " + c.getZ() + " 1 air tag " + tag), 1, "nuke now result");
        List<OrderStatus.Round> burst = rounds(level, tag);
        h.assertValueEqual(burst.size(), 1, "unfinished rounds at the burst");
        h.assertValueEqual(burst.getFirst().phase(), OrderStatus.Phase.IMPACT, "phase");
        Detonation d = NuclearEvents.get(level).detonations().stream().max(Comparator.comparingInt(Detonation::id)).orElseThrow();
        double reach = d.blastReach();
        long frontEnd = d.gameTime() + (long) Math.floor(d.arrivalTicks(reach) + d.positivePhaseTicks(reach)) + 2;
        long[] zeroAt = {-1};
        boolean[] reloaded = {false};
        h.onEachTick(() -> {
            long now = level.getGameTime();
            int n = count(level, tag);
            if (!reloaded[0] && now >= d.gameTime() + 20 && now < frontEnd) {
                NuclearEvents.reloadForTest(level);
                h.assertValueEqual(count(level, tag), 1, "impact after a restart mid-front");
                reloaded[0] = true;
            }
            if (zeroAt[0] < 0 && n == 0) {
                h.assertTrue(now > frontEnd, "impact ended at " + now + ", before its front at " + frontEnd);
                zeroAt[0] = now;
            }
            if (zeroAt[0] >= 0) h.assertValueEqual(n, 0, "impact work reopened");
        });
        h.succeedWhen(() -> {
            h.assertTrue(reloaded[0], "the front was too short to restart within it");
            h.assertTrue(zeroAt[0] >= 0 && level.getGameTime() >= zeroAt[0] + 40, "impact work not over for 40 ticks");
        });
    }

    // ---------------------------------------------------------------- helpers

    private static List<OrderStatus.Round> rounds(ServerLevel level, String tag) {
        return OrderStatus.rounds(level.getServer(), new OrderStatus.Filter(null, tag));
    }

    private static int count(ServerLevel level, String tag) {
        return rounds(level, tag).size();
    }

    /** Round ids with their phase and tag, sorted. */
    private static List<String> snapshot(ServerLevel level, String tag) {
        return rounds(level, tag).stream().map(r -> r.ref().round() + " " + r.phase() + " " + r.ref().tag()).sorted().toList();
    }

    /** {@code command} as {@code source}, its result. */
    private static int run(GameTestHelper h, CommandSourceStack source, String command) {
        try {
            return h.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw new GameTestAssertException("/" + command + ": " + e.getMessage());
        }
    }

    private static Objective objective(ServerLevel level, String name) {
        ServerScoreboard board = level.getServer().getScoreboard();
        Objective o = board.getObjective(name);
        return o != null ? o : board.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(name), ObjectiveCriteria.RenderType.INTEGER, false, null);
    }

    private static int score(ServerLevel level, Objective o, String holder) {
        return level.getServer().getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(holder), o).get();
    }

    private static <T extends net.neoforged.bus.api.Event> void listen(GameTestHelper h, Class<T> type, Consumer<T> listener) {
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, type, listener);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(listener));
    }

    /** A console that keeps what commands tell it. */
    private static final class Console implements CommandSource {
        final List<Component> said = new ArrayList<>();
        private CommandSourceStack source;
        private GameTestHelper h;

        static Console open(GameTestHelper h, String name) {
            Console c = new Console();
            ServerLevel level = h.getLevel();
            c.h = h;
            c.source = new CommandSourceStack(c, Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)), Vec2.ZERO, level, 4, name, Component.literal(name),
                    level.getServer(), null);
            return c;
        }

        int run(String command) {
            return OrderStatusGameTests.run(h, source, command);
        }

        /** Through the server's command execution ({@code execute} forks need it). */
        void perform(String command) {
            h.getLevel().getServer().getCommands().performPrefixedCommand(source, command);
        }

        /** Some line holds {@code text}: a translation key, or literal text in it or its arguments. */
        boolean mentions(String text) {
            for (Component c : said) if (mentions(c, text)) return true;
            return false;
        }

        private static boolean mentions(Component c, String text) {
            if (c.getContents() instanceof TranslatableContents t) {
                if (t.getKey().equals(text)) return true;
                for (Object arg : t.getArgs()) {
                    if (arg instanceof Component a ? mentions(a, text) : String.valueOf(arg).contains(text)) return true;
                }
            }
            if (c.getContents() instanceof PlainTextContents p && p.text().contains(text)) return true;
            for (Component s : c.getSiblings()) if (mentions(s, text)) return true;
            return false;
        }

        @Override
        public void sendSystemMessage(Component message) {
            said.add(message);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
